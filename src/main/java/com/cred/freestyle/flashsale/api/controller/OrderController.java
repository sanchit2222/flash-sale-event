package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.dto.CheckoutRequest;
import com.cred.freestyle.flashsale.api.dto.CheckoutResponse;
import com.cred.freestyle.flashsale.domain.model.Order;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.security.SecurityUtils;
import com.cred.freestyle.flashsale.service.OrderService;
import com.cred.freestyle.flashsale.service.ReservationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for order operations.
 * Handles checkout and order management for flash sale purchases.
 *
 * @author Flash Sale Team
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final ReservationService reservationService;
    private final CloudWatchMetricsService metricsService;

    public OrderController(
            OrderService orderService,
            ReservationService reservationService,
            CloudWatchMetricsService metricsService
    ) {
        this.orderService = orderService;
        this.reservationService = reservationService;
        this.metricsService = metricsService;
    }

    /**
     * Create order from reservation after successful payment.
     * This is the final step in the flash sale purchase flow.
     *
     * Flow:
     * 1. User creates reservation (holds inventory for 2 minutes)
     * 2. User completes payment on payment gateway
     * 3. User calls this endpoint with payment details
     * 4. System confirms reservation and creates order
     *
     * Authorization: User can only checkout their own reservations
     *
     * @param request Checkout request with reservation and payment details
     * @return Order details
     */
    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CheckoutResponse> checkout(
            @Valid @RequestBody CheckoutRequest request
    ) {
        long startTime = System.currentTimeMillis();

        try {
            // Verify user owns the reservation they're checking out
            Reservation reservation = reservationService.findReservationById(request.getReservationId())
                    .orElseThrow(() -> new IllegalStateException("Reservation not found: " + request.getReservationId()));

            SecurityUtils.verifyUserAccess(reservation.getUserId());

            logger.info("Processing checkout - reservation: {}, payment: {}",
                    request.getReservationId(), request.getPaymentTransactionId());

            // Call service to create order from reservation
            Order order = orderService.createOrderFromReservation(
                    request.getReservationId(),
                    request.getPaymentTransactionId(),
                    request.getPaymentMethod(),
                    request.getShippingAddress()
            );

            // Convert to response DTO
            CheckoutResponse response = CheckoutResponse.fromEntity(order);

            // Record metrics
            long duration = System.currentTimeMillis() - startTime;
            metricsService.recordCheckoutLatency(duration);

            logger.info("Successfully processed checkout - order: {}, reservation: {}",
                    order.getOrderId(), request.getReservationId());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalStateException e) {
            // Business logic exceptions (reservation expired, not found, etc.)
            logger.warn("Checkout failed - reservation: {}, reason: {}",
                    request.getReservationId(), e.getMessage());

            // Error metrics already recorded in service layer
            throw e; // Will be handled by global exception handler

        } catch (Exception e) {
            logger.error("Unexpected error during checkout - reservation: {}",
                    request.getReservationId(), e);

            metricsService.recordError("CHECKOUT_ERROR", "checkout");
            throw e;
        }
    }

    /**
     * Get order details by order ID.
     *
     * Authorization: User can only view their own orders (or admin can view any)
     *
     * @param orderId Order ID
     * @return Order details
     */
    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CheckoutResponse> getOrder(@PathVariable String orderId) {
        logger.debug("Fetching order: {}", orderId);

        return orderService.findOrderById(orderId)
                .map(order -> {
                    // Verify user can only access their own orders
                    SecurityUtils.verifyUserAccess(order.getUserId());
                    return CheckoutResponse.fromEntity(order);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get order by reservation ID.
     *
     * Authorization: User can only view orders for their own reservations
     *
     * @param reservationId Reservation ID
     * @return Order details if order exists for the reservation
     */
    @GetMapping("/reservation/{reservationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CheckoutResponse> getOrderByReservation(
            @PathVariable String reservationId
    ) {
        logger.debug("Fetching order for reservation: {}", reservationId);

        // Verify user owns the reservation
        Reservation reservation = reservationService.findReservationById(reservationId)
                .orElse(null);

        if (reservation != null) {
            SecurityUtils.verifyUserAccess(reservation.getUserId());
        }

        return orderService.findOrderByReservationId(reservationId)
                .map(CheckoutResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all orders for a user.
     *
     * Authorization: User can only view their own orders
     *
     * @param userId User ID
     * @return List of user's orders
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CheckoutResponse>> getUserOrders(@PathVariable String userId) {
        logger.debug("Fetching orders for user: {}", userId);

        // Verify user can only access their own orders
        SecurityUtils.verifyUserAccess(userId);

        List<CheckoutResponse> orders = orderService.getOrdersByUserId(userId)
                .stream()
                .map(CheckoutResponse::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(orders);
    }

    /**
     * Mark an order as fulfilled (admin/fulfillment endpoint).
     *
     * Authorization: Admin only
     *
     * @param orderId Order ID
     * @return Updated order
     */
    @PutMapping("/{orderId}/fulfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CheckoutResponse> fulfillOrder(@PathVariable String orderId) {
        logger.info("Marking order as fulfilled: {}", orderId);

        try {
            // Admin access already verified by @PreAuthorize
            Order order = orderService.fulfillOrder(orderId);
            CheckoutResponse response = CheckoutResponse.fromEntity(order);

            logger.info("Successfully fulfilled order: {}", orderId);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            logger.warn("Failed to fulfill order: {}, reason: {}", orderId, e.getMessage());
            throw e;
        }
    }

    /**
     * Cancel an order (customer service endpoint).
     *
     * Authorization: User can cancel their own orders, or admin can cancel any
     *
     * @param orderId Order ID
     * @param reason Cancellation reason (query parameter)
     * @return Updated order
     */
    @DeleteMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CheckoutResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestParam(required = false, defaultValue = "Customer request") String reason
    ) {
        logger.info("Cancelling order: {}, reason: {}", orderId, reason);

        try {
            // Fetch order to verify ownership
            Order existingOrder = orderService.findOrderById(orderId)
                    .orElseThrow(() -> new IllegalStateException("Order not found"));

            // Verify user can only cancel their own orders (admins can cancel any)
            SecurityUtils.verifyUserAccess(existingOrder.getUserId());

            Order order = orderService.cancelOrder(orderId, reason);
            CheckoutResponse response = CheckoutResponse.fromEntity(order);

            logger.info("Successfully cancelled order: {}", orderId);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            logger.warn("Failed to cancel order: {}, reason: {}", orderId, e.getMessage());
            throw e;
        }
    }
}
