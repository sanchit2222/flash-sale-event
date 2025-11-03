package com.cred.freestyle.flashsale.exception;

/**
 * Exception thrown when attempting to use an expired reservation.
 * Reservations expire after 2 minutes if not converted to orders.
 *
 * @author Flash Sale Team
 */
public class ReservationExpiredException extends RuntimeException {

    private final String reservationId;

    public ReservationExpiredException(String reservationId) {
        super(String.format("Reservation %s has expired. Reservations are valid for 2 minutes only",
                reservationId));
        this.reservationId = reservationId;
    }

    public String getReservationId() {
        return reservationId;
    }
}
