package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.dto.ProductAvailabilityResponse;
import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST controller for product operations.
 * Handles product listing and availability checking for flash sales.
 *
 * @author Flash Sale Team
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;
    private final InventoryRepository inventoryRepository;
    private final RedisCacheService cacheService;
    private final CloudWatchMetricsService metricsService;

    public ProductController(
            ProductService productService,
            InventoryRepository inventoryRepository,
            RedisCacheService cacheService,
            CloudWatchMetricsService metricsService
    ) {
        this.productService = productService;
        this.inventoryRepository = inventoryRepository;
        this.cacheService = cacheService;
        this.metricsService = metricsService;
    }

    /**
     * Get product availability by SKU ID.
     * Returns product details including real-time stock availability.
     * This is a high-traffic endpoint during flash sales.
     *
     * @param skuId Product SKU ID
     * @return Product availability information
     */
    @GetMapping("/{skuId}/availability")
    public ResponseEntity<ProductAvailabilityResponse> getProductAvailability(
            @PathVariable String skuId
    ) {
        long startTime = System.currentTimeMillis();

        try {
            logger.debug("Checking product availability for SKU: {}", skuId);

            // Fetch product from service (cache-first)
            Optional<Product> productOpt = productService.findProductBySkuId(skuId);
            if (productOpt.isEmpty()) {
                logger.warn("Product not found: {}", skuId);
                return ResponseEntity.notFound().build();
            }

            Product product = productOpt.get();

            // Check if product is active
            if (!product.getIsActive()) {
                logger.warn("Product is not active: {}", skuId);
                return ResponseEntity.notFound().build();
            }

            // Get available count from cache first, fallback to database
            Integer availableCount = getAvailableCount(skuId);

            // Record inventory level metric
            if (availableCount != null) {
                metricsService.recordInventoryLevel(skuId, availableCount);
            }

            // Build response
            ProductAvailabilityResponse response = ProductAvailabilityResponse.fromEntity(product, availableCount);

            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Product availability check completed for SKU: {} in {}ms", skuId, duration);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error checking product availability for SKU: {}", skuId, e);
            metricsService.recordError("PRODUCT_AVAILABILITY_ERROR", "getProductAvailability");
            throw e;
        }
    }

    /**
     * Get all active products for a flash sale event.
     *
     * @param flashSaleEventId Flash sale event ID
     * @return List of products in the event
     */
    @GetMapping("/event/{flashSaleEventId}")
    public ResponseEntity<List<ProductAvailabilityResponse>> getProductsByEvent(
            @PathVariable String flashSaleEventId
    ) {
        logger.debug("Fetching products for flash sale event: {}", flashSaleEventId);

        List<Product> products = productService.findProductsByFlashSaleEventId(flashSaleEventId);

        List<ProductAvailabilityResponse> responses = products.stream()
                .map(product -> {
                    Integer availableCount = getAvailableCount(product.getSkuId());
                    return ProductAvailabilityResponse.fromEntity(product, availableCount);
                })
                .collect(Collectors.toList());

        logger.debug("Found {} products for event: {}", responses.size(), flashSaleEventId);
        return ResponseEntity.ok(responses);
    }

    /**
     * Get all active products (admin/monitoring endpoint).
     *
     * @return List of all active products
     */
    @GetMapping
    public ResponseEntity<List<ProductAvailabilityResponse>> getAllActiveProducts() {
        logger.debug("Fetching all active products");

        List<Product> products = productService.findActiveProducts();

        List<ProductAvailabilityResponse> responses = products.stream()
                .map(product -> {
                    Integer availableCount = getAvailableCount(product.getSkuId());
                    return ProductAvailabilityResponse.fromEntity(product, availableCount);
                })
                .collect(Collectors.toList());

        logger.debug("Found {} active products", responses.size());
        return ResponseEntity.ok(responses);
    }

    /**
     * Get available products by category.
     *
     * @param category Product category
     * @return List of products in the category
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<ProductAvailabilityResponse>> getProductsByCategory(
            @PathVariable String category
    ) {
        logger.debug("Fetching products for category: {}", category);

        List<Product> products = productService.findProductsByCategory(category);

        List<ProductAvailabilityResponse> responses = products.stream()
                .map(product -> {
                    Integer availableCount = getAvailableCount(product.getSkuId());
                    return ProductAvailabilityResponse.fromEntity(product, availableCount);
                })
                .collect(Collectors.toList());

        logger.debug("Found {} products for category: {}", responses.size(), category);
        return ResponseEntity.ok(responses);
    }

    /**
     * Get available count for a product.
     * Uses cache-first strategy with database fallback.
     *
     * @param skuId Product SKU ID
     * @return Available count
     */
    private Integer getAvailableCount(String skuId) {
        // Try cache first
        Optional<Integer> cachedStock = cacheService.getStockCount(skuId);
        if (cachedStock.isPresent()) {
            metricsService.recordCacheHit("stock");
            return cachedStock.get();
        }

        // Cache miss - fetch from database
        metricsService.recordCacheMiss("stock");
        Optional<Inventory> inventoryOpt = inventoryRepository.findBySkuId(skuId);
        if (inventoryOpt.isPresent()) {
            Integer availableCount = inventoryOpt.get().getAvailableCount();
            // Update cache
            cacheService.setStockCount(skuId, availableCount);
            return availableCount;
        }

        logger.warn("Inventory not found for SKU: {}", skuId);
        return 0;
    }
}
