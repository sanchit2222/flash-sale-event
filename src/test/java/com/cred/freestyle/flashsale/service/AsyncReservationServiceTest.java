package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationRequestMessage;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AsyncReservationService.
 *
 * Tests the Kafka-based async reservation submission logic.
 *
 * @author Flash Sale Team
 */
@ExtendWith(MockitoExtension.class)
class AsyncReservationServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserPurchaseTrackingRepository userPurchaseTrackingRepository;

    @Mock
    private CloudWatchMetricsService metricsService;

    private ObjectMapper objectMapper;
    private AsyncReservationService service;

    private static final String TEST_USER_ID = "user123";
    private static final String TEST_SKU_ID = "SKU-001";
    private static final Integer TEST_QUANTITY = 1;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AsyncReservationService(
            kafkaTemplate,
            cacheService,
            reservationRepository,
            userPurchaseTrackingRepository,
            metricsService,
            objectMapper
        );
    }

    @Test
    void testSubmitReservationRequest_Success() throws Exception {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));

        CompletableFuture<SendResult<String, String>> kafkaFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(kafkaFuture);

        // Act
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        // Assert
        assertNotNull(result);
        String requestId = result.get();
        assertNotNull(requestId);

        // Verify Kafka send was called
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), messageCaptor.capture());

        assertEquals("reservation-requests", topicCaptor.getValue());
        assertEquals(TEST_SKU_ID, keyCaptor.getValue()); // Partition key

        // Verify message content
        ReservationRequestMessage message = objectMapper.readValue(
            messageCaptor.getValue(),
            ReservationRequestMessage.class
        );
        assertEquals(TEST_USER_ID, message.getUserId());
        assertEquals(TEST_SKU_ID, message.getSkuId());
        assertEquals(TEST_QUANTITY, message.getQuantity());

        // Verify metrics
        verify(metricsService).recordReservationLatency(anyLong());
        verify(metricsService).recordKafkaPublishLatency(eq("reservation-requests"), anyLong());
    }

    @Test
    void testSubmitReservationRequest_UserAlreadyPurchased() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(true);

        // Act & Assert
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        // Should complete exceptionally
        assertTrue(result.isCompletedExceptionally());

        // Verify no Kafka send
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // Verify metrics
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "USER_ALREADY_PURCHASED");
    }

    @Test
    void testSubmitReservationRequest_UserHasActiveReservation() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.of("existing-reservation-id"));

        // Act & Assert
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        assertTrue(result.isCompletedExceptionally());

        // Verify no Kafka send
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // Verify metrics
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "USER_HAS_ACTIVE_RESERVATION");
        verify(metricsService).recordCacheHit("reservation");
    }

    @Test
    void testSubmitReservationRequest_UserHasActiveReservation_DatabaseFallback() {
        // Arrange - Cache miss, but DB finds active reservation
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty()); // Cache miss
        when(reservationRepository.hasActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(true); // DB finds active reservation

        // Act & Assert
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        assertTrue(result.isCompletedExceptionally());

        // Verify database fallback was called
        verify(reservationRepository).hasActiveReservation(TEST_USER_ID, TEST_SKU_ID);

        // Verify no Kafka send
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // Verify metrics
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "USER_HAS_ACTIVE_RESERVATION");
        verify(metricsService).recordCacheMiss("reservation");
    }

    @Test
    void testSubmitReservationRequest_OutOfStock() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(0));

        // Act & Assert
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        assertTrue(result.isCompletedExceptionally());

        // Verify no Kafka send
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // Verify metrics
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "OUT_OF_STOCK");
        verify(metricsService).recordInventoryStockOut(TEST_SKU_ID);
    }

    @Test
    void testSubmitReservationRequest_KafkaPublishFailure() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));

        CompletableFuture<SendResult<String, String>> kafkaFuture = new CompletableFuture<>();
        kafkaFuture.completeExceptionally(new RuntimeException("Kafka error"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(kafkaFuture);

        // Act & Assert
        CompletableFuture<String> result = service.submitReservationRequest(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        assertTrue(result.isCompletedExceptionally());

        // Verify metrics
        verify(metricsService).recordError("KAFKA_PUBLISH_ERROR", "submitReservationRequest");
    }

    @Test
    void testSubmitReservationRequestSync_Success() throws Exception {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));

        CompletableFuture<SendResult<String, String>> kafkaFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(kafkaFuture);

        // Act
        String requestId = service.submitReservationRequestSync(
            TEST_USER_ID,
            TEST_SKU_ID,
            TEST_QUANTITY
        );

        // Assert
        assertNotNull(requestId);
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void testSubmitReservationRequestSync_ThrowsIllegalStateException() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(true);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            service.submitReservationRequestSync(TEST_USER_ID, TEST_SKU_ID, TEST_QUANTITY);
        });
    }

    @Test
    void testHasUserAlreadyPurchased_CacheHit() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(true);

        // Act
        service.submitReservationRequest(TEST_USER_ID, TEST_SKU_ID, TEST_QUANTITY);

        // Assert
        verify(cacheService).hasUserPurchased(TEST_USER_ID, TEST_SKU_ID);
        verify(userPurchaseTrackingRepository, never()).existsByUserIdAndSkuId(anyString(), anyString());
        verify(metricsService).recordCacheHit("user_limit");
    }

    @Test
    void testHasUserAlreadyPurchased_CacheMiss_DatabaseCheck() {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(true);

        // Act
        service.submitReservationRequest(TEST_USER_ID, TEST_SKU_ID, TEST_QUANTITY);

        // Assert - should check database after cache miss
        verify(userPurchaseTrackingRepository).existsByUserIdAndSkuId(TEST_USER_ID, TEST_SKU_ID);
    }

    @Test
    void testIdempotencyKeyGeneration() throws Exception {
        // Arrange
        when(cacheService.hasUserPurchased(TEST_USER_ID, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID, TEST_SKU_ID))
            .thenReturn(Optional.empty());
        when(cacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));

        CompletableFuture<SendResult<String, String>> kafkaFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(kafkaFuture);

        // Act
        service.submitReservationRequest(TEST_USER_ID, TEST_SKU_ID, TEST_QUANTITY).get();

        // Assert
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), messageCaptor.capture());

        ReservationRequestMessage message = objectMapper.readValue(
            messageCaptor.getValue(),
            ReservationRequestMessage.class
        );

        // Idempotency key format: userId:skuId (no timestamp to prevent duplicate reservations)
        assertNotNull(message.getIdempotencyKey());
        assertEquals(TEST_USER_ID + ":" + TEST_SKU_ID, message.getIdempotencyKey());
    }
}
