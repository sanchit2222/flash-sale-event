package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.Reservation;
import com.cred.freestyle.flashsale.domain.model.Reservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Reservation entity.
 * Provides data access methods for reservation management.
 *
 * @author Flash Sale Team
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    /**
     * Find reservation by idempotency key.
     * Used to prevent duplicate reservations from the same user.
     *
     * @param idempotencyKey Idempotency key (format: {user_id}:{sku_id}:{timestamp})
     * @return Optional containing the reservation if found
     */
    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Check if reservation exists with the given idempotency key.
     * Used for deduplication in batch processing.
     *
     * @param idempotencyKey Idempotency key
     * @return true if reservation exists
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Find all reservations for a user with a specific status.
     *
     * @param userId User ID
     * @param status Reservation status
     * @return List of reservations
     */
    List<Reservation> findByUserIdAndStatus(String userId, ReservationStatus status);

    /**
     * Find all reservations for a specific product (SKU).
     *
     * @param skuId Product SKU ID
     * @param status Reservation status
     * @return List of reservations
     */
    List<Reservation> findBySkuIdAndStatus(String skuId, ReservationStatus status);

    /**
     * Find reservation for a specific user and product.
     * Used to check if user has an active reservation for a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param status Reservation status
     * @return Optional containing the reservation if found
     */
    Optional<Reservation> findByUserIdAndSkuIdAndStatus(
            String userId,
            String skuId,
            ReservationStatus status
    );

    /**
     * Find all active (RESERVED) reservations for a user and product.
     * Used to enforce 1 unit per user per product limit.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return List of active reservations
     */
    @Query("SELECT r FROM Reservation r WHERE r.userId = :userId AND r.skuId = :skuId " +
           "AND r.status = 'RESERVED' AND r.expiresAt > CURRENT_TIMESTAMP")
    List<Reservation> findActiveReservations(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Check if user has an active reservation for a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return true if user has an active reservation
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Reservation r " +
           "WHERE r.userId = :userId AND r.skuId = :skuId " +
           "AND r.status = 'RESERVED' AND r.expiresAt > CURRENT_TIMESTAMP")
    boolean hasActiveReservation(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Find all expired reservations that need to be processed.
     * Finds RESERVED reservations where expiresAt < current time.
     *
     * @param currentTime Current timestamp
     * @return List of expired reservations
     */
    @Query("SELECT r FROM Reservation r WHERE r.status = 'RESERVED' " +
           "AND r.expiresAt < :currentTime")
    List<Reservation> findExpiredReservations(@Param("currentTime") Instant currentTime);

    /**
     * Bulk update expired reservations to EXPIRED status.
     * This is called by a scheduled job to clean up expired reservations.
     *
     * @param currentTime Current timestamp
     * @param expiredAt Timestamp to set for expired_at field
     * @return Number of reservations updated
     */
    @Modifying
    @Query("UPDATE Reservation r SET r.status = 'EXPIRED', r.expiredAt = :expiredAt " +
           "WHERE r.status = 'RESERVED' AND r.expiresAt < :currentTime")
    int markExpiredReservations(
            @Param("currentTime") Instant currentTime,
            @Param("expiredAt") Instant expiredAt
    );

    /**
     * Count active reservations for a product.
     * Used for monitoring and analytics.
     *
     * @param skuId Product SKU ID
     * @return Number of active reservations
     */
    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.skuId = :skuId " +
           "AND r.status = 'RESERVED' AND r.expiresAt > CURRENT_TIMESTAMP")
    long countActiveReservationsBySkuId(@Param("skuId") String skuId);

    /**
     * Count total reservations for a product by status.
     *
     * @param skuId Product SKU ID
     * @param status Reservation status
     * @return Number of reservations
     */
    long countBySkuIdAndStatus(String skuId, ReservationStatus status);

    /**
     * Find reservations by user ID (all statuses).
     *
     * @param userId User ID
     * @return List of reservations
     */
    List<Reservation> findByUserId(String userId);

    /**
     * Find all active reservations for a user.
     * Used to display user's current reservations.
     *
     * @param userId User ID
     * @param currentTime Current timestamp
     * @return List of active reservations
     */
    @Query("SELECT r FROM Reservation r WHERE r.userId = :userId " +
           "AND r.status = 'RESERVED' AND r.expiresAt > :currentTime")
    List<Reservation> findActiveReservationsByUserId(
            @Param("userId") String userId,
            @Param("currentTime") Instant currentTime
    );

    /**
     * Find confirmed reservations for a user and product.
     * Used to check if user has already purchased a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return List of confirmed reservations
     */
    @Query("SELECT r FROM Reservation r WHERE r.userId = :userId AND r.skuId = :skuId " +
           "AND r.status = 'CONFIRMED'")
    List<Reservation> findConfirmedReservations(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Check if user has already purchased a product (has CONFIRMED reservation).
     * Used to enforce 1 purchase per user per product limit.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return true if user has confirmed reservation (purchased)
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Reservation r " +
           "WHERE r.userId = :userId AND r.skuId = :skuId AND r.status = 'CONFIRMED'")
    boolean hasConfirmedReservation(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Find reservations created within a time range.
     * Used for analytics and reporting.
     *
     * @param startTime Start time
     * @param endTime End time
     * @return List of reservations
     */
    @Query("SELECT r FROM Reservation r WHERE r.createdAt BETWEEN :startTime AND :endTime")
    List<Reservation> findByCreatedAtBetween(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Delete expired reservations older than a certain date.
     * Used for data cleanup/archival.
     *
     * @param cutoffDate Cutoff date
     * @return Number of reservations deleted
     */
    @Modifying
    @Query("DELETE FROM Reservation r WHERE r.status = 'EXPIRED' AND r.expiredAt < :cutoffDate")
    int deleteExpiredReservationsBefore(@Param("cutoffDate") Instant cutoffDate);
}
