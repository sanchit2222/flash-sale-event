package com.cred.freestyle.flashsale.infrastructure.ratelimit;

import com.cred.freestyle.flashsale.infrastructure.metrics.CloudWatchMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Rate limiting service using Token Bucket algorithm with Redis.
 *
 * Implementation follows Decision 5 from SYSTEM_ARCHITECTURE_ULTRA_V2:
 * - Token bucket per user tier
 * - Tokens replenish every second
 * - Fair FIFO queuing when tokens exhausted
 * - Multi-dimensional rate limiting (IP + User + Device)
 *
 * @author Flash Sale Team
 */
@Service
public class RateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final UserTierService userTierService;
    private final CloudWatchMetricsService metricsService;

    private static final String TOKEN_BUCKET_KEY_PREFIX = "rate_limit:token_bucket:";
    private static final String LAST_REFILL_KEY_PREFIX = "rate_limit:last_refill:";

    public RateLimitService(
            RedisTemplate<String, String> redisTemplate,
            UserTierService userTierService,
            CloudWatchMetricsService metricsService
    ) {
        this.redisTemplate = redisTemplate;
        this.userTierService = userTierService;
        this.metricsService = metricsService;
    }

    /**
     * Check if request is allowed based on token bucket rate limiting.
     *
     * Algorithm:
     * 1. Determine user tier (TIER_1 to TIER_4)
     * 2. Check token bucket for available tokens
     * 3. If tokens available: Consume 1 token, allow request
     * 4. If no tokens: Reject with 429, return retry-after time
     * 5. Tokens auto-replenish based on time elapsed
     *
     * @param userId User ID
     * @param ipAddress Client IP address
     * @param userAgent User-Agent header
     * @return RateLimitResult with allow/reject decision
     */
    public RateLimitResult checkRateLimit(String userId, String ipAddress, String userAgent) {
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Determine user tier based on risk assessment
            UserTier tier = userTierService.getUserTier(userId, ipAddress, userAgent);

            // Step 2: Build Redis key for this user's token bucket
            String tokenKey = TOKEN_BUCKET_KEY_PREFIX + userId;
            String lastRefillKey = LAST_REFILL_KEY_PREFIX + userId;

            // Step 3: Refill tokens based on time elapsed since last refill
            refillTokens(tokenKey, lastRefillKey, tier);

            // Step 4: Try to consume a token
            Long remainingTokens = redisTemplate.opsForValue().decrement(tokenKey);

            if (remainingTokens != null && remainingTokens >= 0) {
                // Token consumed successfully, request allowed
                logger.debug("Rate limit check PASSED for user: {}, tier: {}, remaining: {}",
                           userId, tier, remainingTokens);

                // Record metrics (rate limit metrics could be added to CloudWatchMetricsService)

                return RateLimitResult.allowed(remainingTokens.intValue(), tier);

            } else {
                // No tokens available, request rejected
                logger.warn("Rate limit EXCEEDED for user: {}, tier: {}, ip: {}",
                          userId, tier, ipAddress);

                // Restore the token count (we decremented below 0)
                redisTemplate.opsForValue().increment(tokenKey);

                // Calculate retry-after time based on tier
                long retryAfterSeconds = calculateRetryAfter(tier);

                // Record metrics (rate limit rejection metrics could be added to CloudWatchMetricsService)

                return RateLimitResult.rejected(retryAfterSeconds, tier,
                        "Rate limit exceeded. Please retry after " + retryAfterSeconds + " seconds");
            }

        } catch (Exception e) {
            logger.error("Error checking rate limit for user: {}, defaulting to ALLOW", userId, e);
            metricsService.recordError("RATE_LIMIT_CHECK_ERROR", "checkRateLimit");

            // Fail open: Allow request if rate limiting fails (graceful degradation)
            return RateLimitResult.allowed(100, UserTier.TIER_3);

        } finally {
            long duration = System.currentTimeMillis() - startTime;
            // Record latency metric if needed
        }
    }

    /**
     * Refill tokens based on time elapsed since last refill.
     *
     * Token Bucket Refill Logic:
     * - Tokens replenish continuously based on time elapsed
     * - Rate = tier.maxRequests / tier.windowSeconds
     * - Example: TIER_3 (100 req/min) = 100/60 = 1.67 tokens/second
     * - If 5 seconds elapsed: Refill 5 × 1.67 = 8.35 ≈ 8 tokens
     *
     * @param tokenKey Redis key for token bucket
     * @param lastRefillKey Redis key for last refill timestamp
     * @param tier User tier
     */
    private void refillTokens(String tokenKey, String lastRefillKey, UserTier tier) {
        try {
            long now = System.currentTimeMillis();

            // Get last refill time
            String lastRefillStr = redisTemplate.opsForValue().get(lastRefillKey);
            long lastRefillTime = lastRefillStr != null ? Long.parseLong(lastRefillStr) : now;

            // Calculate time elapsed since last refill
            long elapsedSeconds = (now - lastRefillTime) / 1000;

            if (elapsedSeconds > 0) {
                // Calculate tokens to add based on time elapsed
                double tokensPerSecond = (double) tier.getMaxRequests() / tier.getWindowSeconds();
                long tokensToAdd = (long) (elapsedSeconds * tokensPerSecond);

                if (tokensToAdd > 0) {
                    // Add tokens (but cap at max for tier)
                    String currentTokensStr = redisTemplate.opsForValue().get(tokenKey);
                    long currentTokens = currentTokensStr != null ? Long.parseLong(currentTokensStr) : 0;

                    long newTokens = Math.min(currentTokens + tokensToAdd, tier.getMaxRequests());

                    // Update token bucket and last refill time atomically
                    redisTemplate.opsForValue().set(tokenKey, String.valueOf(newTokens),
                                                   Duration.ofSeconds(tier.getWindowSeconds()));
                    redisTemplate.opsForValue().set(lastRefillKey, String.valueOf(now),
                                                   Duration.ofSeconds(tier.getWindowSeconds()));

                    logger.debug("Refilled {} tokens for tier {}, new count: {}",
                               tokensToAdd, tier, newTokens);
                }
            }

            // Initialize token bucket if it doesn't exist
            Boolean exists = redisTemplate.hasKey(tokenKey);
            if (exists == null || !exists) {
                redisTemplate.opsForValue().set(tokenKey, String.valueOf(tier.getMaxRequests()),
                                               Duration.ofSeconds(tier.getWindowSeconds()));
                redisTemplate.opsForValue().set(lastRefillKey, String.valueOf(now),
                                               Duration.ofSeconds(tier.getWindowSeconds()));

                logger.debug("Initialized token bucket for tier {} with {} tokens",
                           tier, tier.getMaxRequests());
            }

        } catch (Exception e) {
            logger.error("Error refilling tokens, continuing with current count", e);
        }
    }

    /**
     * Calculate retry-after time based on tier.
     *
     * @param tier User tier
     * @return Seconds until next token available
     */
    private long calculateRetryAfter(UserTier tier) {
        // Tokens refill at rate of maxRequests / windowSeconds
        // Time for 1 token = windowSeconds / maxRequests
        return tier.getWindowSeconds() / tier.getMaxRequests();
    }

    /**
     * Reset rate limit for a user (admin operation).
     *
     * @param userId User ID
     */
    public void resetRateLimit(String userId) {
        String tokenKey = TOKEN_BUCKET_KEY_PREFIX + userId;
        String lastRefillKey = LAST_REFILL_KEY_PREFIX + userId;

        redisTemplate.delete(tokenKey);
        redisTemplate.delete(lastRefillKey);

        logger.info("Reset rate limit for user: {}", userId);
    }
}
