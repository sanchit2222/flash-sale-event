package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Inventory entity.
 * Provides data access methods for inventory management with support for pessimistic locking.
 *
 * @author Flash Sale Team
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * Find inventory by SKU ID.
     *
     * @param skuId Product SKU ID
     * @return Optional containing the inventory if found
     */
    Optional<Inventory> findBySkuId(String skuId);

    /**
     * Find inventory by SKU ID with pessimistic write lock.
     * This prevents concurrent modifications during high-traffic scenarios.
     * Use this method when you need to atomically read and update inventory.
     *
     * @param skuId Product SKU ID
     * @return Optional containing the inventory if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.skuId = :skuId")
    Optional<Inventory> findBySkuIdWithLock(@Param("skuId") String skuId);

    /**
     * Find all inventories with available stock (available_count > 0).
     *
     * @return List of inventories with available stock
     */
    @Query("SELECT i FROM Inventory i WHERE i.availableCount > 0")
    List<Inventory> findAllAvailableInventories();

    /**
     * Find all sold out inventories (available_count = 0).
     *
     * @return List of sold out inventories
     */
    @Query("SELECT i FROM Inventory i WHERE i.availableCount = 0")
    List<Inventory> findAllSoldOutInventories();

    /**
     * Check if inventory exists for a SKU.
     *
     * @param skuId Product SKU ID
     * @return true if inventory exists
     */
    boolean existsBySkuId(String skuId);

    /**
     * Atomically increment reserved count.
     * This is a custom update query that leverages optimistic locking.
     *
     * @param skuId Product SKU ID
     * @param quantity Quantity to reserve
     * @return Number of rows updated (1 if successful, 0 if failed due to version mismatch)
     */
    @Modifying
    @Query("UPDATE Inventory i SET " +
           "i.reservedCount = i.reservedCount + :quantity, " +
           "i.availableCount = i.totalCount - (i.reservedCount + :quantity) - i.soldCount, " +
           "i.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE i.skuId = :skuId AND i.availableCount >= :quantity")
    int incrementReservedCount(@Param("skuId") String skuId, @Param("quantity") Integer quantity);

    /**
     * Atomically decrement reserved count (when reservation expires or is cancelled).
     *
     * @param skuId Product SKU ID
     * @param quantity Quantity to release
     * @return Number of rows updated
     */
    @Modifying
    @Query("UPDATE Inventory i SET " +
           "i.reservedCount = i.reservedCount - :quantity, " +
           "i.availableCount = i.totalCount - (i.reservedCount - :quantity) - i.soldCount, " +
           "i.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE i.skuId = :skuId AND i.reservedCount >= :quantity")
    int decrementReservedCount(@Param("skuId") String skuId, @Param("quantity") Integer quantity);

    /**
     * Atomically convert reserved count to sold count (on successful payment).
     *
     * @param skuId Product SKU ID
     * @param quantity Quantity to confirm
     * @return Number of rows updated
     */
    @Modifying
    @Query("UPDATE Inventory i SET " +
           "i.reservedCount = i.reservedCount - :quantity, " +
           "i.soldCount = i.soldCount + :quantity, " +
           "i.availableCount = i.totalCount - (i.reservedCount - :quantity) - (i.soldCount + :quantity), " +
           "i.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE i.skuId = :skuId AND i.reservedCount >= :quantity")
    int confirmReservation(@Param("skuId") String skuId, @Param("quantity") Integer quantity);

    /**
     * Get current available count for a SKU.
     * Lightweight query that only fetches the available count.
     *
     * @param skuId Product SKU ID
     * @return Available count, or null if SKU not found
     */
    @Query("SELECT i.availableCount FROM Inventory i WHERE i.skuId = :skuId")
    Integer getAvailableCount(@Param("skuId") String skuId);

    /**
     * Check if SKU has available inventory.
     *
     * @param skuId Product SKU ID
     * @return true if available_count > 0
     */
    @Query("SELECT CASE WHEN i.availableCount > 0 THEN true ELSE false END " +
           "FROM Inventory i WHERE i.skuId = :skuId")
    Boolean isAvailable(@Param("skuId") String skuId);

    /**
     * Find inventories by SKU IDs (bulk fetch).
     *
     * @param skuIds List of SKU IDs
     * @return List of inventories
     */
    @Query("SELECT i FROM Inventory i WHERE i.skuId IN :skuIds")
    List<Inventory> findBySkuIdIn(@Param("skuIds") List<String> skuIds);
}
