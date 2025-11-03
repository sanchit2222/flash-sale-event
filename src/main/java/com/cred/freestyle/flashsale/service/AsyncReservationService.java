package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationRequestMessage;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Async reservation service implementing Kafka-based batch processing.
 *
 * This service replaces direct database writes with Kafka message publishing.
 * The InventoryBatchConsumer processes these messages in batches of 250,
 * achieving 25k RPS throughput with the single-writer pattern.
 *
 * Flow:
 * 1. API receives reservation request
 * 2. This service validates user limits and publishes to Kafka
 * 3. Kafka consumer processes batch (250 requests in 10ms)
 * 4. Response is available via polling or callback
 *
 * @author Flash Sale Team
 */
@Service
public class AsyncReservationService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncReservationService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisCacheService cacheService;
    private final ReservationRepository reservationRepository;
    private final UserPurchaseTrackingRepository userPurchaseTrackingRepository;
    private final CloudWatchMetricsService metricsService;
    private final ObjectMapper objectMapper;

    private static final String RESERVATION_REQUESTS_TOPIC = "reservation-requests";
    private static final int KAFKA_PUBLISH_TIMEOUT_MS = 5000; // 5 seconds

    public AsyncReservationService(
            KafkaTemplate<String, String> kafkaTemplate,
            RedisCacheService cacheService,
            ReservationRepository reservationRepository,
            UserPurchaseTrackingRepository userPurchaseTrackingRepository,
            CloudWatchMetricsService metricsService,
            ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.cacheService = cacheService;
        this.reservationRepository = reservationRepository;
        this.userPurchaseTrackingRepository = userPurchaseTrackingRepository;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit a reservation request to Kafka for batch processing.
     *
     * This method performs fast pre-validation and publishes to Kafka.
     * The actual inventory allocation happens in the batch consumer.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param quantity Quantity to reserve (always 1 for flash sales)
     * @return CompletableFuture with request ID for tracking
     * @throws IllegalStateException if user has already purchased or has active reservation
     */
    public CompletableFuture<String> submitReservationRequest(
            String userId,
            String skuId,
            Integer quantity
    ) {
        long startTime = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String correlationId = String.format("%s-%s-%d", userId, skuId, System.currentTimeMillis());

        logger.info("Submitting reservation request: requestId={}, userId={}, skuId={}, quantity={}",
                   requestId, userId, skuId, quantity);

        try {
            // Step 1: Validate quantity (must be exactly 1 per user, business rule)
            if (quantity == null || quantity != 1) {
                logger.warn("Invalid quantity for reservation request: userId={}, skuId={}, quantity={}",
                           userId, skuId, quantity);
                metricsService.recordReservationFailure(skuId, "INVALID_QUANTITY");

                CompletableFuture<String> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(
                    new IllegalArgumentException("Quantity must be exactly 1 per user")
                );
                return failedFuture;
            }

            // Step 2: Fast pre-validation (cache-only checks to minimize latency)
            validateUserLimits(userId, skuId);

            // Step 3: Check cached inventory availability (fail fast if clearly out of stock)
            Optional<Integer> cachedStock = cacheService.getStockCount(skuId);
            if (cachedStock.isPresent() && cachedStock.get() < quantity) {
                logger.warn("Insufficient cached inventory for SKU: {}, requested: {}, available: {}",
                           skuId, quantity, cachedStock.get());
                metricsService.recordReservationFailure(skuId, "OUT_OF_STOCK");
                metricsService.recordInventoryStockOut(skuId);

                CompletableFuture<String> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(
                    new IllegalStateException("Product is out of stock")
                );
                return failedFuture;
            }

            // Step 4: Create reservation request message
            String idempotencyKey = generateIdempotencyKey(userId, skuId);
            ReservationRequestMessage message = new ReservationRequestMessage(
                requestId,
                userId,
                skuId,
                quantity,
                idempotencyKey,
                correlationId
            );

            // Step 5: Publish to Kafka (partitioned by SKU for single-writer pattern)
            String messageJson = objectMapper.writeValueAsString(message);

            long kafkaStartTime = System.currentTimeMillis();
            CompletableFuture<SendResult<String, String>> kafkaFuture = kafkaTemplate.send(
                RESERVATION_REQUESTS_TOPIC,
                skuId, // Key for partitioning - ensures all requests for same SKU go to same partition
                messageJson
            );

            // Step 6: Handle Kafka publish result
            return kafkaFuture.handle((result, ex) -> {
                long kafkaDuration = System.currentTimeMillis() - kafkaStartTime;
                metricsService.recordKafkaPublishLatency(RESERVATION_REQUESTS_TOPIC, kafkaDuration);

                if (ex != null) {
                    logger.error("Failed to publish reservation request to Kafka: requestId={}, skuId={}",
                               requestId, skuId, ex);
                    metricsService.recordError("KAFKA_PUBLISH_ERROR", "submitReservationRequest");
                    throw new RuntimeException("Failed to submit reservation request", ex);
                }

                logger.info("Published reservation request to Kafka: requestId={}, partition={}, offset={}",
                           requestId,
                           result.getRecordMetadata().partition(),
                           result.getRecordMetadata().offset());

                long totalDuration = System.currentTimeMillis() - startTime;
                metricsService.recordReservationLatency(totalDuration);

                return requestId;
            });

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize reservation request: requestId={}", requestId, e);
            metricsService.recordError("JSON_SERIALIZATION_ERROR", "submitReservationRequest");

            CompletableFuture<String> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;

        } catch (Exception e) {
            logger.error("Error submitting reservation request: requestId={}, userId={}, skuId={}",
                       requestId, userId, skuId, e);
            metricsService.recordError("RESERVATION_SUBMISSION_ERROR", "submitReservationRequest");

            CompletableFuture<String> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    /**
     * Submit reservation request and wait for result (blocking).
     * This is a convenience method for synchronous API endpoints.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param quantity Quantity to reserve
     * @return Request ID
     * @throws IllegalStateException if validation fails
     * @throws RuntimeException if Kafka publish fails
     */
    public String submitReservationRequestSync(String userId, String skuId, Integer quantity) {
        try {
            return submitReservationRequest(userId, skuId, quantity)
                .get(KAFKA_PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("Error in sync reservation submission: userId={}, skuId={}",
                       userId, skuId, e);

            if (e.getCause() instanceof IllegalStateException) {
                throw (IllegalStateException) e.getCause();
            }

            throw new RuntimeException("Failed to submit reservation request", e);
        }
    }

    /**
     * Validate user purchase limits before submitting to Kafka.
     * This is a fast pre-validation to fail fast and reduce unnecessary Kafka traffic.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @throws IllegalStateException if user limit exceeded
     */
    private void validateUserLimits(String userId, String skuId) {
        // Check if user has already purchased (cache first)
        if (hasUserAlreadyPurchased(userId, skuId)) {
            logger.warn("User {} has already purchased SKU: {}", userId, skuId);
            metricsService.recordReservationFailure(skuId, "USER_ALREADY_PURCHASED");
            throw new IllegalStateException("User has already purchased this product");
        }

        // Check if user has active reservation (cache first)
        if (hasActiveReservation(userId, skuId)) {
            logger.warn("User {} already has active reservation for SKU: {}", userId, skuId);
            metricsService.recordReservationFailure(skuId, "USER_HAS_ACTIVE_RESERVATION");
            throw new IllegalStateException("User already has an active reservation for this product");
        }
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
     * Checks cache first for performance, falls back to database for accuracy.
     */
    private boolean hasActiveReservation(String userId, String skuId) {
        // Check cache first
        Optional<String> cachedReservation = cacheService.getActiveReservation(userId, skuId);
        if (cachedReservation.isPresent()) {
            metricsService.recordCacheHit("reservation");
            return true;
        }

        // Check database as fallback to ensure consistency with batch consumer
        boolean hasActive = reservationRepository.hasActiveReservation(userId, skuId);
        metricsService.recordCacheMiss("reservation");
        return hasActive;
    }

    /**
     * Generate idempotency key for reservation.
     *
     * CRITICAL: Idempotency key must be userId:skuId (without timestamp) to prevent
     * race conditions where the same user submits multiple simultaneous requests for
     * the same SKU. With this approach:
     * - First request creates reservation with idempotency key "user123:SKU-001"
     * - Second simultaneous request has same key → rejected as duplicate
     * - This prevents users from getting multiple reservations for same product
     *
     * Note: Once user's reservation expires/cancels, they can request again (new record)
     */
    private String generateIdempotencyKey(String userId, String skuId) {
        return userId + ":" + skuId;  // No timestamp! User can only have one request per SKU
    }

    /**
     * Get estimated queue position for a request.
     * This can be used to provide users with wait time estimates.
     *
     * @param skuId Product SKU ID
     * @return Estimated queue depth
     */
    public int getEstimatedQueueDepth(String skuId) {
        // This would require tracking via metrics or a separate queue depth service
        // For now, return a placeholder
        // TODO: Implement queue depth tracking via Kafka consumer lag monitoring
        return 0;
    }
}
