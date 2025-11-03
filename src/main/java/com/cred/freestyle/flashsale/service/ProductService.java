package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing products in the flash sale system.
 * Handles product retrieval, availability checks, and inventory synchronization.
 * 
 * TODO: There are a lot of methods implemented here which are not necessarily needed. We can clean this up if time permits.
 * @author Flash Sale Team
 */
@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final RedisCacheService cacheService;
    private final CloudWatchMetricsService metricsService;

    public ProductService(
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            RedisCacheService cacheService,
            CloudWatchMetricsService metricsService
    ) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.cacheService = cacheService;
        this.metricsService = metricsService;
    }

    /**
     * Find product by SKU ID with caching.
     *
     * @param skuId Product SKU ID
     * @return Product if found
     */
    public Optional<Product> findProductBySkuId(String skuId) {
        logger.debug("Finding product by SKU: {}", skuId);

        // Check cache first
        Optional<Product> cachedProduct = cacheService.getProduct(skuId, Product.class);
        if (cachedProduct.isPresent()) {
            metricsService.recordCacheHit("product");
            return cachedProduct;
        }

        // Fetch from database
        Optional<Product> product = productRepository.findBySkuId(skuId);
        if (product.isPresent()) {
            // Cache for future requests
            cacheService.cacheProduct(skuId, product.get());
        }

        metricsService.recordCacheMiss("product");
        return product;
    }

    /**
     * Get all active products for a flash sale event.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of active products
     */
    public List<Product> getActiveProductsByEventId(String flashSaleEventId) {
        logger.debug("Getting active products for event: {}", flashSaleEventId);
        return productRepository.findByFlashSaleEventIdAndIsActiveTrue(flashSaleEventId);
    }

    /**
     * Get product availability (including real-time inventory).
     *
     * @param skuId Product SKU ID
     * @return ProductAvailability DTO
     */
    @Transactional(readOnly = true)
    public ProductAvailability getProductAvailability(String skuId) {
        long startTime = System.currentTimeMillis();

        try {
            logger.debug("Getting availability for SKU: {}", skuId);

            // Get product
            Product product = findProductBySkuId(skuId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + skuId));

            // Get inventory
            
            Inventory inventory = inventoryRepository.findBySkuId(skuId)
                    .orElseThrow(() -> new IllegalStateException("Inventory not found for SKU: " + skuId));

            // Record inventory level metric
            metricsService.recordInventoryLevel(skuId, inventory.getAvailableCount());

            // Build availability response
            ProductAvailability availability = new ProductAvailability(
                    product.getSkuId(),
                    product.getName(),
                    product.getFlashSalePrice(),
                    inventory.getAvailableCount(),
                    inventory.isAvailable(),
                    inventory.isSoldOut()
            );

            metricsService.recordDatabaseLatency("getProductAvailability",
                    System.currentTimeMillis() - startTime);

            return availability;

        } catch (Exception e) {
            logger.error("Error getting availability for SKU: {}", skuId, e);
            metricsService.recordError("PRODUCT_AVAILABILITY_ERROR", "getProductAvailability");
            throw e;
        }
    }

    /**
     * Get available stock count for a product (fast check using cache).
     *
     * @param skuId Product SKU ID
     * @return Available count
     */
    public Integer getAvailableStockCount(String skuId) {
        // Check cache first
        Optional<Integer> cachedCount = cacheService.getStockCount(skuId);
        if (cachedCount.isPresent()) {
            metricsService.recordCacheHit("stock");
            return cachedCount.get();
        }

        // Fallback to database
        Integer count = inventoryRepository.getAvailableCount(skuId);
        if (count != null) {
            cacheService.setStockCount(skuId, count);
        }

        metricsService.recordCacheMiss("stock");
        return count != null ? count : 0;
    }

    /**
     * Check if product is available for purchase.
     *
     * @param skuId Product SKU ID
     * @return true if product is available
     */
    public boolean isProductAvailable(String skuId) {
        Integer availableCount = getAvailableStockCount(skuId);
        return availableCount != null && availableCount > 0;
    }

    /**
     * Get all active products.
     *
     * @return List of active products
     */
    public List<Product> getAllActiveProducts() {
        return productRepository.findByIsActiveTrue();
    }

    /**
     * Find products by flash sale event ID.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of products for the event
     */
    public List<Product> findProductsByFlashSaleEventId(String flashSaleEventId) {
        logger.debug("Finding products for flash sale event: {}", flashSaleEventId);
        return productRepository.findByFlashSaleEventIdAndIsActiveTrue(flashSaleEventId);
    }

    /**
     * Find all active products.
     *
     * @return List of active products
     */
    public List<Product> findActiveProducts() {
        logger.debug("Finding all active products");
        return productRepository.findByIsActiveTrue();
    }

    /**
     * Find products by category.
     *
     * @param category Product category
     * @return List of products in the category
     */
    public List<Product> findProductsByCategory(String category) {
        logger.debug("Finding products by category: {}", category);
        return productRepository.findByCategory(category);
    }

    /**
     * Inner class for product availability response.
     */
    public static class ProductAvailability {
        private final String skuId;
        private final String name;
        private final java.math.BigDecimal price;
        private final Integer availableCount;
        private final boolean available;
        private final boolean soldOut;

        public ProductAvailability(
                String skuId,
                String name,
                java.math.BigDecimal price,
                Integer availableCount,
                boolean available,
                boolean soldOut
        ) {
            this.skuId = skuId;
            this.name = name;
            this.price = price;
            this.availableCount = availableCount;
            this.available = available;
            this.soldOut = soldOut;
        }

        // Getters
        public String getSkuId() { return skuId; }
        public String getName() { return name; }
        public java.math.BigDecimal getPrice() { return price; }
        public Integer getAvailableCount() { return availableCount; }
        public boolean isAvailable() { return available; }
        public boolean isSoldOut() { return soldOut; }
    }
}
