package com.budgetbuddy.security.zerotrust.identity;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for IdentityVerificationService
 * Tests identity verification and permission checks
 */
@ExtendWith(MockitoExtension.class)
class IdentityVerificationServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private IdentityVerificationService identityVerificationService;

    private String testUserId;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        Set<String> roles = new HashSet<>();
        roles.add("USER");
        testUser.setRoles(roles);
    }

    @Test
    void testVerifyIdentity_WithValidUser_ReturnsTrue() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.verifyIdentity(testUserId);

        // Then
        assertTrue(result);
        verify(userRepository, times(1)).findById(testUserId);
    }

    @Test
    void testVerifyIdentity_WithDisabledUser_ReturnsFalse() {
        // Given
        testUser.setEnabled(false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.verifyIdentity(testUserId);

        // Then
        assertFalse(result);
    }

    @Test
    void testVerifyIdentity_WithUnverifiedEmail_ReturnsFalse() {
        // Given
        testUser.setEmailVerified(false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.verifyIdentity(testUserId);

        // Then
        assertFalse(result);
    }

    @Test
    void testVerifyIdentity_WithNonExistentUser_ReturnsFalse() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.empty());

        // When
        boolean result = identityVerificationService.verifyIdentity(testUserId);

        // Then
        assertFalse(result);
    }

    @Test
    void testHasPermission_WithAdminRole_ReturnsTrue() {
        // Given
        Set<String> adminRoles = new HashSet<>();
        adminRoles.add("ADMIN");
        testUser.setRoles(adminRoles);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.hasPermission(testUserId, "/api/admin/users", "DELETE");

        // Then
        assertTrue(result);
    }

    @Test
    void testHasPermission_WithUserRole_ReturnsTrueForOwnResources() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.hasPermission(testUserId, "/api/transactions", "GET");

        // Then
        assertTrue(result);
    }

    @Test
    void testHasPermission_WithUserRole_ReturnsFalseForAdminResources() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.hasPermission(testUserId, "/api/admin/users", "DELETE");

        // Then
        assertFalse(result);
    }

    @Test
    void testHasPermission_WithNoRoles_ReturnsFalse() {
        // Given
        testUser.setRoles(null);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.hasPermission(testUserId, "/api/transactions", "GET");

        // Then
        assertFalse(result);
    }

    @Test
    void testHasPermission_WithEmptyRoles_ReturnsFalse() {
        // Given
        testUser.setRoles(new HashSet<>());
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        boolean result = identityVerificationService.hasPermission(testUserId, "/api/transactions", "GET");

        // Then
        assertFalse(result);
    }

    @Test
    void testGetUserRoles_WithValidUser_ReturnsRoles() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // When
        Set<String> result = identityVerificationService.getUserRoles(testUserId);

        // Then
        assertNotNull(result);
        assertTrue(result.contains("USER"));
        verify(userRepository, times(1)).findById(testUserId);
    }

    @Test
    void testGetUserRoles_WithNonExistentUser_ReturnsEmptySet() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.empty());

        // When
        Set<String> result = identityVerificationService.getUserRoles(testUserId);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}

