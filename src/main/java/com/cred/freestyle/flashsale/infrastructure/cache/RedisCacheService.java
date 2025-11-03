package com.cred.freestyle.flashsale.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache service for high-performance caching.
 * Provides methods to cache inventory counts, product data, and user purchase limits.
 *
 * Cache Keys:
 * - stock:{sku_id} -> Available inventory count (Integer)
 * - product:{sku_id} -> Product details (JSON)
 * - user_limit:{user_id}:{sku_id} -> User purchase flag (Boolean)
 *
 * @author Flash Sale Team
 */
@Service
public class RedisCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Cache key prefixes
    private static final String STOCK_PREFIX = "stock:";
    private static final String PRODUCT_PREFIX = "product:";
    private static final String USER_LIMIT_PREFIX = "user_limit:";
    private static final String RESERVATION_PREFIX = "reservation:";
    private static final String REJECTION_PREFIX = "rejection:";

    // Cache TTL durations
    private static final Duration STOCK_TTL = Duration.ofMinutes(5);
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);
    private static final Duration USER_LIMIT_TTL = Duration.ofHours(24);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(3); // Slightly longer than reservation expiry
    private static final Duration REJECTION_TTL = Duration.ofMinutes(3); // Same as reservation TTL for polling

    public RedisCacheService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Get available stock count from cache.
     *
     * @param skuId Product SKU ID
     * @return Optional containing stock count if cached
     */
    public Optional<Integer> getStockCount(String skuId) {
        try {
            String key = STOCK_PREFIX + skuId;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache hit for stock count: {}", skuId);
                return Optional.of(Integer.parseInt(value));
            }
            logger.debug("Cache miss for stock count: {}", skuId);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting stock count from cache for SKU: {}", skuId, e);
            return Optional.empty();
        }
    }

    /**
     * Set stock count in cache.
     *
     * @param skuId Product SKU ID
     * @param count Available stock count
     */
    public void setStockCount(String skuId, Integer count) {
        try {
            String key = STOCK_PREFIX + skuId;
            redisTemplate.opsForValue().set(key, count.toString(), STOCK_TTL);
            logger.debug("Cached stock count for {}: {}", skuId, count);
        } catch (Exception e) {
            logger.error("Error setting stock count in cache for SKU: {}", skuId, e);
        }
    }

    /**
     * Decrement stock count in cache atomically.
     * Returns the new count after decrement.
     *
     * @param skuId Product SKU ID
     * @param quantity Quantity to decrement
     * @return New stock count after decrement, or null if key doesn't exist
     */
    public Long decrementStockCount(String skuId, Integer quantity) {
        try {
            String key = STOCK_PREFIX + skuId;
            Long newCount = redisTemplate.opsForValue().decrement(key, quantity);
            logger.debug("Decremented stock count for {} by {}: new count = {}", skuId, quantity, newCount);
            return newCount;
        } catch (Exception e) {
            logger.error("Error decrementing stock count in cache for SKU: {}", skuId, e);
            return null;
        }
    }

    /**
     * Increment stock count in cache atomically.
     * Used when reservations expire.
     *
     * @param skuId Product SKU ID
     * @param quantity Quantity to increment
     * @return New stock count after increment, or null if error
     */
    public Long incrementStockCount(String skuId, Integer quantity) {
        try {
            String key = STOCK_PREFIX + skuId;
            Long newCount = redisTemplate.opsForValue().increment(key, quantity);
            logger.debug("Incremented stock count for {} by {}: new count = {}", skuId, quantity, newCount);
            return newCount;
        } catch (Exception e) {
            logger.error("Error incrementing stock count in cache for SKU: {}", skuId, e);
            return null;
        }
    }

    /**
     * Invalidate stock count cache for a SKU.
     *
     * @param skuId Product SKU ID
     */
    public void invalidateStockCount(String skuId) {
        try {
            String key = STOCK_PREFIX + skuId;
            redisTemplate.delete(key);
            logger.debug("Invalidated stock count cache for: {}", skuId);
        } catch (Exception e) {
            logger.error("Error invalidating stock count cache for SKU: {}", skuId, e);
        }
    }

    /**
     * Cache product data as JSON.
     *
     * @param skuId Product SKU ID
     * @param productData Product object to cache
     */
    public <T> void cacheProduct(String skuId, T productData) {
        try {
            String key = PRODUCT_PREFIX + skuId;
            String json = objectMapper.writeValueAsString(productData);
            redisTemplate.opsForValue().set(key, json, PRODUCT_TTL);
            logger.debug("Cached product data for: {}", skuId);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing product data for SKU: {}", skuId, e);
        } catch (Exception e) {
            logger.error("Error caching product data for SKU: {}", skuId, e);
        }
    }

    /**
     * Get cached product data.
     *
     * @param skuId Product SKU ID
     * @param clazz Product class type
     * @return Optional containing product if cached
     */
    public <T> Optional<T> getProduct(String skuId, Class<T> clazz) {
        try {
            String key = PRODUCT_PREFIX + skuId;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                T product = objectMapper.readValue(json, clazz);
                logger.debug("Cache hit for product: {}", skuId);
                return Optional.of(product);
            }
            logger.debug("Cache miss for product: {}", skuId);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting product from cache for SKU: {}", skuId, e);
            return Optional.empty();
        }
    }

    /**
     * Mark that user has purchased a product (enforce 1 per user limit).
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     */
    public void markUserPurchased(String userId, String skuId) {
        try {
            String key = USER_LIMIT_PREFIX + userId + ":" + skuId;
            redisTemplate.opsForValue().set(key, "true", USER_LIMIT_TTL);
            logger.debug("Marked user {} as purchased for SKU: {}", userId, skuId);
        } catch (Exception e) {
            logger.error("Error marking user purchase in cache for user {} and SKU {}", userId, skuId, e);
        }
    }

    /**
     * Check if user has already purchased a product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return true if user has purchased
     */
    public boolean hasUserPurchased(String userId, String skuId) {
        try {
            String key = USER_LIMIT_PREFIX + userId + ":" + skuId;
            String value = redisTemplate.opsForValue().get(key);
            boolean hasPurchased = "true".equals(value);
            logger.debug("User {} purchase check for SKU {}: {}", userId, skuId, hasPurchased);
            return hasPurchased;
        } catch (Exception e) {
            logger.error("Error checking user purchase in cache for user {} and SKU {}", userId, skuId, e);
            // Fail open - return false to allow database check
            return false;
        }
    }

    /**
     * Cache active reservation for a user and product.
     * Used to quickly check if user has pending reservation.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param reservationId Reservation ID
     */
    public void cacheActiveReservation(String userId, String skuId, String reservationId) {
        try {
            String key = RESERVATION_PREFIX + userId + ":" + skuId;
            redisTemplate.opsForValue().set(key, reservationId, RESERVATION_TTL);
            logger.debug("Cached active reservation {} for user {} and SKU {}", reservationId, userId, skuId);
        } catch (Exception e) {
            logger.error("Error caching reservation for user {} and SKU {}", userId, skuId, e);
        }
    }

    /**
     * Get active reservation ID for user and product.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return Optional containing reservation ID if exists
     */
    public Optional<String> getActiveReservation(String userId, String skuId) {
        try {
            String key = RESERVATION_PREFIX + userId + ":" + skuId;
            String reservationId = redisTemplate.opsForValue().get(key);
            if (reservationId != null) {
                logger.debug("Found active reservation {} for user {} and SKU {}", reservationId, userId, skuId);
                return Optional.of(reservationId);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting reservation from cache for user {} and SKU {}", userId, skuId, e);
            return Optional.empty();
        }
    }

    /**
     * Clear active reservation cache.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     */
    public void clearActiveReservation(String userId, String skuId) {
        try {
            String key = RESERVATION_PREFIX + userId + ":" + skuId;
            redisTemplate.delete(key);
            logger.debug("Cleared active reservation for user {} and SKU {}", userId, skuId);
        } catch (Exception e) {
            logger.error("Error clearing reservation cache for user {} and SKU {}", userId, skuId, e);
        }
    }

    /**
     * Cache rejection response for a user and product.
     * Used to immediately notify polling clients about rejected requests.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @param status Rejection status (e.g., OUT_OF_STOCK, USER_ALREADY_PURCHASED)
     * @param errorMessage Error message describing the rejection reason
     */
    public void cacheRejection(String userId, String skuId, String status, String errorMessage) {
        try {
            String key = REJECTION_PREFIX + userId + ":" + skuId;
            String value = status + ":" + errorMessage;
            redisTemplate.opsForValue().set(key, value, REJECTION_TTL);
            logger.debug("Cached rejection for user {} and SKU {}: {} - {}", userId, skuId, status, errorMessage);
        } catch (Exception e) {
            logger.error("Error caching rejection for user {} and SKU {}", userId, skuId, e);
        }
    }

    /**
     * Get rejection response for user and product.
     * Returns a String array with [status, errorMessage] if rejection exists.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     * @return Optional containing [status, errorMessage] if rejection cached
     */
    public Optional<String[]> getRejection(String userId, String skuId) {
        try {
            String key = REJECTION_PREFIX + userId + ":" + skuId;
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && value.contains(":")) {
                String[] parts = value.split(":", 2); // Split into [status, errorMessage]
                logger.debug("Found rejection for user {} and SKU {}: {}", userId, skuId, parts[0]);
                return Optional.of(parts);
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error getting rejection from cache for user {} and SKU {}", userId, skuId, e);
            return Optional.empty();
        }
    }

    /**
     * Clear rejection cache.
     *
     * @param userId User ID
     * @param skuId Product SKU ID
     */
    public void clearRejection(String userId, String skuId) {
        try {
            String key = REJECTION_PREFIX + userId + ":" + skuId;
            redisTemplate.delete(key);
            logger.debug("Cleared rejection for user {} and SKU {}", userId, skuId);
        } catch (Exception e) {
            logger.error("Error clearing rejection cache for user {} and SKU {}", userId, skuId, e);
        }
    }

    /**
     * Clear all cache entries (use with caution).
     * Primarily for testing or emergency cache flush.
     */
    public void clearAllCache() {
        try {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
            logger.warn("Cleared all Redis cache");
        } catch (Exception e) {
            logger.error("Error clearing all cache", e);
        }
    }
}
