package com.cred.freestyle.flashsale.infrastructure.scheduler;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job to clean up expired reservations (Layer 2 of Three-Layer Redundancy System).
 *
 * Three-Layer Redundancy System (from SYSTEM_ARCHITECTURE_ULTRA_V2):
 * - Layer 1: Redis TTL (Real-time, <1ms) - Auto-delete after 120 seconds
 * - Layer 2: Scheduled Cleanup (Periodic, every 10s) - Database source of truth ← THIS CLASS
 * - Layer 3: Event Stream (Reactive, 0ms) - Kafka publishes expiry events
 *
 * This scheduler:
 * 1. Runs every 10 seconds (configurable)
 * 2. Finds all RESERVED reservations where expires_at < NOW()
 * 3. Marks them as EXPIRED in database
 * 4. Releases reserved inventory
 * 5. Publishes expiry events to Kafka (Layer 3)
 * 6. Updates Redis cache (sync with Layer 1)
 *
 * Reliability:
 * - Catches any expirations missed by Redis TTL (Layer 1)
 * - Database is source of truth (survives Redis failures)
 * - Max lag: 10 seconds after actual expiry time
 * - Idempotent: Safe to run multiple times on same reservation
 *
 * @author Flash Sale Team
 */
@Service
public class ReservationExpiryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final RedisCacheService cacheService;
    private final KafkaProducerService kafkaProducerService;
    private final CloudWatchMetricsService metricsService;

    @Value("${flashsale.reservation.expiry-scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${flashsale.reservation.expiry-scheduler.batch-size:100}")
    private int batchSize;

    public ReservationExpiryScheduler(
            ReservationRepository reservationRepository,
            InventoryRepository inventoryRepository,
            RedisCacheService cacheService,
            KafkaProducerService kafkaProducerService,
            CloudWatchMetricsService metricsService
    ) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.cacheService = cacheService;
        this.kafkaProducerService = kafkaProducerService;
        this.metricsService = metricsService;
    }

    /**
     * Scheduled cleanup job for expired reservations.
     *
     * Runs every 10 seconds (10,000ms fixed delay).
     * Fixed delay means: wait 10s after previous execution completes before starting next one.
     *
     * Process:
     * 1. Find expired reservations (RESERVED status, expires_at < NOW())
     * 2. Process in batches (default: 100 at a time)
     * 3. For each expired reservation:
     *    - Mark as EXPIRED in database
     *    - Release reserved inventory
     *    - Publish Kafka event (Layer 3)
     *    - Update Redis cache (Layer 1 sync)
     *    - Record metrics
     *
     * Example Timeline:
     * T=0s:   User creates reservation (expires_at = T+120s)
     * T=120s: Reservation expires (Redis TTL deletes cache)
     * T=130s: This scheduler runs, detects expired reservation in DB
     *         ↳ Marks as EXPIRED, releases inventory, publishes event
     *
     * Max lag: 10 seconds (scheduler interval)
     */
    @Scheduled(fixedDelay = 10000) // Every 10 seconds (as per SYSTEM_ARCHITECTURE_ULTRA_V2)
    @Transactional
    public void cleanupExpiredReservations() {
        if (!schedulerEnabled) {
            logger.debug("Reservation expiry scheduler is disabled");
            return;
        }

        long startTime = System.currentTimeMillis();
        Instant now = Instant.now();

        try {
            // Step 1: Find all expired reservations
            List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(now);

            if (expiredReservations.isEmpty()) {
                logger.debug("No expired reservations found");
                return;
            }

            logger.info("Found {} expired reservations to process", expiredReservations.size());

            int processedCount = 0;
            int failedCount = 0;

            // Step 2: Process each expired reservation
            for (Reservation reservation : expiredReservations) {
                try {
                    processExpiredReservation(reservation, now);
                    processedCount++;

                    // Record metric for each expiry
                    metricsService.recordReservationExpiry(reservation.getSkuId());

                } catch (Exception e) {
                    logger.error("Error processing expired reservation: {}",
                                reservation.getReservationId(), e);
                    failedCount++;
                    metricsService.recordError("RESERVATION_EXPIRY_PROCESSING_ERROR",
                                              "cleanupExpiredReservations");
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            logger.info("Cleanup completed: {} processed, {} failed, duration: {}ms",
                       processedCount, failedCount, duration);

            // Record overall metrics
            if (processedCount > 0) {
                logger.info("Released inventory for {} expired reservations", processedCount);
            }

        } catch (Exception e) {
            logger.error("Error in reservation expiry scheduler", e);
            metricsService.recordError("RESERVATION_EXPIRY_SCHEDULER_ERROR",
                                      "cleanupExpiredReservations");
        }
    }

    /**
     * Process a single expired reservation.
     *
     * Steps:
     * 1. Mark reservation as EXPIRED
     * 2. Release reserved inventory (decrement reserved_count)
     * 3. Publish Kafka expiry event (Layer 3)
     * 4. Clear Redis cache (Layer 1 sync)
     * 5. Update cache stock count
     *
     * @param reservation Expired reservation to process
     * @param expiredAt Timestamp when expiry was detected
     */
    private void processExpiredReservation(Reservation reservation, Instant expiredAt) {
        String reservationId = reservation.getReservationId();
        String userId = reservation.getUserId();
        String skuId = reservation.getSkuId();
        int quantity = reservation.getQuantity();

        logger.debug("Processing expired reservation: {} (user: {}, sku: {}, qty: {})",
                    reservationId, userId, skuId, quantity);

        // Step 1: Mark as EXPIRED in database
        reservation.expire();
        reservationRepository.save(reservation);

        // Step 2: Release reserved inventory
        // This decrements the reserved_count in inventory table
        inventoryRepository.decrementReservedCount(skuId, quantity);

        // Step 3: Publish Kafka expiry event (Layer 3 - Event Stream)
        try {
            ReservationEvent event = new ReservationEvent(
                    reservationId,
                    userId,
                    skuId,
                    quantity,
                    ReservationEvent.EventType.EXPIRED,
                    reservation.getIdempotencyKey()
            );
            kafkaProducerService.publishReservationExpired(event);

            logger.debug("Published expiry event for reservation: {}", reservationId);

        } catch (Exception e) {
            logger.error("Failed to publish expiry event for reservation: {}",
                        reservationId, e);
            // Continue processing - event publishing is best-effort
            // Inventory is already released in database (source of truth)
        }

        // Step 4: Clear Redis cache (Layer 1 sync)
        try {
            cacheService.clearActiveReservation(userId, skuId);
            logger.debug("Cleared reservation cache for user: {}, sku: {}", userId, skuId);
        } catch (Exception e) {
            logger.error("Failed to clear reservation cache for user: {}, sku: {}",
                        userId, skuId, e);
            // Continue - cache will auto-expire anyway (3min TTL)
        }

        // Step 5: Increment stock count in cache
        try {
            cacheService.incrementStockCount(skuId, quantity);
            logger.debug("Incremented stock count in cache for sku: {} by {}", skuId, quantity);
        } catch (Exception e) {
            logger.error("Failed to update stock count in cache for sku: {}", skuId, e);
            // Continue - cache will be refreshed from DB eventually
        }

        logger.info("Successfully processed expired reservation: {} (released {} units of {})",
                   reservationId, quantity, skuId);
    }

    /**
     * Manual trigger for cleanup (admin operation, used for testing).
     * Processes all expired reservations immediately without waiting for scheduled run.
     *
     * @return Number of reservations processed
     */
    public int triggerCleanupNow() {
        logger.info("Manual cleanup triggered");
        Instant now = Instant.now();

        List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(now);

        for (Reservation reservation : expiredReservations) {
            try {
                processExpiredReservation(reservation, now);
            } catch (Exception e) {
                logger.error("Error processing reservation: {}",
                            reservation.getReservationId(), e);
            }
        }

        logger.info("Manual cleanup completed: {} reservations processed",
                   expiredReservations.size());
        return expiredReservations.size();
    }
}
