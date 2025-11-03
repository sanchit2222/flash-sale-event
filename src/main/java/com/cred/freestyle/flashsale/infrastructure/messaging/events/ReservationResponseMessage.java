package com.cred.freestyle.flashsale.infrastructure.messaging.events;

import java.time.Instant;

/**
 * Response message for reservation requests processed by the Kafka consumer.
 * Used to communicate the result of batch processing back to waiting clients.
 *
 * @author Flash Sale Team
 */
public class ReservationResponseMessage {

    private String requestId;
    private String reservationId; // Null if reservation failed
    private ResponseStatus status;
    private String errorMessage; // Null if successful
    private Instant expiresAt; // Null if reservation failed
    private Instant processedAt;

    /**
     * Default constructor for deserialization.
     */
    public ReservationResponseMessage() {
    }

    /**
     * Constructor for successful reservation response.
     *
     * @param requestId Original request ID
     * @param reservationId Created reservation ID
     * @param expiresAt Reservation expiry timestamp
     */
    public static ReservationResponseMessage success(String requestId, String reservationId, Instant expiresAt) {
        ReservationResponseMessage response = new ReservationResponseMessage();
        response.requestId = requestId;
        response.reservationId = reservationId;
        response.status = ResponseStatus.SUCCESS;
        response.expiresAt = expiresAt;
        response.processedAt = Instant.now();
        return response;
    }

    /**
     * Constructor for failed reservation response.
     *
     * @param requestId Original request ID
     * @param status Failure status
     * @param errorMessage Error message
     */
    public static ReservationResponseMessage failure(String requestId, ResponseStatus status, String errorMessage) {
        ReservationResponseMessage response = new ReservationResponseMessage();
        response.requestId = requestId;
        response.status = status;
        response.errorMessage = errorMessage;
        response.processedAt = Instant.now();
        return response;
    }

    // Getters and setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public ResponseStatus getStatus() {
        return status;
    }

    public void setStatus(ResponseStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    /**
     * Response status enum.
     */
    public enum ResponseStatus {
        SUCCESS,
        OUT_OF_STOCK,
        USER_ALREADY_PURCHASED,
        USER_HAS_ACTIVE_RESERVATION,
        DUPLICATE_REQUEST,
        INVALID_REQUEST,  // Invalid quantity or request parameters
        PROCESSING_ERROR
    }

    @Override
    public String toString() {
        return "ReservationResponseMessage{" +
                "requestId='" + requestId + '\'' +
                ", reservationId='" + reservationId + '\'' +
                ", status=" + status +
                ", errorMessage='" + errorMessage + '\'' +
                ", expiresAt=" + expiresAt +
                ", processedAt=" + processedAt +
                '}';
    }
}
