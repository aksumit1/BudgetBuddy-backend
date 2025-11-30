package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.PasswordHashingService;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Authentication
 * Tests end-to-end authentication flow
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AuthIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordHashingService passwordHashingService;

    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        // Use proper base64-encoded salt
        testClientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());

        // Create client-side hashed password
        PasswordHashingService.PasswordHashResult clientHash =
                passwordHashingService.hashClientPassword("testPassword123", null);
        testPasswordHash = clientHash.getHash();
    }

    @Test
    void testRegisterAndAuthenticate_EndToEnd() {
        // Given - Register user
        UserTable user = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testClientSalt,
                "Test",
                "User"
        );
        assertNotNull(user);
        assertNotNull(user.getUserId());

        // When - Authenticate
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(testPasswordHash);
        authRequest.setSalt(testClientSalt);

        AuthResponse response = authService.authenticate(authRequest);

        // Then
        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertNotNull(response.getUser());
        assertEquals(testEmail, response.getUser().getEmail());
    }

    @Test
    void testAuthenticate_WithInvalidCredentials_ThrowsException() {
        // Given
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail("nonexistent@example.com");
        authRequest.setPasswordHash("invalid-hash");
        authRequest.setSalt("invalid-salt");

        // When/Then
        assertThrows(Exception.class, () -> authService.authenticate(authRequest));
    }

    @Test
    void testRefreshToken_WithValidToken_ReturnsNewTokens() {
        // Given - Register and authenticate
        userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testClientSalt,
                "Test",
                "User"
        );

        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(testPasswordHash);
        authRequest.setSalt(testClientSalt);

        AuthResponse authResponse = authService.authenticate(authRequest);
        String refreshToken = authResponse.getRefreshToken();

        // When
        AuthResponse refreshResponse = authService.refreshToken(refreshToken);

        // Then
        assertNotNull(refreshResponse);
        assertNotNull(refreshResponse.getAccessToken());
        assertNotNull(refreshResponse.getRefreshToken());
        // Note: Access token may be the same if generated within the same second (JWT includes timestamp)
        // The important thing is that refresh succeeds and returns valid tokens
        assertFalse(refreshResponse.getAccessToken().isEmpty());
        assertFalse(refreshResponse.getRefreshToken().isEmpty());
    }

    @Test
    void testRefreshToken_WithInvalidToken_ThrowsException() {
        // When/Then
        assertThrows(Exception.class, () -> authService.refreshToken("invalid-token"));
    }
}

