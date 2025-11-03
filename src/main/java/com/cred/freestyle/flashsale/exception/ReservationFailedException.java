package com.cred.freestyle.flashsale.exception;

/**
 * Exception thrown when a reservation request fails during batch processing.
 * This exception carries the rejection status and error message from the batch consumer,
 * allowing clients to immediately receive specific failure reasons instead of timing out.
 *
 * Common rejection statuses:
 * - OUT_OF_STOCK: Product is out of stock
 * - USER_ALREADY_PURCHASED: User has already purchased this product (1 unit per user limit)
 * - DUPLICATE_REQUEST: Duplicate reservation request detected
 * - VALIDATION_FAILED: Request failed validation
 *
 * @author Flash Sale Team
 */
public class ReservationFailedException extends RuntimeException {

    private final String status;
    private final String errorMessage;

    /**
     * Create a ReservationFailedException with status and error message.
     *
     * @param status Rejection status (e.g., OUT_OF_STOCK, USER_ALREADY_PURCHASED)
     * @param errorMessage Human-readable error message describing the rejection reason
     */
    public ReservationFailedException(String status, String errorMessage) {
        super(String.format("Reservation failed: %s - %s", status, errorMessage));
        this.status = status;
        this.errorMessage = errorMessage;
    }

    /**
     * Get the rejection status.
     *
     * @return Rejection status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Get the error message.
     *
     * @return Error message
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
