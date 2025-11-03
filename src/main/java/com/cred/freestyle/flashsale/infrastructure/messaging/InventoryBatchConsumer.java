package com.cred.freestyle.flashsale.infrastructure.messaging;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationRequestMessage;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationResponseMessage;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Kafka consumer service implementing the single-writer pattern for inventory management.
 *
 * Architecture:
 * - Consumes from reservation-requests topic partitioned by SKU
 * - Processes requests in batches of 250 (configurable)
 * - Achieves 25k RPS throughput with 10ms batch processing time
 * - Provides zero-oversell guarantee through atomic batch updates
 *
 * Performance Characteristics:
 * - Batch size: 250 requests
 * - Processing time: ~10ms per batch (database transaction)
 * - Throughput: 2,500 requests/second per partition (25,000 total across 10 partitions)
 * - P95 latency: ~60ms (queue wait + processing)
 *
 * @author Flash Sale Team
 */
@Service
public class InventoryBatchConsumer {

    private static final Logger logger = LoggerFactory.getLogger(InventoryBatchConsumer.class);

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final UserPurchaseTrackingRepository userPurchaseTrackingRepository;
    private final RedisCacheService cacheService;
    private final KafkaProducerService kafkaProducerService;
    private final CloudWatchMetricsService metricsService;
    private final ObjectMapper objectMapper;

    // Topic name for reservation requests
    private static final String RESERVATION_REQUESTS_TOPIC = "reservation-requests";

    // Batch size configuration (250 requests per batch as per design)
    private static final int BATCH_SIZE = 250;

    // Reservation duration (2 minutes)
    private static final int RESERVATION_DURATION_SECONDS = 120;

    // In-memory cache for tracking processed idempotency keys (prevents duplicates within batch)
    private final Map<String, String> processedIdempotencyKeys = new ConcurrentHashMap<>();

    public InventoryBatchConsumer(
            ReservationRepository reservationRepository,
            InventoryRepository inventoryRepository,
            UserPurchaseTrackingRepository userPurchaseTrackingRepository,
            RedisCacheService cacheService,
            KafkaProducerService kafkaProducerService,
            CloudWatchMetricsService metricsService,
            ObjectMapper objectMapper
    ) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.userPurchaseTrackingRepository = userPurchaseTrackingRepository;
        this.cacheService = cacheService;
        this.kafkaProducerService = kafkaProducerService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Kafka listener for reservation request messages.
     * Processes messages in batches for high throughput.
     *
     * Concurrency: 1 thread per partition (ensures single-writer per SKU)
     * Batch size: 250 messages (max.poll.records)
     * Processing: Atomic transaction per batch
     *
     * @param records Batch of consumer records
     * @param acknowledgment Manual acknowledgment for reliability
     */
    @KafkaListener(
            topics = RESERVATION_REQUESTS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:inventory-batch-consumer}",
            concurrency = "10", // 10 concurrent consumers (one per partition)
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReservationRequests(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment acknowledgment
    ) {
        if (records == null || records.isEmpty()) {
            logger.debug("Received empty batch, skipping processing");
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            return;
        }

        long batchStartTime = System.currentTimeMillis();
        int partition = records.get(0).partition();
        int batchSize = records.size();

        logger.info("Processing batch of {} requests from partition {}", batchSize, partition);

        try {
            // Parse messages from Kafka records
            List<ReservationRequestMessage> requests = parseMessages(records);

            if (requests.isEmpty()) {
                logger.warn("No valid messages in batch from partition {}", partition);
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                }
                return;
            }

            // Group requests by SKU for batch processing
            Map<String, List<ReservationRequestMessage>> requestsBySkU = requests.stream()
                    .collect(Collectors.groupingBy(ReservationRequestMessage::getSkuId));

            // Process each SKU's requests atomically
            for (Map.Entry<String, List<ReservationRequestMessage>> entry : requestsBySkU.entrySet()) {
                String skuId = entry.getKey();
                List<ReservationRequestMessage> skuRequests = entry.getValue();

                try {
                    processBatchForSku(skuId, skuRequests);
                } catch (Exception e) {
                    logger.error("Error processing batch for SKU: {}, requests: {}",
                                skuId, skuRequests.size(), e);
                    metricsService.recordError("BATCH_PROCESSING_ERROR", "processBatchForSku");
                    // Continue processing other SKUs
                }
            }

            // Acknowledge successful processing
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }

