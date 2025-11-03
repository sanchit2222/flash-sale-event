package com.cred.freestyle.flashsale.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * CloudWatch metrics service for monitoring and observability.
 * Publishes custom metrics to AWS CloudWatch via Micrometer.
 *
 * Key Metrics:
 * - Reservation success/failure rates
 * - Inventory availability
 * - API latency (p50, p95, p99)
 * - Error rates
 * - Cache hit/miss rates
 *
 * @author Flash Sale Team
 */
@Service
public class CloudWatchMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(CloudWatchMetricsService.class);

    private final MeterRegistry meterRegistry;

    // Metric name prefixes
    private static final String METRIC_PREFIX = "flashsale.";
    private static final String RESERVATION_PREFIX = METRIC_PREFIX + "reservation.";
    private static final String INVENTORY_PREFIX = METRIC_PREFIX + "inventory.";
    private static final String CACHE_PREFIX = METRIC_PREFIX + "cache.";
    private static final String ORDER_PREFIX = METRIC_PREFIX + "order.";

    public CloudWatchMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Record successful reservation creation.
     *
     * @param skuId Product SKU ID
     */
    public void recordReservationSuccess(String skuId) {
        Counter.builder(RESERVATION_PREFIX + "success")
                .tag("sku_id", skuId)
                .description("Successful reservation creations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded reservation success for SKU: {}", skuId);
    }

    /**
     * Record failed reservation attempt.
     *
     * @param skuId Product SKU ID
     * @param reason Failure reason (e.g., "OUT_OF_STOCK", "USER_LIMIT_EXCEEDED")
     */
    public void recordReservationFailure(String skuId, String reason) {
        Counter.builder(RESERVATION_PREFIX + "failure")
                .tag("sku_id", skuId)
                .tag("reason", reason)
                .description("Failed reservation attempts")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded reservation failure for SKU: {}, reason: {}", skuId, reason);
    }

    /**
     * Record reservation expiry.
     *
     * @param skuId Product SKU ID
     */
    public void recordReservationExpiry(String skuId) {
        Counter.builder(RESERVATION_PREFIX + "expired")
                .tag("sku_id", skuId)
                .description("Expired reservations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded reservation expiry for SKU: {}", skuId);
    }

    /**
     * Record reservation confirmation (payment successful).
     *
     * @param skuId Product SKU ID
     */
    public void recordReservationConfirmation(String skuId) {
        Counter.builder(RESERVATION_PREFIX + "confirmed")
                .tag("sku_id", skuId)
                .description("Confirmed reservations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded reservation confirmation for SKU: {}", skuId);
    }

    /**
     * Record reservation cancellation (user-initiated).
     *
     * @param skuId Product SKU ID
     */
    public void recordReservationCancellation(String skuId) {
        Counter.builder(RESERVATION_PREFIX + "cancelled")
                .tag("sku_id", skuId)
                .description("Cancelled reservations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded reservation cancellation for SKU: {}", skuId);
    }

    /**
     * Record inventory stock out event.
     *
     * @param skuId Product SKU ID
     */
    public void recordInventoryStockOut(String skuId) {
        Counter.builder(INVENTORY_PREFIX + "stockout")
                .tag("sku_id", skuId)
                .description("Product stock out events")
                .register(meterRegistry)
                .increment();
        logger.info("Recorded inventory stock out for SKU: {}", skuId);
    }

    /**
     * Record current inventory level.
     *
     * @param skuId Product SKU ID
     * @param availableCount Available inventory count
     */
    public void recordInventoryLevel(String skuId, Integer availableCount) {
        meterRegistry.gauge(INVENTORY_PREFIX + "available",
                io.micrometer.core.instrument.Tags.of("sku_id", skuId),
                availableCount);
        logger.debug("Recorded inventory level for SKU: {}, count: {}", skuId, availableCount);
    }

    /**
     * Record cache hit.
     *
     * @param cacheType Type of cache (e.g., "stock", "product", "user_limit")
     */
    public void recordCacheHit(String cacheType) {
        Counter.builder(CACHE_PREFIX + "hit")
                .tag("cache_type", cacheType)
                .description("Cache hits")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record cache miss.
     *
     * @param cacheType Type of cache
     */
    public void recordCacheMiss(String cacheType) {
        Counter.builder(CACHE_PREFIX + "miss")
                .tag("cache_type", cacheType)
                .description("Cache misses")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record API latency for reservation endpoint.
     *
     * @param durationMs Duration in milliseconds
     */
    public void recordReservationLatency(long durationMs) {
        Timer.builder(RESERVATION_PREFIX + "latency")
                .description("Reservation API latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record API latency for checkout endpoint.
     *
     * @param durationMs Duration in milliseconds
     */
    public void recordCheckoutLatency(long durationMs) {
        Timer.builder(ORDER_PREFIX + "checkout.latency")
                .description("Checkout API latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record successful order creation.
     *
     * @param skuId Product SKU ID
     */
    public void recordOrderSuccess(String skuId) {
        Counter.builder(ORDER_PREFIX + "success")
                .tag("sku_id", skuId)
                .description("Successful order creations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded order success for SKU: {}", skuId);
    }

    /**
     * Record failed order creation.
     *
     * @param skuId Product SKU ID
     * @param reason Failure reason
     */
    public void recordOrderFailure(String skuId, String reason) {
        Counter.builder(ORDER_PREFIX + "failure")
                .tag("sku_id", skuId)
                .tag("reason", reason)
                .description("Failed order creations")
                .register(meterRegistry)
                .increment();
        logger.debug("Recorded order failure for SKU: {}, reason: {}", skuId, reason);
    }

    /**
     * Record database query latency.
     *
     * @param queryType Type of query (e.g., "findInventory", "saveReservation")
     * @param durationMs Duration in milliseconds
     */
    public void recordDatabaseLatency(String queryType, long durationMs) {
        Timer.builder(METRIC_PREFIX + "database.latency")
                .tag("query_type", queryType)
                .description("Database query latency")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record Kafka publish latency.
     *
     * @param topic Topic name
     * @param durationMs Duration in milliseconds
     */
    public void recordKafkaPublishLatency(String topic, long durationMs) {
        Timer.builder(METRIC_PREFIX + "kafka.publish.latency")
                .tag("topic", topic)
                .description("Kafka publish latency")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record concurrent requests for a product.
     * Used to monitor hot product traffic spikes.
     *
     * @param skuId Product SKU ID
     * @param concurrentRequests Number of concurrent requests
     */
    public void recordConcurrentRequests(String skuId, int concurrentRequests) {
        meterRegistry.gauge(METRIC_PREFIX + "concurrent.requests",
                io.micrometer.core.instrument.Tags.of("sku_id", skuId),
                concurrentRequests);
        logger.debug("Recorded concurrent requests for SKU: {}, count: {}", skuId, concurrentRequests);
    }

    /**
     * Record business metric: revenue from a sale.
     *
     * @param skuId Product SKU ID
     * @param amount Sale amount
     */
    public void recordRevenue(String skuId, Double amount) {
        Counter.builder(METRIC_PREFIX + "revenue")
                .tag("sku_id", skuId)
                .description("Revenue from sales")
                .register(meterRegistry)
                .increment(amount);
        logger.debug("Recorded revenue for SKU: {}, amount: {}", skuId, amount);
    }

    /**
     * Record error occurrence.
     *
     * @param errorType Error type (e.g., "DATABASE_ERROR", "CACHE_ERROR")
     * @param operation Operation where error occurred
     */
    public void recordError(String errorType, String operation) {
        Counter.builder(METRIC_PREFIX + "error")
                .tag("error_type", errorType)
                .tag("operation", operation)
                .description("System errors")
                .register(meterRegistry)
                .increment();
        logger.warn("Recorded error: type={}, operation={}", errorType, operation);
    }

    /**
     * Record batch processing metrics.
     *
     * @param skuId Product SKU ID
     * @param batchSize Number of requests in the batch
     * @param durationMs Processing duration in milliseconds
     */
    public void recordBatchProcessing(String skuId, int batchSize, long durationMs) {
        Counter.builder(METRIC_PREFIX + "batch.processed")
                .tag("sku_id", skuId)
                .description("Batches processed")
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge(METRIC_PREFIX + "batch.size",
                io.micrometer.core.instrument.Tags.of("sku_id", skuId),
                batchSize);

        Timer.builder(METRIC_PREFIX + "batch.latency")
                .tag("sku_id", skuId)
                .description("Batch processing latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        logger.debug("Recorded batch processing for SKU: {}, size: {}, duration: {}ms", skuId, batchSize, durationMs);
    }

    /**
     * Record Kafka consumer lag.
     *
     * @param partition Partition number
     * @param lag Number of messages behind
     */
    public void recordConsumerLag(int partition, long lag) {
        meterRegistry.gauge(METRIC_PREFIX + "kafka.consumer.lag",
                io.micrometer.core.instrument.Tags.of("partition", String.valueOf(partition)),
                lag);
        logger.debug("Recorded consumer lag for partition {}: {}", partition, lag);
    }

    /**
     * Record queue depth for a SKU.
     *
     * @param skuId Product SKU ID
     * @param queueDepth Number of pending requests
     */
    public void recordQueueDepth(String skuId, int queueDepth) {
        meterRegistry.gauge(METRIC_PREFIX + "queue.depth",
                io.micrometer.core.instrument.Tags.of("sku_id", skuId),
                queueDepth);
        logger.debug("Recorded queue depth for SKU: {}, depth: {}", skuId, queueDepth);
    }

    /**
     * Record batch allocation success rate.
     *
     * @param skuId Product SKU ID
     * @param successfulAllocations Number of successful allocations in batch
     * @param totalRequests Total requests in batch
     */
    public void recordBatchAllocationRate(String skuId, int successfulAllocations, int totalRequests) {
        double allocationRate = totalRequests > 0 ? (double) successfulAllocations / totalRequests : 0.0;

        meterRegistry.gauge(METRIC_PREFIX + "batch.allocation.rate",
                io.micrometer.core.instrument.Tags.of("sku_id", skuId),
                allocationRate);

        Counter.builder(METRIC_PREFIX + "batch.allocated")
                .tag("sku_id", skuId)
                .description("Successful allocations from batch")
                .register(meterRegistry)
                .increment(successfulAllocations);

        Counter.builder(METRIC_PREFIX + "batch.rejected")
                .tag("sku_id", skuId)
                .description("Rejected requests from batch")
                .register(meterRegistry)
                .increment(totalRequests - successfulAllocations);

        logger.debug("Recorded batch allocation rate for SKU: {}, rate: {}, allocated: {}/{}",
                     skuId, allocationRate, successfulAllocations, totalRequests);
    }

    /**
     * Record end-to-end reservation latency from request to response.
     *
     * @param durationMs Duration in milliseconds
     */
    public void recordEndToEndLatency(long durationMs) {
        Timer.builder(RESERVATION_PREFIX + "e2e.latency")
                .description("End-to-end reservation latency (API to Kafka to DB)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record oversell detection (critical metric).
     *
     * @param skuId Product SKU ID
     * @param oversellCount Number of oversold units
     */
    public void recordOversell(String skuId, int oversellCount) {
        Counter.builder(INVENTORY_PREFIX + "oversell")
                .tag("sku_id", skuId)
                .description("CRITICAL: Inventory oversell detected")
                .register(meterRegistry)
                .increment(oversellCount);
        logger.error("CRITICAL: Recorded oversell for SKU: {}, count: {}", skuId, oversellCount);
    }
}
