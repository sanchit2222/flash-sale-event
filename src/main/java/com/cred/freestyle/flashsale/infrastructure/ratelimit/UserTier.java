package com.cred.freestyle.flashsale.infrastructure.ratelimit;

/**
 * User tier for rate limiting.
 * Different tiers have different request quotas based on risk assessment.
 *
 * @author Flash Sale Team
 */
public enum UserTier {
    /**
     * Tier 1: Suspected bot or high-risk user.
     * Very restrictive rate limits.
     */
    TIER_1(1, 60),  // 1 request per minute

    /**
     * Tier 2: New user or medium-risk user.
     * Moderate rate limits.
     */
    TIER_2(50, 60),  // 50 requests per minute

    /**
     * Tier 3: Verified user (normal).
     * Standard rate limits for legitimate users.
     */
    TIER_3(100, 60),  // 100 requests per minute

    /**
     * Tier 4: Premium/trusted user.
     * Higher rate limits for loyal customers.
     */
    TIER_4(200, 60);  // 200 requests per minute

    private final int maxRequests;
    private final int windowSeconds;

    UserTier(int maxRequests, int windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public int getRequestsPerSecond() {
        return maxRequests / windowSeconds;
    }
}
