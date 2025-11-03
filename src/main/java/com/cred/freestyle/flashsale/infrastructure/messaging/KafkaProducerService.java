package com.cred.freestyle.flashsale.infrastructure.messaging;

import com.cred.freestyle.flashsale.infrastructure.messaging.events.ReservationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer service for publishing reservation and inventory events.
 * Implements single-writer pattern for inventory updates.
 *
 * Topic partitioning strategy:
 * - Key: sku_id (ensures all events for a product go to same partition)
 * - This enables single-writer pattern per product for inventory consistency
 *
 * @author Flash Sale Team
 */
@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Topic names
    private static final String RESERVATION_TOPIC = "flash-sale-reservations";
    private static final String INVENTORY_UPDATE_TOPIC = "flash-sale-inventory-updates";
    private static final String ORDER_TOPIC = "flash-sale-orders";

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish reservation created event.
     * This event will be consumed by inventory service to decrement stock.
     *
     * @param event Reservation event
     */
    public void publishReservationCreated(ReservationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // Use skuId as key for partitioning - ensures all events for a product go to same partition
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    RESERVATION_TOPIC,
                    event.getSkuId(),
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Published reservation created event for reservation {}, SKU: {}, partition: {}",
                            event.getReservationId(), event.getSkuId(), result.getRecordMetadata().partition());
                } else {
                    logger.error("Failed to publish reservation created event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId(), ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing reservation event for reservation {}", event.getReservationId(), e);
        }
    }

    /**
     * Publish reservation expired event.
     * This event will be consumed by inventory service to release reserved stock.
     *
     * @param event Reservation event
     */
    public void publishReservationExpired(ReservationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    RESERVATION_TOPIC,
                    event.getSkuId(),
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Published reservation expired event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId());
                } else {
                    logger.error("Failed to publish reservation expired event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId(), ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing reservation expired event for reservation {}", event.getReservationId(), e);
        }
    }

    /**
     * Publish reservation confirmed event (payment successful).
     * This event will be consumed by inventory service to convert reserved to sold.
     *
     * @param event Reservation event
     */
    public void publishReservationConfirmed(ReservationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    RESERVATION_TOPIC,
                    event.getSkuId(),
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Published reservation confirmed event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId());
                } else {
                    logger.error("Failed to publish reservation confirmed event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId(), ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing reservation confirmed event for reservation {}", event.getReservationId(), e);
        }
    }

    /**
     * Publish reservation cancelled event (user-initiated cancellation).
     * This event will be consumed by inventory service to release reserved inventory.
     *
     * @param event Reservation event
     */
    public void publishReservationCancelled(ReservationEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    RESERVATION_TOPIC,
                    event.getSkuId(),
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Published reservation cancelled event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId());
                } else {
                    logger.error("Failed to publish reservation cancelled event for reservation {}, SKU: {}",
                            event.getReservationId(), event.getSkuId(), ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing reservation cancelled event for reservation {}", event.getReservationId(), e);
        }
    }

    /**
     * Publish inventory update event.
     * Used for cache invalidation and real-time inventory updates.
     *
     * @param skuId Product SKU ID
     * @param availableCount New available count
     * @param eventType Event type (e.g., "RESERVED", "RELEASED", "SOLD")
     */
    public void publishInventoryUpdate(String skuId, Integer availableCount, String eventType) {
        try {
            InventoryUpdateEvent event = new InventoryUpdateEvent(skuId, availableCount, eventType);
            String payload = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    INVENTORY_UPDATE_TOPIC,
                    skuId,
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.debug("Published inventory update event for SKU: {}, type: {}, count: {}",
                            skuId, eventType, availableCount);
                } else {
                    logger.error("Failed to publish inventory update event for SKU: {}", skuId, ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing inventory update event for SKU: {}", skuId, e);
        }
    }

    /**
     * Publish order created event.
     * Used for order fulfillment and analytics.
     *
     * @param orderId Order ID
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param quantity Quantity ordered
     */
    public void publishOrderCreated(String orderId, String userId, String skuId, Integer quantity) {
        try {
            OrderEvent event = new OrderEvent(orderId, userId, skuId, quantity, "CREATED");
            String payload = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    ORDER_TOPIC,
                    skuId,
                    payload
            );

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    logger.info("Published order created event for order: {}, SKU: {}", orderId, skuId);
                } else {
                    logger.error("Failed to publish order created event for order: {}", orderId, ex);
                }
            });
        } catch (JsonProcessingException e) {
            logger.error("Error serializing order event for order: {}", orderId, e);
        }
    }

    /**
     * Inner class for inventory update events.
     */
    private static class InventoryUpdateEvent {
        private final String skuId;
        private final Integer availableCount;
        private final String eventType;
        private final long timestamp;

        public InventoryUpdateEvent(String skuId, Integer availableCount, String eventType) {
            this.skuId = skuId;
            this.availableCount = availableCount;
            this.eventType = eventType;
            this.timestamp = System.currentTimeMillis();
        }

        public String getSkuId() { return skuId; }
        public Integer getAvailableCount() { return availableCount; }
        public String getEventType() { return eventType; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * Inner class for order events.
     */
    private static class OrderEvent {
        private final String orderId;
        private final String userId;
        private final String skuId;
        private final Integer quantity;
        private final String eventType;
        private final long timestamp;

        public OrderEvent(String orderId, String userId, String skuId, Integer quantity, String eventType) {
            this.orderId = orderId;
            this.userId = userId;
            this.skuId = skuId;
            this.quantity = quantity;
            this.eventType = eventType;
            this.timestamp = System.currentTimeMillis();
        }

        public String getOrderId() { return orderId; }
        public String getUserId() { return userId; }
        public String getSkuId() { return skuId; }
        public Integer getQuantity() { return quantity; }
        public String getEventType() { return eventType; }
        public long getTimestamp() { return timestamp; }
    }
}
