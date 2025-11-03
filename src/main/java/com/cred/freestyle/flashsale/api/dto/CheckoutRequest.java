package com.cred.freestyle.flashsale.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for checkout/order creation.
 * User must have an active reservation before checkout.
 *
 * @author Flash Sale Team
 */
public class CheckoutRequest {

    @NotBlank(message = "Reservation ID is required")
    private String reservationId;

    @NotBlank(message = "Payment transaction ID is required")
    private String paymentTransactionId;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @NotBlank(message = "Shipping address is required")
    private String shippingAddress;

    public CheckoutRequest() {
    }

    public CheckoutRequest(String reservationId, String paymentTransactionId,
                          String paymentMethod, String shippingAddress) {
        this.reservationId = reservationId;
        this.paymentTransactionId = paymentTransactionId;
        this.paymentMethod = paymentMethod;
        this.shippingAddress = shippingAddress;
    }

    // Getters and setters
    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
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
}
