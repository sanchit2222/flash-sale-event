package com.cred.freestyle.flashsale.testutil;

import com.cred.freestyle.flashsale.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Builder class for creating test data objects.
 * Provides fluent API for building domain models with sensible defaults.
 */
public class TestDataBuilder {

    /**
     * Builder for Product
     */
    public static class ProductBuilder {
        private String skuId = "SKU-" + UUID.randomUUID().toString();
        private String name = "Test Product";
        private String description = "Test product description";
        private BigDecimal basePrice = new BigDecimal("1999.00");
        private BigDecimal flashSalePrice = new BigDecimal("999.00");
        private String category = "Electronics";
        private String flashSaleEventId = "event-001";
        private Integer totalInventory = 1000;
        private Boolean isActive = true;

        public ProductBuilder skuId(String skuId) {
            this.skuId = skuId;
            return this;
        }

        public ProductBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ProductBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ProductBuilder basePrice(BigDecimal basePrice) {
            this.basePrice = basePrice;
            return this;
        }

        public ProductBuilder flashSalePrice(BigDecimal flashSalePrice) {
            this.flashSalePrice = flashSalePrice;
            return this;
        }

        public ProductBuilder category(String category) {
            this.category = category;
            return this;
        }

        public ProductBuilder flashSaleEventId(String flashSaleEventId) {
            this.flashSaleEventId = flashSaleEventId;
            return this;
        }

        public ProductBuilder totalInventory(Integer totalInventory) {
            this.totalInventory = totalInventory;
            return this;
        }

        public ProductBuilder inactive() {
            this.isActive = false;
            return this;
        }

        public Product build() {
            Product product = new Product();
            product.setSkuId(skuId);
            product.setName(name);
            product.setDescription(description);
            product.setBasePrice(basePrice);
            product.setFlashSalePrice(flashSalePrice);
            product.setCategory(category);
            product.setFlashSaleEventId(flashSaleEventId);
            product.setTotalInventory(totalInventory);
            product.setIsActive(isActive);
            return product;
        }
    }

    /**
     * Builder for Inventory
     */
    public static class InventoryBuilder {
        private String skuId = "SKU-" + UUID.randomUUID().toString();
        private Integer totalStock = 1000;
        private Integer availableStock = 1000;
        private Integer reservedStock = 0;
        private Integer soldStock = 0;

        public InventoryBuilder skuId(String skuId) {
            this.skuId = skuId;
            return this;
        }

        public InventoryBuilder totalStock(Integer totalStock) {
            this.totalStock = totalStock;
            this.availableStock = totalStock;
            return this;
        }

        public InventoryBuilder availableStock(Integer availableStock) {
            this.availableStock = availableStock;
            return this;
        }

        public InventoryBuilder reservedStock(Integer reservedStock) {
            this.reservedStock = reservedStock;
            return this;
        }

        public InventoryBuilder soldStock(Integer soldStock) {
            this.soldStock = soldStock;
            return this;
        }

        public InventoryBuilder outOfStock() {
            this.availableStock = 0;
            this.soldStock = this.totalStock;
            return this;
        }

        public Inventory build() {
            Inventory inventory = new Inventory();
            inventory.setSkuId(skuId);
            inventory.setTotalCount(totalStock);
            inventory.setAvailableCount(availableStock);
            inventory.setReservedCount(reservedStock);
            inventory.setSoldCount(soldStock);
            inventory.setVersion(0);
            return inventory;
        }
    }

    /**
     * Builder for Reservation
     */
    public static class ReservationBuilder {
        private String reservationId = "RES-" + UUID.randomUUID().toString();
        private String userId = "user-" + UUID.randomUUID().toString();
        private String skuId = "SKU-" + UUID.randomUUID().toString();
        private Integer quantity = 1;
        private Reservation.ReservationStatus status = Reservation.ReservationStatus.RESERVED;
        private Instant expiresAt = Instant.now().plus(2, ChronoUnit.MINUTES);
        private String idempotencyKey = UUID.randomUUID().toString();

        public ReservationBuilder reservationId(String reservationId) {
            this.reservationId = reservationId;
            return this;
        }

