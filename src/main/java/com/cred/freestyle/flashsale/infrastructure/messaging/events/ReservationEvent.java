package com.cred.freestyle.flashsale.infrastructure.messaging.events;

import java.time.Instant;

/**
 * Event representing a reservation lifecycle event.
 * Published to Kafka for inventory management using single-writer pattern.
 *
 * Event Types:
 * - CREATED: Reservation created (decrement inventory)
 * - CONFIRMED: Reservation confirmed/paid (convert reserved to sold)
 * - EXPIRED: Reservation expired (release inventory)
 * - CANCELLED: Reservation cancelled (release inventory)
 *
 * @author Flash Sale Team
 */
public class ReservationEvent {

    private String reservationId;
    private String userId;
    private String skuId;
    private Integer quantity;
    private EventType eventType;
    private Instant timestamp;
    private String idempotencyKey;

    /**
     * Default constructor for deserialization.
     */
    public ReservationEvent() {
    }

    /**
     * Constructor for creating reservation events.
     *
     * @param reservationId Reservation ID
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param quantity Quantity reserved
     * @param eventType Event type
     * @param idempotencyKey Idempotency key
     */
    public ReservationEvent(
            String reservationId,
            String userId,
            String skuId,
            Integer quantity,
            EventType eventType,
            String idempotencyKey
    ) {
        this.reservationId = reservationId;
        this.userId = userId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.idempotencyKey = idempotencyKey;
    }

    // Getters and setters
    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
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

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Event type enum.
     */
    public enum EventType {
        CREATED,
        CONFIRMED,
        EXPIRED,
        CANCELLED
    }

    @Override
    public String toString() {
        return "ReservationEvent{" +
                "reservationId='" + reservationId + '\'' +
                ", userId='" + userId + '\'' +
                ", skuId='" + skuId + '\'' +
                ", quantity=" + quantity +
                ", eventType=" + eventType +
                ", timestamp=" + timestamp +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                '}';
    }
}
