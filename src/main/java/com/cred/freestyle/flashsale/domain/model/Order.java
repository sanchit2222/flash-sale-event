package com.cred.freestyle.flashsale.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Order entity representing a confirmed purchase after successful payment.
 * Created when a reservation is converted to an order.
 *
 * @author Flash Sale Team
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_sku_id", columnList = "sku_id"),
    @Index(name = "idx_reservation_id", columnList = "reservation_id", unique = true),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    /**
     * Foreign key reference to reservations.reservation_id.
     * One-to-one relationship: each order is created from exactly one reservation.
     */
    @Column(name = "reservation_id", nullable = false, unique = true, length = 36)
    private String reservationId;

    /**
     * User who placed the order.
     */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /**
     * Product SKU that was purchased.
     */
    @Column(name = "sku_id", nullable = false, length = 100)
    private String skuId;

    /**
     * Quantity ordered (always 1 for flash sales).
     */
    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /**
     * Final price paid (flash sale price at the time of purchase).
     */
    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    /**
     * Order status tracking.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /**
     * Payment transaction ID from payment gateway.
     * Used for reconciliation and refund processing.
     */
    @Column(name = "payment_transaction_id", length = 255)
    private String paymentTransactionId;

    /**
     * Payment method used (e.g., "CREDIT_CARD", "UPI", "WALLET").
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /**
     * Timestamp when payment was completed.
     */
    @Column(name = "payment_completed_at")
    private Instant paymentCompletedAt;

    /**
     * Shipping address (stored as JSON or denormalized fields).
     * For simplicity, using TEXT column. In production, consider separate Address entity.
     */
    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    /**
     * Timestamp when order was created (same as payment completion for flash sales).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when order was last updated.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Timestamp when order was fulfilled/shipped.
     */
    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    /**
     * Timestamp when order was cancelled (if applicable).
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Cancellation reason (if cancelled).
     */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @PrePersist
    protected void onCreate() {
        if (orderId == null) {
            orderId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();

        // Default status to PAYMENT_PENDING
        if (status == null) {
            status = OrderStatus.PAYMENT_PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Mark order as payment completed.
     *
     * @param transactionId Payment gateway transaction ID
     */
    public void completePayment(String transactionId) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentTransactionId = transactionId;
        this.paymentCompletedAt = Instant.now();
    }

    /**
     * Mark order as fulfilled (shipped).
     */
    public void fulfill() {
        this.status = OrderStatus.FULFILLED;
        this.fulfilledAt = Instant.now();
    }

    /**
     * Cancel the order.
     *
     * @param reason Cancellation reason
     */
    public void cancel(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = Instant.now();
    }

    /**
     * Check if order is in a final state (cannot be modified).
     *
     * @return true if order is fulfilled or cancelled
     */
    public boolean isFinalState() {
        return status == OrderStatus.FULFILLED ||
               status == OrderStatus.CANCELLED;
    }

    /**
     * Order status enum.
     */
    public enum OrderStatus {
        /**
         * Order created, awaiting payment.
         */
        PAYMENT_PENDING,

        /**
         * Payment successful, order confirmed.
         */
        CONFIRMED,

        /**
         * Order shipped/fulfilled.
         */
        FULFILLED,

        /**
         * Order cancelled (payment failed or user cancelled).
         */
        CANCELLED,

        /**
         * Payment failed after multiple retries.
         */
        PAYMENT_FAILED
    }
}
