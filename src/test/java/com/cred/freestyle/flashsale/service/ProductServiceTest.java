package com.cred.freestyle.flashsale.service;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ProductRepository;
import com.cred.freestyle.flashsale.service.ProductService.ProductAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProductService.
 * Tests product retrieval, caching, and availability checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Unit Tests")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private CloudWatchMetricsService metricsService;

    @InjectMocks
    private ProductService productService;

    private String skuId;
    private Product product;

    @BeforeEach
    void setUp() {
        skuId = "SKU-001";
        product = Product.builder()
                .skuId(skuId)
                .name("Flash Sale Product")
                .flashSalePrice(new BigDecimal("999.99"))
                .category("Electronics")
                .isActive(true)
                .build();
    }

    // ========================================
    // findProductBySkuId() Tests
    // ========================================

    @Test
    @DisplayName("findProductBySkuId - CacheHit: Should return product from cache")
    void findProductBySkuId_CacheHit() {
        // Given
        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.of(product));

        // When
        Optional<Product> result = productService.findProductBySkuId(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSkuId()).isEqualTo(skuId);

        verify(cacheService).getProduct(skuId, Product.class);
        verify(metricsService).recordCacheHit("product");
        verify(productRepository, never()).findBySkuId(anyString());
    }

    @Test
    @DisplayName("findProductBySkuId - CacheMiss: Should fetch from DB and cache result")
    void findProductBySkuId_CacheMiss() {
        // Given
        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.empty());
        when(productRepository.findBySkuId(skuId)).thenReturn(Optional.of(product));

        // When
        Optional<Product> result = productService.findProductBySkuId(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSkuId()).isEqualTo(skuId);

        verify(productRepository).findBySkuId(skuId);
        verify(cacheService).cacheProduct(skuId, product);
        verify(metricsService).recordCacheMiss("product");
    }

    @Test
    @DisplayName("findProductBySkuId - NotFound: Should return empty when product not found")
    void findProductBySkuId_NotFound() {
        // Given
        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.empty());
        when(productRepository.findBySkuId(skuId)).thenReturn(Optional.empty());

        // When
        Optional<Product> result = productService.findProductBySkuId(skuId);

        // Then
        assertThat(result).isEmpty();
        verify(cacheService, never()).cacheProduct(anyString(), any());
    }

    // ========================================
    // getProductAvailability() Tests
    // ========================================

    @Test
    @DisplayName("getProductAvailability - Success: Should return availability with inventory data")
    void getProductAvailability_Success() {
        // Given
        Inventory inventory = Inventory.builder()
                .skuId(skuId)
                .totalCount(100)
                .availableCount(75)
                .reservedCount(20)
                .soldCount(5)
                .build();

        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.of(product));
        when(inventoryRepository.findBySkuId(skuId)).thenReturn(Optional.of(inventory));

        // When
        ProductAvailability result = productService.getProductAvailability(skuId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getSkuId()).isEqualTo(skuId);
        assertThat(result.getName()).isEqualTo("Flash Sale Product");
        assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("999.99"));
        assertThat(result.getAvailableCount()).isEqualTo(75);
        assertThat(result.isAvailable()).isTrue();
        assertThat(result.isSoldOut()).isFalse();

        verify(metricsService).recordInventoryLevel(skuId, 75);
        verify(metricsService).recordDatabaseLatency(eq("getProductAvailability"), anyLong());
    }

    @Test
    @DisplayName("getProductAvailability - ProductNotFound: Should throw exception")
    void getProductAvailability_ProductNotFound() {
        // Given
        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.empty());
        when(productRepository.findBySkuId(skuId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> productService.getProductAvailability(skuId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    @DisplayName("getProductAvailability - InventoryNotFound: Should throw exception")
    void getProductAvailability_InventoryNotFound() {
        // Given
        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.of(product));
        when(inventoryRepository.findBySkuId(skuId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> productService.getProductAvailability(skuId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inventory not found");

        verify(metricsService).recordError("PRODUCT_AVAILABILITY_ERROR", "getProductAvailability");
    }

    @Test
    @DisplayName("getProductAvailability - SoldOut: Should indicate sold out status")
    void getProductAvailability_SoldOut() {
        // Given
        Inventory inventory = Inventory.builder()
                .skuId(skuId)
                .totalCount(100)
                .availableCount(0)
                .reservedCount(30)
                .soldCount(70)
                .build();

        when(cacheService.getProduct(skuId, Product.class)).thenReturn(Optional.of(product));
        when(inventoryRepository.findBySkuId(skuId)).thenReturn(Optional.of(inventory));

        // When
        ProductAvailability result = productService.getProductAvailability(skuId);

        // Then
        assertThat(result.getAvailableCount()).isEqualTo(0);
        assertThat(result.isAvailable()).isFalse();
        assertThat(result.isSoldOut()).isTrue();
    }

    // ========================================
    // getAvailableStockCount() Tests
    // ========================================

    @Test
    @DisplayName("getAvailableStockCount - CacheHit: Should return cached stock count")
    void getAvailableStockCount_CacheHit() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.of(50));

        // When
        Integer result = productService.getAvailableStockCount(skuId);

        // Then
        assertThat(result).isEqualTo(50);
        verify(metricsService).recordCacheHit("stock");
        verify(inventoryRepository, never()).getAvailableCount(anyString());
    }

    @Test
    @DisplayName("getAvailableStockCount - CacheMiss: Should fetch from DB and cache")
    void getAvailableStockCount_CacheMiss() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.empty());
        when(inventoryRepository.getAvailableCount(skuId)).thenReturn(75);

        // When
        Integer result = productService.getAvailableStockCount(skuId);

        // Then
        assertThat(result).isEqualTo(75);
        verify(inventoryRepository).getAvailableCount(skuId);
        verify(cacheService).setStockCount(skuId, 75);
        verify(metricsService).recordCacheMiss("stock");
    }

    @Test
    @DisplayName("getAvailableStockCount - NotFoundInDb: Should return 0 when null in DB")
    void getAvailableStockCount_NotFoundInDb() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.empty());
        when(inventoryRepository.getAvailableCount(skuId)).thenReturn(null);

        // When
        Integer result = productService.getAvailableStockCount(skuId);

        // Then
        assertThat(result).isEqualTo(0);
        verify(cacheService, never()).setStockCount(anyString(), anyInt());
    }

    // ========================================
    // isProductAvailable() Tests
    // ========================================

    @Test
    @DisplayName("isProductAvailable - Available: Should return true when stock available")
    void isProductAvailable_Available() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.of(10));

        // When
        boolean result = productService.isProductAvailable(skuId);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isProductAvailable - NotAvailable: Should return false when no stock")
    void isProductAvailable_NotAvailable() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.of(0));

        // When
        boolean result = productService.isProductAvailable(skuId);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isProductAvailable - NullStock: Should return false when stock is null")
    void isProductAvailable_NullStock() {
        // Given
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.empty());
        when(inventoryRepository.getAvailableCount(skuId)).thenReturn(null);

        // When
        boolean result = productService.isProductAvailable(skuId);

        // Then
        assertThat(result).isFalse();
    }

    // ========================================
    // getAllActiveProducts() Tests
    // ========================================

    @Test
    @DisplayName("getAllActiveProducts - ShouldReturnActiveProducts")
    void getAllActiveProducts_ShouldReturnActiveProducts() {
        // Given
        Product product1 = Product.builder().skuId("SKU-001").isActive(true).build();
        Product product2 = Product.builder().skuId("SKU-002").isActive(true).build();
        List<Product> activeProducts = Arrays.asList(product1, product2);

        when(productRepository.findByIsActiveTrue()).thenReturn(activeProducts);

        // When
        List<Product> result = productService.getAllActiveProducts();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(product1, product2);
    }

    // ========================================
    // getActiveProductsByEventId() Tests
    // ========================================

    @Test
    @DisplayName("getActiveProductsByEventId - ShouldReturnProductsForEvent")
    void getActiveProductsByEventId_ShouldReturnProductsForEvent() {
        // Given
        String eventId = "EVENT-001";
        Product product1 = Product.builder().skuId("SKU-001").flashSaleEventId(eventId).build();
        Product product2 = Product.builder().skuId("SKU-002").flashSaleEventId(eventId).build();
        List<Product> eventProducts = Arrays.asList(product1, product2);

        when(productRepository.findByFlashSaleEventIdAndIsActiveTrue(eventId))
                .thenReturn(eventProducts);

        // When
        List<Product> result = productService.getActiveProductsByEventId(eventId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(product1, product2);
    }

    // ========================================
    // findProductsByCategory() Tests
    // ========================================

    @Test
    @DisplayName("findProductsByCategory - ShouldReturnProductsInCategory")
    void findProductsByCategory_ShouldReturnProductsInCategory() {
        // Given
        String category = "Electronics";
        Product product1 = Product.builder().skuId("SKU-001").category(category).build();
        Product product2 = Product.builder().skuId("SKU-002").category(category).build();
        List<Product> categoryProducts = Arrays.asList(product1, product2);

        when(productRepository.findByCategory(category)).thenReturn(categoryProducts);

        // When
        List<Product> result = productService.findProductsByCategory(category);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(product1, product2);
    }

    // ========================================
    // findActiveProducts() Tests
    // ========================================

    @Test
    @DisplayName("findActiveProducts - ShouldReturnAllActiveProducts")
    void findActiveProducts_ShouldReturnAllActiveProducts() {
        // Given
        List<Product> activeProducts = Arrays.asList(
                Product.builder().skuId("SKU-001").isActive(true).build(),
                Product.builder().skuId("SKU-002").isActive(true).build()
        );

        when(productRepository.findByIsActiveTrue()).thenReturn(activeProducts);

        // When
        List<Product> result = productService.findActiveProducts();

        // Then
        assertThat(result).hasSize(2);
        verify(productRepository).findByIsActiveTrue();
    }

    // ========================================
    // findProductsByFlashSaleEventId() Tests
    // ========================================

    @Test
    @DisplayName("findProductsByFlashSaleEventId - ShouldReturnEventProducts")
    void findProductsByFlashSaleEventId_ShouldReturnEventProducts() {
        // Given
        String eventId = "EVENT-123";
        List<Product> eventProducts = Arrays.asList(
                Product.builder().skuId("SKU-001").flashSaleEventId(eventId).build()
        );

        when(productRepository.findByFlashSaleEventIdAndIsActiveTrue(eventId))
                .thenReturn(eventProducts);

        // When
        List<Product> result = productService.findProductsByFlashSaleEventId(eventId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFlashSaleEventId()).isEqualTo(eventId);
    }
}
