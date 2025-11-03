package com.cred.freestyle.flashsale.infrastructure.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration for rate limiting interceptor.
 *
 * Applies rate limiting to specific endpoints based on risk profile:
 * - Write endpoints (POST /api/v1/reservations): Strict rate limiting
 * - Read endpoints: More permissive or no rate limiting
 *
 * @author Flash Sale Team
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Value("${flashsale.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    public RateLimitConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (rateLimitingEnabled) {
            // Apply rate limiting to write endpoints (reservation creation)
            registry.addInterceptor(rateLimitInterceptor)
                   .addPathPatterns("/api/v1/reservations")  // POST /api/v1/reservations
                   .addPathPatterns("/api/v1/orders/**");    // POST /api/v1/orders/*/checkout

            // Optionally: Add different rate limiter for read endpoints with more permissive limits
            // registry.addInterceptor(readRateLimitInterceptor)
            //        .addPathPatterns("/api/v1/products/**");  // GET requests
        }
    }
}
