package com.cred.freestyle.flashsale.infrastructure.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Service to determine user tier for rate limiting based on risk assessment.
 *
 * Risk assessment factors:
 * - User account age and history
 * - IP reputation (VPN, proxy, known bot IPs)
 * - Device fingerprint
 * - Behavioral patterns (request rate, timing patterns)
 *
 * @author Flash Sale Team
 */
@Service
public class UserTierService {

    private static final Logger logger = LoggerFactory.getLogger(UserTierService.class);

    private final RedisTemplate<String, String> redisTemplate;

    private static final String USER_TIER_KEY_PREFIX = "user_tier:";
    private static final String REQUEST_HISTORY_KEY_PREFIX = "request_history:";

    // Known VPN/proxy provider patterns (simplified for demo)
    private static final Set<String> SUSPICIOUS_USER_AGENTS = new HashSet<>(Arrays.asList(
        "bot", "crawler", "spider", "scraper", "curl", "wget", "python-requests"
    ));

    public UserTierService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Determine user tier based on multi-dimensional risk assessment.
     *
     * Algorithm (from SYSTEM_ARCHITECTURE_ULTRA_V2, Decision 5):
     * 1. Calculate risk score (0-100)
     * 2. Device fingerprinting check (+50 if bot pattern)
     * 3. Behavioral check (+40 if rapid requests)
     * 4. IP reputation (+20 if VPN, +100 if blacklisted)
     * 5. Assign tier based on score:
     *    - Score > 80: TIER_1 (1 req/min)
     *    - Score > 50: TIER_2 (50 req/min)
     *    - Score > 20: TIER_3 (100 req/min - default)
     *    - Score <= 20: TIER_4 (200 req/min - premium)
     *
     * @param userId User ID
     * @param ipAddress Client IP address
     * @param userAgent User-Agent header
     * @return UserTier for rate limiting
     */
    public UserTier getUserTier(String userId, String ipAddress, String userAgent) {
        try {
            // Check if tier is cached
            String cachedTier = redisTemplate.opsForValue().get(USER_TIER_KEY_PREFIX + userId);
            if (cachedTier != null) {
                try {
                    return UserTier.valueOf(cachedTier);
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid cached tier for user: {}, recalculating", userId);
                }
            }

            // Calculate risk score
            int riskScore = 0;

            // 1. Device fingerprinting check (User-Agent analysis)
            if (isSuspiciousUserAgent(userAgent)) {
                riskScore += 50;
                logger.debug("Suspicious User-Agent detected for user: {}", userId);
            }

            // 2. Behavioral check (request rate in last second)
            int requestRateLastSecond = getRequestRateLastSecond(userId);
            if (requestRateLastSecond > 100) {
                riskScore += 40;
                logger.debug("High request rate detected for user: {} ({})", userId, requestRateLastSecond);
            } else if (requestRateLastSecond > 50) {
                riskScore += 20;
            }

            // 3. IP reputation check (simplified - in production would check against IP reputation DB)
            if (isVpnOrProxy(ipAddress)) {
                riskScore += 20;
                logger.debug("VPN/Proxy IP detected for user: {}", userId);
            }

            // 4. Blacklist check (in production, would check against external blacklist)
            if (isBlacklisted(userId, ipAddress)) {
                riskScore += 100;
                logger.warn("Blacklisted user/IP detected: {}/{}", userId, ipAddress);
            }

            // Assign tier based on risk score
            UserTier tier;
            if (riskScore > 80) {
                tier = UserTier.TIER_1;  // High risk
            } else if (riskScore > 50) {
                tier = UserTier.TIER_2;  // Medium risk
            } else if (riskScore > 20) {
                tier = UserTier.TIER_3;  // Normal
            } else {
                tier = UserTier.TIER_4;  // Premium/trusted
            }

            // Cache tier for 5 minutes
            redisTemplate.opsForValue().set(USER_TIER_KEY_PREFIX + userId, tier.name(),
                                           Duration.ofMinutes(5));

            logger.debug("Assigned tier {} to user: {} (risk score: {})", tier, userId, riskScore);
            return tier;

        } catch (Exception e) {
            logger.error("Error determining user tier for user: {}, defaulting to TIER_3", userId, e);
            return UserTier.TIER_3;  // Default to normal tier on error
        }
    }

