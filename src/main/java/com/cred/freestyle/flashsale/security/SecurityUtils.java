package com.cred.freestyle.flashsale.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility class for security and authorization operations.
 * Provides helper methods for checking user permissions and extracting user identity.
 *
 * @author Flash Sale Team
 */
public class SecurityUtils {

    /**
     * Get the currently authenticated user ID.
     *
     * @return User ID from authentication context, or null if not authenticated
     */
    public static String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof String) {
                return (String) principal;
            }
        }

        return null;
    }

    /**
     * Check if the current user has a specific role.
     *
     * @param role Role to check (without ROLE_ prefix)
     * @return true if user has the role, false otherwise
     */
    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;

        return authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals(roleWithPrefix));
    }

    /**
     * Check if the current user is an admin.
     *
     * @return true if user has ADMIN role
     */
    public static boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /**
     * Verify that the current user matches the specified user ID.
     * Throws AccessDeniedException if user IDs don't match and user is not admin.
     *
     * @param userId User ID to verify against
     * @throws AccessDeniedException if access is denied
     */
    public static void verifyUserAccess(String userId) {
        String currentUserId = getCurrentUserId();

        if (currentUserId == null) {
            throw new AccessDeniedException("User not authenticated");
        }

        // Admins can access any user's resources
        if (isAdmin()) {
            return;
        }

        // Regular users can only access their own resources
        if (!currentUserId.equals(userId)) {
            throw new AccessDeniedException(
                "Access denied: User " + currentUserId + " cannot access resources for user " + userId
            );
        }
    }

    /**
     * Verify that the current user is an admin.
     * Throws AccessDeniedException if user is not admin.
     *
     * @throws AccessDeniedException if access is denied
     */
    public static void verifyAdminAccess() {
        if (!isAdmin()) {
            throw new AccessDeniedException("Access denied: Admin role required");
        }
    }
}
