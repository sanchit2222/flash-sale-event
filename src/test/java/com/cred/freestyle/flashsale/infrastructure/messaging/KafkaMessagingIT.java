package com.cred.freestyle.flashsale.infrastructure.messaging;

import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for Kafka messaging using Testcontainers.
 * Tests Kafka producer service with real Kafka broker.
 */
@SpringBootTest(properties = {
    "spring.data.redis.enabled=false"
})
@Testcontainers
@DisplayName("Kafka Messaging Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaMessagingIT {

    private static final String RESERVATION_TOPIC = "flash-sale-reservations";
    private static final String INVENTORY_UPDATE_TOPIC = "flash-sale-inventory-updates";
    private static final String ORDER_TOPIC = "flash-sale-orders";

    @Container
    @SuppressWarnings("resource") // Testcontainers manages lifecycle automatically
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withEmbeddedZookeeper();

    @Autowired
    private KafkaProducerService kafkaProducerService;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> testConsumer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);
        testConsumer = consumerFactory.createConsumer();
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    // ========================================
    // Reservation Event Tests
    // ========================================

    @Test
    @Order(1)
    @DisplayName("publishReservationCreated - Publishes event to Kafka")
    void publishReservationCreated_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        ReservationEvent event = new ReservationEvent(
                "RES-001",
                "user-123",
                "SKU-001",
                1,
                ReservationEvent.EventType.CREATED,
                "idempotency-key-001"
        );

        // When
        kafkaProducerService.publishReservationCreated(event);

        // Then - Wait for message and verify
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                assertThat(record.topic()).isEqualTo(RESERVATION_TOPIC);
                assertThat(record.key()).isEqualTo("SKU-001"); // Partitioned by skuId

                // Deserialize and verify event
                ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);
                assertThat(receivedEvent.getReservationId()).isEqualTo("RES-001");
                assertThat(receivedEvent.getUserId()).isEqualTo("user-123");
                assertThat(receivedEvent.getSkuId()).isEqualTo("SKU-001");
                assertThat(receivedEvent.getQuantity()).isEqualTo(1);
                assertThat(receivedEvent.getEventType()).isEqualTo(ReservationEvent.EventType.CREATED);
                assertThat(receivedEvent.getIdempotencyKey()).isEqualTo("idempotency-key-001");
            });
    }

    @Test
    @Order(2)
    @DisplayName("publishReservationConfirmed - Publishes confirmed event")
    void publishReservationConfirmed_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        ReservationEvent event = new ReservationEvent(
                "RES-002",
                "user-456",
                "SKU-002",
                2,
                ReservationEvent.EventType.CONFIRMED,
                "idempotency-key-002"
        );

        // When
        kafkaProducerService.publishReservationConfirmed(event);

        // Then
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);

                assertThat(receivedEvent.getReservationId()).isEqualTo("RES-002");
                assertThat(receivedEvent.getEventType()).isEqualTo(ReservationEvent.EventType.CONFIRMED);
            });
    }

    @Test
    @Order(3)
    @DisplayName("publishReservationExpired - Publishes expired event")
    void publishReservationExpired_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        ReservationEvent event = new ReservationEvent(
                "RES-003",
                "user-789",
                "SKU-003",
                1,
                ReservationEvent.EventType.EXPIRED,
                "idempotency-key-003"
        );

        // When
        kafkaProducerService.publishReservationExpired(event);

        // Then
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);

                assertThat(receivedEvent.getReservationId()).isEqualTo("RES-003");
                assertThat(receivedEvent.getEventType()).isEqualTo(ReservationEvent.EventType.EXPIRED);
            });
    }

    @Test
    @Order(4)
    @DisplayName("publishReservationCancelled - Publishes cancelled event")
    void publishReservationCancelled_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        ReservationEvent event = new ReservationEvent(
                "RES-004",
                "user-999",
                "SKU-004",
                1,
                ReservationEvent.EventType.CANCELLED,
                "idempotency-key-004"
        );

        // When
        kafkaProducerService.publishReservationCancelled(event);

        // Then
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);

                assertThat(receivedEvent.getReservationId()).isEqualTo("RES-004");
                assertThat(receivedEvent.getEventType()).isEqualTo(ReservationEvent.EventType.CANCELLED);
            });
    }

    // ========================================
    // Partitioning Tests
    // ========================================

    @Test
    @Order(5)
    @DisplayName("publishReservationCreated - Same SKU goes to same partition")
    void publishReservationCreated_SameSKU_SamePartition() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        String sharedSkuId = "SKU-PARTITION-TEST";
        Set<Integer> partitions = new HashSet<>();

        // When - Publish multiple events with same SKU
        for (int i = 0; i < 5; i++) {
            ReservationEvent event = new ReservationEvent(
                    "RES-PART-" + i,
                    "user-" + i,
                    sharedSkuId,
                    1,
                    ReservationEvent.EventType.CREATED,
                    "key-" + i
            );
            kafkaProducerService.publishReservationCreated(event);
        }

        // Then - All messages should go to the same partition
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    if (record.key().equals(sharedSkuId)) {
                        partitions.add(record.partition());
                    }
                }

                // All messages with same key should be in same partition
                assertThat(partitions).hasSize(1);
            });
    }

    @Test
    @Order(6)
    @DisplayName("publishReservationCreated - Different SKUs can go to different partitions")
    void publishReservationCreated_DifferentSKUs_MayDifferPartitions() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        List<String> skuIds = Arrays.asList("SKU-A", "SKU-B", "SKU-C", "SKU-D", "SKU-E");
        Map<String, Integer> skuToPartition = new HashMap<>();

        // When - Publish events for different SKUs
        for (String skuId : skuIds) {
            ReservationEvent event = new ReservationEvent(
                    "RES-" + skuId,
                    "user-test",
                    skuId,
                    1,
                    ReservationEvent.EventType.CREATED,
                    "key-" + skuId
            );
            kafkaProducerService.publishReservationCreated(event);
        }

        // Then - Record partition for each SKU
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    if (skuIds.contains(record.key())) {
                        skuToPartition.put(record.key(), record.partition());
                    }
                }

                // Should have received messages for all SKUs
                assertThat(skuToPartition.keySet()).containsAll(skuIds);
            });

        // Each SKU should consistently map to same partition
        assertThat(skuToPartition.values()).isNotEmpty();
    }

    // ========================================
    // Inventory Update Event Tests
    // ========================================

    @Test
    @Order(7)
    @DisplayName("publishInventoryUpdate - Publishes inventory update event")
    void publishInventoryUpdate_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(INVENTORY_UPDATE_TOPIC));

        String skuId = "SKU-INV-001";
        Integer availableCount = 95;
        String eventType = "RESERVED";

        // When
        kafkaProducerService.publishInventoryUpdate(skuId, availableCount, eventType);

        // Then
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                assertThat(record.topic()).isEqualTo(INVENTORY_UPDATE_TOPIC);
                assertThat(record.key()).isEqualTo(skuId);

                // Verify event structure
                String payload = record.value();
                assertThat(payload).contains("\"skuId\":\"" + skuId + "\"");
                assertThat(payload).contains("\"availableCount\":" + availableCount);
                assertThat(payload).contains("\"eventType\":\"" + eventType + "\"");
                assertThat(payload).contains("\"timestamp\":");
            });
    }

    @Test
    @Order(8)
    @DisplayName("publishInventoryUpdate - Multiple event types")
    void publishInventoryUpdate_MultipleEventTypes() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(INVENTORY_UPDATE_TOPIC));

        List<String> eventTypes = Arrays.asList("RESERVED", "RELEASED", "SOLD");
        String skuId = "SKU-INV-002";

        // When - Publish different event types
        for (int i = 0; i < eventTypes.size(); i++) {
            kafkaProducerService.publishInventoryUpdate(skuId, 100 - (i * 10), eventTypes.get(i));
        }

        // Then - Verify all events received
        Set<String> receivedEventTypes = new HashSet<>();
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    if (record.key().equals(skuId)) {
                        String payload = record.value();
                        for (String eventType : eventTypes) {
                            if (payload.contains("\"eventType\":\"" + eventType + "\"")) {
                                receivedEventTypes.add(eventType);
                            }
                        }
                    }
                }

                assertThat(receivedEventTypes).containsAll(eventTypes);
            });
    }

    // ========================================
    // Order Event Tests
    // ========================================

    @Test
    @Order(9)
    @DisplayName("publishOrderCreated - Publishes order created event")
    void publishOrderCreated_PublishesEvent() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(ORDER_TOPIC));

        String orderId = "ORD-001";
        String userId = "user-order-001";
        String skuId = "SKU-ORDER-001";
        Integer quantity = 2;

        // When
        kafkaProducerService.publishOrderCreated(orderId, userId, skuId, quantity);

        // Then
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                assertThat(record.topic()).isEqualTo(ORDER_TOPIC);
                assertThat(record.key()).isEqualTo(skuId);

                // Verify event structure
                String payload = record.value();
                assertThat(payload).contains("\"orderId\":\"" + orderId + "\"");
                assertThat(payload).contains("\"userId\":\"" + userId + "\"");
                assertThat(payload).contains("\"skuId\":\"" + skuId + "\"");
                assertThat(payload).contains("\"quantity\":" + quantity);
                assertThat(payload).contains("\"eventType\":\"CREATED\"");
            });
    }

    @Test
    @Order(10)
    @DisplayName("publishOrderCreated - Multiple orders")
    void publishOrderCreated_MultipleOrders() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(ORDER_TOPIC));

        int orderCount = 5;
        List<String> orderIds = new ArrayList<>();

        // When - Publish multiple order events
        for (int i = 0; i < orderCount; i++) {
            String orderId = "ORD-MULTI-" + i;
            orderIds.add(orderId);
            kafkaProducerService.publishOrderCreated(
                    orderId,
                    "user-" + i,
                    "SKU-" + i,
                    1
            );
        }

        // Then - Verify all orders received
        Set<String> receivedOrderIds = new HashSet<>();
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    String payload = record.value();
                    for (String orderId : orderIds) {
                        if (payload.contains("\"orderId\":\"" + orderId + "\"")) {
                            receivedOrderIds.add(orderId);
                        }
                    }
                }

                assertThat(receivedOrderIds).containsAll(orderIds);
            });
    }

    // ========================================
    // Serialization and Data Integrity Tests
    // ========================================

    @Test
    @Order(11)
    @DisplayName("Reservation event - Serialization preserves all fields")
    void reservationEvent_SerializationPreservesFields() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        Instant timestamp = Instant.now();
        ReservationEvent event = new ReservationEvent(
                "RES-SERIALIZE-001",
                "user-serialize",
                "SKU-SERIALIZE",
                3,
                ReservationEvent.EventType.CREATED,
                "idempotency-serialize"
        );

        // When
        kafkaProducerService.publishReservationCreated(event);

        // Then - Verify all fields preserved
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));
                assertThat(records.count()).isGreaterThan(0);

                ConsumerRecord<String, String> record = records.iterator().next();
                ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);

                assertThat(receivedEvent.getReservationId()).isEqualTo("RES-SERIALIZE-001");
                assertThat(receivedEvent.getUserId()).isEqualTo("user-serialize");
                assertThat(receivedEvent.getSkuId()).isEqualTo("SKU-SERIALIZE");
                assertThat(receivedEvent.getQuantity()).isEqualTo(3);
                assertThat(receivedEvent.getEventType()).isEqualTo(ReservationEvent.EventType.CREATED);
                assertThat(receivedEvent.getIdempotencyKey()).isEqualTo("idempotency-serialize");
                assertThat(receivedEvent.getTimestamp()).isNotNull();
                assertThat(receivedEvent.getTimestamp()).isAfterOrEqualTo(timestamp.minusSeconds(5));
            });
    }

    // ========================================
    // Message Ordering Tests
    // ========================================

    @Test
    @Order(12)
    @DisplayName("Same SKU events - Maintain order in same partition")
    void sameSKUEvents_MaintainOrder() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        String skuId = "SKU-ORDER-TEST";
        List<ReservationEvent.EventType> eventTypes = Arrays.asList(
                ReservationEvent.EventType.CREATED,
                ReservationEvent.EventType.CONFIRMED,
                ReservationEvent.EventType.EXPIRED
        );

        // When - Publish events in sequence
        for (int i = 0; i < eventTypes.size(); i++) {
            ReservationEvent event = new ReservationEvent(
                    "RES-ORDER-" + i,
                    "user-order",
                    skuId,
                    1,
                    eventTypes.get(i),
                    "key-order-" + i
            );
            kafkaProducerService.publishReservationCreated(event);
            Thread.sleep(100); // Small delay to ensure ordering
        }

        // Then - Verify events received in order
        List<ReservationEvent.EventType> receivedEventTypes = new ArrayList<>();
        await()
            .atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(1));

                for (ConsumerRecord<String, String> record : records) {
                    if (record.key().equals(skuId)) {
                        ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);
                        receivedEventTypes.add(receivedEvent.getEventType());
                    }
                }

                assertThat(receivedEventTypes).hasSize(3);
            });

        // Events should be in the same order they were published
        assertThat(receivedEventTypes).containsExactlyElementsOf(eventTypes);
    }

    // ========================================
    // High Volume Tests
    // ========================================

    @Test
    @Order(13)
    @DisplayName("High volume - Handles multiple concurrent events")
    void highVolume_HandlesMultipleConcurrentEvents() throws Exception {
        // Given
        testConsumer.subscribe(Collections.singletonList(RESERVATION_TOPIC));

        int eventCount = 50;
        Set<String> publishedReservationIds = new HashSet<>();

        // When - Publish many events
        for (int i = 0; i < eventCount; i++) {
            String reservationId = "RES-VOLUME-" + i;
            publishedReservationIds.add(reservationId);

            ReservationEvent event = new ReservationEvent(
                    reservationId,
                    "user-volume-" + i,
                    "SKU-VOLUME-" + (i % 10), // 10 different SKUs
                    1,
                    ReservationEvent.EventType.CREATED,
                    "key-volume-" + i
            );
            kafkaProducerService.publishReservationCreated(event);
        }

        // Then - Verify all events received
        Set<String> receivedReservationIds = new HashSet<>();
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(2));

                for (ConsumerRecord<String, String> record : records) {
                    ReservationEvent receivedEvent = objectMapper.readValue(record.value(), ReservationEvent.class);
                    receivedReservationIds.add(receivedEvent.getReservationId());
                }

                assertThat(receivedReservationIds).containsAll(publishedReservationIds);
            });
    }
}
