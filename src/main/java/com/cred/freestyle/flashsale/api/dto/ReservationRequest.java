package com.cred.freestyle.flashsale.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for creating a reservation.
 *
 * @author Flash Sale Team
 */
public class ReservationRequest {

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotBlank(message = "SKU ID is required")
    private String skuId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    public ReservationRequest() {
    }

    public ReservationRequest(String userId, String skuId, Integer quantity) {
        this.userId = userId;
        this.skuId = skuId;
        this.quantity = quantity;
    }

    // Getters and setters
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
}
