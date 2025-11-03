package com.cred.freestyle.flashsale.api;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationRequestMessage;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ProductRepository;
import com.cred.freestyle.flashsale.repository.ReservationRepository;
import com.cred.freestyle.flashsale.repository.UserPurchaseTrackingRepository;
import com.cred.freestyle.flashsale.service.AsyncReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Kafka batch processing reservation flow.
 *
 * Tests the complete end-to-end flow:
 * 1. AsyncReservationService publishes to Kafka
 * 2. InventoryBatchConsumer processes batch
 * 3. Database updates occur
 * 4. Cache updates occur
 *
 * Uses embedded Kafka for realistic testing.
 *
 * @author Flash Sale Team
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.group-id=test-consumer-group",
    "spring.kafka.consumer.enable-auto-commit=false",
    "spring.kafka.consumer.max-poll-records=250"
})
@EmbeddedKafka(
    partitions = 1,
    topics = {"reservation-requests", "reservation-responses"},
    brokerProperties = {
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    }
)
@DirtiesContext
@DisplayName("Kafka Batch Reservation Integration Tests")
class KafkaBatchReservationIT {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private AsyncReservationService asyncReservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private UserPurchaseTrackingRepository userPurchaseTrackingRepository;

    @MockBean
    private RedisCacheService redisCacheService;

    @MockBean
    private CloudWatchMetricsService cloudWatchMetricsService;

    @Autowired
    private ObjectMapper objectMapper;

    private Product testProduct;
    private Inventory testInventory;

    private static final String TEST_SKU_ID = "SKU-KAFKA-TEST-001";
    private static final int INITIAL_INVENTORY = 1000;

    @BeforeEach
    void setUp() {
        // Clean up
        reservationRepository.deleteAll();
        userPurchaseTrackingRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        // Create test product
        testProduct = Product.builder()
                .skuId(TEST_SKU_ID)
                .name("Kafka Test Product")
                .description("Product for Kafka batch processing tests")
                .category("Electronics")
                .basePrice(new BigDecimal("1999.99"))
                .flashSalePrice(new BigDecimal("999.99"))
                .flashSaleEventId("EVENT-KAFKA-001")
                .totalInventory(INITIAL_INVENTORY)
                .isActive(true)
                .build();
        productRepository.save(testProduct);

        // Create test inventory
        testInventory = Inventory.builder()
                .skuId(TEST_SKU_ID)
                .totalCount(INITIAL_INVENTORY)
                .availableCount(INITIAL_INVENTORY)
                .reservedCount(0)
                .soldCount(0)
                .build();
        inventoryRepository.save(testInventory);

        // Mock cache responses (cache-first validation)
        when(redisCacheService.hasUserPurchased(anyString(), eq(TEST_SKU_ID))).thenReturn(false);
        when(redisCacheService.getActiveReservation(anyString(), eq(TEST_SKU_ID))).thenReturn(Optional.empty());
        when(redisCacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(INITIAL_INVENTORY));
    }

    // ============================================
    // Single Request End-to-End Flow
    // ============================================

