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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuthService Password Format Validation
 * Tests that only secure password_hash + salt format is accepted, not plaintext passwords
 * 
 * DISABLED: Java 25 compatibility issue - Mockito/ByteBuddy cannot mock UserRepository
 * due to Java 25 bytecode (major version 69) not being fully supported by ByteBuddy.
 * Will be re-enabled when Mockito/ByteBuddy adds full Java 25 support.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Mockito cannot mock UserRepository")
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
        testUser.setClientSalt(testClientSalt);
        testUser.setServerSalt("server-salt");

        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
    }

    @Test
    void testAuthenticate_WithSecureFormat_ShouldSucceed() {
        // Given - Request with password_hash and salt
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(testPasswordHash);
        request.setSalt(testClientSalt);

        // Mock password verification
        when(passwordHashingService.verifyClientPassword(
                testPasswordHash, testClientSalt, 
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

        // When/Then - Should throw exception with accurate error message
        AppException ex = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("password_hash") || 
                   ex.getMessage().contains("secure format") ||
                   ex.getMessage().contains("required"));
        assertFalse(ex.getMessage().contains("password_hash+salt or password"), 
                   "Error message should not mention legacy password format");
    }

    @Test
    void testAuthenticate_WithMissingPasswordHash_ShouldThrowException() {
        // Given - Request with salt but no password_hash
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setSalt(testClientSalt);
        // passwordHash is null

        // When/Then - Should throw exception
        AppException ex = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
    }

    @Test
    void testAuthenticate_WithMissingSalt_ShouldThrowException() {
        // Given - Request with password_hash but no salt
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(testPasswordHash);
        // salt is null

        // When/Then - Should throw exception
        AppException ex = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
    }
}

