package com.cred.freestyle.flashsale.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Product entity representing a flash sale product.
 * Each product has its own inventory, pricing, and metadata.
 *
 * @author Flash Sale Team
 */
@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_flash_sale_event", columnList = "flash_sale_event_id"),
    @Index(name = "idx_category", columnList = "category"),
    @Index(name = "idx_active", columnList = "is_active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    /**
     * Unique SKU identifier for the product (e.g., "IPHONE15-256GB", "LAPTOP-DELL-XPS").
     * This is the primary key and routing key for Kafka partitioning.
     */
    @Id
    @Column(name = "sku_id", nullable = false, length = 100)
    private String skuId;

    /**
     * Display name of the product.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Detailed description of the product.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Product category (e.g., "Electronics", "Fashion", "Home").
     * Used for search filtering and analytics.
     */
    @Column(name = "category", length = 100)
    private String category;

    /**
     * Original price in INR before discount.
     */
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    /**
     * Flash sale price in INR (discounted price).
     */
    @Column(name = "flash_sale_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal flashSalePrice;

    /**
     * Discount percentage (e.g., 70 for 70% off).
     * Calculated from base_price and flash_sale_price.
     */
    @Column(name = "discount_percentage")
    private Integer discountPercentage;

    /**
     * URL to product image (stored in CDN).
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Total inventory available for this product in the flash sale.
     * This is denormalized from inventory table for fast read access.
     */
    @Column(name = "total_inventory", nullable = false)
    private Integer totalInventory;

    /**
     * Foreign key to flash_sale_events table.
     * Links this product to a specific flash sale event.
     */
    @Column(name = "flash_sale_event_id", length = 36)
    private String flashSaleEventId;

    /**
     * Whether this product is active and available for purchase.
     * Can be used to soft-delete or temporarily disable products.
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /**
     * Timestamp when this product was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this product was last updated.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Automatically set createdAt before persisting.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();

        // Calculate discount percentage if not set
        if (discountPercentage == null && basePrice != null && flashSalePrice != null) {
            BigDecimal discount = basePrice.subtract(flashSalePrice);
            BigDecimal percentage = discount.divide(basePrice, 4, RoundingMode.HALF_UP)
                                            .multiply(BigDecimal.valueOf(100));
            discountPercentage = percentage.intValue();
        }
    }

    /**
     * Automatically update updatedAt before updating.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
