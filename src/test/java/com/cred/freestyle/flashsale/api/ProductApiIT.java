package com.cred.freestyle.flashsale.api;

import com.cred.freestyle.flashsale.domain.model.Inventory;
import com.cred.freestyle.flashsale.domain.model.Product;
import com.cred.freestyle.flashsale.infrastructure.cache.RedisCacheService;
import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import com.cred.freestyle.flashsale.repository.InventoryRepository;
import com.cred.freestyle.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack API integration tests for Product endpoints.
 * Tests product availability, search, and filtering with real database.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:productdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "spring.data.redis.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DisplayName("Product API Integration Tests")
class ProductApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockBean
    private RedisCacheService redisCacheService;

    @MockBean
    private CloudWatchMetricsService cloudWatchMetricsService;

    private Product activeProduct;
    private Product inactiveProduct;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        // Clean up
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        // Create active product
        activeProduct = Product.builder()
                .skuId("SKU-ACTIVE-001")
                .name("Active Flash Sale Product")
                .description("Test active product")
                .category("Electronics")
                .basePrice(new BigDecimal("2999.99"))
                .flashSalePrice(new BigDecimal("1499.99"))
                .flashSaleEventId("EVENT-001")
                .totalInventory(50)
                .isActive(true)
                .build();
        productRepository.save(activeProduct);

        // Create inactive product
        inactiveProduct = Product.builder()
                .skuId("SKU-INACTIVE-001")
                .name("Inactive Product")
                .description("Test inactive product")
                .category("Electronics")
                .basePrice(new BigDecimal("1999.99"))
                .flashSalePrice(new BigDecimal("999.99"))
                .flashSaleEventId("EVENT-001")
                .totalInventory(25)
                .isActive(false)
                .build();
        productRepository.save(inactiveProduct);

        // Create inventory for active product
        inventory = Inventory.builder()
                .skuId("SKU-ACTIVE-001")
                .totalCount(50)
                .availableCount(45)
                .reservedCount(3)
                .soldCount(2)
                .build();
        inventoryRepository.save(inventory);
    }

    // ========================================
    // Product Availability Tests
    // ========================================

    @Test
    @DisplayName("GET /{skuId}/availability - Active product returns availability")
    void getProductAvailability_ActiveProduct_ReturnsAvailability() throws Exception {
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", "SKU-ACTIVE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value("SKU-ACTIVE-001"))
                .andExpect(jsonPath("$.name").value("Active Flash Sale Product"))
                .andExpect(jsonPath("$.availableCount").value(45))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Inactive product returns 404")
    void getProductAvailability_InactiveProduct_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", "SKU-INACTIVE-001"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Non-existent product returns 404")
    void getProductAvailability_NonExistent_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", "SKU-NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{skuId}/availability - Product without inventory returns zero")
    void getProductAvailability_NoInventory_ReturnsZero() throws Exception {
        // Create product without inventory
        Product noInventoryProduct = Product.builder()
                .skuId("SKU-NO-INVENTORY")
                .name("No Inventory Product")
                .category("Electronics")
                .basePrice(new BigDecimal("999.99"))
                .flashSalePrice(new BigDecimal("499.99"))
                .flashSaleEventId("EVENT-001")
                .totalInventory(0)
                .isActive(true)
                .build();
        productRepository.save(noInventoryProduct);

        mockMvc.perform(get("/api/v1/products/{skuId}/availability", "SKU-NO-INVENTORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCount").value(0));
    }

    // ========================================
    // Product Listing Tests
    // ========================================

    @Test
    @DisplayName("GET / - Returns all active products")
    void getAllProducts_ReturnsActiveProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].skuId").value("SKU-ACTIVE-001"))
                .andExpect(jsonPath("$[0].isActive").value(true));
    }

    @Test
    @DisplayName("GET / - Returns empty list when no active products")
    void getAllProducts_NoActiveProducts_ReturnsEmptyList() throws Exception {
        // Deactivate all products
        activeProduct.setIsActive(false);
        productRepository.save(activeProduct);

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ========================================
    // Event-based Product Listing Tests
    // ========================================

    @Test
    @DisplayName("GET /event/{eventId} - Returns products for event")
    void getProductsByEvent_ReturnsEventProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products/event/{eventId}", "EVENT-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[*].flashSaleEventId", everyItem(is("EVENT-001"))));
    }

    @Test
    @DisplayName("GET /event/{eventId} - Returns empty list for non-existent event")
    void getProductsByEvent_NonExistentEvent_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/products/event/{eventId}", "EVENT-NONEXISTENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /event/{eventId} - Includes both active and inactive products")
    void getProductsByEvent_IncludesAllProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products/event/{eventId}", "EVENT-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ========================================
    // Category-based Product Listing Tests
    // ========================================

    @Test
    @DisplayName("GET /category/{category} - Returns products in category")
    void getProductsByCategory_ReturnsFilteredProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products/category/{category}", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[*].category", everyItem(is("Electronics"))));
    }

    @Test
    @DisplayName("GET /category/{category} - Returns empty list for non-existent category")
    void getProductsByCategory_NonExistentCategory_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/products/category/{category}", "NonExistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /category/{category} - Only returns active products")
    void getProductsByCategory_OnlyActiveProducts() throws Exception {
        // Create another active product in same category
        Product activeElectronics = Product.builder()
                .skuId("SKU-ACTIVE-002")
                .name("Another Active Product")
                .category("Electronics")
                .basePrice(new BigDecimal("3999.99"))
                .flashSalePrice(new BigDecimal("1999.99"))
                .flashSaleEventId("EVENT-002")
                .totalInventory(30)
                .isActive(true)
                .build();
        productRepository.save(activeElectronics);

        mockMvc.perform(get("/api/v1/products/category/{category}", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].isActive", everyItem(is(true))));
    }

    // ========================================
    // Multiple Products with Different Categories
    // ========================================

    @Test
    @DisplayName("Multiple categories - Returns correct products per category")
    void multipleCategories_CorrectFiltering() throws Exception {
        // Create products in different categories
        Product fashionProduct = Product.builder()
                .skuId("SKU-FASHION-001")
                .name("Fashion Product")
                .category("Fashion")
                .basePrice(new BigDecimal("4999.99"))
                .flashSalePrice(new BigDecimal("2499.99"))
                .flashSaleEventId("EVENT-001")
                .totalInventory(20)
                .isActive(true)
                .build();
        productRepository.save(fashionProduct);

        Product homeProduct = Product.builder()
                .skuId("SKU-HOME-001")
                .name("Home Product")
                .category("Home")
                .basePrice(new BigDecimal("1499.99"))
                .flashSalePrice(new BigDecimal("749.99"))
                .flashSaleEventId("EVENT-001")
                .totalInventory(15)
                .isActive(true)
                .build();
        productRepository.save(homeProduct);

        // Test Electronics category
        mockMvc.perform(get("/api/v1/products/category/{category}", "Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category").value("Electronics"));

        // Test Fashion category
        mockMvc.perform(get("/api/v1/products/category/{category}", "Fashion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category").value("Fashion"));

        // Test Home category
        mockMvc.perform(get("/api/v1/products/category/{category}", "Home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category").value("Home"));
    }

    // ========================================
    // Price and Discount Tests
    // ========================================

    @Test
    @DisplayName("Product includes correct pricing information")
    void getProduct_IncludesPricingInfo() throws Exception {
        mockMvc.perform(get("/api/v1/products/{skuId}/availability", "SKU-ACTIVE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").exists())
                .andExpect(jsonPath("$.flashSalePrice").exists())
                .andExpect(jsonPath("$.discountPercentage").exists());
    }
}
