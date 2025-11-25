package com.budgetbuddy.security.zerotrust.identity;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Identity Verification Service
 * Implements continuous identity verification
 * Verifies user identity and permissions
 */
@Service
public class IdentityVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(IdentityVerificationService.class);

    private final UserRepository userRepository;

    public IdentityVerificationService(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Verify user identity
     */
    public boolean verifyIdentity(final String userId) {
        return userRepository.findById(userId)
                .map((user) -> {
                    // Check if user is enabled
                    if (user.getEnabled() == null || !user.getEnabled()) {
                        logger.warn("Identity verification failed: User disabled - {}", userId);
                        return false;
                    }

                    // Check if email is verified
                    if (user.getEmailVerified() == null || !user.getEmailVerified()) {
                        logger.warn("Identity verification failed: Email not verified - {}", userId);
                        return false;
                    }

                    return true;
                })
                .orElse(false);
    }

    /**
     * Check if user has permission for resource and action
     */
    public boolean hasPermission(final String userId, final String resource, final String action) {
        return userRepository.findById(userId)
                .map((user) -> {
                    Set<String> roles = user.getRoles();
                    if (roles == null || roles.isEmpty()) {
                        return false;
                    }

                    // Check permissions based on role
                    if (roles.contains("ADMIN")) {
                        return true; // Admin has all permissions
                    }

                    // Resource-based permissions
                    if (resource.startsWith("/api/admin") || resource.startsWith("/api/compliance")) {
                        return roles.contains("ADMIN");
                    }

                    // Action-based permissions
                    if ("DELETE".equals(action) && resource.contains("/api/transactions")) {
                        // Only allow delete for own transactions
                        return true; // Additional check needed in controller
                    }

                    // Default: allow for authenticated users
                    return roles.contains("USER");
                })
                .orElse(false);
    }

    /**
     * Get user roles
     */
    public Set<String> getUserRoles(String userId) {
        return userRepository.findById(userId)
                .map(UserTable::getRoles)
                .orElse(Set.of());
    }
}

