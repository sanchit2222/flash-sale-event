package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ReservationRepository using Testcontainers.
 * Tests repository methods against a real PostgreSQL database.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("ReservationRepository Integration Tests")
class ReservationRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private ReservationRepository reservationRepository;

    private Reservation testReservation;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();

        testReservation = new Reservation();
        testReservation.setReservationId("RES-TEST-001");
        testReservation.setUserId("user-123");
        testReservation.setSkuId("SKU-001");
        testReservation.setQuantity(1);
        testReservation.setStatus(ReservationStatus.RESERVED);
        testReservation.setCreatedAt(Instant.now());
        testReservation.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
    }

    // ========================================
    // findByUserId Tests
    // ========================================

    @Test
    @DisplayName("findByUserId - Should return all reservations for user")
    void findByUserId_ReturnsUserReservations() {
        // Given
        reservationRepository.save(testReservation);

        Reservation res2 = new Reservation();
        res2.setReservationId("RES-TEST-002");
        res2.setUserId("user-123");
        res2.setSkuId("SKU-002");
        res2.setQuantity(1);
        res2.setStatus(ReservationStatus.RESERVED);
        res2.setCreatedAt(Instant.now());
        res2.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(res2);

        // Another user's reservation
        Reservation res3 = new Reservation();
        res3.setReservationId("RES-TEST-003");
        res3.setUserId("user-999");
        res3.setSkuId("SKU-003");
        res3.setQuantity(1);
        res3.setStatus(ReservationStatus.RESERVED);
        res3.setCreatedAt(Instant.now());
        res3.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(res3);

        // When
        List<Reservation> userReservations = reservationRepository.findByUserId("user-123");

        // Then
        assertThat(userReservations).hasSize(2);
        assertThat(userReservations).extracting(Reservation::getUserId)
                .containsOnly("user-123");
    }

    // ========================================
    // findByUserIdAndSkuIdAndStatus Tests
    // ========================================

    @Test
    @DisplayName("findByUserIdAndSkuIdAndStatus - Should find active reservation")
    void findByUserIdAndSkuIdAndStatus_FindsActiveReservation() {
        // Given
        reservationRepository.save(testReservation);

        // When
        Optional<Reservation> found = reservationRepository.findByUserIdAndSkuIdAndStatus(
                "user-123", "SKU-001", ReservationStatus.RESERVED);

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getReservationId()).isEqualTo("RES-TEST-001");
    }

    @Test
    @DisplayName("findByUserIdAndSkuIdAndStatus - Should return empty for different status")
    void findByUserIdAndSkuIdAndStatus_DifferentStatus_ReturnsEmpty() {
        // Given
        reservationRepository.save(testReservation);

        // When
        Optional<Reservation> found = reservationRepository.findByUserIdAndSkuIdAndStatus(
                "user-123", "SKU-001", ReservationStatus.CONFIRMED);

        // Then
        assertThat(found).isEmpty();
    }

    // ========================================
    // findActiveReservationsByUserId Tests
    // ========================================

    @Test
    @DisplayName("findActiveReservationsByUserId - Should return only RESERVED status reservations")
    void findActiveReservationsByUserId_ReturnsOnlyReserved() {
        // Given
        reservationRepository.save(testReservation);

        // Confirmed reservation
        Reservation confirmed = new Reservation();
        confirmed.setReservationId("RES-TEST-004");
        confirmed.setUserId("user-123");
        confirmed.setSkuId("SKU-004");
        confirmed.setQuantity(1);
        confirmed.setStatus(ReservationStatus.CONFIRMED);
        confirmed.setCreatedAt(Instant.now());
        confirmed.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(confirmed);

        // When
        List<Reservation> activeReservations =
                reservationRepository.findActiveReservationsByUserId("user-123", Instant.now());

        // Then
        assertThat(activeReservations).hasSize(1);
        assertThat(activeReservations.get(0).getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    // ========================================
    // findExpiredReservations Tests
    // ========================================

    @Test
    @DisplayName("findExpiredReservations - Should find reservations past expiry time")
    void findExpiredReservations_FindsExpiredReservations() {
        // Given - Create expired reservation
        Reservation expired = new Reservation();
        expired.setReservationId("RES-EXPIRED-001");
        expired.setUserId("user-expired");
        expired.setSkuId("SKU-EXPIRED");
        expired.setQuantity(1);
        expired.setStatus(ReservationStatus.RESERVED);
        expired.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)); // Expired 1 minute ago
        reservationRepository.save(expired);

        // Active reservation
        reservationRepository.save(testReservation);

        // When
        List<Reservation> expiredReservations =
                reservationRepository.findExpiredReservations(Instant.now());

        // Then
        assertThat(expiredReservations).hasSize(1);
        assertThat(expiredReservations.get(0).getReservationId()).isEqualTo("RES-EXPIRED-001");
    }

    @Test
    @DisplayName("findExpiredReservations - Should exclude non-RESERVED statuses")
    void findExpiredReservations_ExcludesNonReservedStatus() {
        // Given - Expired but confirmed
        Reservation expiredConfirmed = new Reservation();
        expiredConfirmed.setReservationId("RES-EXP-CONF-001");
        expiredConfirmed.setUserId("user-test");
        expiredConfirmed.setSkuId("SKU-TEST");
        expiredConfirmed.setQuantity(1);
        expiredConfirmed.setStatus(ReservationStatus.CONFIRMED);
        expiredConfirmed.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        expiredConfirmed.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        reservationRepository.save(expiredConfirmed);

        // When
        List<Reservation> expiredReservations =
                reservationRepository.findExpiredReservations(Instant.now());

        // Then
        assertThat(expiredReservations).isEmpty();
    }

    // ========================================
    // Save and Find Tests
    // ========================================

    @Test
    @DisplayName("save - Should persist reservation with all fields")
    void save_PersistsReservationWithAllFields() {
        // When
        Reservation saved = reservationRepository.save(testReservation);
        reservationRepository.flush();

        // Then
        Optional<Reservation> found = reservationRepository.findById(saved.getReservationId());
        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("user-123");
        assertThat(found.get().getSkuId()).isEqualTo("SKU-001");
        assertThat(found.get().getQuantity()).isEqualTo(1);
        assertThat(found.get().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("save - Should update existing reservation")
    void save_UpdatesExistingReservation() {
        // Given
        Reservation saved = reservationRepository.save(testReservation);
        String originalId = saved.getReservationId();

        // When - Update status
        saved.setStatus(ReservationStatus.CONFIRMED);
        saved.setConfirmedAt(Instant.now());
        reservationRepository.save(saved);
        reservationRepository.flush();

        // Then
        Optional<Reservation> updated = reservationRepository.findById(originalId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(updated.get().getConfirmedAt()).isNotNull();
    }

    // ========================================
    // Delete Tests
    // ========================================

    @Test
    @DisplayName("delete - Should remove reservation from database")
    void delete_RemovesReservation() {
        // Given
        Reservation saved = reservationRepository.save(testReservation);
        String reservationId = saved.getReservationId();

        // When
        reservationRepository.delete(saved);
        reservationRepository.flush();

        // Then
        Optional<Reservation> found = reservationRepository.findById(reservationId);
        assertThat(found).isEmpty();
    }

    // ========================================
    // Count Tests
    // ========================================

    @Test
    @DisplayName("count - Should return correct count of reservations")
    void count_ReturnsCorrectCount() {
        // Given
        reservationRepository.save(testReservation);

        Reservation res2 = new Reservation();
        res2.setReservationId("RES-TEST-005");
        res2.setUserId("user-456");
        res2.setSkuId("SKU-005");
        res2.setQuantity(1);
        res2.setStatus(ReservationStatus.RESERVED);
        res2.setCreatedAt(Instant.now());
        res2.setExpiresAt(Instant.now().plus(2, ChronoUnit.MINUTES));
        reservationRepository.save(res2);

        // When
        long count = reservationRepository.count();

        // Then
        assertThat(count).isEqualTo(2);
    }
}
