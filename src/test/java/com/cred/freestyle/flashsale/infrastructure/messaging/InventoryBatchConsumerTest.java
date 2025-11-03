package com.cred.freestyle.flashsale.infrastructure.messaging;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationRequestMessage;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryBatchConsumer.
 *
 * Tests the Kafka batch consumer implementing single-writer pattern for inventory management.
 * Covers batch processing, validation, allocation, and error handling scenarios.
 *
 * @author Flash Sale Team
 */
@ExtendWith(MockitoExtension.class)
class InventoryBatchConsumerTest {

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
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private InventoryBatchConsumer consumer;

    private static final String TEST_SKU_ID = "SKU-001";
    private static final String TEST_USER_ID_1 = "user123";
    private static final String TEST_USER_ID_2 = "user456";
    private static final String TEST_REQUEST_ID_1 = "req-001";
    private static final String TEST_REQUEST_ID_2 = "req-002";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new InventoryBatchConsumer(
            reservationRepository,
            inventoryRepository,
            userPurchaseTrackingRepository,
            cacheService,
            kafkaProducerService,
            metricsService,
            objectMapper
        );
    }

    // ============= consumeReservationRequests Tests =============

    @Test
    void testConsumeReservationRequests_EmptyBatch() {
        // Arrange
        List<ConsumerRecord<String, String>> emptyRecords = new ArrayList<>();

        // Act
        consumer.consumeReservationRequests(emptyRecords, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        verify(metricsService, never()).recordBatchProcessing(anyString(), anyInt(), anyLong());
    }

    @Test
    void testConsumeReservationRequests_NullBatch() {
        // Act
        consumer.consumeReservationRequests(null, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        verify(metricsService, never()).recordBatchProcessing(anyString(), anyInt(), anyLong());
    }

    @Test
    void testConsumeReservationRequests_SuccessfulProcessing() throws Exception {
        // Arrange
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ConsumerRecord<String, String>> records = Arrays.asList(
            createConsumerRecord(TEST_SKU_ID, message)
        );

        // Mock successful processing
        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(100);
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(1);
        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        Reservation savedReservation = Reservation.builder()
            .reservationId("res-001")
            .userId(TEST_USER_ID_1)
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .status(ReservationStatus.RESERVED)
            .build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(savedReservation));

        // Act
        consumer.consumeReservationRequests(records, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();
        verify(metricsService).recordBatchProcessing(eq("ALL"), eq(1), anyLong());
        verify(metricsService).recordBatchProcessing(eq(TEST_SKU_ID), anyInt(), anyLong());
        verify(kafkaProducerService).publishReservationCreated(any(ReservationEvent.class));
    }

    @Test
    void testConsumeReservationRequests_FatalError() throws Exception {
        // Arrange
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ConsumerRecord<String, String>> records = Arrays.asList(
            createConsumerRecord(TEST_SKU_ID, message)
        );

        // Simulate fatal error during processing
        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenThrow(new RuntimeException("Database error"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            consumer.consumeReservationRequests(records, acknowledgment);
        });

        // Should not acknowledge on fatal error (Kafka will redeliver)
        verify(acknowledgment, never()).acknowledge();
        verify(metricsService).recordError("BATCH_PROCESSING_FATAL_ERROR", "consumeReservationRequests");
    }

    @Test
    void testConsumeReservationRequests_InvalidJsonMessage() throws Exception {
        // Arrange
        ConsumerRecord<String, String> invalidRecord = new ConsumerRecord<>(
            "reservation-requests", 0, 0L, TEST_SKU_ID, "{invalid json}"
        );
        List<ConsumerRecord<String, String>> records = Arrays.asList(invalidRecord);

        // Act
        consumer.consumeReservationRequests(records, acknowledgment);

        // Assert
        verify(acknowledgment).acknowledge();  // Should acknowledge even with parse errors
        verify(metricsService).recordError("MESSAGE_PARSE_ERROR", "parseMessages");
    }

    // ============= processBatchForSku Tests =============

    @Test
    void testProcessBatchForSku_AllocationSuccess() {
        // Arrange - Happy path: Phase 1 succeeds, no need for Phase 2
        ReservationRequestMessage message1 = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        ReservationRequestMessage message2 = createTestMessage(TEST_USER_ID_2, TEST_SKU_ID, TEST_REQUEST_ID_2);
        List<ReservationRequestMessage> requests = Arrays.asList(message1, message2);

        // Phase 1: Full batch allocation succeeds (returns 1 = success)
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 2)).thenReturn(1);
        when(cacheService.hasUserPurchased(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(cacheService.getActiveReservation(anyString(), eq(TEST_SKU_ID))).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(reservationRepository.hasActiveReservation(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        Reservation res1 = Reservation.builder()
            .reservationId("res-001")
            .userId(TEST_USER_ID_1)
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .build();
        Reservation res2 = Reservation.builder()
            .reservationId("res-002")
            .userId(TEST_USER_ID_2)
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(res1, res2));

        when(inventoryRepository.findBySkuId(TEST_SKU_ID)).thenReturn(Optional.of(
            createInventory(TEST_SKU_ID, 100, 2, 0)
        ));

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        // Verify Phase 1: Full batch allocation in single call
        verify(inventoryRepository).incrementReservedCount(TEST_SKU_ID, 2);
        // Verify Phase 2 was NOT invoked (happy path optimization)
        verify(inventoryRepository, never()).getAvailableCount(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Reservation>> reservationCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(reservationCaptor.capture());
        assertEquals(2, reservationCaptor.getValue().size());

        verify(cacheService).decrementStockCount(TEST_SKU_ID, 2);
        verify(kafkaProducerService, times(2)).publishReservationCreated(any(ReservationEvent.class));
        verify(metricsService, times(2)).recordReservationSuccess(TEST_SKU_ID);
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 2, 2);
    }

    @Test
    void testProcessBatchForSku_PartialAllocation() {
        // Arrange - 3 requests but only 2 available
        // Two-phase allocation: Phase 1 fails (try 3), Phase 2 succeeds (allocate 2)
        ReservationRequestMessage msg1 = createTestMessage("user1", TEST_SKU_ID, "req1");
        ReservationRequestMessage msg2 = createTestMessage("user2", TEST_SKU_ID, "req2");
        ReservationRequestMessage msg3 = createTestMessage("user3", TEST_SKU_ID, "req3");
        List<ReservationRequestMessage> requests = Arrays.asList(msg1, msg2, msg3);

        // Phase 1: Try full batch (3) - fails (returns 0)
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 3)).thenReturn(0);

        // Phase 2: Read available count, then allocate exactly that
        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(2);
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 2)).thenReturn(1);

        when(cacheService.hasUserPurchased(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(cacheService.getActiveReservation(anyString(), eq(TEST_SKU_ID))).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(reservationRepository.hasActiveReservation(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        Reservation res1 = Reservation.builder().reservationId("res-001").userId("user1").skuId(TEST_SKU_ID).quantity(1).build();
        Reservation res2 = Reservation.builder().reservationId("res-002").userId("user2").skuId(TEST_SKU_ID).quantity(1).build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(res1, res2));

        when(inventoryRepository.findBySkuId(TEST_SKU_ID)).thenReturn(Optional.of(
            createInventory(TEST_SKU_ID, 100, 2, 0)
        ));

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        // Verify Phase 1: Tried full batch first
        verify(inventoryRepository).incrementReservedCount(TEST_SKU_ID, 3);

        // Verify Phase 2: Read available count, then allocated exactly 2
        verify(inventoryRepository).getAvailableCount(TEST_SKU_ID);
        verify(inventoryRepository).incrementReservedCount(TEST_SKU_ID, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Reservation>> reservationCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(reservationCaptor.capture());
        assertEquals(2, reservationCaptor.getValue().size());  // Only 2 reservations (FIFO)

        verify(kafkaProducerService, times(2)).publishReservationCreated(any(ReservationEvent.class));
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 2, 3);  // 2 out of 3
    }

    @Test
    void testProcessBatchForSku_OutOfStock() {
        // Arrange - Out of stock: Phase 1 fails, Phase 2 finds 0 available
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        // Phase 1: Try full batch - fails
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(0);
        // Phase 2: Read available count - 0
        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(0);

        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(inventoryRepository).incrementReservedCount(TEST_SKU_ID, 1);  // Phase 1 attempt
        verify(inventoryRepository).getAvailableCount(TEST_SKU_ID);  // Phase 2 read
        verify(reservationRepository, never()).saveAll(anyList());
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 0, 1);
        verify(metricsService).recordInventoryStockOut(TEST_SKU_ID);
    }

    @Test
    void testProcessBatchForSku_UserAlreadyPurchased() {
        // Arrange - All requests filtered out during validation
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(true);  // Already purchased

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert - No allocation attempt since no valid requests
        verify(inventoryRepository, never()).incrementReservedCount(anyString(), anyInt());
        verify(inventoryRepository, never()).getAvailableCount(anyString());
        verify(reservationRepository, never()).saveAll(anyList());
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "USER_ALREADY_PURCHASED");
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 0, 1);
    }

    @Test
    void testProcessBatchForSku_UserHasActiveReservation() {
        // Arrange - All requests filtered out during validation
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID))
            .thenReturn(Optional.of("existing-reservation"));  // Has active reservation

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert - No allocation attempt since no valid requests
        verify(inventoryRepository, never()).incrementReservedCount(anyString(), anyInt());
        verify(inventoryRepository, never()).getAvailableCount(anyString());
        verify(reservationRepository, never()).saveAll(anyList());
        verify(metricsService).recordReservationFailure(TEST_SKU_ID, "USER_HAS_ACTIVE_RESERVATION");
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 0, 1);
    }

    @Test
    void testProcessBatchForSku_DuplicateRequest() {
        // Arrange - All requests filtered out during validation
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(message.getIdempotencyKey())).thenReturn(true);  // Duplicate

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert - No allocation attempt since no valid requests
        verify(inventoryRepository, never()).incrementReservedCount(anyString(), anyInt());
        verify(inventoryRepository, never()).getAvailableCount(anyString());
        verify(reservationRepository, never()).saveAll(anyList());
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 0, 1);
    }

    @Test
    void testProcessBatchForSku_InventoryUpdateFailed() {
        // Arrange - Rare case: Both Phase 1 and Phase 2 fail
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        // Phase 1: Try full batch - fails
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(0);
        // Phase 2: Read available count, then try allocation - also fails (race condition)
        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(1);
        // This stubbing is intentionally lenient - if called with 1, return 0 (failure)
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(0);

        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(inventoryRepository, times(2)).incrementReservedCount(TEST_SKU_ID, 1);  // Phase 1 + Phase 2
        verify(inventoryRepository).getAvailableCount(TEST_SKU_ID);
        verify(reservationRepository, never()).saveAll(anyList());  // Should not create reservations
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 0, 1);
        verify(metricsService).recordInventoryStockOut(TEST_SKU_ID);
    }

    @Test
    void testProcessBatchForSku_OversellDetection() {
        // Arrange - Happy path with oversell detection
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        // Phase 1: Full batch succeeds
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(1);
        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);

        Reservation reservation = Reservation.builder()
            .reservationId("res-001")
            .userId(TEST_USER_ID_1)
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(reservation));

        // Simulate oversell: reserved + sold > total
        Inventory oversoldInventory = createInventory(TEST_SKU_ID, 100, 80, 30);  // 80+30=110 > 100
        when(inventoryRepository.findBySkuId(TEST_SKU_ID)).thenReturn(Optional.of(oversoldInventory));

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(metricsService).recordOversell(TEST_SKU_ID, 10);  // 110 - 100 = 10
    }

    @Test
    void testProcessBatchForSku_MixedValidation() {
        // Arrange - Mix of valid, duplicate, and already purchased (only 1 valid request)
        ReservationRequestMessage valid = createTestMessage("user1", TEST_SKU_ID, "req1");
        ReservationRequestMessage duplicate = createTestMessage("user2", TEST_SKU_ID, "req2");
        ReservationRequestMessage purchased = createTestMessage("user3", TEST_SKU_ID, "req3");
        List<ReservationRequestMessage> requests = Arrays.asList(valid, duplicate, purchased);

        // Phase 1: Full batch allocation for 1 valid request
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(1);

        // user1 - valid
        when(cacheService.hasUserPurchased("user1", TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation("user1", TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId("user1", TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation("user1", TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(valid.getIdempotencyKey())).thenReturn(false);

        // user2 - duplicate
        when(cacheService.hasUserPurchased("user2", TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation("user2", TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId("user2", TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation("user2", TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(duplicate.getIdempotencyKey())).thenReturn(true);

        // user3 - already purchased
        when(cacheService.hasUserPurchased("user3", TEST_SKU_ID)).thenReturn(true);

        Reservation res = Reservation.builder()
            .reservationId("res-001")
            .userId("user1")
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(res));

        when(inventoryRepository.findBySkuId(TEST_SKU_ID)).thenReturn(Optional.of(
            createInventory(TEST_SKU_ID, 100, 1, 0)
        ));

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(inventoryRepository).incrementReservedCount(TEST_SKU_ID, 1);  // Only 1 valid
        verify(inventoryRepository, never()).getAvailableCount(any());  // Happy path

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Reservation>> reservationCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(reservationCaptor.capture());
        assertEquals(1, reservationCaptor.getValue().size());

        verify(kafkaProducerService, times(1)).publishReservationCreated(any(ReservationEvent.class));
        verify(metricsService).recordBatchAllocationRate(TEST_SKU_ID, 1, 3);
    }

    @Test
    void testProcessBatchForSku_CacheHitForUserLimit() {
        // Arrange
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(100);
        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(true);

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(metricsService).recordCacheHit("user_limit");
        verify(userPurchaseTrackingRepository, never()).existsByUserIdAndSkuId(anyString(), anyString());
    }

    @Test
    void testProcessBatchForSku_CacheMissForUserLimit() {
        // Arrange
        ReservationRequestMessage message = createTestMessage(TEST_USER_ID_1, TEST_SKU_ID, TEST_REQUEST_ID_1);
        List<ReservationRequestMessage> requests = Arrays.asList(message);

        when(inventoryRepository.getAvailableCount(TEST_SKU_ID)).thenReturn(100);
        when(cacheService.hasUserPurchased(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(cacheService.getActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(Optional.empty());
        when(userPurchaseTrackingRepository.existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.hasActiveReservation(TEST_USER_ID_1, TEST_SKU_ID)).thenReturn(false);
        when(reservationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(inventoryRepository.incrementReservedCount(TEST_SKU_ID, 1)).thenReturn(1);

        Reservation res = Reservation.builder()
            .reservationId("res-001")
            .userId(TEST_USER_ID_1)
            .skuId(TEST_SKU_ID)
            .quantity(1)
            .build();
        when(reservationRepository.saveAll(anyList())).thenReturn(Arrays.asList(res));

        when(inventoryRepository.findBySkuId(TEST_SKU_ID)).thenReturn(Optional.of(
            createInventory(TEST_SKU_ID, 100, 1, 0)
        ));

        // Act
        consumer.processBatchForSku(TEST_SKU_ID, requests);

        // Assert
        verify(metricsService).recordCacheMiss("user_limit");
        verify(userPurchaseTrackingRepository).existsByUserIdAndSkuId(TEST_USER_ID_1, TEST_SKU_ID);
    }

    // ============= Helper Methods =============

    private ReservationRequestMessage createTestMessage(String userId, String skuId, String requestId) {
        String idempotencyKey = userId + ":" + skuId + ":" + System.nanoTime();
        return new ReservationRequestMessage(
            requestId,
            userId,
            skuId,
            1,
            idempotencyKey,
            userId + "-" + skuId
        );
    }

    private ConsumerRecord<String, String> createConsumerRecord(String key, ReservationRequestMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            return new ConsumerRecord<>("reservation-requests", 0, 0L, key, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test record", e);
        }
    }

    private Inventory createInventory(String skuId, int totalCount, int reservedCount, int soldCount) {
        return Inventory.builder()
            .skuId(skuId)
            .totalCount(totalCount)
            .reservedCount(reservedCount)
            .soldCount(soldCount)
            .availableCount(totalCount - reservedCount - soldCount)
            .build();
    }
}
