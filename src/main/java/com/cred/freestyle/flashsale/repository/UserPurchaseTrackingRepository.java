package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.UserPurchaseTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for UserPurchaseTracking entity.
 * Provides data access methods for tracking user purchases and enforcing purchase limits.
 *
 * @author Flash Sale Team
 */
@Repository
public interface UserPurchaseTrackingRepository extends JpaRepository<UserPurchaseTracking, String> {

    /**
     * Find purchase tracking record by user and SKU.
     * Used to check if user has already purchased a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return Optional containing the tracking record if found
     */
    Optional<UserPurchaseTracking> findByUserIdAndSkuId(String userId, String skuId);

    /**
     * Find all purchases by a user.
     *
     * @param userId User ID
     * @return List of purchase tracking records
     */
    List<UserPurchaseTracking> findByUserId(String userId);

    /**
     * Find all purchases for a product.
     *
     * @param skuId Product SKU ID
     * @return List of purchase tracking records
     */
    List<UserPurchaseTracking> findBySkuId(String skuId);

    /**
     * Check if user has purchased a specific product.
     * Returns true if user has purchased at least 1 unit.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return true if user has purchased the product
     */
    boolean existsByUserIdAndSkuId(String userId, String skuId);

    /**
     * Get total quantity purchased by user for a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return Total quantity purchased, or null if no purchase found
     */
    @Query("SELECT upt.quantityPurchased FROM UserPurchaseTracking upt " +
           "WHERE upt.userId = :userId AND upt.skuId = :skuId")
    Integer getQuantityPurchased(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Find all purchases for a flash sale event.
     * Used for analytics and reporting.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of purchase tracking records
     */
    List<UserPurchaseTracking> findByFlashSaleEventId(String flashSaleEventId);

    /**
     * Count total purchases for a product.
     * Used for sales analytics.
     *
     * @param skuId Product SKU ID
     * @return Number of purchases
     */
    long countBySkuId(String skuId);

    /**
     * Count total purchases by a user across all products.
     *
     * @param userId User ID
     * @return Number of purchases
     */
    long countByUserId(String userId);

    /**
     * Find purchase by order ID.
     *
     * @param orderId Order ID
     * @return Optional containing the tracking record if found
     */
    Optional<UserPurchaseTracking> findByOrderId(String orderId);

    /**
     * Find purchase by reservation ID.
     *
     * @param reservationId Reservation ID
     * @return Optional containing the tracking record if found
     */
    Optional<UserPurchaseTracking> findByReservationId(String reservationId);

    /**
     * Check if user has reached purchase limit for a product.
     * For flash sales, limit is 1 unit per product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param limit Purchase limit (typically 1)
     * @return true if user has reached or exceeded limit
     */
    @Query("SELECT CASE WHEN upt.quantityPurchased >= :limit THEN true ELSE false END " +
           "FROM UserPurchaseTracking upt " +
           "WHERE upt.userId = :userId AND upt.skuId = :skuId")
    Boolean hasReachedLimit(
            @Param("userId") String userId,
            @Param("skuId") String skuId,
            @Param("limit") Integer limit
    );

    /**
     * Find all user IDs who purchased a specific product.
     * Used for marketing and analytics.
     *
     * @param skuId Product SKU ID
     * @return List of user IDs
     */
    @Query("SELECT upt.userId FROM UserPurchaseTracking upt WHERE upt.skuId = :skuId")
    List<String> findUserIdsBySkuId(@Param("skuId") String skuId);

    /**
     * Find all SKU IDs purchased by a user.
     *
     * @param userId User ID
     * @return List of SKU IDs
     */
    @Query("SELECT upt.skuId FROM UserPurchaseTracking upt WHERE upt.userId = :userId")
    List<String> findSkuIdsByUserId(@Param("userId") String userId);
}
