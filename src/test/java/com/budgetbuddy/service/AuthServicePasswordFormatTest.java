package com.budgetbuddy.service;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuthService Password Format Validation
 * Tests that only secure password_hash + salt format is accepted, not plaintext passwords
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServicePasswordFormatTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHashingService passwordHashingService;

    @Mock
    private com.budgetbuddy.security.JwtTokenProvider jwtTokenProvider;

    @Mock
    private com.budgetbuddy.service.UserService userService;

    private AuthService authService;
    private UserTable testUser;
    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        authService = new AuthService(jwtTokenProvider, userService, passwordHashingService, userRepository);
        testEmail = "test@example.com";
        testPasswordHash = "hashed-password";
        testClientSalt = "client-salt";

        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(testEmail);
        testUser.setPasswordHash("server-hashed-password");
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        testUser.setServerSalt("server-salt");
        testUser.setEnabled(true); // Ensure user is enabled for all tests

        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
    }

    @Test
    void testAuthenticate_WithSecureFormat_ShouldSucceed() {
        // Given - Request with password_hash (BREAKING CHANGE: Client salt removed)
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(testPasswordHash);
        // BREAKING CHANGE: Client salt removed - backend handles salt management

        // Mock password verification (BREAKING CHANGE: verifyClientPassword signature changed)
        when(passwordHashingService.verifyClientPassword(
                testPasswordHash, 
                testUser.getPasswordHash(), testUser.getServerSalt()))
                .thenReturn(true);

        // When/Then - Should not throw exception for secure format
        assertDoesNotThrow(() -> {
            authService.authenticate(request);
        });
    }

    @Test
    void testAuthenticate_WithPlaintextPassword_ShouldThrowException() {
        // Given - Request with plaintext password (legacy format, no longer supported)
        // Note: AuthRequest doesn't have setPassword() - only password_hash and salt are supported
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        // Don't set passwordHash or salt - this simulates missing required fields
        // But user must be enabled for the test to reach the format check
        testUser.setEnabled(true);

        // When/Then - Should throw exception with accurate error message
        AppException ex = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        // The error could be INVALID_INPUT (format check) or ACCOUNT_DISABLED (if user is disabled)
        // Since we're testing format validation, we expect INVALID_INPUT
        assertTrue(ex.getErrorCode() == ErrorCode.INVALID_INPUT || 
                   ex.getMessage().contains("password_hash") ||
                   ex.getMessage().contains("secure format"));
    }

    @Test
    void testAuthenticate_WithMissingPasswordHash_ShouldThrowException() {
        // Given - Request with no password_hash (BREAKING CHANGE: Client salt removed, only password_hash required)
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        // passwordHash is null
        testUser.setEnabled(true); // Ensure user is enabled to reach format check

        // When/Then - Should throw exception
        AppException ex = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        // The error could be INVALID_INPUT (format check) or ACCOUNT_DISABLED (if user is disabled)
        assertTrue(ex.getErrorCode() == ErrorCode.INVALID_INPUT || 
                   ex.getMessage().contains("password_hash") ||
                   ex.getMessage().contains("secure format"));
    }
}

