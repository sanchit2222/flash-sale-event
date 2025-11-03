package com.cred.freestyle.flashsale.api.dto;

import com.cred.freestyle.flashsale.domain.model.Reservation;

import java.time.Instant;

/**
 * Response DTO for reservation operations.
 *
 * @author Flash Sale Team
 */
public class ReservationResponse {

    private String reservationId;
    private String userId;
    private String skuId;
    private Integer quantity;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
    private Long expiresInSeconds;

    public ReservationResponse() {
    }

    /**
     * Create response from Reservation entity.
     *
     * @param reservation Reservation entity
     * @return ReservationResponse
     */
    public static ReservationResponse fromEntity(Reservation reservation) {
        ReservationResponse response = new ReservationResponse();
        response.setReservationId(reservation.getReservationId());
        response.setUserId(reservation.getUserId());
        response.setSkuId(reservation.getSkuId());
        response.setQuantity(reservation.getQuantity());
        response.setStatus(reservation.getStatus().name());
        response.setExpiresAt(reservation.getExpiresAt());
        response.setCreatedAt(reservation.getCreatedAt());

        // Calculate time remaining until expiry
        long secondsRemaining = reservation.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        response.setExpiresInSeconds(Math.max(0, secondsRemaining));

        return response;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(Long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}
