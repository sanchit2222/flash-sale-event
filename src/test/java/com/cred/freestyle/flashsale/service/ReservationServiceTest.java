package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReservationService.
 * Tests core reservation business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService Unit Tests")
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private UserPurchaseTrackingRepository userPurchaseTrackingRepository;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private CloudWatchMetricsService metricsService;

    @Mock
    private AsyncReservationService asyncReservationService;

    @InjectMocks
    private ReservationService reservationService;

    private String userId;
    private String skuId;
    private Integer quantity;

    @BeforeEach
    void setUp() {
        userId = "user-123";
        skuId = "SKU-001";
        quantity = 1;
    }

    // ========================================
    // createReservation() Tests
    // ========================================

    @Test
    @DisplayName("createReservation - Success: Should create reservation via Kafka batch processing")
    void createReservation_Success() {
        // Given
        String requestId = "req-123";
        String reservationId = "RES-001";

        // Mock AsyncReservationService to return request ID
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenReturn(requestId);

        // Mock polling: reservation appears in cache on first check
        when(cacheService.getActiveReservation(userId, skuId))
                .thenReturn(Optional.of(reservationId));

        Reservation savedReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(120))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(savedReservation));

        // When
        Reservation result = reservationService.createReservation(userId, skuId, quantity);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReservationId()).isEqualTo(reservationId);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getSkuId()).isEqualTo(skuId);
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.RESERVED);

        // Verify interactions
        verify(asyncReservationService).submitReservationRequestSync(userId, skuId, quantity);
        verify(cacheService, atLeastOnce()).getActiveReservation(userId, skuId);
        verify(reservationRepository).findById(reservationId);
        verify(metricsService).recordEndToEndLatency(anyLong());

        // Verify NO direct DB updates
        verify(inventoryRepository, never()).incrementReservedCount(anyString(), anyInt());
        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("createReservation - UserAlreadyPurchased: Should throw exception when user already purchased")
    void createReservation_UserAlreadyPurchased() {
        // Given - AsyncReservationService performs pre-validation and throws exception
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenThrow(new IllegalStateException("User has already purchased this product"));

        // When / Then
        assertThatThrownBy(() -> reservationService.createReservation(userId, skuId, quantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already purchased");

        // Verify no polling happened
        verify(cacheService, never()).getActiveReservation(anyString(), anyString());
        verify(reservationRepository, never()).findById(anyString());

        // IllegalStateException is caught and re-thrown without calling recordError
        // (recordError is only called for generic exceptions)
        verify(metricsService, never()).recordError(anyString(), anyString());
    }

    @Test
    @DisplayName("createReservation - UserAlreadyPurchasedInDb: Should throw when user purchased (DB check)")
    void createReservation_UserAlreadyPurchasedInDb() {
        // Given - AsyncReservationService performs pre-validation (cache miss, DB hit)
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenThrow(new IllegalStateException("User has already purchased this product"));

        // When / Then
        assertThatThrownBy(() -> reservationService.createReservation(userId, skuId, quantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already purchased");

        // Verify no polling happened
        verify(cacheService, never()).getActiveReservation(anyString(), anyString());
        verify(reservationRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("createReservation - UserHasActiveReservation: Should throw when user has active reservation")
    void createReservation_UserHasActiveReservation() {
        // Given - AsyncReservationService performs pre-validation and detects active reservation
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenThrow(new IllegalStateException("User already has an active reservation for this product"));

        // When / Then
        assertThatThrownBy(() -> reservationService.createReservation(userId, skuId, quantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active reservation");

        // Verify no polling happened
        verify(cacheService, never()).getActiveReservation(anyString(), anyString());
        verify(reservationRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("createReservation - OutOfStockCache: Should throw when cache shows out of stock")
    void createReservation_OutOfStockCache() {
        // Given - AsyncReservationService performs pre-validation and detects out of stock
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenThrow(new IllegalStateException("Product is out of stock"));

        // When / Then
        assertThatThrownBy(() -> reservationService.createReservation(userId, skuId, quantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of stock");

        // Verify no polling happened
        verify(cacheService, never()).getActiveReservation(anyString(), anyString());
        verify(reservationRepository, never()).findById(anyString());
        verify(inventoryRepository, never()).incrementReservedCount(anyString(), anyInt());
    }

    @Test
    @DisplayName("createReservation - PollingTimeout: Should throw when batch processing times out")
    void createReservation_PollingTimeout() {
        // Given
        String requestId = "req-123";

        // Mock AsyncReservationService to return request ID
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenReturn(requestId);

        // Mock polling: reservation never appears in cache (timeout scenario)
        // With progressive backoff (5ms → 10ms → 20ms → 50ms → 100ms),
        // 100 attempts will exhaust the timeout (~1 second)
        when(cacheService.getActiveReservation(userId, skuId))
                .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> reservationService.createReservation(userId, skuId, quantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        // Verify polling happened 100 times (max attempts with progressive backoff)
        verify(cacheService, times(100)).getActiveReservation(userId, skuId);
        verify(metricsService).recordError(eq("RESERVATION_TIMEOUT"), eq("createReservation"));
    }

    @Test
    @DisplayName("createReservation - PollingWithRetries: Should succeed after multiple polling attempts")
    void createReservation_PollingWithRetries() {
        // Given
        String requestId = "req-123";
        String reservationId = "RES-001";

        // Mock AsyncReservationService to return request ID
        when(asyncReservationService.submitReservationRequestSync(userId, skuId, quantity))
                .thenReturn(requestId);

        // Mock polling: reservation appears in cache after 3 attempts
        // With progressive backoff, first 3 attempts use 5ms interval (happy path)
        // Total latency: ~15ms (3 attempts × 5ms) - optimized for fast batch processing
        when(cacheService.getActiveReservation(userId, skuId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reservationId));

        Reservation savedReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(120))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(savedReservation));

        // When
        Reservation result = reservationService.createReservation(userId, skuId, quantity);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReservationId()).isEqualTo(reservationId);

        // Verify polling happened 3 times before finding the reservation
        verify(cacheService, times(3)).getActiveReservation(userId, skuId);
        verify(reservationRepository).findById(reservationId);
        verify(metricsService).recordEndToEndLatency(anyLong());
    }

    // ========================================
    // confirmReservation() Tests
    // ========================================

    @Test
    @DisplayName("confirmReservation - Success: Should confirm active reservation")
    void confirmReservation_Success() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .idempotencyKey("test-key")
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);

        // When
        Reservation result = reservationService.confirmReservation(reservationId);

        // Then
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(reservationRepository).save(reservation);
        verify(kafkaProducerService).publishReservationConfirmed(any(ReservationEvent.class));
        verify(cacheService).clearActiveReservation(userId, skuId);
        verify(cacheService).markUserPurchased(userId, skuId);
        verify(metricsService).recordReservationConfirmation(skuId);
    }

    @Test
    @DisplayName("confirmReservation - NotFound: Should throw when reservation not found")
    void confirmReservation_NotFound() {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> reservationService.confirmReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("confirmReservation - AlreadyExpired: Should throw when reservation expired")
    void confirmReservation_AlreadyExpired() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)) // Expired
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When / Then
        assertThatThrownBy(() -> reservationService.confirmReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("confirmReservation - AlreadyConfirmed: Should throw when reservation already confirmed")
    void confirmReservation_AlreadyConfirmed() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.now().plus(2, ChronoUnit.MINUTES))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When / Then
        assertThatThrownBy(() -> reservationService.confirmReservation(reservationId))
                .isInstanceOf(IllegalStateException.class);

        verify(kafkaProducerService, never()).publishReservationConfirmed(any());
    }

    // ========================================
    // expireReservation() Tests
    // ========================================

    @Test
    @DisplayName("expireReservation - Success: Should expire reservation and release inventory")
    void expireReservation_Success() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .idempotencyKey("test-key")
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When
        reservationService.expireReservation(reservationId);

        // Then
        verify(reservationRepository).save(reservation);
        verify(inventoryRepository).decrementReservedCount(skuId, quantity);
        verify(cacheService).incrementStockCount(skuId, quantity);
        verify(cacheService).clearActiveReservation(userId, skuId);
        verify(kafkaProducerService).publishReservationExpired(any(ReservationEvent.class));
        verify(metricsService).recordReservationExpiry(skuId);
    }

    @Test
    @DisplayName("expireReservation - NotFound: Should throw when reservation not found")
    void expireReservation_NotFound() {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> reservationService.expireReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("expireReservation - AlreadyConfirmed: Should not expire confirmed reservation")
    void expireReservation_AlreadyConfirmed() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .status(ReservationStatus.CONFIRMED)
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When
        reservationService.expireReservation(reservationId);

        // Then - should return early without expiring
        verify(reservationRepository, never()).save(any());
        verify(inventoryRepository, never()).decrementReservedCount(anyString(), anyInt());
        verify(kafkaProducerService, never()).publishReservationExpired(any());
    }

    // ========================================
    // cancelReservation() Tests
    // ========================================

    @Test
    @DisplayName("cancelReservation - Success: Should cancel reservation and release inventory")
    void cancelReservation_Success() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .skuId(skuId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .idempotencyKey("test-key")
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);

        // When
        Reservation result = reservationService.cancelReservation(reservationId);

        // Then
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(inventoryRepository).decrementReservedCount(skuId, quantity);
        verify(cacheService).incrementStockCount(skuId, quantity);
        verify(cacheService).clearActiveReservation(userId, skuId);
        verify(kafkaProducerService).publishReservationCancelled(any(ReservationEvent.class));
        verify(metricsService).recordReservationCancellation(skuId);
    }

    @Test
    @DisplayName("cancelReservation - NotFound: Should throw when reservation not found")
    void cancelReservation_NotFound() {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> reservationService.cancelReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("cancelReservation - CannotCancelConfirmed: Should throw when trying to cancel confirmed reservation")
    void cancelReservation_CannotCancelConfirmed() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .status(ReservationStatus.CONFIRMED)
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When / Then
        assertThatThrownBy(() -> reservationService.cancelReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel");

        verify(inventoryRepository, never()).decrementReservedCount(anyString(), anyInt());
    }

    // ========================================
    // findReservationById() Tests
    // ========================================

    @Test
    @DisplayName("findReservationById - Found: Should return reservation when found")
    void findReservationById_Found() {
        // Given
        String reservationId = "RES-001";
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When
        Optional<Reservation> result = reservationService.findReservationById(reservationId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getReservationId()).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("findReservationById - NotFound: Should return empty when not found")
    void findReservationById_NotFound() {
        // Given
        String reservationId = "RES-NONEXISTENT";
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        // When
        Optional<Reservation> result = reservationService.findReservationById(reservationId);

        // Then
        assertThat(result).isEmpty();
    }

    // ========================================
    // getActiveReservationsByUserId() Tests
    // ========================================

    @Test
    @DisplayName("getActiveReservationsByUserId - ShouldReturnActiveReservations")
    void getActiveReservationsByUserId_ShouldReturnActiveReservations() {
        // Given
        Reservation res1 = Reservation.builder().reservationId("RES-001").userId(userId).build();
        Reservation res2 = Reservation.builder().reservationId("RES-002").userId(userId).build();
        List<Reservation> activeReservations = Arrays.asList(res1, res2);

        when(reservationRepository.findActiveReservationsByUserId(eq(userId), any(Instant.class)))
                .thenReturn(activeReservations);

        // When
        List<Reservation> result = reservationService.getActiveReservationsByUserId(userId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(res1, res2);
    }
}
