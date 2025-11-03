package com.cred.freestyle.flashsale.infrastructure.ratelimit;

/**
 * Result of rate limit check.
 *
 * @author Flash Sale Team
 */
public class RateLimitResult {
    private final boolean allowed;
    private final int remainingTokens;
    private final long retryAfterSeconds;
    private final UserTier tier;
    private final String reason;

    private RateLimitResult(boolean allowed, int remainingTokens, long retryAfterSeconds,
                            UserTier tier, String reason) {
        this.allowed = allowed;
        this.remainingTokens = remainingTokens;
        this.retryAfterSeconds = retryAfterSeconds;
        this.tier = tier;
        this.reason = reason;
    }

    public static RateLimitResult allowed(int remainingTokens, UserTier tier) {
        return new RateLimitResult(true, remainingTokens, 0, tier, null);
    }

    public static RateLimitResult rejected(long retryAfterSeconds, UserTier tier, String reason) {
        return new RateLimitResult(false, 0, retryAfterSeconds, tier, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int getRemainingTokens() {
        return remainingTokens;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public UserTier getTier() {
        return tier;
    }

    public String getReason() {
        return reason;
    }
}
