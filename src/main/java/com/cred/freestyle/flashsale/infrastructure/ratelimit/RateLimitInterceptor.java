package com.cred.freestyle.flashsale.infrastructure.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor to apply rate limiting before controller methods execute.
 *
 * Applied to write endpoints (POST /api/v1/reservations) with strict limits.
 * Read endpoints can have more permissive limits or skip rate limiting entirely.
 *
 * @author Flash Sale Team
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // Extract user info from request
        String userId = extractUserId(request);
        String ipAddress = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        // Check rate limit
        RateLimitResult result = rateLimitService.checkRateLimit(userId, ipAddress, userAgent);

        if (result.isAllowed()) {
            // Add rate limit headers to response
            response.setHeader("X-RateLimit-Limit", String.valueOf(result.getTier().getMaxRequests()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.getRemainingTokens()));
            response.setHeader("X-RateLimit-Tier", result.getTier().name());

            return true;  // Allow request to proceed

        } else {
            // Rate limit exceeded - reject with 429
            logger.warn("Rate limit exceeded for user: {}, ip: {}, tier: {}",
                       userId, ipAddress, result.getTier());

            response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
            response.setHeader("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
            response.setHeader("X-RateLimit-Limit", String.valueOf(result.getTier().getMaxRequests()));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("X-RateLimit-Tier", result.getTier().name());
            response.setContentType("application/json");

            // Return error response body
            String errorJson = String.format(
                "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"%s\",\"retryAfter\":%d}",
                result.getReason(),
                result.getRetryAfterSeconds()
            );
            response.getWriter().write(errorJson);

            return false;  // Block request
        }
    }

    /**
     * Extract user ID from request.
     *
     * Priority:
     * 1. Authenticated user ID from security context (when auth is implemented)
     * 2. User ID from request body (for now)
     * 3. IP address as fallback
     *
     * @param request HTTP request
     * @return User identifier
     */
    private String extractUserId(HttpServletRequest request) {
        // TODO: When authentication is implemented, extract from SecurityContext
        // Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // if (auth != null && auth.isAuthenticated()) {
        //     return auth.getName();
        // }

        // For now: Use IP address as user identifier
        // In production with auth: Use actual user ID from JWT/session
        return getClientIp(request);
    }

    /**
     * Get client IP address, handling proxies and load balancers.
     *
     * @param request HTTP request
     * @return Client IP address
     */
    private String getClientIp(HttpServletRequest request) {
        // Check X-Forwarded-For header (set by load balancers/proxies)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: client, proxy1, proxy2
            // First IP is the original client
            return xForwardedFor.split(",")[0].trim();
        }

        // Check X-Real-IP header (alternative proxy header)
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // Fallback to remote address
        return request.getRemoteAddr();
    }
}
