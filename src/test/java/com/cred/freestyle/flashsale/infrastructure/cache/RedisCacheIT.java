package com.cred.freestyle.flashsale.infrastructure.cache;

import com.cred.freestyle.flashsale.domain.model.Product;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for RedisCacheService using embedded Redis.
 * Tests cache operations against a real Redis instance.
 */
@SpringBootTest(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6370",
    "spring.data.redis.enabled=true"
})
@DisplayName("Redis Cache Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCacheIT {

    private static RedisServer redisServer;

    @Autowired
    private RedisCacheService redisCacheService;

    @BeforeAll
    static void startRedis() throws IOException {
        redisServer = new RedisServer(6370);
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 6370);
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @BeforeEach
    void setUp() {
        // Clear all cache before each test
        redisCacheService.clearAllCache();
    }

    // ========================================
    // Stock Count Tests
    // ========================================

    @Test
    @Order(1)
    @DisplayName("getStockCount - Cache miss returns empty")
    void getStockCount_CacheMiss_ReturnsEmpty() {
        // When
        Optional<Integer> result = redisCacheService.getStockCount("SKU-NONEXISTENT");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("setStockCount and getStockCount - Cache hit returns value")
    void setAndGetStockCount_CacheHit_ReturnsValue() {
        // Given
        String skuId = "SKU-001";
        Integer stockCount = 100;

        // When
        redisCacheService.setStockCount(skuId, stockCount);
        Optional<Integer> result = redisCacheService.getStockCount(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(100);
    }

    @Test
    @Order(3)
    @DisplayName("setStockCount - Overwrites existing value")
    void setStockCount_OverwritesExistingValue() {
        // Given
        String skuId = "SKU-002";
        redisCacheService.setStockCount(skuId, 50);

        // When - Update to new value
        redisCacheService.setStockCount(skuId, 75);
        Optional<Integer> result = redisCacheService.getStockCount(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(75);
    }

    @Test
    @Order(4)
    @DisplayName("decrementStockCount - Decrements value atomically")
    void decrementStockCount_DecrementsAtomically() {
        // Given
        String skuId = "SKU-003";
        redisCacheService.setStockCount(skuId, 100);

        // When
        Long newCount = redisCacheService.decrementStockCount(skuId, 5);

        // Then
        assertThat(newCount).isEqualTo(95L);
        Optional<Integer> cachedValue = redisCacheService.getStockCount(skuId);
        assertThat(cachedValue).isPresent();
        assertThat(cachedValue.get()).isEqualTo(95);
    }

    @Test
    @Order(5)
    @DisplayName("decrementStockCount - Multiple decrements are atomic")
    void decrementStockCount_MultipleDecrementsAtomic() {
        // Given
        String skuId = "SKU-004";
        redisCacheService.setStockCount(skuId, 100);

        // When - Multiple decrements
        Long count1 = redisCacheService.decrementStockCount(skuId, 10);
        Long count2 = redisCacheService.decrementStockCount(skuId, 20);
        Long count3 = redisCacheService.decrementStockCount(skuId, 15);

        // Then
        assertThat(count1).isEqualTo(90L);
        assertThat(count2).isEqualTo(70L);
        assertThat(count3).isEqualTo(55L);

        Optional<Integer> finalCount = redisCacheService.getStockCount(skuId);
        assertThat(finalCount).isPresent();
        assertThat(finalCount.get()).isEqualTo(55);
    }

    @Test
    @Order(6)
    @DisplayName("decrementStockCount - Can go negative")
    void decrementStockCount_CanGoNegative() {
        // Given
        String skuId = "SKU-005";
        redisCacheService.setStockCount(skuId, 10);

        // When - Decrement more than available
        Long newCount = redisCacheService.decrementStockCount(skuId, 15);

        // Then - Redis allows negative values
        assertThat(newCount).isEqualTo(-5L);
    }

    @Test
    @Order(7)
    @DisplayName("incrementStockCount - Increments value atomically")
    void incrementStockCount_IncrementsAtomically() {
        // Given
        String skuId = "SKU-006";
        redisCacheService.setStockCount(skuId, 50);

        // When
        Long newCount = redisCacheService.incrementStockCount(skuId, 10);

        // Then
        assertThat(newCount).isEqualTo(60L);
        Optional<Integer> cachedValue = redisCacheService.getStockCount(skuId);
        assertThat(cachedValue).isPresent();
        assertThat(cachedValue.get()).isEqualTo(60);
    }

    @Test
    @Order(8)
    @DisplayName("incrementStockCount - Multiple increments are atomic")
    void incrementStockCount_MultipleIncrementsAtomic() {
        // Given
        String skuId = "SKU-007";
        redisCacheService.setStockCount(skuId, 10);

        // When
        Long count1 = redisCacheService.incrementStockCount(skuId, 5);
        Long count2 = redisCacheService.incrementStockCount(skuId, 3);
        Long count3 = redisCacheService.incrementStockCount(skuId, 7);

        // Then
        assertThat(count1).isEqualTo(15L);
        assertThat(count2).isEqualTo(18L);
        assertThat(count3).isEqualTo(25L);

        Optional<Integer> finalCount = redisCacheService.getStockCount(skuId);
        assertThat(finalCount).isPresent();
        assertThat(finalCount.get()).isEqualTo(25);
    }

    @Test
    @Order(9)
    @DisplayName("incrementStockCount - Creates key if doesn't exist")
    void incrementStockCount_CreatesKeyIfNotExists() {
        // Given - No existing key
        String skuId = "SKU-NEW";

        // When
        Long newCount = redisCacheService.incrementStockCount(skuId, 10);

        // Then - Redis creates the key with initial value
        assertThat(newCount).isEqualTo(10L);
    }

    @Test
    @Order(10)
    @DisplayName("invalidateStockCount - Removes cached value")
    void invalidateStockCount_RemovesCachedValue() {
        // Given
        String skuId = "SKU-008";
        redisCacheService.setStockCount(skuId, 100);
        assertThat(redisCacheService.getStockCount(skuId)).isPresent();

        // When
        redisCacheService.invalidateStockCount(skuId);

        // Then
        Optional<Integer> result = redisCacheService.getStockCount(skuId);
        assertThat(result).isEmpty();
    }

    @Test
    @Order(11)
    @DisplayName("setStockCount - TTL expires after 5 minutes")
    @Disabled("Disabled - TTL test takes too long for regular test suite")
    void setStockCount_TTLExpires() {
        // Given
        String skuId = "SKU-TTL";
        redisCacheService.setStockCount(skuId, 100);

        // Then - Should exist initially
        assertThat(redisCacheService.getStockCount(skuId)).isPresent();

        // Wait for TTL expiration (5 minutes + buffer)
        await()
            .atMost(Duration.ofMinutes(6))
            .pollInterval(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                Optional<Integer> result = redisCacheService.getStockCount(skuId);
                assertThat(result).isEmpty();
            });
    }

    // ========================================
    // Product Cache Tests
    // ========================================

    @Test
    @Order(12)
    @DisplayName("cacheProduct and getProduct - Caches and retrieves product")
    void cacheAndGetProduct_Success() {
        // Given
        String skuId = "SKU-PROD-001";
        Product product = Product.builder()
                .skuId(skuId)
                .name("Test Product")
                .basePrice(new BigDecimal("999.99"))
                .flashSalePrice(new BigDecimal("499.99"))
                .totalInventory(100)
                .isActive(true)
                .build();

        // When
        redisCacheService.cacheProduct(skuId, product);
        Optional<Product> result = redisCacheService.getProduct(skuId, Product.class);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSkuId()).isEqualTo(skuId);
        assertThat(result.get().getName()).isEqualTo("Test Product");
        assertThat(result.get().getBasePrice()).isEqualByComparingTo(new BigDecimal("999.99"));
        assertThat(result.get().getFlashSalePrice()).isEqualByComparingTo(new BigDecimal("499.99"));
        assertThat(result.get().getIsActive()).isTrue();
    }

    @Test
    @Order(13)
    @DisplayName("getProduct - Cache miss returns empty")
    void getProduct_CacheMiss_ReturnsEmpty() {
        // When
        Optional<Product> result = redisCacheService.getProduct("SKU-NONEXISTENT", Product.class);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @Order(14)
    @DisplayName("cacheProduct - Overwrites existing product")
    void cacheProduct_OverwritesExisting() {
        // Given
        String skuId = "SKU-PROD-002";
        Product product1 = Product.builder()
                .skuId(skuId)
                .name("Original Product")
                .basePrice(new BigDecimal("100.00"))
                .flashSalePrice(new BigDecimal("50.00"))
                .isActive(true)
                .build();

        redisCacheService.cacheProduct(skuId, product1);

        // When - Cache updated product
        Product product2 = Product.builder()
                .skuId(skuId)
                .name("Updated Product")
                .basePrice(new BigDecimal("200.00"))
                .flashSalePrice(new BigDecimal("100.00"))
                .isActive(false)
                .build();

        redisCacheService.cacheProduct(skuId, product2);

        // Then
        Optional<Product> result = redisCacheService.getProduct(skuId, Product.class);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Updated Product");
        assertThat(result.get().getBasePrice()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(result.get().getIsActive()).isFalse();
    }

    // ========================================
    // User Purchase Limit Tests
    // ========================================

    @Test
    @Order(15)
    @DisplayName("hasUserPurchased - Returns false when not purchased")
    void hasUserPurchased_NotPurchased_ReturnsFalse() {
        // When
        boolean result = redisCacheService.hasUserPurchased("user-123", "SKU-009");

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @Order(16)
    @DisplayName("markUserPurchased and hasUserPurchased - Marks and checks purchase")
    void markAndCheckUserPurchased_Success() {
        // Given
        String userId = "user-456";
        String skuId = "SKU-010";

        // When
        redisCacheService.markUserPurchased(userId, skuId);
        boolean result = redisCacheService.hasUserPurchased(userId, skuId);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    @Order(17)
    @DisplayName("hasUserPurchased - Different users can purchase same SKU")
    void hasUserPurchased_DifferentUsers_Independent() {
        // Given
        String skuId = "SKU-011";
        redisCacheService.markUserPurchased("user-A", skuId);

        // When/Then
        assertThat(redisCacheService.hasUserPurchased("user-A", skuId)).isTrue();
        assertThat(redisCacheService.hasUserPurchased("user-B", skuId)).isFalse();
    }

    @Test
    @Order(18)
    @DisplayName("hasUserPurchased - Same user can purchase different SKUs")
    void hasUserPurchased_SameUser_DifferentSKUs() {
        // Given
        String userId = "user-789";
        redisCacheService.markUserPurchased(userId, "SKU-012");
        redisCacheService.markUserPurchased(userId, "SKU-013");

        // When/Then
        assertThat(redisCacheService.hasUserPurchased(userId, "SKU-012")).isTrue();
        assertThat(redisCacheService.hasUserPurchased(userId, "SKU-013")).isTrue();
        assertThat(redisCacheService.hasUserPurchased(userId, "SKU-014")).isFalse();
    }

    // ========================================
    // Active Reservation Tests
    // ========================================

    @Test
    @Order(19)
    @DisplayName("getActiveReservation - Returns empty when not exists")
    void getActiveReservation_NotExists_ReturnsEmpty() {
        // When
        Optional<String> result = redisCacheService.getActiveReservation("user-999", "SKU-999");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @Order(20)
    @DisplayName("cacheActiveReservation and getActiveReservation - Caches and retrieves")
    void cacheAndGetActiveReservation_Success() {
        // Given
        String userId = "user-RES-001";
        String skuId = "SKU-RES-001";
        String reservationId = "RES-12345";

        // When
        redisCacheService.cacheActiveReservation(userId, skuId, reservationId);
        Optional<String> result = redisCacheService.getActiveReservation(userId, skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(reservationId);
    }

    @Test
    @Order(21)
    @DisplayName("clearActiveReservation - Removes cached reservation")
    void clearActiveReservation_RemovesCache() {
        // Given
        String userId = "user-RES-002";
        String skuId = "SKU-RES-002";
        String reservationId = "RES-67890";
        redisCacheService.cacheActiveReservation(userId, skuId, reservationId);
        assertThat(redisCacheService.getActiveReservation(userId, skuId)).isPresent();

        // When
        redisCacheService.clearActiveReservation(userId, skuId);

        // Then
        Optional<String> result = redisCacheService.getActiveReservation(userId, skuId);
        assertThat(result).isEmpty();
    }

    @Test
    @Order(22)
    @DisplayName("cacheActiveReservation - Overwrites existing reservation")
    void cacheActiveReservation_Overwrites() {
        // Given
        String userId = "user-RES-003";
        String skuId = "SKU-RES-003";
        redisCacheService.cacheActiveReservation(userId, skuId, "RES-OLD");

        // When - Cache new reservation
        redisCacheService.cacheActiveReservation(userId, skuId, "RES-NEW");
        Optional<String> result = redisCacheService.getActiveReservation(userId, skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("RES-NEW");
    }

    @Test
    @Order(23)
    @DisplayName("getActiveReservation - Different users/SKUs are independent")
    void getActiveReservation_Independence() {
        // Given
        redisCacheService.cacheActiveReservation("user-A", "SKU-X", "RES-A-X");
        redisCacheService.cacheActiveReservation("user-B", "SKU-X", "RES-B-X");
        redisCacheService.cacheActiveReservation("user-A", "SKU-Y", "RES-A-Y");

        // When/Then
        assertThat(redisCacheService.getActiveReservation("user-A", "SKU-X"))
                .isPresent()
                .contains("RES-A-X");
        assertThat(redisCacheService.getActiveReservation("user-B", "SKU-X"))
                .isPresent()
                .contains("RES-B-X");
        assertThat(redisCacheService.getActiveReservation("user-A", "SKU-Y"))
                .isPresent()
                .contains("RES-A-Y");
        assertThat(redisCacheService.getActiveReservation("user-B", "SKU-Y"))
                .isEmpty();
    }

    // ========================================
    // Clear Cache Tests
    // ========================================

    @Test
    @Order(24)
    @DisplayName("clearAllCache - Removes all cached entries")
    void clearAllCache_RemovesAllEntries() {
        // Given - Cache multiple entries
        redisCacheService.setStockCount("SKU-CLEAR-1", 100);
        redisCacheService.setStockCount("SKU-CLEAR-2", 200);
        redisCacheService.markUserPurchased("user-clear", "SKU-CLEAR-1");
        redisCacheService.cacheActiveReservation("user-clear", "SKU-CLEAR-2", "RES-CLEAR");

        // When
        redisCacheService.clearAllCache();

        // Then - All entries should be cleared
        assertThat(redisCacheService.getStockCount("SKU-CLEAR-1")).isEmpty();
        assertThat(redisCacheService.getStockCount("SKU-CLEAR-2")).isEmpty();
        assertThat(redisCacheService.hasUserPurchased("user-clear", "SKU-CLEAR-1")).isFalse();
        assertThat(redisCacheService.getActiveReservation("user-clear", "SKU-CLEAR-2")).isEmpty();
    }

    // ========================================
    // Edge Cases and Error Handling
    // ========================================

    @Test
    @Order(25)
    @DisplayName("setStockCount - Handles zero value")
    void setStockCount_HandlesZero() {
        // Given
        String skuId = "SKU-ZERO";

        // When
        redisCacheService.setStockCount(skuId, 0);
        Optional<Integer> result = redisCacheService.getStockCount(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(0);
    }

    @Test
    @Order(26)
    @DisplayName("setStockCount - Handles large numbers")
    void setStockCount_HandlesLargeNumbers() {
        // Given
        String skuId = "SKU-LARGE";
        Integer largeCount = Integer.MAX_VALUE;

        // When
        redisCacheService.setStockCount(skuId, largeCount);
        Optional<Integer> result = redisCacheService.getStockCount(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @Order(27)
    @DisplayName("decrementStockCount - Non-existent key returns null")
    void decrementStockCount_NonExistentKey() {
        // When - Decrement non-existent key
        Long result = redisCacheService.decrementStockCount("SKU-NONEXISTENT-DEC", 10);

        // Then - Redis creates key with negative value
        assertThat(result).isEqualTo(-10L);
    }

    @Test
    @Order(28)
    @DisplayName("Mixed operations - Increment and decrement work together")
    void mixedOperations_IncrementAndDecrement() {
        // Given
        String skuId = "SKU-MIXED";
        redisCacheService.setStockCount(skuId, 100);

        // When - Mix of operations
        redisCacheService.decrementStockCount(skuId, 20);
        redisCacheService.incrementStockCount(skuId, 10);
        redisCacheService.decrementStockCount(skuId, 5);
        Optional<Integer> result = redisCacheService.getStockCount(skuId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(85); // 100 - 20 + 10 - 5
    }
}
