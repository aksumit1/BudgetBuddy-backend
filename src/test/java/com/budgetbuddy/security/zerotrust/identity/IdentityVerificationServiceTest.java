package com.budgetbuddy.security.zerotrust.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Comprehensive tests for IdentityVerificationService */
class IdentityVerificationServiceTest {

    private static final String USER_123 = "user-123";

    @Mock private UserRepository userRepository;

    private IdentityVerificationService identityVerificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        identityVerificationService = new IdentityVerificationService(userRepository);
    }

    @Test
    @DisplayName("Should verify identity for enabled user with verified email")
    void testVerifyIdentitySuccess() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(true);
        user.setEmailVerified(true);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertTrue(verified);
    }

    @Test
    @DisplayName("Should fail verification for disabled user")
    void testVerifyIdentityDisabledUser() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(false);
        user.setEmailVerified(true);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should fail verification for unverified email")
    void testVerifyIdentityUnverifiedEmail() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(true);
        user.setEmailVerified(false);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should fail verification for non-existent user")
    void testVerifyIdentityNonExistentUser() {
        // Given
        final String userId = USER_123;
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());

        // When
        final boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should grant permission for admin user")
    void testHasPermissionAdmin() {
        // Given
        final String userId = "admin-123";
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("ADMIN"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean hasPermission =
                identityVerificationService.hasPermission(userId, "/api/admin", "DELETE");

        // Then
        assertTrue(hasPermission);
    }

    @Test
    @DisplayName("Should deny permission for regular user accessing admin resource")
    void testHasPermissionRegularUserAdminResource() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean hasPermission =
                identityVerificationService.hasPermission(userId, "/api/admin", "GET");

        // Then
        assertFalse(hasPermission);
    }

    @Test
    @DisplayName("Should grant permission for regular user accessing user resource")
    void testHasPermissionRegularUserUserResource() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final boolean hasPermission =
                identityVerificationService.hasPermission(userId, "/api/transactions", "GET");

        // Then
        assertTrue(hasPermission);
    }

    @Test
    @DisplayName("Should return user roles")
    void testGetUserRoles() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER", "PREMIUM"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        final Set<String> roles = identityVerificationService.getUserRoles(userId);

        // Then
        assertNotNull(roles);
        assertEquals(2, roles.size());
        assertTrue(roles.contains("USER"));
        assertTrue(roles.contains("PREMIUM"));
    }

    @Test
    @DisplayName("Should return empty set for non-existent user")
    void testGetUserRolesNonExistentUser() {
        // Given
        final String userId = USER_123;
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());

        // When
        final Set<String> roles = identityVerificationService.getUserRoles(userId);

        // Then
        assertNotNull(roles);
        assertTrue(roles.isEmpty());
    }
}
