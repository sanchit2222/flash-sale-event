package com.cred.freestyle.flashsale.infrastructure.messaging.events;

import java.time.Instant;

/**
 * Message representing a reservation request published to Kafka.
 * Used in the single-writer pattern for inventory management.
 *
 * The consumer processes these messages in batches of 250 to achieve 25k RPS throughput.
 *
 * @author Flash Sale Team
 */
public class ReservationRequestMessage {

    private String requestId;
    private String userId;
    private String skuId;
    private Integer quantity;
    private String idempotencyKey;
    private Instant timestamp;
    private String correlationId; // For distributed tracing

    /**
     * Default constructor for deserialization.
     */
    public ReservationRequestMessage() {
    }

    /**
     * Constructor for creating reservation request messages.
     *
     * @param requestId Unique request ID
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param quantity Quantity to reserve
     * @param idempotencyKey Idempotency key for deduplication
     * @param correlationId Correlation ID for distributed tracing
     */
    public ReservationRequestMessage(
            String requestId,
            String userId,
            String skuId,
            Integer quantity,
            String idempotencyKey,
            String correlationId
    ) {
        this.requestId = requestId;
        this.userId = userId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.timestamp = Instant.now();
        this.correlationId = correlationId;
    }

    // Getters and setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public String toString() {
        return "ReservationRequestMessage{" +
                "requestId='" + requestId + '\'' +
                ", userId='" + userId + '\'' +
                ", skuId='" + skuId + '\'' +
                ", quantity=" + quantity +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", timestamp=" + timestamp +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
}
