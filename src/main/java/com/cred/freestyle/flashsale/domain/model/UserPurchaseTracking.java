package com.cred.freestyle.flashsale.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity to track user purchases per product to enforce purchase limits.
 * Enforces rule: Each user can purchase at most 1 unit of each product.
 * This prevents users from bypassing limits by creating multiple reservations.
 *
 * @author Flash Sale Team
 */
@Entity
@Table(name = "user_purchase_tracking", indexes = {
    @Index(name = "idx_user_sku_unique", columnList = "user_id, sku_id", unique = true),
    @Index(name = "idx_flash_sale_event", columnList = "flash_sale_event_id"),
    @Index(name = "idx_user_id", columnList = "user_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPurchaseTracking {

    @Id
    @Column(name = "tracking_id", nullable = false, length = 36)
    private String trackingId;

    /**
     * User who made the purchase.
     */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /**
     * Product SKU that was purchased.
     */
    @Column(name = "sku_id", nullable = false, length = 100)
    private String skuId;

    /**
     * Flash sale event ID (for partitioning and cleanup).
     */
    @Column(name = "flash_sale_event_id", length = 36)
    private String flashSaleEventId;

    /**
     * Total quantity purchased by this user for this product.
     * Always 1 for flash sales, but kept for extensibility.
     */
    @Column(name = "quantity_purchased", nullable = false)
    @Builder.Default
    private Integer quantityPurchased = 1;

    /**
     * Order ID that confirmed this purchase.
     */
    @Column(name = "order_id", length = 36)
    private String orderId;

    /**
     * Reservation ID that was converted to order.
     */
    @Column(name = "reservation_id", length = 36)
    private String reservationId;

    /**
     * Timestamp when the purchase was confirmed.
     */
    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;

    /**
     * Timestamp when this record was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (trackingId == null) {
            trackingId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();

        // Default purchased_at to now if not set
        if (purchasedAt == null) {
            purchasedAt = Instant.now();
        }
    }

    /**
     * Check if user has already purchased this product.
     * This method is used for quick checks before attempting reservation.
     *
     * @return true if user has purchased at least 1 unit
     */
    public boolean hasReachedLimit() {
        // For flash sales, limit is 1 unit per product
        return quantityPurchased != null && quantityPurchased >= 1;
    }
}
