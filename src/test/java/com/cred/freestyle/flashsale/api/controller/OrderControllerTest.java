package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.exception.GlobalExceptionHandler;
import com.cred.freestyle.flashsale.domain.model.Order;
import com.cred.freestyle.flashsale.domain.model.Order.OrderStatus;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for OrderController using MockMvc.
 */
@WebMvcTest(OrderController.class)
@ContextConfiguration(classes = {OrderController.class, GlobalExceptionHandler.class})
@DisplayName("OrderController Tests")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private CloudWatchMetricsService metricsService;

    // ========================================
    // POST /api/v1/orders/checkout Tests
    // ========================================

    @Test
    @DisplayName("POST /checkout - Valid request returns 201 Created")
    void checkout_ValidRequest_Returns201() throws Exception {
        // Given
        String requestBody = """
                {
                    "reservationId": "RES-001",
                    "paymentTransactionId": "PAY-12345",
                    "paymentMethod": "CREDIT_CARD",
                    "shippingAddress": "123 Main St, City, State 12345"
                }
                """;

        Order order = Order.builder()
                .orderId("ORD-001")
                .reservationId("RES-001")
                .userId("user-123")
                .skuId("SKU-001")
                .quantity(1)
                .totalPrice(new BigDecimal("999.99"))
                .paymentTransactionId("PAY-12345")
                .paymentMethod("CREDIT_CARD")
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build();

        when(orderService.createOrderFromReservation(
                "RES-001", "PAY-12345", "CREDIT_CARD", "123 Main St, City, State 12345"))
                .thenReturn(order);

        // When / Then
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ORD-001"))
                .andExpect(jsonPath("$.reservationId").value("RES-001"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(orderService).createOrderFromReservation(
                "RES-001", "PAY-12345", "CREDIT_CARD", "123 Main St, City, State 12345");
        verify(metricsService).recordCheckoutLatency(anyLong());
    }

    @Test
    @DisplayName("POST /checkout - Missing reservationId returns 400")
    void checkout_MissingReservationId_Returns400() throws Exception {
        // Given
        String requestBody = """
                {
                    "paymentTransactionId": "PAY-12345",
                    "paymentMethod": "CREDIT_CARD",
                    "shippingAddress": "123 Main St"
                }
                """;

        // When / Then
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrderFromReservation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /checkout - Reservation expired throws exception")
    void checkout_ReservationExpired_ThrowsException() throws Exception {
        // Given
        String requestBody = """
                {
                    "reservationId": "RES-001",
                    "paymentTransactionId": "PAY-12345",
                    "paymentMethod": "CREDIT_CARD",
                    "shippingAddress": "123 Main St"
                }
                """;

        when(orderService.createOrderFromReservation(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Reservation has expired or is no longer active"));

        // When / Then
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /checkout - Reservation not found throws exception")
    void checkout_ReservationNotFound_ThrowsException() throws Exception {
        // Given
        String requestBody = """
                {
                    "reservationId": "RES-NONEXISTENT",
                    "paymentTransactionId": "PAY-12345",
                    "paymentMethod": "CREDIT_CARD",
                    "shippingAddress": "123 Main St"
                }
                """;

        when(orderService.createOrderFromReservation(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Reservation not found: RES-NONEXISTENT"));

        // When / Then
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // GET /api/v1/orders/{orderId} Tests
    // ========================================

    @Test
    @DisplayName("GET /{orderId} - Existing order returns 200")
    void getOrder_Exists_Returns200() throws Exception {
        // Given
        String orderId = "ORD-001";
        Order order = Order.builder()
                .orderId(orderId)
                .userId("user-123")
                .skuId("SKU-001")
                .status(OrderStatus.CONFIRMED)
                .build();

        when(orderService.findOrderById(orderId)).thenReturn(Optional.of(order));

        // When / Then
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(orderService).findOrderById(orderId);
    }

    @Test
    @DisplayName("GET /{orderId} - Non-existent order returns 404")
    void getOrder_NotFound_Returns404() throws Exception {
        // Given
        String orderId = "ORD-NONEXISTENT";
        when(orderService.findOrderById(orderId)).thenReturn(Optional.empty());

        // When / Then
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isNotFound());

        verify(orderService).findOrderById(orderId);
    }

    // ========================================
    // GET /api/v1/orders/reservation/{reservationId} Tests
    // ========================================

    @Test
    @DisplayName("GET /reservation/{reservationId} - Returns order for reservation")
    void getOrderByReservation_Exists_Returns200() throws Exception {
        // Given
        String reservationId = "RES-001";
        Order order = Order.builder()
                .orderId("ORD-001")
                .reservationId(reservationId)
                .userId("user-123")
                .skuId("SKU-001")
                .quantity(1)
                .totalPrice(new BigDecimal("999.99"))
                .status(OrderStatus.CONFIRMED)
                .build();

        when(orderService.findOrderByReservationId(reservationId)).thenReturn(Optional.of(order));

        // When / Then
        mockMvc.perform(get("/api/v1/orders/reservation/{reservationId}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId));

        verify(orderService).findOrderByReservationId(reservationId);
    }

    @Test
    @DisplayName("GET /reservation/{reservationId} - No order returns 404")
    void getOrderByReservation_NotFound_Returns404() throws Exception {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(orderService.findOrderByReservationId(reservationId)).thenReturn(Optional.empty());

        // When / Then
        mockMvc.perform(get("/api/v1/orders/reservation/{reservationId}", reservationId))
                .andExpect(status().isNotFound());
    }

    // ========================================
    // GET /api/v1/orders/user/{userId} Tests
    // ========================================

    @Test
    @DisplayName("GET /user/{userId} - Returns user orders")
    void getUserOrders_ReturnsOrderList() throws Exception {
        // Given
        String userId = "user-123";
        Order order1 = Order.builder()
                .orderId("ORD-001")
                .userId(userId)
                .skuId("SKU-001")
                .reservationId("RES-001")
                .quantity(1)
                .totalPrice(new BigDecimal("999.99"))
                .status(OrderStatus.CONFIRMED)
                .build();
        Order order2 = Order.builder()
                .orderId("ORD-002")
                .userId(userId)
                .skuId("SKU-002")
                .reservationId("RES-002")
                .quantity(1)
                .totalPrice(new BigDecimal("1499.99"))
                .status(OrderStatus.CONFIRMED)
                .build();
        List<Order> orders = Arrays.asList(order1, order2);

        when(orderService.getOrdersByUserId(userId)).thenReturn(orders);

        // When / Then
        mockMvc.perform(get("/api/v1/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].orderId").value("ORD-001"))
                .andExpect(jsonPath("$[1].orderId").value("ORD-002"));

        verify(orderService).getOrdersByUserId(userId);
    }

    @Test
    @DisplayName("GET /user/{userId} - Returns empty list when no orders")
    void getUserOrders_EmptyList_Returns200() throws Exception {
        // Given
        String userId = "user-123";
        when(orderService.getOrdersByUserId(userId)).thenReturn(Arrays.asList());

        // When / Then
        mockMvc.perform(get("/api/v1/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ========================================
    // PUT /api/v1/orders/{orderId}/fulfill Tests
    // ========================================

    @Test
    @DisplayName("PUT /{orderId}/fulfill - Successfully fulfills order")
    void fulfillOrder_Success_Returns200() throws Exception {
        // Given
        String orderId = "ORD-001";
        Order fulfilledOrder = Order.builder()
                .orderId(orderId)
                .status(OrderStatus.FULFILLED)
                .fulfilledAt(Instant.now())
                .build();

        when(orderService.fulfillOrder(orderId)).thenReturn(fulfilledOrder);

        // When / Then
        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        verify(orderService).fulfillOrder(orderId);
    }

    @Test
    @DisplayName("PUT /{orderId}/fulfill - Order not found throws exception")
    void fulfillOrder_NotFound_ThrowsException() throws Exception {
        // Given
        String orderId = "ORD-NONEXISTENT";
        when(orderService.fulfillOrder(orderId))
                .thenThrow(new IllegalStateException("Order not found: " + orderId));

        // When / Then
        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", orderId))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // DELETE /api/v1/orders/{orderId} Tests
    // ========================================

    @Test
    @DisplayName("DELETE /{orderId} - Successfully cancels order")
    void cancelOrder_Success_Returns200() throws Exception {
        // Given
        String orderId = "ORD-001";
        Order cancelledOrder = Order.builder()
                .orderId(orderId)
                .status(OrderStatus.CANCELLED)
                .cancellationReason("Customer request")
                .build();

        when(orderService.cancelOrder(orderId, "Customer request")).thenReturn(cancelledOrder);

        // When / Then
        mockMvc.perform(delete("/api/v1/orders/{orderId}", orderId)
                        .param("reason", "Customer request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(orderService).cancelOrder(orderId, "Customer request");
    }

    @Test
    @DisplayName("DELETE /{orderId} - Default reason when not provided")
    void cancelOrder_DefaultReason_Returns200() throws Exception {
        // Given
        String orderId = "ORD-001";
        Order cancelledOrder = Order.builder()
                .orderId(orderId)
                .status(OrderStatus.CANCELLED)
                .build();

        when(orderService.cancelOrder(orderId, "Customer request")).thenReturn(cancelledOrder);

        // When / Then
        mockMvc.perform(delete("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isOk());

        verify(orderService).cancelOrder(orderId, "Customer request");
    }

    @Test
    @DisplayName("DELETE /{orderId} - Cannot cancel fulfilled order")
    void cancelOrder_FulfilledOrder_ThrowsException() throws Exception {
        // Given
        String orderId = "ORD-001";
        when(orderService.cancelOrder(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Cannot cancel order in final state: FULFILLED"));

        // When / Then
        mockMvc.perform(delete("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    @DisplayName("POST /checkout - Unexpected error records metrics")
    void checkout_UnexpectedError_RecordsMetrics() throws Exception {
        // Given
        String requestBody = """
                {
                    "reservationId": "RES-001",
                    "paymentTransactionId": "PAY-12345",
                    "paymentMethod": "CREDIT_CARD",
                    "shippingAddress": "123 Main St"
                }
                """;

        when(orderService.createOrderFromReservation(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Database error"));

        // When / Then
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is5xxServerError());

        verify(metricsService).recordError("CHECKOUT_ERROR", "checkout");
    }
}
