package com.cred.freestyle.flashsale.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Inventory entity tracking real-time stock counts for each product.
 * This is the source of truth for inventory availability.
 * One inventory row per SKU.
 *
 * @author Flash Sale Team
 */
@Entity
@Table(name = "inventory", indexes = {
    @Index(name = "idx_sku_unique", columnList = "sku_id", unique = true),
    @Index(name = "idx_available_count", columnList = "available_count")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {

    @Id
    @Column(name = "inventory_id", nullable = false, length = 36)
    private String inventoryId;

    /**
     * Foreign key reference to products.sku_id.
     * One-to-one relationship with Product.
     */
    @Column(name = "sku_id", nullable = false, unique = true, length = 100)
    private String skuId;

    /**
     * Total units available for this SKU in the flash sale.
     * This is set at the beginning and never changes.
     */
    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    /**
     * Currently reserved units (2-minute hold).
     * Atomic counter incremented when reservations are made.
     */
    @Column(name = "reserved_count", nullable = false)
    private Integer reservedCount;

    /**
     * Confirmed sold units (payment completed).
     * Atomic counter incremented on successful checkout.
     */
    @Column(name = "sold_count", nullable = false)
    private Integer soldCount;

    /**
     * Computed available count.
     * available_count = total_count - reserved_count - sold_count
     * This should be recalculated in application logic, not stored.
     * Kept here for query performance (indexed).
     */
    @Column(name = "available_count", nullable = false)
    private Integer availableCount;

    /**
     * Optimistic locking version to prevent race conditions.
     * JPA automatically increments this on each update.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (inventoryId == null) {
            inventoryId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();

        // Initialize counts if not set
        if (reservedCount == null) reservedCount = 0;
        if (soldCount == null) soldCount = 0;
        if (availableCount == null) {
            availableCount = totalCount - reservedCount - soldCount;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        // Recalculate available count on each update
        availableCount = totalCount - reservedCount - soldCount;
    }

    /**
     * Check if inventory is available for reservation.
     *
     * @return true if available_count > 0
     */
    public boolean isAvailable() {
        return availableCount != null && availableCount > 0;
    }

    /**
     * Check if product is completely sold out.
     *
     * @return true if no units available and reserved
     */
    public boolean isSoldOut() {
        return availableCount != null && availableCount == 0 &&
               (reservedCount + soldCount >= totalCount);
    }
}
