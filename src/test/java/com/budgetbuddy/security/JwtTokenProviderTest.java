package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for JwtTokenProvider */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class JwtTokenProviderTest {

    @Mock private SecretsManagerService secretsManagerService;

    @Mock private Authentication authentication;

    @Mock private UserDetails userDetails;

    @InjectMocks private JwtTokenProvider jwtTokenProvider;

    private static final String TEST_SECRET =
            "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha512-algorithm";
    private static final String TEST_USERNAME = "test@example.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretFallback", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretName", "test/jwt-secret");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpiration", 86_400_000L); // 24 hours
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpiration", 604_800_000L); // 7 days
    }

    @Test
    void testGenerateTokenWithValidAuthenticationReturnsToken() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        final String token = jwtTokenProvider.generateToken(authentication);

        // Then
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testGenerateRefreshTokenWithValidUsernameReturnsToken() {
        // Given
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        final String token = jwtTokenProvider.generateRefreshToken(TEST_USERNAME);

        // Then
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testValidateTokenWithValidTokenReturnsTrue() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        final String token = jwtTokenProvider.generateToken(authentication);

        // When
        final boolean isValid = jwtTokenProvider.validateToken(token);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testValidateTokenWithInvalidTokenReturnsFalse() {
        // Given
        final String invalidToken = "invalid.token.here";
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        final boolean isValid = jwtTokenProvider.validateToken(invalidToken);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGetUsernameFromTokenWithValidTokenReturnsUsername() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        final String token = jwtTokenProvider.generateToken(authentication);

        // When
        final String username = jwtTokenProvider.getUsernameFromToken(token);

        // Then
        assertEquals(TEST_USERNAME, username);
    }

    @Test
    void testGetExpirationDateFromTokenWithValidTokenReturnsDate() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        final String token = jwtTokenProvider.generateToken(authentication);

        // When
        final Date expiration = jwtTokenProvider.getExpirationDateFromToken(token);

        // Then
        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()));
    }

    @Test
    void testGetSecretFromSecretsManagerReturnsSecret() {
        // Given
        final String secretFromManager = "secret-from-manager";
        when(secretsManagerService.getSecret(anyString(), anyString()))
                .thenReturn(secretFromManager);

        // When
        final String secret = (String) ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");

        // Then
        assertEquals(secretFromManager, secret);
    }

    @Test
    void testGetSecretFromFallbackReturnsFallback() {
        // Given
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        final String secret = (String) ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");

        // Then
        assertEquals(TEST_SECRET, secret);
    }

    @Test
    void testGetSecretWhenNotConfiguredThrowsException() {
        // Given
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretFallback", "");
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When/Then
        assertThrows(
                IllegalStateException.class,
                () -> {
                    ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");
                });
    }
}
