package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.Order;
import com.cred.freestyle.flashsale.domain.model.Order.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Order entity.
 * Provides data access methods for order management.
 *
 * @author Flash Sale Team
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * Find order by reservation ID.
     * One-to-one relationship: each order is created from exactly one reservation.
     *
     * @param reservationId Reservation ID
     * @return Optional containing the order if found
     */
    Optional<Order> findByReservationId(String reservationId);

    /**
     * Find all orders for a user.
     *
     * @param userId User ID
     * @return List of orders
     */
    List<Order> findByUserId(String userId);

    /**
     * Find all orders for a user with specific status.
     *
     * @param userId User ID
     * @param status Order status
     * @return List of orders
     */
    List<Order> findByUserIdAndStatus(String userId, OrderStatus status);

    /**
     * Find all orders for a specific product (SKU).
     *
     * @param skuId Product SKU ID
     * @return List of orders
     */
    List<Order> findBySkuId(String skuId);

    /**
     * Find all orders for a specific product with status.
     *
     * @param skuId Product SKU ID
     * @param status Order status
     * @return List of orders
     */
    List<Order> findBySkuIdAndStatus(String skuId, OrderStatus status);

    /**
     * Find order by payment transaction ID.
     * Used for payment reconciliation.
     *
     * @param transactionId Payment transaction ID
     * @return Optional containing the order if found
     */
    Optional<Order> findByPaymentTransactionId(String transactionId);

    /**
     * Find orders by status.
     *
     * @param status Order status
     * @return List of orders
     */
    List<Order> findByStatus(OrderStatus status);

    /**
     * Find pending orders (payment pending).
     * Used to identify orders that need payment processing.
     *
     * @return List of pending orders
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'PAYMENT_PENDING'")
    List<Order> findPendingOrders();

    /**
     * Find confirmed orders for a user and product.
     * Used to verify user has purchased a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return List of confirmed orders
     */
    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.skuId = :skuId " +
           "AND o.status = 'CONFIRMED'")
    List<Order> findConfirmedOrders(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Check if user has confirmed order for a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return true if user has confirmed order
     */
    @Query("SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END FROM Order o " +
           "WHERE o.userId = :userId AND o.skuId = :skuId AND o.status = 'CONFIRMED'")
    boolean hasConfirmedOrder(
            @Param("userId") String userId,
            @Param("skuId") String skuId
    );

    /**
     * Count confirmed orders for a product.
     * Used for sales analytics.
     *
     * @param skuId Product SKU ID
     * @return Number of confirmed orders
     */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.skuId = :skuId AND o.status = 'CONFIRMED'")
    long countConfirmedOrdersBySkuId(@Param("skuId") String skuId);

    /**
     * Count orders by status for a product.
     *
     * @param skuId Product SKU ID
     * @param status Order status
     * @return Number of orders
     */
    long countBySkuIdAndStatus(String skuId, OrderStatus status);

    /**
     * Find orders created within a time range.
     * Used for reporting and analytics.
     *
     * @param startTime Start time
     * @param endTime End time
     * @return List of orders
     */
    @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startTime AND :endTime")
    List<Order> findByCreatedAtBetween(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Find fulfilled orders for a product.
     *
     * @param skuId Product SKU ID
     * @return List of fulfilled orders
     */
    @Query("SELECT o FROM Order o WHERE o.skuId = :skuId AND o.status = 'FULFILLED'")
    List<Order> findFulfilledOrdersBySkuId(@Param("skuId") String skuId);

    /**
     * Find orders by user ID and time range.
     * Used for user order history.
     *
     * @param userId User ID
     * @param startTime Start time
     * @param endTime End time
     * @return List of orders
     */
    @Query("SELECT o FROM Order o WHERE o.userId = :userId " +
           "AND o.createdAt BETWEEN :startTime AND :endTime " +
           "ORDER BY o.createdAt DESC")
    List<Order> findUserOrderHistory(
            @Param("userId") String userId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Find failed payment orders that need retry.
     *
     * @return List of payment failed orders
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'PAYMENT_FAILED'")
    List<Order> findPaymentFailedOrders();

    /**
     * Check if order exists for reservation.
     *
     * @param reservationId Reservation ID
     * @return true if order exists
     */
    boolean existsByReservationId(String reservationId);
}
