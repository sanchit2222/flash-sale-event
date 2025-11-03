package com.cred.freestyle.flashsale.api;

import com.cred.freestyle.flashsale.domain.model.*;
import com.cred.freestyle.flashsale.domain.model.Order.OrderStatus;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack API integration tests for Order endpoints.
 * Tests checkout flow, order management, and fulfillment with real database.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:orderdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.data.redis.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("Order API Integration Tests")
class OrderApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockBean
    private RedisCacheService redisCacheService;

    @MockBean
    private CloudWatchMetricsService cloudWatchMetricsService;

    private Product testProduct;
    private Inventory testInventory;
    private Reservation testReservation;

    @BeforeEach
    void setUp() {
        // Clean up
        orderRepository.deleteAll();
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        // Create test product
        testProduct = Product.builder()
                .skuId("SKU-ORDER-TEST")
                .name("Order Test Product")
                .description("Product for order testing")
                .category("Electronics")
                .basePrice(new BigDecimal("2999.99"))
                .flashSalePrice(new BigDecimal("1499.99"))
                .flashSaleEventId("EVENT-ORDER-001")
                .totalInventory(100)
                .isActive(true)
                .build();
        productRepository.save(testProduct);

        // Create test inventory
        testInventory = Inventory.builder()
                .skuId("SKU-ORDER-TEST")
                .totalCount(100)
                .availableCount(95)
                .reservedCount(3)
                .soldCount(2)
                .build();
        inventoryRepository.save(testInventory);

        // Create test reservation
        testReservation = new Reservation();
        testReservation.setReservationId("RES-ORDER-TEST");
        testReservation.setUserId("user-order-test");
        testReservation.setSkuId("SKU-ORDER-TEST");
        testReservation.setQuantity(1);
        testReservation.setStatus(ReservationStatus.RESERVED);
        testReservation.setCreatedAt(Instant.now());
        testReservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(testReservation);
    }

    // ========================================
    // Complete Checkout Flow Tests
    // ========================================

    @Test
    @DisplayName("Complete checkout flow - Create order, retrieve, and fulfill")
    void completeCheckoutFlow_Success() throws Exception {
        String checkoutRequest = """
                {
                    "reservationId": "RES-ORDER-TEST",
                    "paymentMethod": "CREDIT_CARD",
                    "paymentTransactionId": "TXN-123456",
                    "shippingAddress": "123 Main St, City, Country"
                }
                """;

        // Step 1: Create order via checkout
        String orderId = mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.reservationId").value("RES-ORDER-TEST"))
                .andExpect(jsonPath("$.userId").value("user-order-test"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentMethod").value("CREDIT_CARD"))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        // Verify reservation is confirmed
        Reservation updatedReservation = reservationRepository.findById("RES-ORDER-TEST").orElseThrow();
        assertThat(updatedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // Verify inventory is updated
        Inventory updatedInventory = inventoryRepository.findBySkuId("SKU-ORDER-TEST").orElseThrow();
        assertThat(updatedInventory.getReservedCount()).isEqualTo(2); // One less reserved
        assertThat(updatedInventory.getSoldCount()).isEqualTo(3); // One more sold

        // Step 2: Retrieve order
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Step 3: Fulfill order
        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.fulfilledAt").exists());
    }

    // ========================================
    // Checkout Validation Tests
    // ========================================

    @Test
    @DisplayName("POST /checkout - Missing paymentMethod returns 400")
    void checkout_MissingPaymentMethod_Returns400() throws Exception {
        String requestBody = """
                {
                    "reservationId": "RES-ORDER-TEST",
                    "shippingAddress": "123 Main St"
                }
                """;

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /checkout - Non-existent reservation returns 400")
    void checkout_NonExistentReservation_Returns400() throws Exception {
        String requestBody = """
                {
                    "reservationId": "RES-NONEXISTENT",
                    "paymentMethod": "CREDIT_CARD",
                    "paymentTransactionId": "TXN-999"
                }
                """;

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /checkout - Expired reservation returns 400")
    void checkout_ExpiredReservation_Returns400() throws Exception {
        // Create expired reservation
        Reservation expired = new Reservation();
        expired.setReservationId("RES-EXPIRED");
        expired.setUserId("user-test");
        expired.setSkuId("SKU-ORDER-TEST");
        expired.setQuantity(1);
        expired.setStatus(ReservationStatus.RESERVED);
        expired.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        reservationRepository.save(expired);

        String requestBody = """
                {
                    "reservationId": "RES-EXPIRED",
                    "paymentMethod": "CREDIT_CARD",
                    "paymentTransactionId": "TXN-EXP"
                }
                """;

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /checkout - Already confirmed reservation returns 400")
    void checkout_AlreadyConfirmedReservation_Returns400() throws Exception {
        // Create confirmed reservation
        Reservation confirmed = new Reservation();
        confirmed.setReservationId("RES-CONFIRMED");
        confirmed.setUserId("user-test");
        confirmed.setSkuId("SKU-ORDER-TEST");
        confirmed.setQuantity(1);
        confirmed.setStatus(ReservationStatus.CONFIRMED);
        confirmed.setCreatedAt(Instant.now());
        confirmed.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        confirmed.setConfirmedAt(Instant.now());
        reservationRepository.save(confirmed);

        String requestBody = """
                {
                    "reservationId": "RES-CONFIRMED",
                    "paymentMethod": "CREDIT_CARD",
                    "paymentTransactionId": "TXN-CONF"
                }
                """;

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // Order Retrieval Tests
    // ========================================

    @Test
    @DisplayName("GET /{orderId} - Existing order returns 200")
    void getOrder_Exists_Returns200() throws Exception {
        // Create order directly
        Order order = new Order();
        order.setOrderId("ORD-GET-TEST");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentMethod("CREDIT_CARD");
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(get("/api/v1/orders/{orderId}", "ORD-GET-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-GET-TEST"))
                .andExpect(jsonPath("$.userId").value("user-order-test"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("GET /{orderId} - Non-existent order returns 404")
    void getOrder_NotFound_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{orderId}", "ORD-NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /reservation/{reservationId} - Returns order for reservation")
    void getOrderByReservation_Exists_Returns200() throws Exception {
        // Create order
        Order order = new Order();
        order.setOrderId("ORD-RES-TEST");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.CONFIRMED);
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(get("/api/v1/orders/reservation/{reservationId}", "RES-ORDER-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value("RES-ORDER-TEST"));
    }

    @Test
    @DisplayName("GET /user/{userId} - Returns all user orders")
    void getUserOrders_ReturnsUserOrders() throws Exception {
        String userId = "user-multiple-orders";

        // Create multiple orders
        Order order1 = new Order();
        order1.setOrderId("ORD-USER-1");
        order1.setReservationId("RES-1");
        order1.setUserId(userId);
        order1.setSkuId("SKU-ORDER-TEST");
        order1.setQuantity(1);
        order1.setTotalPrice(new BigDecimal("1499.99"));
        order1.setStatus(OrderStatus.CONFIRMED);
        order1.setCreatedAt(Instant.now());
        orderRepository.save(order1);

        Order order2 = new Order();
        order2.setOrderId("ORD-USER-2");
        order2.setReservationId("RES-2");
        order2.setUserId(userId);
        order2.setSkuId("SKU-ORDER-TEST");
        order2.setQuantity(1);
        order2.setTotalPrice(new BigDecimal("2999.99"));
        order2.setStatus(OrderStatus.FULFILLED);
        order2.setCreatedAt(Instant.now());
        orderRepository.save(order2);

        mockMvc.perform(get("/api/v1/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].userId", everyItem(is(userId))));
    }

    // ========================================
    // Order Fulfillment Tests
    // ========================================

    @Test
    @DisplayName("PUT /{orderId}/fulfill - Confirmed order can be fulfilled")
    void fulfillOrder_ConfirmedOrder_Success() throws Exception {
        // Create confirmed order
        Order order = new Order();
        order.setOrderId("ORD-FULFILL-TEST");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.CONFIRMED);
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", "ORD-FULFILL-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.fulfilledAt").exists());
    }

    @Test
    @DisplayName("PUT /{orderId}/fulfill - Already fulfilled order returns 400")
    void fulfillOrder_AlreadyFulfilled_Returns400() throws Exception {
        // Create fulfilled order
        Order order = new Order();
        order.setOrderId("ORD-ALREADY-FULFILLED");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.FULFILLED);
        order.setCreatedAt(Instant.now());
        order.setFulfilledAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", "ORD-ALREADY-FULFILLED"))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // Order Cancellation Tests
    // ========================================

    @Test
    @DisplayName("DELETE /{orderId} - Can cancel confirmed order")
    void cancelOrder_ConfirmedOrder_Success() throws Exception {
        // Create confirmed order
        Order order = new Order();
        order.setOrderId("ORD-CANCEL-TEST");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.CONFIRMED);
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(delete("/api/v1/orders/{orderId}", "ORD-CANCEL-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        // Verify inventory is restored
        Inventory restoredInventory = inventoryRepository.findBySkuId("SKU-ORDER-TEST").orElseThrow();
        assertThat(restoredInventory.getSoldCount()).isEqualTo(1); // One less sold
        assertThat(restoredInventory.getAvailableCount()).isEqualTo(96); // One more available
    }

    @Test
    @DisplayName("DELETE /{orderId} - Cannot cancel fulfilled order")
    void cancelOrder_FulfilledOrder_Returns400() throws Exception {
        // Create fulfilled order
        Order order = new Order();
        order.setOrderId("ORD-FULFILLED-CANCEL");
        order.setReservationId("RES-ORDER-TEST");
        order.setUserId("user-order-test");
        order.setSkuId("SKU-ORDER-TEST");
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("1499.99"));
        order.setStatus(OrderStatus.FULFILLED);
        order.setCreatedAt(Instant.now());
        order.setFulfilledAt(Instant.now());
        orderRepository.save(order);

        mockMvc.perform(delete("/api/v1/orders/{orderId}", "ORD-FULFILLED-CANCEL"))
                .andExpect(status().is4xxClientError());
    }
}