    @Test
    @DisplayName("End-to-end flow: Single reservation request through Kafka")
    void singleReservationRequest_EndToEnd_Success() throws Exception {
        String userId = "user-e2e-single";

        // Step 1: Submit reservation request (publishes to Kafka)
        CompletableFuture<String> requestIdFuture = asyncReservationService.submitReservationRequest(
            userId,
            TEST_SKU_ID,
            1
        );

        String requestId = requestIdFuture.get(5, TimeUnit.SECONDS);
        assertThat(requestId).isNotNull();

        // Step 2: Wait for Kafka consumer to process (async)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Reservation> reservations = reservationRepository.findByUserId(userId);
            assertThat(reservations).hasSize(1);
            assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(reservations.get(0).getSkuId()).isEqualTo(TEST_SKU_ID);
            assertThat(reservations.get(0).getQuantity()).isEqualTo(1);
        });

        // Step 3: Verify inventory was updated
        Inventory updatedInventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
        assertThat(updatedInventory.getReservedCount()).isEqualTo(1);
        assertThat(updatedInventory.getAvailableCount()).isEqualTo(INITIAL_INVENTORY - 1);
    }

    // ============================================
    // Batch Processing Tests
    // ============================================

    @Test
    @DisplayName("Batch processing: Process 250 requests in a single batch")
    void batchProcessing_250Requests_Success() throws Exception {
        int batchSize = 250;

        // Step 1: Submit 250 reservation requests
        List<CompletableFuture<String>> futures = IntStream.range(0, batchSize)
            .mapToObj(i -> asyncReservationService.submitReservationRequest(
                "user-batch-" + i,
                TEST_SKU_ID,
                1
            ))
            .collect(Collectors.toList());

        // Wait for all submissions to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Step 2: Wait for batch consumer to process all requests
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            long reservationCount = reservationRepository.count();
            assertThat(reservationCount).isEqualTo(batchSize);
        });

        // Step 3: Verify all reservations have RESERVED status
        List<Reservation> allReservations = (List<Reservation>) reservationRepository.findAll();
        assertThat(allReservations)
            .hasSize(batchSize)
            .allMatch(r -> r.getStatus() == ReservationStatus.RESERVED)
            .allMatch(r -> r.getSkuId().equals(TEST_SKU_ID))
            .allMatch(r -> r.getQuantity() == 1);

        // Step 4: Verify inventory
        Inventory updatedInventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
        assertThat(updatedInventory.getReservedCount()).isEqualTo(batchSize);
        assertThat(updatedInventory.getAvailableCount()).isEqualTo(INITIAL_INVENTORY - batchSize);
    }

    @Test
    @DisplayName("Batch processing: High throughput - 500 concurrent requests")
    void batchProcessing_HighThroughput_500Requests_Success() throws Exception {
        int totalRequests = 500;

        // Submit 500 requests concurrently
        List<CompletableFuture<String>> futures = IntStream.range(0, totalRequests)
            .parallel()
            .mapToObj(i -> asyncReservationService.submitReservationRequest(
                "user-high-throughput-" + i,
                TEST_SKU_ID,
                1
            ))
            .collect(Collectors.toList());

        // Wait for all submissions
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(15, TimeUnit.SECONDS);

        // Wait for processing to complete
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            long reservationCount = reservationRepository.count();
            assertThat(reservationCount).isEqualTo(totalRequests);
        });

        // Verify inventory consistency
        Inventory updatedInventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
        assertThat(updatedInventory.getReservedCount()).isEqualTo(totalRequests);
        assertThat(updatedInventory.getAvailableCount()).isEqualTo(INITIAL_INVENTORY - totalRequests);

        // CRITICAL: Verify no oversell
        int totalAllocated = updatedInventory.getReservedCount() + updatedInventory.getSoldCount();
        assertThat(totalAllocated).isLessThanOrEqualTo(updatedInventory.getTotalCount());
    }

    // ============================================
    // Stock Depletion Tests
    // ============================================

    @Test
    @DisplayName("Stock depletion: Partial allocation when inventory runs out")
    void stockDepletion_PartialAllocation_Success() throws Exception {
        // Set inventory to 100
        testInventory.setTotalCount(100);
        testInventory.setAvailableCount(100);
        inventoryRepository.save(testInventory);
        when(redisCacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(100));

        // Submit 150 requests (50 should be rejected)
        int totalRequests = 150;
        int availableStock = 100;

        List<CompletableFuture<String>> futures = IntStream.range(0, totalRequests)
            .mapToObj(i -> asyncReservationService.submitReservationRequest(
                "user-depletion-" + i,
                TEST_SKU_ID,
                1
            ))
            .collect(Collectors.toList());

        // Wait for all submissions
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Wait for processing
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Inventory inventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
            // Should allocate exactly 100 (FIFO order)
            assertThat(inventory.getReservedCount()).isEqualTo(availableStock);
            assertThat(inventory.getAvailableCount()).isEqualTo(0);
        });

        // Verify only 100 reservations were created
        long reservationCount = reservationRepository.count();
        assertThat(reservationCount).isEqualTo(availableStock);
    }

    @Test
    @DisplayName("Stock depletion: Zero-oversell guarantee under high load")
    void stockDepletion_ZeroOversellGuarantee_Success() throws Exception {
        // Set inventory to 50
        testInventory.setTotalCount(50);
        testInventory.setAvailableCount(50);
        inventoryRepository.save(testInventory);
        when(redisCacheService.getStockCount(TEST_SKU_ID)).thenReturn(Optional.of(50));

        // Submit 200 concurrent requests
        int totalRequests = 200;
        int availableStock = 50;

        List<CompletableFuture<String>> futures = IntStream.range(0, totalRequests)
            .parallel()
            .mapToObj(i -> asyncReservationService.submitReservationRequest(
                "user-oversell-test-" + i,
                TEST_SKU_ID,
                1
            ))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(15, TimeUnit.SECONDS);

        // Wait for processing
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            Inventory inventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
            assertThat(inventory.getAvailableCount()).isEqualTo(0);
        });

        // CRITICAL: Verify zero oversell
        Inventory finalInventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
        int totalAllocated = finalInventory.getReservedCount() + finalInventory.getSoldCount();

        assertThat(totalAllocated).isLessThanOrEqualTo(finalInventory.getTotalCount());
        assertThat(finalInventory.getReservedCount()).isEqualTo(availableStock);

        long reservationCount = reservationRepository.count();
        assertThat(reservationCount).isEqualTo(availableStock);
    }

    // ============================================
    // User Limit Validation Tests
    // ============================================

    @Test
    @DisplayName("User limit: Reject duplicate requests from same user")
    void userLimit_DuplicateRequest_Rejected() throws Exception {
        String userId = "user-duplicate";

        // First request should succeed
        CompletableFuture<String> firstRequest = asyncReservationService.submitReservationRequest(
            userId,
            TEST_SKU_ID,
            1
        );
        firstRequest.get(5, TimeUnit.SECONDS);

        // Wait for first request to be processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Reservation> reservations = reservationRepository.findByUserId(userId);
            assertThat(reservations).hasSize(1);
        });

        // Mock cache to indicate user already has a reservation
        when(redisCacheService.getActiveReservation(userId, TEST_SKU_ID))
            .thenReturn(Optional.of("existing-reservation-id"));

        // Second request should be rejected
        CompletableFuture<String> secondRequest = asyncReservationService.submitReservationRequest(
            userId,
            TEST_SKU_ID,
            1
        );

        assertThat(secondRequest.isCompletedExceptionally()).isTrue();

        // Verify only one reservation exists
        List<Reservation> reservations = reservationRepository.findByUserId(userId);
        assertThat(reservations).hasSize(1);
    }

    @Test
    @DisplayName("User limit: Reject request if user already purchased")
    void userLimit_UserAlreadyPurchased_Rejected() throws Exception {
        String userId = "user-already-purchased";

        // Mock cache to indicate user already purchased
        when(redisCacheService.hasUserPurchased(userId, TEST_SKU_ID)).thenReturn(true);

        // Request should be rejected immediately (pre-validation)
        CompletableFuture<String> request = asyncReservationService.submitReservationRequest(
            userId,
            TEST_SKU_ID,
            1
        );

        assertThat(request.isCompletedExceptionally()).isTrue();

        // Verify no reservation was created
        List<Reservation> reservations = reservationRepository.findByUserId(userId);
        assertThat(reservations).isEmpty();
    }

    // ============================================
    // Idempotency Tests
    // ============================================

    @Test
    @DisplayName("Idempotency: Duplicate messages are deduplicated")
    void idempotency_DuplicateMessages_Deduplicated() throws Exception {
        String userId = "user-idempotency";

        // Create idempotency key manually
        String idempotencyKey = userId + ":" + TEST_SKU_ID + ":" + System.currentTimeMillis();

        // Create message
        ReservationRequestMessage message = new ReservationRequestMessage(
            UUID.randomUUID().toString(),
            userId,
            TEST_SKU_ID,
            1,
            idempotencyKey,
            userId + "-" + TEST_SKU_ID
        );

        // Publish same message twice to Kafka directly
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        Producer<String, String> producer = new DefaultKafkaProducerFactory<>(
            producerProps,
            new StringSerializer(),
            new StringSerializer()
        ).createProducer();

        String messageJson = objectMapper.writeValueAsString(message);
        ProducerRecord<String, String> record1 = new ProducerRecord<>(
            "reservation-requests",
            TEST_SKU_ID,
            messageJson
        );
        ProducerRecord<String, String> record2 = new ProducerRecord<>(
            "reservation-requests",
            TEST_SKU_ID,
            messageJson
        );

        producer.send(record1).get();
        producer.send(record2).get();
        producer.close();

        // Wait for processing
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Reservation> reservations = reservationRepository.findByUserId(userId);
            // Should only have ONE reservation despite two messages
            assertThat(reservations).hasSize(1);
        });

        // Verify inventory was only decremented once
        Inventory inventory = inventoryRepository.findBySkuId(TEST_SKU_ID).orElseThrow();
        assertThat(inventory.getReservedCount()).isEqualTo(1);
    }

    // ============================================
    // Performance Verification Tests
    // ============================================

    @Test
    @DisplayName("Performance: Verify batch processing latency")
    void performance_BatchProcessingLatency_MeetsTarget() throws Exception {
        int batchSize = 250;

        long startTime = System.currentTimeMillis();

        // Submit batch
        List<CompletableFuture<String>> futures = IntStream.range(0, batchSize)
            .mapToObj(i -> asyncReservationService.submitReservationRequest(
                "user-perf-" + i,
                TEST_SKU_ID,
                1
            ))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        long submissionTime = System.currentTimeMillis() - startTime;

        // Wait for processing
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = reservationRepository.count();
            assertThat(count).isEqualTo(batchSize);
        });

        long totalTime = System.currentTimeMillis() - startTime;

        // Verify performance targets
        // Submission should be fast (< 2 seconds for 250 requests)
        assertThat(submissionTime).isLessThan(2000);

        // Total processing time should be reasonable (< 10 seconds for one batch)
        assertThat(totalTime).isLessThan(10000);

        System.out.println("Performance Test Results:");
        System.out.println("  Batch size: " + batchSize);
        System.out.println("  Submission time: " + submissionTime + "ms");
        System.out.println("  Total processing time: " + totalTime + "ms");
        System.out.println("  Throughput: " + (batchSize * 1000.0 / totalTime) + " RPS");
    }
}
