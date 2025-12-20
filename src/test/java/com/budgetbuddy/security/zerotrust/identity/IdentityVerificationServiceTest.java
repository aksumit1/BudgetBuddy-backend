package com.budgetbuddy.security.zerotrust.identity;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for IdentityVerificationService
 */
class IdentityVerificationServiceTest {

    @Mock
    private UserRepository userRepository;

    private IdentityVerificationService identityVerificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        identityVerificationService = new IdentityVerificationService(userRepository);
    }

    @Test
    @DisplayName("Should verify identity for enabled user with verified email")
    void testVerifyIdentity_Success() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(true);
        user.setEmailVerified(true);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertTrue(verified);
    }

    @Test
    @DisplayName("Should fail verification for disabled user")
    void testVerifyIdentity_DisabledUser() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(false);
        user.setEmailVerified(true);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should fail verification for unverified email")
    void testVerifyIdentity_UnverifiedEmail() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEnabled(true);
        user.setEmailVerified(false);

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should fail verification for non-existent user")
    void testVerifyIdentity_NonExistentUser() {
        // Given
        String userId = "user-123";
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());

        // When
        boolean verified = identityVerificationService.verifyIdentity(userId);

        // Then
        assertFalse(verified);
    }

    @Test
    @DisplayName("Should grant permission for admin user")
    void testHasPermission_Admin() {
        // Given
        String userId = "admin-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("ADMIN"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean hasPermission = identityVerificationService.hasPermission(userId, "/api/admin", "DELETE");

        // Then
        assertTrue(hasPermission);
    }

    @Test
    @DisplayName("Should deny permission for regular user accessing admin resource")
    void testHasPermission_RegularUserAdminResource() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean hasPermission = identityVerificationService.hasPermission(userId, "/api/admin", "GET");

        // Then
        assertFalse(hasPermission);
    }

    @Test
    @DisplayName("Should grant permission for regular user accessing user resource")
    void testHasPermission_RegularUserUserResource() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        boolean hasPermission = identityVerificationService.hasPermission(userId, "/api/transactions", "GET");

        // Then
        assertTrue(hasPermission);
    }

    @Test
    @DisplayName("Should return user roles")
    void testGetUserRoles() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setRoles(Set.of("USER", "PREMIUM"));

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));

        // When
        Set<String> roles = identityVerificationService.getUserRoles(userId);

        // Then
        assertNotNull(roles);
        assertEquals(2, roles.size());
        assertTrue(roles.contains("USER"));
        assertTrue(roles.contains("PREMIUM"));
    }

    @Test
    @DisplayName("Should return empty set for non-existent user")
    void testGetUserRoles_NonExistentUser() {
        // Given
        String userId = "user-123";
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.empty());

        // When
        Set<String> roles = identityVerificationService.getUserRoles(userId);

        // Then
        assertNotNull(roles);
        assertTrue(roles.isEmpty());
    }
}