    /**
     * Check if User-Agent indicates bot or automated tool.
     *
     * @param userAgent User-Agent header
     * @return true if suspicious
     */
    private boolean isSuspiciousUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return true;  // Missing User-Agent is suspicious
        }

        String lowerUserAgent = userAgent.toLowerCase();
        return SUSPICIOUS_USER_AGENTS.stream().anyMatch(lowerUserAgent::contains);
    }

    /**
     * Get request rate for user in the last second.
     *
     * @param userId User ID
     * @return Number of requests in last second
     */
    private int getRequestRateLastSecond(String userId) {
        try {
            String historyKey = REQUEST_HISTORY_KEY_PREFIX + userId;

            // Increment request count
            Long count = redisTemplate.opsForValue().increment(historyKey);

            // Set expiry if this is first request
            if (count != null && count == 1) {
                redisTemplate.expire(historyKey, Duration.ofSeconds(1));
            }

            return count != null ? count.intValue() : 0;

        } catch (Exception e) {
            logger.error("Error getting request rate for user: {}", userId, e);
            return 0;
        }
    }

    /**
     * Check if IP is from VPN or proxy provider.
     *
     * In production, this would check against:
     * - IP reputation databases (MaxMind, IPQualityScore)
     * - Known VPN/proxy IP ranges
     * - Cloud provider IP ranges (AWS, GCP, Azure)
     *
     * @param ipAddress IP address
     * @return true if VPN/proxy detected
     */
    private boolean isVpnOrProxy(String ipAddress) {
        // Simplified check - in production, use IP reputation service
        // Example: Check against known cloud provider ranges

        // For demo: Flag localhost and common test IPs as safe
        if (ipAddress == null ||
            ipAddress.equals("127.0.0.1") ||
            ipAddress.equals("::1") ||
            ipAddress.startsWith("192.168.") ||
            ipAddress.startsWith("10.")) {
            return false;
        }

        // In production: Check external IP reputation database
        // return ipReputationService.isVpnOrProxy(ipAddress);

        return false;  // Default to not VPN for now
    }

    /**
     * Check if user or IP is blacklisted.
     *
     * @param userId User ID
     * @param ipAddress IP address
     * @return true if blacklisted
     */
    private boolean isBlacklisted(String userId, String ipAddress) {
        try {
            // Check user blacklist
            Boolean userBlacklisted = redisTemplate.hasKey("blacklist:user:" + userId);
            if (userBlacklisted != null && userBlacklisted) {
                return true;
            }

            // Check IP blacklist
            Boolean ipBlacklisted = redisTemplate.hasKey("blacklist:ip:" + ipAddress);
            return ipBlacklisted != null && ipBlacklisted;

        } catch (Exception e) {
            logger.error("Error checking blacklist for user: {}", userId, e);
            return false;
        }
    }

    /**
     * Manually set user tier (admin operation).
     *
     * @param userId User ID
     * @param tier User tier to assign
     */
    public void setUserTier(String userId, UserTier tier) {
        redisTemplate.opsForValue().set(USER_TIER_KEY_PREFIX + userId, tier.name(),
                                       Duration.ofHours(24));
        logger.info("Manually set tier {} for user: {}", tier, userId);
    }

    /**
     * Add user to blacklist (admin operation).
     *
     * @param userId User ID
     */
    public void blacklistUser(String userId) {
        redisTemplate.opsForValue().set("blacklist:user:" + userId, "true",
                                       Duration.ofDays(365));
        logger.warn("Blacklisted user: {}", userId);
    }

    /**
     * Add IP to blacklist (admin operation).
     *
     * @param ipAddress IP address
     */
    public void blacklistIp(String ipAddress) {
        redisTemplate.opsForValue().set("blacklist:ip:" + ipAddress, "true",
                                       Duration.ofDays(30));
        logger.warn("Blacklisted IP: {}", ipAddress);
    }
}
