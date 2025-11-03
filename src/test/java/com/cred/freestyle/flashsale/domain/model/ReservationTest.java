package com.cred.freestyle.flashsale.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Reservation domain model.
 */
@DisplayName("Reservation Domain Model Tests")
class ReservationTest {

    @Test
    @DisplayName("Should mark reservation as expired when expire() is called")
    void shouldExpireReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setReservationId("RES-001");
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);

        // When
        reservation.expire();

        // Then
        assertThat(reservation.getStatus()).isEqualTo(Reservation.ReservationStatus.EXPIRED);
        assertThat(reservation.getExpiredAt()).isNotNull();
        assertThat(reservation.getExpiredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Should mark reservation as confirmed when confirm() is called")
    void shouldConfirmReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setReservationId("RES-001");
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);

        // When
        reservation.confirm();

        // Then
        assertThat(reservation.getStatus()).isEqualTo(Reservation.ReservationStatus.CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isNotNull();
        assertThat(reservation.getConfirmedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Should mark reservation as cancelled when cancel() is called")
    void shouldCancelReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setReservationId("RES-001");
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);

        // When
        reservation.cancel();

        // Then
        assertThat(reservation.getStatus()).isEqualTo(Reservation.ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
        assertThat(reservation.getCancelledAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Should return true when reservation is expired based on expiresAt timestamp")
    void shouldDetectExpiredReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        // When
        boolean isExpired = reservation.isExpired();

        // Then
        assertThat(isExpired).isTrue();
    }

    @Test
    @DisplayName("Should return false when reservation is not yet expired")
    void shouldDetectNonExpiredReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));

        // When
        boolean isExpired = reservation.isExpired();

        // Then
        assertThat(isExpired).isFalse();
    }

    @Test
    @DisplayName("Should detect active reservation when status is RESERVED and not expired")
    void shouldDetectActiveReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);
        reservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));

        // When
        boolean isActive = reservation.isActive();

        // Then
        assertThat(isActive).isTrue();
    }

    @Test
    @DisplayName("Should detect inactive reservation when status is not RESERVED")
    void shouldDetectInactiveReservationWhenConfirmed() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setStatus(Reservation.ReservationStatus.CONFIRMED);
        reservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));

        // When
        boolean isActive = reservation.isActive();

        // Then
        assertThat(isActive).isFalse();
    }

    @Test
    @DisplayName("Should detect inactive reservation when time expired")
    void shouldDetectInactiveReservationWhenTimeExpired() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);
        reservation.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        // When
        boolean isActive = reservation.isActive();

        // Then
        assertThat(isActive).isFalse();
    }

    @Test
    @DisplayName("Should have correct timestamps after marking as failed")
    void shouldHaveCorrectTimestampsAfterFail() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);

        // When
        reservation.fail();

        // Then
        assertThat(reservation.getStatus()).isEqualTo(Reservation.ReservationStatus.FAILED);
    }

    @Test
    @DisplayName("Should correctly identify cancelled reservation")
    void shouldIdentifyCancelledReservation() {
        // Given
        Reservation reservation = new Reservation();
        reservation.setStatus(Reservation.ReservationStatus.RESERVED);

        // When
        reservation.cancel();

        // Then
        assertThat(reservation.getStatus()).isEqualTo(Reservation.ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }
}
