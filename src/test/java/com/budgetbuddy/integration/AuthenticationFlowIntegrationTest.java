package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.PasswordHashingService;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Comprehensive Authentication Flow Integration Tests
 *
 * <p>CRITICAL: These tests verify that password hashing is consistent across registration and
 * login, which would have caught the salt issue where different salts were used each time.
 *
 * <p>Tests: 1. Register -> Sign Out -> Sign In (same password) 2. Sign In -> Sign Out -> Sign In
 * (same password) 3. Password hash consistency verification
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Authentication Flow Integration Tests")
class AuthenticationFlowIntegrationTest {

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private PasswordHashingService passwordHashingService;

    private String testEmail;
    private String testPassword;
    private String testPasswordHash;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        testPassword = "TestPassword123!";

        // CRITICAL: Simulate client-side password hashing with consistent salt
        // This mimics what the iOS app does: derive salt from email, hash password
        // The salt must be consistent for the same email to allow password verification
        final String consistentSalt = deriveConsistentSaltFromEmail(testEmail);
        final PasswordHashingService.PasswordHashResult clientHash =
                passwordHashingService.hashPlaintextPassword(
                        testPassword, java.util.Base64.getDecoder().decode(consistentSalt));
        testPasswordHash = clientHash.getHash();
    }

    /**
     * Simulates iOS client-side salt derivation from email This ensures the same email always
     * produces the same salt
     */
    private String deriveConsistentSaltFromEmail(final String email) {
        try {
            final java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            final byte[] hash =
                    digest.digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            // Take first 16 bytes as salt (matching iOS implementation)
            final byte[] salt = new byte[16];
            System.arraycopy(hash, 0, salt, 0, 16);
            return java.util.Base64.getEncoder().encodeToString(salt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive salt from email", e);
        }
    }

    @Test
    @DisplayName("Register -> Sign Out -> Sign In: Same password should work")
    void testRegisterSignOutSignInWithSamePasswordSucceeds() {
        // Step 1: Register user
        final UserTable user =
                userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");
        assertNotNull(user, "User should be created");
        assertNotNull(user.getUserId(), "User should have userId");
        assertNotNull(user.getPasswordHash(), "User should have password hash");
        assertNotNull(user.getServerSalt(), "User should have server salt");

        // Step 2: Authenticate (simulating sign in after registration)
        final AuthRequest registerAuthRequest = new AuthRequest();
        registerAuthRequest.setEmail(testEmail);
        registerAuthRequest.setPasswordHash(testPasswordHash);

        final AuthResponse registerResponse = authService.authenticate(registerAuthRequest);
        assertNotNull(registerResponse, "Authentication should succeed");
        assertNotNull(registerResponse.getAccessToken(), "Should receive access token");
        assertNotNull(registerResponse.getRefreshToken(), "Should receive refresh token");

        // Step 3: Simulate sign out (clear tokens, but user remains in database)
        // In real app, this would clear tokens from Keychain/UserDefaults
        // For this test, we just verify the user can authenticate again

        // Step 4: Sign in again with the SAME password
        // CRITICAL: This is where the bug would have been caught
        // If salt was different each time, this would fail
        final String loginPasswordHash = testPasswordHash; // Same hash as registration

        final AuthRequest loginAuthRequest = new AuthRequest();
        loginAuthRequest.setEmail(testEmail);
        loginAuthRequest.setPasswordHash(loginPasswordHash);

        final AuthResponse loginResponse = authService.authenticate(loginAuthRequest);

        // Then: Should succeed with same password
        assertNotNull(loginResponse, "Login should succeed with same password");
        assertNotNull(loginResponse.getAccessToken(), "Should receive access token");
        assertNotNull(loginResponse.getRefreshToken(), "Should receive refresh token");
        assertNotNull(loginResponse.getUser(), "Should receive user info");
        assertEquals(testEmail, loginResponse.getUser().getEmail(), "Email should match");
    }

    @Test
    @DisplayName("Sign In -> Sign Out -> Sign In: Same password should work")
    void testSignInSignOutSignInWithSamePasswordSucceeds() {
        // Step 1: Register user first
        final UserTable user =
                userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");
        assertNotNull(user, "User should be created");

        // Step 2: First sign in
        final AuthRequest firstLoginRequest = new AuthRequest();
        firstLoginRequest.setEmail(testEmail);
        firstLoginRequest.setPasswordHash(testPasswordHash);

        final AuthResponse firstLoginResponse = authService.authenticate(firstLoginRequest);
        assertNotNull(firstLoginResponse, "First login should succeed");
        assertNotNull(firstLoginResponse.getAccessToken(), "Should receive access token");

        // Step 3: Simulate sign out (clear tokens)
        // In real app, this would clear tokens from Keychain/UserDefaults

        // Step 4: Sign in again with the SAME password
        // CRITICAL: This verifies password hash consistency
        final AuthRequest secondLoginRequest = new AuthRequest();
        secondLoginRequest.setEmail(testEmail);
        secondLoginRequest.setPasswordHash(testPasswordHash); // Same hash

        final AuthResponse secondLoginResponse = authService.authenticate(secondLoginRequest);

        // Then: Should succeed with same password
        assertNotNull(secondLoginResponse, "Second login should succeed");
        assertNotNull(secondLoginResponse.getAccessToken(), "Should receive access token");
        assertNotNull(secondLoginResponse.getRefreshToken(), "Should receive refresh token");
    }

    @Test
    @DisplayName("Password hash consistency: Same email + password = same client hash")
    void testPasswordHashConsistencySameEmailPasswordProducesSameHash() {
        // Given: Same email and password
        final String email1 = testEmail;
        final String email2 = testEmail; // Same email
        final String password1 = testPassword;
        final String password2 = testPassword; // Same password

        // When: Hash password with consistent salt (derived from email)
        final String salt1 = deriveConsistentSaltFromEmail(email1);
        final String salt2 = deriveConsistentSaltFromEmail(email2);

        assertEquals(salt1, salt2, "Same email should produce same salt");

        final PasswordHashingService.PasswordHashResult hash1 =
                passwordHashingService.hashPlaintextPassword(
                        password1, java.util.Base64.getDecoder().decode(salt1));
        final PasswordHashingService.PasswordHashResult hash2 =
                passwordHashingService.hashPlaintextPassword(
                        password2, java.util.Base64.getDecoder().decode(salt2));

        // Then: Should produce same client hash
        assertEquals(
                hash1.getHash(),
                hash2.getHash(),
                "Same email + password should produce same client hash");
    }

    @Test
    @DisplayName(
            "Password hash inconsistency: Different salts = different hashes (would fail login)")
    void testPasswordHashInconsistencyDifferentSaltsProducesDifferentHashes() {
        // Given: Same password, but different salts (simulating the bug)
        final String password = testPassword;

        // When: Hash with different salts (this is what the bug did)
        final String salt1 =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        final String salt2 =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                new byte[] {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1});

        final PasswordHashingService.PasswordHashResult hash1 =
                passwordHashingService.hashPlaintextPassword(
                        password, java.util.Base64.getDecoder().decode(salt1));
        final PasswordHashingService.PasswordHashResult hash2 =
                passwordHashingService.hashPlaintextPassword(
                        password, java.util.Base64.getDecoder().decode(salt2));

        // Then: Should produce different hashes
        assertNotEquals(
                hash1.getHash(),
                hash2.getHash(),
                "Different salts should produce different hashes");

        // This demonstrates why the bug caused login failures:
        // Registration used salt1, login used salt2 -> different hashes -> login fails
    }

    @Test
    @DisplayName("Multiple sign-ins: Same password should work every time")
    void testMultipleSignInsWithSamePasswordAllSucceed() {
        // Given: Registered user
        final UserTable user =
                userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");
        assertNotNull(user, "User should be created");

        // When: Sign in multiple times with same password
        for (int i = 0; i < 5; i++) {
            final AuthRequest loginRequest = new AuthRequest();
            loginRequest.setEmail(testEmail);
            loginRequest.setPasswordHash(testPasswordHash); // Same hash every time

            final AuthResponse response = authService.authenticate(loginRequest);

            // Then: Each sign-in should succeed
            assertNotNull(response, "Sign-in #" + (i + 1) + " should succeed");
            assertNotNull(response.getAccessToken(), "Should receive access token");
        }
    }
}
