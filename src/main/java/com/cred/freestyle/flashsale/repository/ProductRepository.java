package com.cred.freestyle.flashsale.repository;

import com.cred.freestyle.flashsale.domain.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Product entity.
 * Provides data access methods for flash sale products.
 *
 * @author Flash Sale Team
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, String> {

    /**
     * Find all active products.
     *
     * @return List of active products
     */
    List<Product> findByIsActiveTrue();

    /**
     * Find all products for a specific flash sale event.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of products in the event
     */
    List<Product> findByFlashSaleEventId(String flashSaleEventId);

    /**
     * Find all active products for a specific flash sale event.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of active products in the event
     */
    List<Product> findByFlashSaleEventIdAndIsActiveTrue(String flashSaleEventId);

    /**
     * Find products by category.
     *
     * @param category Product category
     * @return List of products in the category
     */
    List<Product> findByCategory(String category);

    /**
     * Find active products by category.
     *
     * @param category Product category
     * @return List of active products in the category
     */
    List<Product> findByCategoryAndIsActiveTrue(String category);

    /**
     * Find product by SKU ID (same as findById, but more explicit).
     *
     * @param skuId Product SKU ID
     * @return Optional containing the product if found
     */
    Optional<Product> findBySkuId(String skuId);

    /**
     * Check if a product exists and is active.
     *
     * @param skuId Product SKU ID
     * @return true if product exists and is active
     */
    boolean existsBySkuIdAndIsActiveTrue(String skuId);

    /**
     * Find products with inventory greater than a threshold.
     * Used for finding products still available for sale.
     *
     * @param minInventory Minimum inventory threshold
     * @return List of products with inventory above threshold
     */
    @Query("SELECT p FROM Product p WHERE p.totalInventory >= :minInventory AND p.isActive = true")
    List<Product> findAvailableProducts(@Param("minInventory") Integer minInventory);

    /**
     * Count active products in a flash sale event.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return Number of active products
     */
    long countByFlashSaleEventIdAndIsActiveTrue(String flashSaleEventId);

    /**
     * Find products by flash sale event ID and category.
     *
     * @param flashSaleEventId Flash sale event ID
     * @param category Product category
     * @return List of products matching criteria
     */
    List<Product> findByFlashSaleEventIdAndCategoryAndIsActiveTrue(
            String flashSaleEventId,
            String category
    );
}
