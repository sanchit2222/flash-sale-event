package com.cred.freestyle.flashsale.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Reservation entity representing a 2-minute hold on inventory.
 * Reservations can be:
 * - RESERVED: Active 2-minute hold
 * - CONFIRMED: Converted to order (payment successful)
 * - EXPIRED: 2-minute window elapsed
 * - FAILED: Reservation attempt failed (out of stock, user limit exceeded)
 *
 * @author Flash Sale Team
 */
@Entity
@Table(name = "reservations", indexes = {
    @Index(name = "idx_user_status", columnList = "user_id, status"),
    @Index(name = "idx_sku_status", columnList = "sku_id, status"),
    @Index(name = "idx_user_sku_status", columnList = "user_id, sku_id, status"),
    @Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true),
    @Index(name = "idx_expires_at", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    @Id
    @Column(name = "reservation_id", nullable = false, length = 36)
    private String reservationId;

    /**
     * User who made the reservation.
     */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /**
     * Product SKU being reserved.
     */
    @Column(name = "sku_id", nullable = false, length = 100)
    private String skuId;

    /**
     * Quantity reserved (always 1 for flash sales).
     * Kept for extensibility.
     */
    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /**
     * Reservation status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    /**
     * Expiration time (created_at + 2 minutes).
     * After this time, reservation is expired and inventory released.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Idempotency key to prevent duplicate reservations.
     * Format: {user_id}:{sku_id}:{timestamp}
     * Unique constraint ensures exactly-once semantics.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When reservation was confirmed (payment successful).
     * Null if not confirmed yet.
     */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /**
     * When reservation expired (2-minute window elapsed).
     * Null if not expired.
     */
    @Column(name = "expired_at")
    private Instant expiredAt;

    /**
     * When reservation was cancelled by user.
     * Null if not cancelled.
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @PrePersist
    protected void onCreate() {
        if (reservationId == null) {
            reservationId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();

        // Set default expiration to 2 minutes from now
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(120); // 2 minutes
        }

        // Default status to RESERVED
        if (status == null) {
            status = ReservationStatus.RESERVED;
        }
    }

    /**
     * Check if reservation is still active (not expired).
     *
     * @return true if current time is before expiresAt
     */
    public boolean isActive() {
        return status == ReservationStatus.RESERVED &&
               Instant.now().isBefore(expiresAt);
    }

    /**
     * Check if reservation has expired.
     *
     * @return true if current time is after expiresAt
     */
    public boolean isExpired() {
        return status == ReservationStatus.EXPIRED ||
               (status == ReservationStatus.RESERVED && Instant.now().isAfter(expiresAt));
    }

    /**
     * Confirm the reservation (mark as CONFIRMED).
     * Modifies idempotency key to allow user to make future reservations for this SKU.
     */
    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
        // Modify idempotency key: user can reserve again in future (after confirmation)
        this.idempotencyKey = this.idempotencyKey + ":CONFIRMED:" + this.reservationId;
    }

    /**
     * Expire the reservation (mark as EXPIRED).
     * Modifies idempotency key to allow user to retry reservation.
     */
    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.expiredAt = Instant.now();
        // Modify idempotency key: user can reserve again after expiration
        this.idempotencyKey = this.idempotencyKey + ":EXPIRED:" + this.reservationId;
    }

    /**
     * Fail the reservation (mark as FAILED).
     */
    public void fail() {
        this.status = ReservationStatus.FAILED;
        // Failed reservations keep their idempotency key (they were never created successfully)
    }

    /**
     * Cancel the reservation (user-initiated cancellation).
     * Modifies idempotency key to allow user to reserve again.
     */
    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        // Modify idempotency key: user can reserve again after cancellation
        this.idempotencyKey = this.idempotencyKey + ":CANCELLED:" + this.reservationId;
    }

    /**
     * Reservation status enum.
     */
    public enum ReservationStatus {
        /**
         * Active 2-minute hold on inventory.
         */
        RESERVED,

        /**
         * Reservation confirmed (payment successful).
         */
        CONFIRMED,

        /**
         * Reservation expired (2-minute window elapsed).
         */
        EXPIRED,

        /**
         * Reservation cancelled by user.
         */
        CANCELLED,

        /**
         * Reservation attempt failed (out of stock, user limit exceeded).
         */
        FAILED
    }
}
