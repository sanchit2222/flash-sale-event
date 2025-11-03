package com.cred.freestyle.flashsale.exception;

/**
 * Exception thrown when a reservation cannot be found.
 *
 * @author Flash Sale Team
 */
public class ReservationNotFoundException extends RuntimeException {

    private final String reservationId;

    public ReservationNotFoundException(String reservationId) {
        super(String.format("Reservation %s not found", reservationId));
        this.reservationId = reservationId;
    }

    public String getReservationId() {
        return reservationId;
    }
}
