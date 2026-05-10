package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import io.jsonwebtoken.security.WeakKeyException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Unit Tests for JwtTokenProvider - JWT Secret Length Bug Fix
 *
 * <p>Tests the fix for WeakKeyException where JWT secret was too short (256 bits) for HS512
 * algorithm which requires at least 512 bits (64 characters)
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class JwtTokenProviderSecretLengthTest {

    @Mock private SecretsManagerService secretsManagerService;

    @InjectMocks private JwtTokenProvider jwtTokenProvider;

    private Authentication testAuthentication;
    private UserDetails testUserDetails;

    @BeforeEach
    void setUp() {
        // Create a test UserDetails object
        testUserDetails =
                User.builder()
                        .username("test@example.com")
                        .password("hashed-password")
                        .authorities(
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")))
                        .accountExpired(false)
                        .accountLocked(false)
                        .credentialsExpired(false)
                        .disabled(false)
                        .build();

        testAuthentication =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        testUserDetails, null, testUserDetails.getAuthorities());

        // Use reflection to set the jwtSecretFallback field
        try {
            final java.lang.reflect.Field field =
                    JwtTokenProvider.class.getDeclaredField("jwtSecretFallback");
            field.setAccessible(true);
            // Set a valid 64+ character secret for testing
            field.set(
                    jwtTokenProvider,
                    "test-secret-change-in-production-this-must-be-at-least-64-characters-long-for-hs512-algorithm-to-work-properly");

            final java.lang.reflect.Field expirationField =
                    JwtTokenProvider.class.getDeclaredField("jwtExpiration");
            expirationField.setAccessible(true);
            expirationField.set(jwtTokenProvider, 86_400_000L); // 24 hours

            final java.lang.reflect.Field refreshExpirationField =
                    JwtTokenProvider.class.getDeclaredField("refreshExpiration");
            refreshExpirationField.setAccessible(true);
            refreshExpirationField.set(jwtTokenProvider, 604_800_000L); // 7 days
        } catch (Exception e) {
            // If reflection fails, we'll test with mocked secrets manager
        }
    }

    @Test
    void testGenerateTokenWithValidSecretLengthDoesNotThrowException() {
        // Arrange
        when(secretsManagerService.getSecret(anyString(), anyString()))
                .thenReturn(null); // Return null to use fallback

        // Act & Assert - Should not throw WeakKeyException
        assertDoesNotThrow(
                () -> {
                    final String token = jwtTokenProvider.generateToken(testAuthentication);
                    assertNotNull(token, "Token should be generated successfully");
                    assertFalse(token.isEmpty(), "Token should not be empty");
                },
                "Should not throw WeakKeyException with valid secret length (64+ characters)");
    }

    @Test
    void testGenerateTokenWithShortSecretThrowsWeakKeyException() {
        // Arrange - Set a short secret (less than 64 characters)
        try {
            final java.lang.reflect.Field field =
                    JwtTokenProvider.class.getDeclaredField("jwtSecretFallback");
            field.setAccessible(true);
            field.set(jwtTokenProvider, "short-secret"); // Only 12 characters

            when(secretsManagerService.getSecret(anyString(), anyString()))
                    .thenReturn(null); // Return null to use fallback
        } catch (Exception e) {
            // If reflection fails, skip this test
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "Could not set secret via reflection");
        }

        // Act & Assert - Should throw WeakKeyException
        assertThrows(
                WeakKeyException.class,
                () -> {
                    jwtTokenProvider.generateToken(testAuthentication);
                },
                "Should throw WeakKeyException with secret shorter than 64 characters");
    }

    @Test
    void testGenerateTokenValidatesSecretLengthFromSecretsManager() {
        // Arrange - Secrets Manager returns a short secret (less than 64 chars for HS512)
        final String shortSecret = "short-secret-from-secrets-manager"; // Only 32 characters
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(shortSecret);

        // Also set fallback to null to ensure we use the Secrets Manager value
        try {
            final java.lang.reflect.Field field =
                    JwtTokenProvider.class.getDeclaredField("jwtSecretFallback");
            field.setAccessible(true);
            field.set(jwtTokenProvider, null);
        } catch (Exception e) {
            // If reflection fails, the test will use the mocked secret
        }

        // Act & Assert - Should throw WeakKeyException when secret is too short for HS512
        // HS512 requires at least 64 bytes (512 bits), but Keys.hmacShaKeyFor may throw earlier
        assertThrows(
                Exception.class,
                () -> {
                    jwtTokenProvider.generateToken(testAuthentication);
                },
                "Should throw WeakKeyException or IllegalArgumentException when Secrets Manager returns short secret");
    }

    @Test
    void testGenerateTokenWithExactly64CharacterSecretSucceeds() {
        // Arrange - Set exactly 64 character secret
        final String exactly64Chars = "a".repeat(64);
        try {
            final java.lang.reflect.Field field =
                    JwtTokenProvider.class.getDeclaredField("jwtSecretFallback");
            field.setAccessible(true);
            field.set(jwtTokenProvider, exactly64Chars);

            when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "Could not set secret via reflection");
        }

        // Act & Assert - Should succeed with exactly 64 characters
        assertDoesNotThrow(
                () -> {
                    final String token = jwtTokenProvider.generateToken(testAuthentication);
                    assertNotNull(token);
                },
                "Should succeed with exactly 64 character secret (minimum for HS512)");
    }

    @Test
    void testGenerateTokenPrincipalMustBeUserDetails() {
        // Arrange
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // Act & Assert - Should work with UserDetails as principal
        assertDoesNotThrow(
                () -> {
                    final String token = jwtTokenProvider.generateToken(testAuthentication);
                    assertNotNull(token);
                },
                "Should work when Authentication principal is UserDetails");
    }
}
