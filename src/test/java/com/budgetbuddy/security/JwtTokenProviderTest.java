package com.budgetbuddy.security;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for JwtTokenProvider
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class JwtTokenProviderTest {

    @Mock
    private SecretsManagerService secretsManagerService;

    @Mock
    private Authentication authentication;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private JwtTokenProvider jwtTokenProvider;

    private static final String TEST_SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha512-algorithm";
    private static final String TEST_USERNAME = "test@example.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretFallback", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretName", "test/jwt-secret");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpiration", 86400000L); // 24 hours
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpiration", 604800000L); // 7 days
    }

    @Test
    void testGenerateToken_WithValidAuthentication_ReturnsToken() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        String token = jwtTokenProvider.generateToken(authentication);

        // Then
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testGenerateRefreshToken_WithValidUsername_ReturnsToken() {
        // Given
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        String token = jwtTokenProvider.generateRefreshToken(TEST_USERNAME);

        // Then
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testValidateToken_WithValidToken_ReturnsTrue() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        String token = jwtTokenProvider.generateToken(authentication);

        // When
        boolean isValid = jwtTokenProvider.validateToken(token);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testValidateToken_WithInvalidToken_ReturnsFalse() {
        // Given
        String invalidToken = "invalid.token.here";
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        boolean isValid = jwtTokenProvider.validateToken(invalidToken);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGetUsernameFromToken_WithValidToken_ReturnsUsername() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        String token = jwtTokenProvider.generateToken(authentication);

        // When
        String username = jwtTokenProvider.getUsernameFromToken(token);

        // Then
        assertEquals(TEST_USERNAME, username);
    }

    @Test
    void testGetExpirationDateFromToken_WithValidToken_ReturnsDate() {
        // Given
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(TEST_USERNAME);
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);
        String token = jwtTokenProvider.generateToken(authentication);

        // When
        Date expiration = jwtTokenProvider.getExpirationDateFromToken(token);

        // Then
        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()));
    }

    @Test
    void testGetSecret_FromSecretsManager_ReturnsSecret() {
        // Given
        String secretFromManager = "secret-from-manager";
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(secretFromManager);

        // When
        String secret = (String) ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");

        // Then
        assertEquals(secretFromManager, secret);
    }

    @Test
    void testGetSecret_FromFallback_ReturnsFallback() {
        // Given
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When
        String secret = (String) ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");

        // Then
        assertEquals(TEST_SECRET, secret);
    }

    @Test
    void testGetSecret_WhenNotConfigured_ThrowsException() {
        // Given
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecretFallback", "");
        when(secretsManagerService.getSecret(anyString(), anyString())).thenReturn(null);

        // When/Then
        assertThrows(IllegalStateException.class, () -> {
            ReflectionTestUtils.invokeMethod(jwtTokenProvider, "getJwtSecret");
        });
    }
}

