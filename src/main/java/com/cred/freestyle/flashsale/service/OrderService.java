package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.domain.model.Order;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.UserPurchaseTracking;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.OrderRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Service for managing orders in the flash sale system.
 * Handles order creation from confirmed reservations, payment processing,
 * and user purchase tracking.
 *
 * @author Flash Sale Team
 */
@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final UserPurchaseTrackingRepository userPurchaseTrackingRepository;
    private final ReservationService reservationService;
    private final RedisCacheService cacheService;
    private final KafkaProducerService kafkaProducerService;
    private final CloudWatchMetricsService metricsService;

    public OrderService(
            OrderRepository orderRepository,
            ReservationRepository reservationRepository,
            InventoryRepository inventoryRepository,
            UserPurchaseTrackingRepository userPurchaseTrackingRepository,
            ReservationService reservationService,
            RedisCacheService cacheService,
            KafkaProducerService kafkaProducerService,
            CloudWatchMetricsService metricsService
    ) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.userPurchaseTrackingRepository = userPurchaseTrackingRepository;
        this.reservationService = reservationService;
        this.cacheService = cacheService;
        this.kafkaProducerService = kafkaProducerService;
        this.metricsService = metricsService;
    }

    /**
     * Create order from a reservation after successful payment.
     * This is the final step in the flash sale purchase flow:
     * 1. Validate reservation is active
     * 2. Create order record
     * 3. Confirm reservation (convert reserved to sold)
     * 4. Create user purchase tracking record
     * 5. Publish order created event
     *
     * @param reservationId Reservation ID
     * @param paymentTransactionId Payment transaction ID from payment gateway
     * @param paymentMethod Payment method used
     * @param shippingAddress Shipping address
     * @return Created order
     * @throws IllegalStateException if reservation not found, expired, or already converted
     */
    @Transactional
    public Order createOrderFromReservation(
            String reservationId,
            String paymentTransactionId,
            String paymentMethod,
            String shippingAddress
    ) {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("Creating order from reservation: {}, payment: {}", reservationId, paymentTransactionId);

            // Step 1: Validate reservation exists and is active
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));

            if (!reservation.isActive()) {
                logger.warn("Cannot create order from expired/inactive reservation: {}", reservationId);
                metricsService.recordOrderFailure(reservation.getSkuId(), "RESERVATION_EXPIRED");
                throw new IllegalStateException("Reservation has expired or is no longer active");
            }

            // Step 2: Check if order already exists for this reservation (idempotency)
            if (orderRepository.existsByReservationId(reservationId)) {
                logger.warn("Order already exists for reservation: {}", reservationId);
                return orderRepository.findByReservationId(reservationId).get();
            }

            // Step 3: Create order entity
            Order order = Order.builder()
                    .reservationId(reservationId)
                    .userId(reservation.getUserId())
                    .skuId(reservation.getSkuId())
                    .quantity(reservation.getQuantity())
                    .totalPrice(calculateTotalPrice(reservation))
                    .paymentTransactionId(paymentTransactionId)
                    .paymentMethod(paymentMethod)
                    .shippingAddress(shippingAddress)
                    .status(Order.OrderStatus.PAYMENT_PENDING)
                    .build();

            order = orderRepository.save(order);
            logger.info("Created order: {} for reservation: {}", order.getOrderId(), reservationId);

            // Step 4: Confirm reservation (this converts reserved inventory to sold via Kafka event)
            // The reservationService publishes CONFIRMED event which will be processed by event consumer
            reservationService.confirmReservation(reservationId);

            // Step 5: Complete payment on order
            order.completePayment(paymentTransactionId);
            order = orderRepository.save(order);

            // Step 6: Create user purchase tracking record
            UserPurchaseTracking tracking = UserPurchaseTracking.builder()
                    .userId(reservation.getUserId())
                    .skuId(reservation.getSkuId())
                    .quantityPurchased(reservation.getQuantity())
                    .orderId(order.getOrderId())
                    .reservationId(reservationId)
                    .build();

            userPurchaseTrackingRepository.save(tracking);
            logger.info("Created purchase tracking for user: {}, SKU: {}", reservation.getUserId(), reservation.getSkuId());

            // Update cache to reflect that user has purchased
            cacheService.markUserPurchased(reservation.getUserId(), reservation.getSkuId());

            // Step 7: Publish order created event
            kafkaProducerService.publishOrderCreated(
                    order.getOrderId(),
                    order.getUserId(),
                    order.getSkuId(),
                    order.getQuantity()
            );

            // Step 8: Record metrics
            metricsService.recordOrderSuccess(order.getSkuId());
            metricsService.recordCheckoutLatency(System.currentTimeMillis() - startTime);
            metricsService.recordRevenue(order.getSkuId(), order.getTotalPrice().doubleValue());

            logger.info("Successfully created order: {} from reservation: {}", order.getOrderId(), reservationId);
            return order;

        } catch (Exception e) {
            logger.error("Error creating order from reservation: {}", reservationId, e);
            metricsService.recordError("ORDER_CREATION_ERROR", "createOrderFromReservation");
            throw e;
        }
    }

    /**
     * Find order by ID.
     *
     * @param orderId Order ID
     * @return Order if found
     */
    public Optional<Order> findOrderById(String orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * Find order by reservation ID.
     *
     * @param reservationId Reservation ID
     * @return Order if found
     */
    public Optional<Order> findOrderByReservationId(String reservationId) {
        return orderRepository.findByReservationId(reservationId);
    }

    /**
     * Get all orders for a user.
     *
     * @param userId User ID
     * @return List of orders
     */
    public java.util.List<Order> getOrdersByUserId(String userId) {
        return orderRepository.findByUserId(userId);
    }

    /**
     * Mark order as fulfilled.
     *
     * @param orderId Order ID
     * @return Updated order
     */
    @Transactional
    public Order fulfillOrder(String orderId) {
        logger.info("Fulfilling order: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        order.fulfill();
        order = orderRepository.save(order);

        logger.info("Fulfilled order: {}", orderId);
        return order;
    }

    /**
     * Cancel an order.
     *
     * @param orderId Order ID
     * @param reason Cancellation reason
     * @return Updated order
     */
    @Transactional
    public Order cancelOrder(String orderId, String reason) {
        logger.info("Cancelling order: {}, reason: {}", orderId, reason);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        if (order.isFinalState()) {
            throw new IllegalStateException("Cannot cancel order in final state: " + order.getStatus());
        }

        order.cancel(reason);
        order = orderRepository.save(order);

        // TODO: Release inventory if order was not yet fulfilled

        logger.info("Cancelled order: {}", orderId);
        return order;
    }

    /**
     * Calculate total price for an order.
     * In this simple implementation, we just use the reservation quantity.
     * In production, you would fetch the product price and calculate.
     *
     * @param reservation Reservation
     * @return Total price
     */
    private BigDecimal calculateTotalPrice(Reservation reservation) {
        // TODO: Fetch product price from product service
        // For now, return a placeholder value
        // In production, you would do:
        // Product product = productService.findProductBySkuId(reservation.getSkuId()).orElseThrow();
        // return product.getFlashSalePrice().multiply(BigDecimal.valueOf(reservation.getQuantity()));
        return BigDecimal.valueOf(999.99); // Placeholder
    }
}
