package com.cred.freestyle.flashsale.api;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ProductRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
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
 * Full-stack API integration tests for Reservation endpoints.
 * Tests complete flow from HTTP request through service layer to database.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.data.redis.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("Reservation API Integration Tests")
class ReservationApiIT {

    @Autowired
    private MockMvc mockMvc;

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

    @BeforeEach
    void setUp() {
        // Clean up
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        // Create test product
        testProduct = Product.builder()
                .skuId("SKU-TEST-001")
                .name("Flash Sale Test Product")
                .description("Test product for integration tests")
                .category("Electronics")
                .basePrice(new BigDecimal("1999.99"))
                .flashSalePrice(new BigDecimal("999.99"))
                .flashSaleEventId("EVENT-TEST-001")
                .totalInventory(100)
                .isActive(true)
                .build();
        productRepository.save(testProduct);

        // Create test inventory
        testInventory = Inventory.builder()
                .skuId("SKU-TEST-001")
                .totalCount(100)
                .availableCount(100)
                .reservedCount(0)
                .soldCount(0)
                .build();
        inventoryRepository.save(testInventory);
    }

    // ========================================
    // Complete Reservation Flow Tests
    // ========================================

    @Test
    @DisplayName("Complete reservation flow - Create, retrieve, and cancel")
    void completeReservationFlow_Success() throws Exception {
        String userId = "user-flow-test";

        // Step 1: Create reservation
        String createRequest = """
                {
                    "userId": "%s",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """.formatted(userId);

        String reservationId = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").exists())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.skuId").value("SKU-TEST-001"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"reservationId\":\"([^\"]+)\".*", "$1");

        // Verify inventory was decremented
        Inventory updatedInventory = inventoryRepository.findBySkuId("SKU-TEST-001").orElseThrow();
        assertThat(updatedInventory.getAvailableCount()).isEqualTo(99);
        assertThat(updatedInventory.getReservedCount()).isEqualTo(1);

        // Step 2: Retrieve reservation
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        // Step 3: Get user's active reservations
        mockMvc.perform(get("/api/v1/reservations/user/{userId}/active", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reservationId").value(reservationId));

        // Step 4: Cancel reservation
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        // Verify inventory was restored
        Inventory restoredInventory = inventoryRepository.findBySkuId("SKU-TEST-001").orElseThrow();
        assertThat(restoredInventory.getAvailableCount()).isEqualTo(100);
        assertThat(restoredInventory.getReservedCount()).isEqualTo(0);
    }

    // ========================================
    // Concurrent Reservation Tests
    // ========================================

    @Test
    @DisplayName("Multiple users can create reservations simultaneously")
    void multipleReservations_DifferentUsers_Success() throws Exception {
        // User 1 creates reservation
        String request1 = """
                {
                    "userId": "user-1",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request1))
                .andExpect(status().isCreated());

        // User 2 creates reservation
        String request2 = """
                {
                    "userId": "user-2",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request2))
                .andExpect(status().isCreated());

        // Verify both reservations exist
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(reservationRepository.findByUserId("user-1")).hasSize(1);
        assertThat(reservationRepository.findByUserId("user-2")).hasSize(1);

        // Verify inventory
        Inventory inventory = inventoryRepository.findBySkuId("SKU-TEST-001").orElseThrow();
        assertThat(inventory.getAvailableCount()).isEqualTo(98);
        assertThat(inventory.getReservedCount()).isEqualTo(2);
    }

    // ========================================
    // Business Logic Validation Tests
    // ========================================

