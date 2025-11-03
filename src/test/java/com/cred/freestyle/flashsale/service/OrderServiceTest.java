package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.domain.model.Order;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.domain.model.UserPurchaseTracking;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.OrderRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderService.
 * Tests order creation, fulfillment, and cancellation logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Unit Tests")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private UserPurchaseTrackingRepository userPurchaseTrackingRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private CloudWatchMetricsService metricsService;

    @InjectMocks
    private OrderService orderService;

    private String reservationId;
    private String userId;
    private String skuId;
    private String paymentTransactionId;
    private String paymentMethod;
    private String shippingAddress;
    private Reservation activeReservation;

    @BeforeEach
    void setUp() {
        reservationId = "RES-001";
        userId = "user-123";
        skuId = "SKU-001";
        paymentTransactionId = "PAY-12345";
        paymentMethod = "CREDIT_CARD";
        shippingAddress = "123 Main St, City, State 12345";

        activeReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(1)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();
    }

    // ========================================
    // createOrderFromReservation() Tests
    // ========================================

    @Test
    @DisplayName("createOrderFromReservation - Success: Should create order from active reservation")
    void createOrderFromReservation_Success() {
        // Given
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(activeReservation));
        when(orderRepository.existsByReservationId(reservationId)).thenReturn(false);

        Order savedOrder = Order.builder()
                .orderId("ORD-001")
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(1)
                .totalPrice(new BigDecimal("999.99"))
                .paymentTransactionId(paymentTransactionId)
                .status(Order.OrderStatus.PAYMENT_PENDING)
                .build();

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(reservationService.confirmReservation(reservationId)).thenReturn(activeReservation);

        // When
        Order result = orderService.createOrderFromReservation(
                reservationId,
                paymentTransactionId,
                paymentMethod,
                shippingAddress
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo("ORD-001");
        assertThat(result.getReservationId()).isEqualTo(reservationId);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getSkuId()).isEqualTo(skuId);

        // Verify workflow
        verify(reservationRepository).findById(reservationId);
        verify(orderRepository).existsByReservationId(reservationId);
        verify(orderRepository, times(2)).save(any(Order.class)); // Once for creation, once for payment completion
        verify(reservationService).confirmReservation(reservationId);
        verify(inventoryRepository).confirmReservation(skuId, 1);
        verify(userPurchaseTrackingRepository).save(any(UserPurchaseTracking.class));
        verify(kafkaProducerService).publishOrderCreated(anyString(), eq(userId), eq(skuId), eq(1));
        verify(metricsService).recordOrderSuccess(skuId);
        verify(metricsService).recordCheckoutLatency(anyLong());
        verify(metricsService).recordRevenue(eq(skuId), anyDouble());
    }

    @Test
    @DisplayName("createOrderFromReservation - ReservationNotFound: Should throw exception")
    void createOrderFromReservation_ReservationNotFound() {
        // Given
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> orderService.createOrderFromReservation(
                reservationId, paymentTransactionId, paymentMethod, shippingAddress
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createOrderFromReservation - ReservationExpired: Should throw exception")
    void createOrderFromReservation_ReservationExpired() {
        // Given
        Reservation expiredReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)) // Expired
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(expiredReservation));

        // When / Then
        assertThatThrownBy(() -> orderService.createOrderFromReservation(
                reservationId, paymentTransactionId, paymentMethod, shippingAddress
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        verify(metricsService).recordOrderFailure(skuId, "RESERVATION_EXPIRED");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createOrderFromReservation - ReservationAlreadyConfirmed: Should throw exception")
    void createOrderFromReservation_ReservationAlreadyConfirmed() {
        // Given
        Reservation confirmedReservation = Reservation.builder()
                .reservationId(reservationId)
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(confirmedReservation));

        // When / Then
        assertThatThrownBy(() -> orderService.createOrderFromReservation(
                reservationId, paymentTransactionId, paymentMethod, shippingAddress
        ))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("createOrderFromReservation - Idempotent: Should return existing order if already created")
    void createOrderFromReservation_Idempotent() {
        // Given
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(activeReservation));
        when(orderRepository.existsByReservationId(reservationId)).thenReturn(true);

        Order existingOrder = Order.builder()
                .orderId("ORD-EXISTING")
                .reservationId(reservationId)
                .build();

        when(orderRepository.findByReservationId(reservationId)).thenReturn(Optional.of(existingOrder));

        // When
        Order result = orderService.createOrderFromReservation(
                reservationId, paymentTransactionId, paymentMethod, shippingAddress
        );

        // Then
        assertThat(result.getOrderId()).isEqualTo("ORD-EXISTING");
        verify(orderRepository, never()).save(any(Order.class)); // No new order created
        verify(reservationService, never()).confirmReservation(anyString());
    }

    @Test
    @DisplayName("createOrderFromReservation - ShouldCreateUserPurchaseTracking")
    void createOrderFromReservation_ShouldCreateUserPurchaseTracking() {
        // Given
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(activeReservation));
        when(orderRepository.existsByReservationId(reservationId)).thenReturn(false);

        Order savedOrder = Order.builder()
                .orderId("ORD-001")
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(1)
                .totalPrice(new BigDecimal("999.99"))
                .status(Order.OrderStatus.PAYMENT_PENDING)
                .build();

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(reservationService.confirmReservation(reservationId)).thenReturn(activeReservation);

        // When
        orderService.createOrderFromReservation(
                reservationId, paymentTransactionId, paymentMethod, shippingAddress
        );

        // Then
        verify(userPurchaseTrackingRepository).save(argThat(tracking ->
                tracking.getUserId().equals(userId) &&
                        tracking.getSkuId().equals(skuId) &&
                        tracking.getQuantityPurchased().equals(1) &&
                        tracking.getOrderId().equals("ORD-001") &&
                        tracking.getReservationId().equals(reservationId)
        ));
    }

    // ========================================
    // findOrderById() Tests
    // ========================================

    @Test
    @DisplayName("findOrderById - Found: Should return order when found")
    void findOrderById_Found() {
        // Given
        String orderId = "ORD-001";
        Order order = Order.builder().orderId(orderId).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When
        Optional<Order> result = orderService.findOrderById(orderId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("findOrderById - NotFound: Should return empty when not found")
    void findOrderById_NotFound() {
        // Given
        String orderId = "ORD-NONEXISTENT";
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When
        Optional<Order> result = orderService.findOrderById(orderId);

        // Then
        assertThat(result).isEmpty();
    }

    // ========================================
    // findOrderByReservationId() Tests
    // ========================================

    @Test
    @DisplayName("findOrderByReservationId - Found: Should return order for reservation")
    void findOrderByReservationId_Found() {
        // Given
        Order order = Order.builder().orderId("ORD-001").reservationId(reservationId).build();
        when(orderRepository.findByReservationId(reservationId)).thenReturn(Optional.of(order));

        // When
        Optional<Order> result = orderService.findOrderByReservationId(reservationId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getReservationId()).isEqualTo(reservationId);
    }

    // ========================================
    // getOrdersByUserId() Tests
    // ========================================

    @Test
    @DisplayName("getOrdersByUserId - ShouldReturnUserOrders")
    void getOrdersByUserId_ShouldReturnUserOrders() {
        // Given
        Order order1 = Order.builder().orderId("ORD-001").userId(userId).build();
        Order order2 = Order.builder().orderId("ORD-002").userId(userId).build();
        List<Order> userOrders = Arrays.asList(order1, order2);

        when(orderRepository.findByUserId(userId)).thenReturn(userOrders);

        // When
        List<Order> result = orderService.getOrdersByUserId(userId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(order1, order2);
    }

    // ========================================
    // fulfillOrder() Tests
    // ========================================

    @Test
    @DisplayName("fulfillOrder - Success: Should mark order as fulfilled")
    void fulfillOrder_Success() {
        // Given
        String orderId = "ORD-001";
        Order order = Order.builder()
                .orderId(orderId)
                .status(Order.OrderStatus.CONFIRMED)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        // When
        Order result = orderService.fulfillOrder(orderId);

        // Then
        verify(orderRepository).save(order);
        assertThat(result.getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("fulfillOrder - NotFound: Should throw exception")
    void fulfillOrder_NotFound() {
        // Given
        String orderId = "ORD-NONEXISTENT";
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> orderService.fulfillOrder(orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    // ========================================
    // cancelOrder() Tests
    // ========================================

    @Test
    @DisplayName("cancelOrder - Success: Should cancel order")
    void cancelOrder_Success() {
        // Given
        String orderId = "ORD-001";
        String reason = "Customer request";
        Order order = Order.builder()
                .orderId(orderId)
                .status(Order.OrderStatus.PAYMENT_PENDING)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        // When
        Order result = orderService.cancelOrder(orderId, reason);

        // Then
        verify(orderRepository).save(order);
        assertThat(result.getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("cancelOrder - NotFound: Should throw exception")
    void cancelOrder_NotFound() {
        // Given
        String orderId = "ORD-NONEXISTENT";
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> orderService.cancelOrder(orderId, "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("cancelOrder - FinalState: Should throw when order in final state")
    void cancelOrder_FinalState() {
        // Given
        String orderId = "ORD-001";
        Order order = Order.builder()
                .orderId(orderId)
                .status(Order.OrderStatus.FULFILLED)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When / Then
        assertThatThrownBy(() -> orderService.cancelOrder(orderId, "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("final state");

        verify(orderRepository, never()).save(any(Order.class));
    }
}
