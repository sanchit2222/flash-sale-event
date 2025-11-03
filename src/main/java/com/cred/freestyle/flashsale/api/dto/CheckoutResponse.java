package com.cred.freestyle.flashsale.api.dto;

import com.cred.freestyle.flashsale.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for checkout/order creation.
 * Contains order details after successful payment.
 *
 * @author Flash Sale Team
 */
public class CheckoutResponse {

    private String orderId;
    private String reservationId;
    private String userId;
    private String skuId;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String paymentTransactionId;
    private String paymentMethod;
    private String shippingAddress;
    private String status;
    private Instant createdAt;

    public CheckoutResponse() {
    }

    /**
     * Create response from Order entity.
     *
     * @param order Order entity
     * @return CheckoutResponse
     */
    public static CheckoutResponse fromEntity(Order order) {
        CheckoutResponse response = new CheckoutResponse();
        response.setOrderId(order.getOrderId());
        response.setReservationId(order.getReservationId());
        response.setUserId(order.getUserId());
        response.setSkuId(order.getSkuId());
        response.setQuantity(order.getQuantity());
        response.setTotalPrice(order.getTotalPrice());
        response.setPaymentTransactionId(order.getPaymentTransactionId());
        response.setPaymentMethod(order.getPaymentMethod());
        response.setShippingAddress(order.getShippingAddress());
        response.setStatus(order.getStatus().name());
        response.setCreatedAt(order.getCreatedAt());

        return response;
    }

    // Getters and setters
    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public String getPaymentTransactionId() {
        return paymentTransactionId;
    }

    public void setPaymentTransactionId(String paymentTransactionId) {
        this.paymentTransactionId = paymentTransactionId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