    @Test
    @DisplayName("User cannot create duplicate reservation for same product")
    void createReservation_DuplicateForSameProduct_Returns400() throws Exception {
        String userId = "user-duplicate-test";
        String requestBody = """
                {
                    "userId": "%s",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """.formatted(userId);

        // First reservation succeeds
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // Second reservation fails
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value(containsString("already has an active reservation")));
    }

    @Test
    @DisplayName("Cannot create reservation when product is out of stock")
    void createReservation_OutOfStock_Returns400() throws Exception {
        // Set inventory to 0
        testInventory.setAvailableCount(0);
        testInventory.setTotalCount(0);
        inventoryRepository.save(testInventory);

        String requestBody = """
                {
                    "userId": "user-oos-test",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value(containsString("out of stock")));
    }

    @Test
    @DisplayName("Cannot create reservation for inactive product")
    void createReservation_InactiveProduct_Returns400() throws Exception {
        // Deactivate product
        testProduct.setIsActive(false);
        productRepository.save(testProduct);

        String requestBody = """
                {
                    "userId": "user-inactive-test",
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Cannot create reservation for non-existent product")
    void createReservation_NonExistentProduct_Returns400() throws Exception {
        String requestBody = """
                {
                    "userId": "user-nonexistent-test",
                    "skuId": "SKU-DOES-NOT-EXIST",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    // ========================================
    // Validation Tests
    // ========================================

    @Test
    @DisplayName("Create reservation with invalid quantity returns 400")
    void createReservation_InvalidQuantity_Returns400() throws Exception {
        String requestBody = """
                {
                    "userId": "user-test",
                    "skuId": "SKU-TEST-001",
                    "quantity": 0
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Create reservation with missing userId returns 400")
    void createReservation_MissingUserId_Returns400() throws Exception {
        String requestBody = """
                {
                    "skuId": "SKU-TEST-001",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Create reservation with missing skuId returns 400")
    void createReservation_MissingSkuId_Returns400() throws Exception {
        String requestBody = """
                {
                    "userId": "user-test",
                    "quantity": 1
                }
                """;

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ========================================
    // Cancellation Tests
    // ========================================

    @Test
    @DisplayName("Cannot cancel non-existent reservation")
    void cancelReservation_NonExistent_Returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/{id}", "RES-DOES-NOT-EXIST"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Cannot cancel already confirmed reservation")
    void cancelReservation_AlreadyConfirmed_Returns400() throws Exception {
        // Create and confirm a reservation
        Reservation reservation = new Reservation();
        reservation.setReservationId("RES-CONFIRMED-TEST");
        reservation.setUserId("user-confirmed");
        reservation.setSkuId("SKU-TEST-001");
        reservation.setQuantity(1);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setCreatedAt(Instant.now());
        reservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservation.setConfirmedAt(Instant.now());
        reservationRepository.save(reservation);

        mockMvc.perform(delete("/api/v1/reservations/{id}", "RES-CONFIRMED-TEST"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").value(containsString("Cannot cancel")));
    }

    // ========================================
    // Retrieval Tests
    // ========================================

    @Test
    @DisplayName("Get reservation by ID returns correct data")
    void getReservation_ExistingId_ReturnsCorrectData() throws Exception {
        // Create reservation directly in DB
        Reservation reservation = new Reservation();
        reservation.setReservationId("RES-GET-TEST");
        reservation.setUserId("user-get-test");
        reservation.setSkuId("SKU-TEST-001");
        reservation.setQuantity(1);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setCreatedAt(Instant.now());
        reservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(reservation);

        mockMvc.perform(get("/api/v1/reservations/{id}", "RES-GET-TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value("RES-GET-TEST"))
                .andExpect(jsonPath("$.userId").value("user-get-test"))
                .andExpect(jsonPath("$.skuId").value("SKU-TEST-001"))
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    @DisplayName("Get reservation with non-existent ID returns 404")
    void getReservation_NonExistentId_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/{id}", "RES-NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Get active reservations returns only RESERVED status")
    void getActiveReservations_ReturnsOnlyReserved() throws Exception {
        String userId = "user-active-test";

        // Create RESERVED reservation
        Reservation reserved = new Reservation();
        reserved.setReservationId("RES-RESERVED");
        reserved.setUserId(userId);
        reserved.setSkuId("SKU-TEST-001");
        reserved.setQuantity(1);
        reserved.setStatus(ReservationStatus.RESERVED);
        reserved.setCreatedAt(Instant.now());
        reserved.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(reserved);

        // Create CONFIRMED reservation
        Reservation confirmed = new Reservation();
        confirmed.setReservationId("RES-CONFIRMED");
        confirmed.setUserId(userId);
        confirmed.setSkuId("SKU-TEST-001");
        confirmed.setQuantity(1);
        confirmed.setStatus(ReservationStatus.CONFIRMED);
        confirmed.setCreatedAt(Instant.now());
        confirmed.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        confirmed.setConfirmedAt(Instant.now());
        reservationRepository.save(confirmed);

        // Create CANCELLED reservation
        Reservation cancelled = new Reservation();
        cancelled.setReservationId("RES-CANCELLED");
        cancelled.setUserId(userId);
        cancelled.setSkuId("SKU-TEST-001");
        cancelled.setQuantity(1);
        cancelled.setStatus(ReservationStatus.CANCELLED);
        cancelled.setCreatedAt(Instant.now());
        cancelled.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        cancelled.setCancelledAt(Instant.now());
        reservationRepository.save(cancelled);

        mockMvc.perform(get("/api/v1/reservations/user/{userId}/active", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reservationId").value("RES-RESERVED"))
                .andExpect(jsonPath("$[0].status").value("RESERVED"));
    }

    @Test
    @DisplayName("Get active reservations for user with no reservations returns empty list")
    void getActiveReservations_NoReservations_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/user/{userId}/active", "user-no-reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