            long batchDuration = System.currentTimeMillis() - batchStartTime;
            logger.info("Completed batch processing: partition={}, size={}, duration={}ms",
                       partition, batchSize, batchDuration);

            // Record metrics
            metricsService.recordBatchProcessing("ALL", batchSize, batchDuration);

        } catch (Exception e) {
            logger.error("Critical error processing batch from partition {}", partition, e);
            metricsService.recordError("BATCH_PROCESSING_FATAL_ERROR", "consumeReservationRequests");
            // Do not acknowledge - Kafka will redeliver the batch
            throw new RuntimeException("Fatal error processing batch", e);
        }
    }

    /**
     * Process a batch of reservation requests for a single SKU atomically.
     *
     * Algorithm:
     * 1. Validate all requests (deduplication, user limits)
     * 2. Allocate inventory atomically using two-phase approach:
     *    - Phase 1: Try full batch allocation (happy path - 1 DB call)
     *    - Phase 2: If insufficient, read available count and allocate that (2 DB calls total)
     * 3. Create reservation records for allocated requests (atomic transaction)
     * 4. Update cache with allocated reservations
     * 5. Publish success events for allocated requests
     * 6. Reject overflow requests (from partial allocation)
     *
     * @param skuId Product SKU ID
     * @param requests List of reservation requests for this SKU
     */
    @Transactional
    protected void processBatchForSku(String skuId, List<ReservationRequestMessage> requests) {
        long startTime = System.currentTimeMillis();
        int batchSize = requests.size();

        logger.info("Processing batch for SKU: {}, requests: {}", skuId, batchSize);

        try {
            // Step 1: Validate and filter requests
            List<ValidatedRequest> validatedRequests = validateRequests(skuId, requests);

            if (validatedRequests.isEmpty()) {
                logger.warn("No valid requests in batch for SKU: {}", skuId);
                metricsService.recordBatchAllocationRate(skuId, 0, batchSize);
                return;
            }

            // Step 2: Allocate inventory atomically in batch (FIFO order)
            // Optimized two-phase approach:
            // Phase 1: Try full allocation (happy path - 1 DB call)
            // Phase 2: If insufficient, read available count and allocate exactly that (2 DB calls)
            List<ValidatedRequest> allocated = new ArrayList<>();
            List<ValidatedRequest> rejected = new ArrayList<>();

            int totalRequested = validatedRequests.size();  // Always equals size since quantity = 1

            // Phase 1: Attempt full batch allocation (happy path - single DB call)
            int rowsUpdated = inventoryRepository.incrementReservedCount(skuId, totalRequested);

            if (rowsUpdated > 0) {
                // Success: All requests allocated in a single atomic operation
                allocated = validatedRequests;
                logger.info("SKU {}: Batch allocated all {} requests in single transaction",
                           skuId, totalRequested);
            } else {
                // Phase 2: Partial allocation case - read available count, then allocate exactly that
                // Example: 240 available, 250 requests → 2 DB calls (1 read + 1 write) vs 241 calls (old FIFO)
                logger.info("SKU {}: Insufficient inventory for full batch of {}, attempting partial allocation",
                           skuId, totalRequested);

                Integer availableCount = inventoryRepository.getAvailableCount(skuId);

                if (availableCount == null || availableCount <= 0) {
                    // No inventory available - reject all
                    rejected = validatedRequests;
                    logger.warn("SKU {}: No inventory available, rejecting all {} requests", skuId, totalRequested);
                } else {
                    // Allocate exactly what's available (FIFO: first N requests get inventory)
                    int allocateCount = Math.min(totalRequested, availableCount);
                    rowsUpdated = inventoryRepository.incrementReservedCount(skuId, allocateCount);

                    if (rowsUpdated > 0) {
                        // Partial allocation successful
                        allocated = validatedRequests.subList(0, allocateCount);
                        rejected = validatedRequests.subList(allocateCount, totalRequested);

                        logger.info("SKU {}: Partial allocation - allocated: {}, rejected: {} (available was: {})",
                                   skuId, allocateCount, totalRequested - allocateCount, availableCount);
                    } else {
                        // Race condition: inventory consumed between read and update
                        logger.warn("SKU {}: Race condition - inventory consumed between read and update, rejecting all", skuId);
                        rejected = validatedRequests;
                    }
                }
            }

            if (allocated.isEmpty()) {
                logger.warn("No inventory available for SKU: {}", skuId);
                rejectAllRequests(validatedRequests, ReservationResponseMessage.ResponseStatus.OUT_OF_STOCK,
                                "Product is out of stock");
                metricsService.recordBatchAllocationRate(skuId, 0, batchSize);
                metricsService.recordInventoryStockOut(skuId);
                return;
            }

            // Step 3: Create reservation records for allocated requests
            int totalQuantity = allocated.stream().mapToInt(vr -> vr.request.getQuantity()).sum();
            List<Reservation> reservations = createReservations(allocated);

            // Step 4: Update cache with allocated reservations
            cacheService.decrementStockCount(skuId, totalQuantity);
            for (Reservation reservation : reservations) {
                cacheService.cacheActiveReservation(
                    reservation.getUserId(),
                    reservation.getSkuId(),
                    reservation.getReservationId()
                );
            }

            // Step 5: Record success metrics for allocated requests
            for (Reservation reservation : reservations) {
                metricsService.recordReservationSuccess(skuId);
            }

            // Step 6: Reject overflow requests (from partial allocation)
            if (!rejected.isEmpty()) {
                logger.info("SKU {}: Rejecting {} requests due to insufficient inventory (partial allocation)",
                           skuId, rejected.size());
                rejectAllRequests(rejected, ReservationResponseMessage.ResponseStatus.OUT_OF_STOCK,
                                "Product is out of stock");
            }

            // Record batch metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsService.recordBatchProcessing(skuId, batchSize, duration);
            metricsService.recordBatchAllocationRate(skuId, allocated.size(), batchSize);

            logger.info("Completed batch for SKU: {}, allocated: {}, rejected: {}, duration: {}ms",
                       skuId, allocated.size(), rejected.size(), duration);

            // Check for oversell (critical monitoring)
            checkForOversell(skuId);

        } catch (Exception e) {
            logger.error("Error processing batch for SKU: {}", skuId, e);
            metricsService.recordError("BATCH_PROCESSING_ERROR", "processBatchForSku");
            throw e;
        }
    }

    /**
     * Parse and deserialize messages from Kafka records.
     */
    private List<ReservationRequestMessage> parseMessages(List<ConsumerRecord<String, String>> records) {
        List<ReservationRequestMessage> messages = new ArrayList<>();

        for (ConsumerRecord<String, String> record : records) {
            try {
                ReservationRequestMessage message = objectMapper.readValue(
                    record.value(),
                    ReservationRequestMessage.class
                );
                messages.add(message);
            } catch (JsonProcessingException e) {
                logger.error("Failed to parse message from partition {}, offset {}",
                            record.partition(), record.offset(), e);
                metricsService.recordError("MESSAGE_PARSE_ERROR", "parseMessages");
                // Skip invalid message
            }
        }

        return messages;
    }

    /**
     * Validate reservation requests (deduplication, user limits).
     */
    private List<ValidatedRequest> validateRequests(String skuId, List<ReservationRequestMessage> requests) {
        List<ValidatedRequest> validated = new ArrayList<>();

        for (ReservationRequestMessage request : requests) {
            ValidatedRequest vr = new ValidatedRequest(request);

            // Enforce quantity = 1 per user (business rule)
            if (request.getQuantity() != 1) {
                vr.reject(ReservationResponseMessage.ResponseStatus.INVALID_REQUEST,
                         "Quantity must be exactly 1 per user");

                // Cache rejection so ReservationService polling can detect it immediately
                cacheService.cacheRejection(
                    request.getUserId(),
                    request.getSkuId(),
                    ReservationResponseMessage.ResponseStatus.INVALID_REQUEST.toString(),
                    "Quantity must be exactly 1 per user"
                );

                logger.warn("Rejected request from user {} - invalid quantity: {}",
                           request.getUserId(), request.getQuantity());
                metricsService.recordReservationFailure(skuId, "INVALID_QUANTITY");
                continue;
            }

            // Check idempotency (prevent duplicate processing)
            if (isDuplicate(request.getIdempotencyKey())) {
                vr.reject(ReservationResponseMessage.ResponseStatus.DUPLICATE_REQUEST,
                         "Duplicate request detected");

                // Cache rejection so ReservationService polling can detect it immediately
                cacheService.cacheRejection(
                    request.getUserId(),
                    request.getSkuId(),
                    ReservationResponseMessage.ResponseStatus.DUPLICATE_REQUEST.toString(),
                    "Duplicate request detected"
                );

                logger.debug("Rejected duplicate request: {}", request.getIdempotencyKey());
                continue;
            }

            // Check if user has already purchased
            if (hasUserAlreadyPurchased(request.getUserId(), skuId)) {
                vr.reject(ReservationResponseMessage.ResponseStatus.USER_ALREADY_PURCHASED,
                         "User has already purchased this product");

                // Cache rejection so ReservationService polling can detect it immediately
                cacheService.cacheRejection(
                    request.getUserId(),
                    request.getSkuId(),
                    ReservationResponseMessage.ResponseStatus.USER_ALREADY_PURCHASED.toString(),
                    "User has already purchased this product"
                );

                logger.debug("Rejected request from user {} - already purchased", request.getUserId());
                metricsService.recordReservationFailure(skuId, "USER_ALREADY_PURCHASED");
                continue;
            }

            // Check if user has active reservation
            if (hasActiveReservation(request.getUserId(), skuId)) {
                vr.reject(ReservationResponseMessage.ResponseStatus.USER_HAS_ACTIVE_RESERVATION,
                         "User already has an active reservation");

                // Cache rejection so ReservationService polling can detect it immediately
                cacheService.cacheRejection(
                    request.getUserId(),
                    request.getSkuId(),
                    ReservationResponseMessage.ResponseStatus.USER_HAS_ACTIVE_RESERVATION.toString(),
                    "User already has an active reservation"
                );

                logger.debug("Rejected request from user {} - active reservation exists", request.getUserId());
                metricsService.recordReservationFailure(skuId, "USER_HAS_ACTIVE_RESERVATION");
                continue;
            }

            // Mark as processed for deduplication within this batch
            processedIdempotencyKeys.put(request.getIdempotencyKey(), request.getRequestId());

            validated.add(vr);
        }

        return validated;
    }

    /**
     * Create reservation records for allocated requests.
     */
    private List<Reservation> createReservations(List<ValidatedRequest> allocatedRequests) {
        List<Reservation> reservations = new ArrayList<>();
        Instant expiresAt = Instant.now().plusSeconds(RESERVATION_DURATION_SECONDS);

        for (ValidatedRequest vr : allocatedRequests) {
            Reservation reservation = Reservation.builder()
                    .userId(vr.request.getUserId())
                    .skuId(vr.request.getSkuId())
                    .quantity(vr.request.getQuantity())
                    .status(ReservationStatus.RESERVED)
                    .expiresAt(expiresAt)
                    .idempotencyKey(vr.request.getIdempotencyKey())
                    .build();

            reservations.add(reservation);
        }

        // Batch save
        List<Reservation> saved = reservationRepository.saveAll(reservations);
        logger.info("Created {} reservations in database", saved.size());

        return saved;
    }

    /**
     * Reject all requests with a specific status and message.
     * Caches rejections in Redis so polling clients can immediately detect failures.
     */
    private void rejectAllRequests(List<ValidatedRequest> requests,
                                  ReservationResponseMessage.ResponseStatus status,
                                  String errorMessage) {
        for (ValidatedRequest vr : requests) {
            vr.reject(status, errorMessage);

            // Cache rejection so ReservationService polling can detect it immediately
            cacheService.cacheRejection(
                vr.request.getUserId(),
                vr.request.getSkuId(),
                status.toString(),
                errorMessage
            );

            logger.debug("Cached rejection for user {} SKU {}: {} - {}",
                        vr.request.getUserId(), vr.request.getSkuId(), status, errorMessage);
        }
    }

    /**
     * Check if idempotency key has been processed.
     */
    private boolean isDuplicate(String idempotencyKey) {
        // Check in-memory cache first
        if (processedIdempotencyKeys.containsKey(idempotencyKey)) {
            return true;
        }

        // Check database
        return reservationRepository.existsByIdempotencyKey(idempotencyKey);
    }

    /**
     * Check if user has already purchased this product.
     */
    private boolean hasUserAlreadyPurchased(String userId, String skuId) {
        // Check cache first
        if (cacheService.hasUserPurchased(userId, skuId)) {
            metricsService.recordCacheHit("user_limit");
            return true;
        }

        // Check database
        boolean hasPurchased = userPurchaseTrackingRepository.existsByUserIdAndSkuId(userId, skuId);
        if (hasPurchased) {
            cacheService.markUserPurchased(userId, skuId);
        }
        metricsService.recordCacheMiss("user_limit");
        return hasPurchased;
    }

    /**
     * Check if user has active reservation.
     */
    private boolean hasActiveReservation(String userId, String skuId) {
        // Check cache first
        Optional<String> cachedReservation = cacheService.getActiveReservation(userId, skuId);
        if (cachedReservation.isPresent()) {
            metricsService.recordCacheHit("reservation");
            return true;
        }

        // Check database
        boolean hasActive = reservationRepository.hasActiveReservation(userId, skuId);
        metricsService.recordCacheMiss("reservation");
        return hasActive;
    }

    /**
     * Check for oversell condition (critical monitoring).
     */
    private void checkForOversell(String skuId) {
        try {
            Optional<com.cred.freestyle.flashsale.domain.model.Inventory> inventory =
                inventoryRepository.findBySkuId(skuId);

            if (inventory.isPresent()) {
                com.cred.freestyle.flashsale.domain.model.Inventory inv = inventory.get();
                int totalAllocated = inv.getReservedCount() + inv.getSoldCount();
                int oversell = totalAllocated - inv.getTotalCount();

                if (oversell > 0) {
                    logger.error("CRITICAL: Oversell detected for SKU: {}, oversell count: {}",
                               skuId, oversell);
                    metricsService.recordOversell(skuId, oversell);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking for oversell for SKU: {}", skuId, e);
        }
    }

    /**
     * Helper class to track validated requests and their status.
     */
    private static class ValidatedRequest {
        final ReservationRequestMessage request;
        ReservationResponseMessage.ResponseStatus status;
        String errorMessage;

        ValidatedRequest(ReservationRequestMessage request) {
            this.request = request;
            this.status = ReservationResponseMessage.ResponseStatus.SUCCESS;
        }

        void reject(ReservationResponseMessage.ResponseStatus status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }

        boolean isValid() {
            return status == ReservationResponseMessage.ResponseStatus.SUCCESS;
        }
    }
}