        public ReservationBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public ReservationBuilder skuId(String skuId) {
            this.skuId = skuId;
            return this;
        }

        public ReservationBuilder quantity(Integer quantity) {
            this.quantity = quantity;
            return this;
        }

        public ReservationBuilder status(Reservation.ReservationStatus status) {
            this.status = status;
            return this;
        }

        public ReservationBuilder expired() {
            this.status = Reservation.ReservationStatus.EXPIRED;
            this.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            return this;
        }

        public ReservationBuilder confirmed() {
            this.status = Reservation.ReservationStatus.CONFIRMED;
            return this;
        }

        public ReservationBuilder cancelled() {
            this.status = Reservation.ReservationStatus.CANCELLED;
            return this;
        }

        public ReservationBuilder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Reservation build() {
            Reservation reservation = new Reservation();
            reservation.setReservationId(reservationId);
            reservation.setUserId(userId);
            reservation.setSkuId(skuId);
            reservation.setQuantity(quantity);
            reservation.setStatus(status);
            reservation.setExpiresAt(expiresAt);
            reservation.setIdempotencyKey(idempotencyKey);
            reservation.setCreatedAt(Instant.now());
            return reservation;
        }
    }

    /**
     * Builder for Order
     */
    public static class OrderBuilder {
        private String orderId = "ORD-" + UUID.randomUUID().toString();
        private String userId = "user-" + UUID.randomUUID().toString();
        private String skuId = "SKU-" + UUID.randomUUID().toString();
        private String reservationId = "RES-" + UUID.randomUUID().toString();
        private Integer quantity = 1;
        private BigDecimal totalPrice = new BigDecimal("999.99");
        private Order.OrderStatus status = Order.OrderStatus.CONFIRMED;
        private String paymentTransactionId = "PAY-" + UUID.randomUUID().toString();
        private String paymentMethod = "CREDIT_CARD";
        private String shippingAddress = "123 Test Street, City";

        public OrderBuilder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }

        public OrderBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public OrderBuilder skuId(String skuId) {
            this.skuId = skuId;
            return this;
        }

        public OrderBuilder reservationId(String reservationId) {
            this.reservationId = reservationId;
            return this;
        }

        public OrderBuilder quantity(Integer quantity) {
            this.quantity = quantity;
            return this;
        }

        public OrderBuilder totalPrice(BigDecimal totalPrice) {
            this.totalPrice = totalPrice;
            return this;
        }

        public OrderBuilder status(Order.OrderStatus status) {
            this.status = status;
            return this;
        }

        public OrderBuilder paymentTransactionId(String paymentTransactionId) {
            this.paymentTransactionId = paymentTransactionId;
            return this;
        }

        public OrderBuilder paymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public OrderBuilder shippingAddress(String shippingAddress) {
            this.shippingAddress = shippingAddress;
            return this;
        }

        public OrderBuilder paymentPending() {
            this.status = Order.OrderStatus.PAYMENT_PENDING;
            return this;
        }

        public OrderBuilder paymentFailed() {
            this.status = Order.OrderStatus.PAYMENT_FAILED;
            return this;
        }

        public OrderBuilder fulfilled() {
            this.status = Order.OrderStatus.FULFILLED;
            return this;
        }

        public OrderBuilder cancelled() {
            this.status = Order.OrderStatus.CANCELLED;
            return this;
        }

        public Order build() {
            Order order = new Order();
            order.setOrderId(orderId);
            order.setUserId(userId);
            order.setSkuId(skuId);
            order.setReservationId(reservationId);
            order.setQuantity(quantity);
            order.setTotalPrice(totalPrice);
            order.setStatus(status);
            order.setPaymentTransactionId(paymentTransactionId);
            order.setPaymentMethod(paymentMethod);
            order.setShippingAddress(shippingAddress);
            order.setCreatedAt(Instant.now());
            return order;
        }
    }

    // Factory methods
    public static ProductBuilder product() {
        return new ProductBuilder();
    }

    public static InventoryBuilder inventory() {
        return new InventoryBuilder();
    }

    public static ReservationBuilder reservation() {
        return new ReservationBuilder();
    }

    public static OrderBuilder order() {
        return new OrderBuilder();
    }
}
