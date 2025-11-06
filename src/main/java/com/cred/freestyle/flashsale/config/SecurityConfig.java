package com.cred.freestyle.flashsale.config;

import com.cred.freestyle.flashsale.security.HeaderAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for Flash Sale application.
 *
 * Authentication Strategy:
 * - Header-based authentication using X-User-Id header
 * - Stateless session management (no server-side sessions)
 * - Integration with existing API gateway (assumes gateway handles JWT validation)
 *
 * Authorization:
 * - Method-level security using @PreAuthorize annotations
 * - User can only access their own resources (userId verification)
 * - Admin endpoints require ADMIN role
 *
 * Public Endpoints (no authentication required):
 * - /actuator/** (health checks, metrics)
 * - /api/v1/products/** (product listing)
 *
 * @author Flash Sale Team
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Configure HTTP security.
     *
     * Security features:
     * - CSRF disabled (stateless REST API)
     * - Stateless session management
     * - Header-based authentication filter
     * - Public endpoints for actuator and products
     * - All other endpoints require authentication
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless REST API
            .csrf(csrf -> csrf.disable())

            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints - no authentication required
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/products/**").permitAll()

                // All other endpoints require authentication
                .anyRequest().authenticated()
            )

            // Stateless session management (no server-side sessions)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Add custom header authentication filter
            .addFilterBefore(
                headerAuthenticationFilter(),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    /**
     * Header authentication filter bean.
     * Extracts user identity from X-User-Id and X-User-Role headers.
     */
    @Bean
    public HeaderAuthenticationFilter headerAuthenticationFilter() {
        return new HeaderAuthenticationFilter();
    }
}
