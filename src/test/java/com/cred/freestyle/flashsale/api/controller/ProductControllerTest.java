package com.cred.freestyle.flashsale.api.controller;

import com.cred.freestyle.flashsale.api.exception.GlobalExceptionHandler;
import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ProductController using MockMvc.
 */
@WebMvcTest(ProductController.class)
@ContextConfiguration(classes = {ProductController.class, GlobalExceptionHandler.class})
@DisplayName("ProductController Tests")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @MockBean
    private InventoryRepository inventoryRepository;

    @MockBean
    private RedisCacheService cacheService;

    @MockBean
    private CloudWatchMetricsService metricsService;

    // ========================================
    // GET /api/v1/products/{skuId}/availability Tests
    // ========================================

    @Test
    @DisplayName("GET /{skuId}/availability - Existing active product returns 200")
    void getProductAvailability_Exists_Returns200() throws Exception {
        // Given
        String skuId = "SKU-001";
        Product product = Product.builder()
                .skuId(skuId)
                .name("Test Product")
                .flashSalePrice(new BigDecimal("999.99"))
                .isActive(true)
                .build();

        Inventory inventory = Inventory.builder()
                .skuId(skuId)
                .totalCount(100)
                .availableCount(75)
                .reservedCount(20)
                .soldCount(5)
                .build();

        when(productService.findProductBySkuId(skuId)).thenReturn(Optional.of(product));
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.of(75));

        // When / Then
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value(skuId))
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.availableCount").value(75));

        verify(productService).findProductBySkuId(skuId);
        verify(cacheService).getStockCount(skuId);
        verify(metricsService).recordCacheHit("stock");
        verify(metricsService).recordInventoryLevel(skuId, 75);
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Product not found returns 404")
    void getProductAvailability_NotFound_Returns404() throws Exception {
        // Given
        String skuId = "SKU-NONEXISTENT";
        when(productService.findProductBySkuId(skuId)).thenReturn(Optional.empty());

        // When / Then
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", skuId))
                .andExpect(status().isNotFound());

        verify(productService).findProductBySkuId(skuId);
        verify(cacheService, never()).getStockCount(anyString());
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Inactive product returns 404")
    void getProductAvailability_InactiveProduct_Returns404() throws Exception {
        // Given
        String skuId = "SKU-001";
        Product inactiveProduct = Product.builder()
                .skuId(skuId)
                .name("Inactive Product")
                .isActive(false)
                .build();

        when(productService.findProductBySkuId(skuId)).thenReturn(Optional.of(inactiveProduct));

        // When / Then
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", skuId))
                .andExpect(status().isNotFound());

        verify(productService).findProductBySkuId(skuId);
        verify(cacheService, never()).getStockCount(anyString());
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Cache miss fetches from DB")
    void getProductAvailability_CacheMiss_FetchesFromDB() throws Exception {
        // Given
        String skuId = "SKU-001";
        Product product = Product.builder()
                .skuId(skuId)
                .name("Test Product")
                .flashSalePrice(new BigDecimal("999.99"))
                .isActive(true)
                .build();

        Inventory inventory = Inventory.builder()
                .skuId(skuId)
                .availableCount(50)
                .build();

        when(productService.findProductBySkuId(skuId)).thenReturn(Optional.of(product));
        when(cacheService.getStockCount(skuId)).thenReturn(Optional.empty());
        when(inventoryRepository.findBySkuId(skuId)).thenReturn(Optional.of(inventory));

        // When / Then
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCount").value(50));

        verify(metricsService).recordCacheMiss("stock");
        verify(cacheService).setStockCount(skuId, 50);
        verify(inventoryRepository).findBySkuId(skuId);
    }

    // ========================================
    // GET /api/v1/products/event/{eventId} Tests
    // ========================================

    @Test
    @DisplayName("GET /event/{eventId} - Returns products for event")
    void getProductsByEvent_ReturnsProductList() throws Exception {
        // Given
        String eventId = "EVENT-001";
        Product product1 = Product.builder().skuId("SKU-001").name("Product 1").isActive(true).build();
        Product product2 = Product.builder().skuId("SKU-002").name("Product 2").isActive(true).build();
        List<Product> products = Arrays.asList(product1, product2);

        when(productService.findProductsByFlashSaleEventId(eventId)).thenReturn(products);
        when(cacheService.getStockCount("SKU-001")).thenReturn(Optional.of(100));
        when(cacheService.getStockCount("SKU-002")).thenReturn(Optional.of(50));

        // When / Then
        mockMvc.perform(get("/api/v1/products/event/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].skuId").value("SKU-001"))
                .andExpect(jsonPath("$[1].skuId").value("SKU-002"));

        verify(productService).findProductsByFlashSaleEventId(eventId);
    }

    @Test
    @DisplayName("GET /event/{eventId} - Returns empty list when no products")
    void getProductsByEvent_EmptyList_Returns200() throws Exception {
        // Given
        String eventId = "EVENT-EMPTY";
        when(productService.findProductsByFlashSaleEventId(eventId)).thenReturn(Arrays.asList());

        // When / Then
        mockMvc.perform(get("/api/v1/products/event/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ========================================
    // GET /api/v1/products Tests
    // ========================================

    @Test
    @DisplayName("GET / - Returns all active products")
    void getAllActiveProducts_ReturnsProductList() throws Exception {
        // Given
        Product product1 = Product.builder().skuId("SKU-001").name("Product 1").isActive(true).build();
        Product product2 = Product.builder().skuId("SKU-002").name("Product 2").isActive(true).build();
        List<Product> products = Arrays.asList(product1, product2);

        when(productService.findActiveProducts()).thenReturn(products);
        when(cacheService.getStockCount(anyString())).thenReturn(Optional.of(100));

        // When / Then
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].skuId").value("SKU-001"))
                .andExpect(jsonPath("$[1].skuId").value("SKU-002"));

        verify(productService).findActiveProducts();
    }

    // ========================================
    // GET /api/v1/products/category/{category} Tests
    // ========================================

    @Test
    @DisplayName("GET /category/{category} - Returns products for category")
    void getProductsByCategory_ReturnsProductList() throws Exception {
        // Given
        String category = "Electronics";
        Product product1 = Product.builder().skuId("SKU-001").name("Laptop").category(category).build();
        Product product2 = Product.builder().skuId("SKU-002").name("Phone").category(category).build();
        List<Product> products = Arrays.asList(product1, product2);

        when(productService.findProductsByCategory(category)).thenReturn(products);
        when(cacheService.getStockCount(anyString())).thenReturn(Optional.of(50));

        // When / Then
        mockMvc.perform(get("/api/v1/products/category/{category}", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Laptop"))
                .andExpect(jsonPath("$[1].name").value("Phone"));

        verify(productService).findProductsByCategory(category);
    }

    @Test
    @DisplayName("GET /category/{category} - Returns empty list for empty category")
    void getProductsByCategory_EmptyCategory_Returns200() throws Exception {
        // Given
        String category = "NonExistent";
        when(productService.findProductsByCategory(category)).thenReturn(Arrays.asList());

        // When / Then
        mockMvc.perform(get("/api/v1/products/category/{category}", category))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    @DisplayName("GET /{skuId}/availability - Exception triggers error metrics")
    void getProductAvailability_Exception_RecordsError() throws Exception {
        // Given
        String skuId = "SKU-001";
        when(productService.findProductBySkuId(skuId))
                .thenThrow(new RuntimeException("Database error"));

        // When / Then
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", skuId))
                .andExpect(status().is5xxServerError());

        verify(metricsService).recordError("PRODUCT_AVAILABILITY_ERROR", "getProductAvailability");
    }
}
