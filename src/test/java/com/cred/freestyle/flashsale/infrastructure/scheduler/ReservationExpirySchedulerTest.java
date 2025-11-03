package com.cred.freestyle.flashsale.infrastructure.scheduler;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.KafkaProducerService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReservationExpiryScheduler.
 *
 * Tests the Layer 2 (Scheduled Cleanup) of the Three-Layer Redundancy System.
 *
 * @author Flash Sale Team
 */
@ExtendWith(MockitoExtension.class)
class ReservationExpirySchedulerTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private CloudWatchMetricsService metricsService;

    private ReservationExpiryScheduler scheduler;

    private static final String TEST_RESERVATION_ID = "res-123";
    private static final String TEST_USER_ID = "user-456";
    private static final String TEST_SKU_ID = "sku-789";
    private static final int TEST_QUANTITY = 1;

    @BeforeEach
    void setUp() {
        scheduler = new ReservationExpiryScheduler(
                reservationRepository,
                inventoryRepository,
                cacheService,
                kafkaProducerService,
                metricsService
        );
    }

    @Test
    @DisplayName("cleanupExpiredReservations - No expired reservations: Should do nothing")
    void cleanupExpiredReservations_NoExpiredReservations() {
        // Arrange
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        // Act
        scheduler.cleanupExpiredReservations();

        // Assert
        verify(reservationRepository).findExpiredReservations(any(Instant.class));
        verify(reservationRepository, never()).save(any());
        verify(inventoryRepository, never()).decrementReservedCount(anyString(), anyInt());
        verify(kafkaProducerService, never()).publishReservationExpired(any());
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Single expired reservation: Should process successfully")
    void cleanupExpiredReservations_SingleExpiredReservation() {
        // Arrange
        Reservation expiredReservation = createExpiredReservation();
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(expiredReservation));

        // Act
        scheduler.cleanupExpiredReservations();

        // Assert - Verify reservation marked as expired
        verify(reservationRepository).save(argThat(r ->
                r.getReservationId().equals(TEST_RESERVATION_ID) &&
                r.getStatus() == ReservationStatus.EXPIRED &&
                r.getExpiredAt() != null
        ));

        // Verify inventory released
        verify(inventoryRepository).decrementReservedCount(TEST_SKU_ID, TEST_QUANTITY);

        // Verify Kafka event published
        ArgumentCaptor<ReservationEvent> eventCaptor = ArgumentCaptor.forClass(ReservationEvent.class);
        verify(kafkaProducerService).publishReservationExpired(eventCaptor.capture());

        ReservationEvent event = eventCaptor.getValue();
        assertEquals(TEST_RESERVATION_ID, event.getReservationId());
        assertEquals(TEST_USER_ID, event.getUserId());
        assertEquals(TEST_SKU_ID, event.getSkuId());
        assertEquals(TEST_QUANTITY, event.getQuantity());
        assertEquals(ReservationEvent.EventType.EXPIRED, event.getEventType());

        // Verify cache updated
        verify(cacheService).clearActiveReservation(TEST_USER_ID, TEST_SKU_ID);
        verify(cacheService).incrementStockCount(TEST_SKU_ID, TEST_QUANTITY);

        // Verify metrics
        verify(metricsService).recordReservationExpiry(TEST_SKU_ID);
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Multiple expired reservations: Should process all")
    void cleanupExpiredReservations_MultipleExpiredReservations() {
        // Arrange
        Reservation reservation1 = createExpiredReservation();
        Reservation reservation2 = createExpiredReservation();
        reservation2.setReservationId("res-456");

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Arrays.asList(reservation1, reservation2));

        // Act
        scheduler.cleanupExpiredReservations();

        // Assert - Both reservations processed
        verify(reservationRepository, times(2)).save(any(Reservation.class));
        verify(inventoryRepository, times(2)).decrementReservedCount(TEST_SKU_ID, TEST_QUANTITY);
        verify(kafkaProducerService, times(2)).publishReservationExpired(any());
        verify(cacheService, times(2)).clearActiveReservation(TEST_USER_ID, TEST_SKU_ID);
        verify(cacheService, times(2)).incrementStockCount(TEST_SKU_ID, TEST_QUANTITY);
        verify(metricsService, times(2)).recordReservationExpiry(TEST_SKU_ID);
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Kafka publish fails: Should continue processing")
    void cleanupExpiredReservations_KafkaPublishFails() {
        // Arrange
        Reservation expiredReservation = createExpiredReservation();
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(expiredReservation));

        // Simulate Kafka failure
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(kafkaProducerService).publishReservationExpired(any());

        // Act - Should not throw exception
        assertDoesNotThrow(() -> scheduler.cleanupExpiredReservations());

        // Assert - Processing continues despite Kafka failure
        verify(reservationRepository).save(any(Reservation.class));
        verify(inventoryRepository).decrementReservedCount(TEST_SKU_ID, TEST_QUANTITY);
        verify(cacheService).clearActiveReservation(TEST_USER_ID, TEST_SKU_ID);
        verify(cacheService).incrementStockCount(TEST_SKU_ID, TEST_QUANTITY);
        verify(metricsService).recordReservationExpiry(TEST_SKU_ID);
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Cache update fails: Should continue processing")
    void cleanupExpiredReservations_CacheUpdateFails() {
        // Arrange
        Reservation expiredReservation = createExpiredReservation();
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(expiredReservation));

        // Simulate cache failure
        doThrow(new RuntimeException("Redis unavailable"))
                .when(cacheService).clearActiveReservation(anyString(), anyString());

        // Act - Should not throw exception
        assertDoesNotThrow(() -> scheduler.cleanupExpiredReservations());

        // Assert - Processing continues despite cache failure
        verify(reservationRepository).save(any(Reservation.class));
        verify(inventoryRepository).decrementReservedCount(TEST_SKU_ID, TEST_QUANTITY);
        verify(kafkaProducerService).publishReservationExpired(any());
        verify(metricsService).recordReservationExpiry(TEST_SKU_ID);
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Database failure: Should record error")
    void cleanupExpiredReservations_DatabaseFailure() {
        // Arrange
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act - Should not throw exception (graceful degradation)
        assertDoesNotThrow(() -> scheduler.cleanupExpiredReservations());

        // Assert - Error recorded
        verify(metricsService).recordError("RESERVATION_EXPIRY_SCHEDULER_ERROR",
                "cleanupExpiredReservations");
    }

    @Test
    @DisplayName("triggerCleanupNow - Manual trigger: Should process immediately")
    void triggerCleanupNow_ProcessesImmediately() {
        // Arrange
        Reservation reservation1 = createExpiredReservation();
        Reservation reservation2 = createExpiredReservation();
        reservation2.setReservationId("res-456");

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Arrays.asList(reservation1, reservation2));

        // Act
        int processedCount = scheduler.triggerCleanupNow();

        // Assert
        assertEquals(2, processedCount);
        verify(reservationRepository, times(2)).save(any(Reservation.class));
        verify(inventoryRepository, times(2)).decrementReservedCount(anyString(), anyInt());
    }

    @Test
    @DisplayName("cleanupExpiredReservations - Only RESERVED status: Should not process CONFIRMED/EXPIRED")
    void cleanupExpiredReservations_OnlyReservedStatus() {
        // Arrange
        Reservation expiredReservation = createExpiredReservation();
        expiredReservation.setStatus(ReservationStatus.RESERVED);

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(expiredReservation));

        // Act
        scheduler.cleanupExpiredReservations();

        // Assert - Should process RESERVED reservation
        verify(reservationRepository).save(any(Reservation.class));
        verify(inventoryRepository).decrementReservedCount(TEST_SKU_ID, TEST_QUANTITY);
    }

    /**
     * Helper method to create an expired reservation for testing.
     */
    private Reservation createExpiredReservation() {
        Reservation reservation = new Reservation();
        reservation.setReservationId(TEST_RESERVATION_ID);
        reservation.setUserId(TEST_USER_ID);
        reservation.setSkuId(TEST_SKU_ID);
        reservation.setQuantity(TEST_QUANTITY);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setExpiresAt(Instant.now().minusSeconds(60)); // Expired 1 minute ago
        reservation.setIdempotencyKey(TEST_USER_ID + ":" + TEST_SKU_ID);
        reservation.setCreatedAt(Instant.now().minusSeconds(180)); // Created 3 minutes ago
        return reservation;
    }
}
