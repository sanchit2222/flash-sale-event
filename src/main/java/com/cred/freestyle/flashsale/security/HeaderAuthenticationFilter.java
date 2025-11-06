package com.cred.freestyle.flashsale.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Custom authentication filter that extracts user identity from HTTP headers.
 *
 * Header-based Authentication:
 * - X-User-Id: User identifier (required for authenticated requests)
 * - X-User-Role: User role (optional, defaults to USER)
 *
 * This filter assumes the API Gateway has already validated JWT tokens
 * and extracted user claims into headers. The Flash Sale service trusts
 * these headers for user identification and authorization.
 *
 * Security Model:
 * - API Gateway validates JWT and extracts userId, role
 * - Gateway forwards request with X-User-Id and X-User-Role headers
 * - This filter reads headers and creates Spring Security Authentication
 * - Controllers use @PreAuthorize to enforce authorization rules
 *
 * @author Flash Sale Team
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(HeaderAuthenticationFilter.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Extract user ID from header
        String userId = request.getHeader(USER_ID_HEADER);

        if (userId != null && !userId.isBlank()) {
            // Extract role from header (default to USER if not provided)
            String role = request.getHeader(USER_ROLE_HEADER);
            if (role == null || role.isBlank()) {
                role = "USER";
            }

            // Ensure role has ROLE_ prefix for Spring Security
            if (!role.startsWith("ROLE_")) {
                role = "ROLE_" + role;
            }

            // Create authentication token with user ID and role
            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority(role)
            );

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);

            // Set authentication in SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            logger.debug("Authenticated user: {} with role: {}", userId, role);
        } else {
            logger.debug("No {} header found, request will be unauthenticated", USER_ID_HEADER);
        }

        // Continue filter chain
        filterChain.doFilter(request, response);
    }
}
