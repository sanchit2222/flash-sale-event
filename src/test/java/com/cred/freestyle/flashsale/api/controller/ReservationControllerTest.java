package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.exception.GlobalExceptionHandler;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ReservationController using MockMvc.
 * Tests HTTP layer in isolation with mocked service dependencies.
 */
@WebMvcTest(ReservationController.class)
@ContextConfiguration(classes = {ReservationController.class, GlobalExceptionHandler.class})
@DisplayName("ReservationController Tests")
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private CloudWatchMetricsService metricsService;

    // ========================================
    // POST /api/v1/reservations Tests
    // ========================================

    @Test
    @DisplayName("POST /reservations - Valid request returns 201 Created")
    void createReservation_ValidRequest_Returns201() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "skuId": "SKU-001",
                    "quantity": 1
                }
                """;

        Reservation reservation = Reservation.builder()
                .reservationId("RES-001")
                .userId("user-123")
                .skuId("SKU-001")
                .quantity(1)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .createdAt(Instant.now())
                .build();

        when(reservationService.createReservation("user-123", "SKU-001", 1))
                .thenReturn(reservation);

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value("RES-001"))
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.skuId").value("SKU-001"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        verify(reservationService).createReservation("user-123", "SKU-001", 1);
        verify(metricsService).recordReservationLatency(anyLong());
    }

    @Test
    @DisplayName("POST /reservations - Missing userId returns 400 Bad Request")
    void createReservation_MissingUserId_Returns400() throws Exception {
        // Given
        String requestBody = """
                {
                    "skuId": "SKU-001",
                    "quantity": 1
                }
                """;

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(reservationService, never()).createReservation(any(), any(), any());
    }

    @Test
    @DisplayName("POST /reservations - Missing skuId returns 400 Bad Request")
    void createReservation_MissingSkuId_Returns400() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "quantity": 1
                }
                """;

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(reservationService, never()).createReservation(any(), any(), any());
    }

    @Test
    @DisplayName("POST /reservations - Quantity less than 1 returns 400 Bad Request")
    void createReservation_InvalidQuantity_Returns400() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "skuId": "SKU-001",
                    "quantity": 0
                }
                """;

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(reservationService, never()).createReservation(any(), any(), any());
    }

    @Test
    @DisplayName("POST /reservations - Out of stock throws IllegalStateException")
    void createReservation_OutOfStock_ThrowsException() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "skuId": "SKU-001",
                    "quantity": 1
                }
                """;

        when(reservationService.createReservation("user-123", "SKU-001", 1))
                .thenThrow(new IllegalStateException("Product is out of stock"));

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());

        verify(metricsService).recordReservationFailure(eq("SKU-001"), anyString());
    }

    @Test
    @DisplayName("POST /reservations - User already purchased throws IllegalStateException")
    void createReservation_UserAlreadyPurchased_ThrowsException() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "skuId": "SKU-001",
                    "quantity": 1
                }
                """;

        when(reservationService.createReservation("user-123", "SKU-001", 1))
                .thenThrow(new IllegalStateException("User has already purchased this product"));

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());

        verify(metricsService).recordReservationFailure("SKU-001", "USER_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("POST /reservations - User has active reservation throws IllegalStateException")
    void createReservation_UserHasActiveReservation_ThrowsException() throws Exception {
        // Given
        String requestBody = """
                {
                    "userId": "user-123",
                    "skuId": "SKU-001",
                    "quantity": 1
                }
                """;

        when(reservationService.createReservation("user-123", "SKU-001", 1))
                .thenThrow(new IllegalStateException("User already has an active reservation for this product"));

        // When / Then
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());

        verify(metricsService).recordReservationFailure("SKU-001", "DUPLICATE_RESERVATION");
    }

    // ========================================
    // GET /api/v1/reservations/{id} Tests
    // ========================================

    @Test
    @DisplayName("GET /reservations/{id} - Existing reservation returns 200 OK")
    void getReservation_Exists_Returns200() throws Exception {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId("user-123")
                .skuId("SKU-001")
                .quantity(1)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .createdAt(Instant.now())
                .build();

        when(reservationService.findReservationById(reservationId))
                .thenReturn(Optional.of(reservation));

        // When / Then
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.skuId").value("SKU-001"));

        verify(reservationService).findReservationById(reservationId);
    }

    @Test
    @DisplayName("GET /reservations/{id} - Non-existent reservation returns 404 Not Found")
    void getReservation_NotFound_Returns404() throws Exception {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationService.findReservationById(reservationId))
                .thenReturn(Optional.empty());

        // When / Then
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().isNotFound());

        verify(reservationService).findReservationById(reservationId);
    }

    // ========================================
    // DELETE /api/v1/reservations/{id} Tests
    // ========================================

    @Test
    @DisplayName("DELETE /reservations/{id} - Successful cancellation returns 200 OK")
    void cancelReservation_Success_Returns200() throws Exception {
        // Given
        String reservationId = "RES-001";
        Reservation cancelledReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId("user-123")
                .skuId("SKU-001")
                .quantity(1)
                .status(ReservationStatus.CANCELLED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .cancelledAt(Instant.now())
                .build();

        when(reservationService.cancelReservation(reservationId))
                .thenReturn(cancelledReservation);

        // When / Then
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(reservationService).cancelReservation(reservationId);
    }

    @Test
    @DisplayName("DELETE /reservations/{id} - Reservation not found throws exception")
    void cancelReservation_NotFound_ThrowsException() throws Exception {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationService.cancelReservation(reservationId))
                .thenThrow(new IllegalStateException("Reservation not found: " + reservationId));

        // When / Then
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().is4xxClientError());

        verify(reservationService).cancelReservation(reservationId);
    }

    @Test
    @DisplayName("DELETE /reservations/{id} - Already confirmed reservation throws exception")
    void cancelReservation_AlreadyConfirmed_ThrowsException() throws Exception {
        // Given
        String reservationId = "RES-001";
        when(reservationService.cancelReservation(reservationId))
                .thenThrow(new IllegalStateException("Cannot cancel reservation with status: CONFIRMED"));

        // When / Then
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId))
                .andExpect(status().is4xxClientError());

        verify(reservationService).cancelReservation(reservationId);
    }

    // ========================================
    // GET /api/v1/reservations/user/{userId}/active Tests
    // ========================================

    @Test
    @DisplayName("GET /reservations/user/{userId}/active - Returns active reservations")
    void getUserActiveReservations_ReturnsActiveReservations() throws Exception {
        // Given
        String userId = "user-123";
        Reservation res1 = Reservation.builder()
                .reservationId("RES-001")
                .userId(userId)
                .skuId("SKU-001")
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();

        Reservation res2 = Reservation.builder()
                .reservationId("RES-002")
                .userId(userId)
                .skuId("SKU-002")
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(1, ChronoUnit.MINUTES))
                .build();

        List<Reservation> activeReservations = Arrays.asList(res1, res2);

        when(reservationService.getActiveReservationsByUserId(userId))
                .thenReturn(activeReservations);

        // When / Then
        mockMvc.perform(get("/api/v1/reservations/user/{userId}/active", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].reservationId").value("RES-001"))
                .andExpect(jsonPath("$[1].reservationId").value("RES-002"));

        verify(reservationService).getActiveReservationsByUserId(userId);
    }

    @Test
    @DisplayName("GET /reservations/user/{userId}/active - Returns empty list when no active reservations")
    void getUserActiveReservations_EmptyList_Returns200() throws Exception {
        // Given
        String userId = "user-123";
        when(reservationService.getActiveReservationsByUserId(userId))
                .thenReturn(Arrays.asList());

        // When / Then
        mockMvc.perform(get("/api/v1/reservations/user/{userId}/active", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(reservationService).getActiveReservationsByUserId(userId);
    }
}
