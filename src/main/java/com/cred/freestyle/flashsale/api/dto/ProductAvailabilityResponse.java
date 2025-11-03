package com.cred.freestyle.flashsale.api.dto;

import com.cred.freestyle.flashsale.domain.model.Product;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for product availability information.
 * Used to display real-time stock availability to users.
 *
 * @author Flash Sale Team
 */
public class ProductAvailabilityResponse {

    private String skuId;
    private String name;
    private String description;
    private String category;
    private BigDecimal basePrice;
    private BigDecimal flashSalePrice;
    private Integer discountPercentage;
    private Integer availableCount;
    private Integer totalInventory;
    private Boolean isAvailable;
    private Boolean isActive;
    private String imageUrl;

    public ProductAvailabilityResponse() {
    }

    /**
     * Create response from Product entity.
     *
     * @param product Product entity
     * @param availableCount Current available inventory count
     * @return ProductAvailabilityResponse
     */
    public static ProductAvailabilityResponse fromEntity(Product product, Integer availableCount) {
        ProductAvailabilityResponse response = new ProductAvailabilityResponse();
        response.setSkuId(product.getSkuId());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setCategory(product.getCategory());
        response.setBasePrice(product.getBasePrice());
        response.setFlashSalePrice(product.getFlashSalePrice());
        response.setDiscountPercentage(product.getDiscountPercentage());
        response.setTotalInventory(product.getTotalInventory());
        response.setAvailableCount(availableCount);
        response.setIsAvailable(availableCount != null && availableCount > 0);
        response.setIsActive(product.getIsActive());
        response.setImageUrl(product.getImageUrl());

        return response;
    }

    // Getters and setters
    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public BigDecimal getFlashSalePrice() {
        return flashSalePrice;
    }

    public void setFlashSalePrice(BigDecimal flashSalePrice) {
        this.flashSalePrice = flashSalePrice;
    }

    public Integer getDiscountPercentage() {
        return discountPercentage;
    }

    public void setDiscountPercentage(Integer discountPercentage) {
        this.discountPercentage = discountPercentage;
    }

    public Integer getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(Integer availableCount) {
        this.availableCount = availableCount;
    }

    public Integer getTotalInventory() {
        return totalInventory;
    }

    public void setTotalInventory(Integer totalInventory) {
        this.totalInventory = totalInventory;
    }

    public Boolean getIsAvailable() {
        return isAvailable;
    }

    public void setIsAvailable(Boolean isAvailable) {
        this.isAvailable = isAvailable;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
