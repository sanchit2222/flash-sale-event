package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.dto.ReservationRequest;
import com.cred.freestyle.flashsale.api.dto.ReservationResponse;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.security.SecurityUtils;
import com.cred.freestyle.flashsale.service.ReservationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for reservation operations.
 * Handles flash sale reservation creation and retrieval.
 *
 * @author Flash Sale Team
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private static final Logger logger = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservationService;
    private final CloudWatchMetricsService metricsService;

    public ReservationController(
            ReservationService reservationService,
            CloudWatchMetricsService metricsService
    ) {
        this.reservationService = reservationService;
        this.metricsService = metricsService;
    }

    /**
     * Create a new reservation for a flash sale product.
     * This endpoint handles the initial "Add to Cart" action during flash sale.
     *
     * Authorization: User can only create reservations for themselves
     *
     * @param request Reservation request with userId, skuId, quantity
     * @return Reservation response with reservation details and expiry time
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody ReservationRequest request
    ) {
        long startTime = System.currentTimeMillis();

        try {
            // Verify user can only create reservations for themselves
            SecurityUtils.verifyUserAccess(request.getUserId());

            logger.info("Creating reservation - user: {}, sku: {}, quantity: {}",
                    request.getUserId(), request.getSkuId(), request.getQuantity());

            // Call service layer to create reservation
            Reservation reservation = reservationService.createReservation(
                    request.getUserId(),
                    request.getSkuId(),
                    request.getQuantity()
            );

            // Convert entity to response DTO
            ReservationResponse response = ReservationResponse.fromEntity(reservation);

            // Record success metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsService.recordReservationLatency(duration);

            logger.info("Successfully created reservation: {} for user: {}, SKU: {}",
                    reservation.getReservationId(), request.getUserId(), request.getSkuId());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalStateException e) {
            // Business logic exceptions (out of stock, user limit exceeded, etc.)
            logger.warn("Reservation creation failed - user: {}, sku: {}, reason: {}",
                    request.getUserId(), request.getSkuId(), e.getMessage());

            // Record failure metric
            String reason = determineFailureReason(e.getMessage());
            metricsService.recordReservationFailure(request.getSkuId(), reason);

            throw e; // Will be handled by global exception handler

        } catch (Exception e) {
            logger.error("Unexpected error creating reservation - user: {}, sku: {}",
                    request.getUserId(), request.getSkuId(), e);

            metricsService.recordError("RESERVATION_CREATION_ERROR", "createReservation");
            throw e; // Will be handled by global exception handler
        }
    }

    /**
     * Get reservation details by ID.
     *
     * Authorization: User can only view their own reservations (or admin can view any)
     *
     * @param reservationId Reservation ID
     * @return Reservation details
     */
    @GetMapping("/{reservationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReservationResponse> getReservation(
            @PathVariable String reservationId
    ) {
        logger.debug("Fetching reservation: {}", reservationId);

        return reservationService.findReservationById(reservationId)
                .map(reservation -> {
                    // Verify user can only access their own reservations
                    SecurityUtils.verifyUserAccess(reservation.getUserId());
                    return ReservationResponse.fromEntity(reservation);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cancel a reservation before it expires or gets confirmed.
     *
     * Authorization: User can only cancel their own reservations
     *
     * @param reservationId Reservation ID to cancel
     * @return Cancelled reservation details
     */
    @DeleteMapping("/{reservationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReservationResponse> cancelReservation(
            @PathVariable String reservationId
    ) {
        logger.info("Cancelling reservation: {}", reservationId);

        try {
            // Fetch reservation to verify ownership
            Reservation reservation = reservationService.findReservationById(reservationId)
                    .orElseThrow(() -> new IllegalStateException("Reservation not found"));

            // Verify user can only cancel their own reservations
            SecurityUtils.verifyUserAccess(reservation.getUserId());

            Reservation cancelled = reservationService.cancelReservation(reservationId);
            ReservationResponse response = ReservationResponse.fromEntity(cancelled);

            logger.info("Successfully cancelled reservation: {}", reservationId);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            logger.warn("Failed to cancel reservation: {}, reason: {}",
                    reservationId, e.getMessage());
            throw e;
        }
    }

    /**
     * Get all active reservations for a user.
     *
     * Authorization: User can only view their own reservations
     *
     * @param userId User ID
     * @return List of active reservations
     */
    @GetMapping("/user/{userId}/active")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<ReservationResponse>> getUserActiveReservations(
            @PathVariable String userId
    ) {
        logger.debug("Fetching active reservations for user: {}", userId);

        // Verify user can only access their own reservations
        SecurityUtils.verifyUserAccess(userId);

        java.util.List<ReservationResponse> reservations = reservationService
                .getActiveReservationsByUserId(userId)
                .stream()
                .map(ReservationResponse::fromEntity)
                .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(reservations);
    }

    /**
     * Determine failure reason from exception message for metrics tagging.
     *
     * @param message Exception message
     * @return Standardized failure reason
     */
    private String determineFailureReason(String message) {
        if (message == null) {
            return "UNKNOWN";
        }

        String lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("out of stock") || lowerMessage.contains("inventory")) {
            return "OUT_OF_STOCK";
        } else if (lowerMessage.contains("already purchased") || lowerMessage.contains("limit")) {
            return "USER_LIMIT_EXCEEDED";
        } else if (lowerMessage.contains("expired")) {
            return "RESERVATION_EXPIRED";
        } else if (lowerMessage.contains("active reservation")) {
            return "DUPLICATE_RESERVATION";
        } else {
            return "UNKNOWN";
        }
    }
}
