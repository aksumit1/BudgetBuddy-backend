package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for OAuth2Controller
 */
@ExtendWith(MockitoExtension.class)
class OAuth2ControllerTest {

    private OAuth2Controller controller;
    private OAuth2Controller controllerDisabled;

    @BeforeEach
    void setUp() {
        controller = new OAuth2Controller(true); // OAuth2 enabled
        controllerDisabled = new OAuth2Controller(false); // OAuth2 disabled
    }

    @Test
    void testGetOAuth2Config_WithOAuth2Enabled_ReturnsConfig() {
        // When
        ResponseEntity<OAuth2Controller.OAuth2ConfigResponse> response = controller.getOAuth2Config();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getAuthorizationEndpoint());
        assertNotNull(response.getBody().getTokenEndpoint());
        assertNotNull(response.getBody().getClientId());
        assertNotNull(response.getBody().getScopes());
    }

    @Test
    void testGetOAuth2Config_WithOAuth2Disabled_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controllerDisabled.getOAuth2Config());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void testGetUserInfo_WithValidJwt_ReturnsUserInfo() {
        // Given
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("user-123");
        when(jwt.getClaimAsString("email")).thenReturn("test@example.com");
        when(jwt.getClaimAsString("name")).thenReturn("Test User");
        when(jwt.getClaimAsString("preferred_username")).thenReturn("testuser");

        // When
        ResponseEntity<Map<String, Object>> response = controller.getUserInfo(jwt);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("user-123", response.getBody().get("sub"));
        assertEquals("test@example.com", response.getBody().get("email"));
    }

    @Test
    void testGetUserInfo_WithNullJwt_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controller.getUserInfo(null));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    void testGetUserInfo_WithNullClaims_HandlesGracefully() {
        // Given
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(null);
        when(jwt.getClaimAsString("email")).thenReturn(null);
        when(jwt.getClaimAsString("name")).thenReturn(null);
        when(jwt.getClaimAsString("preferred_username")).thenReturn(null);

        // When
        ResponseEntity<Map<String, Object>> response = controller.getUserInfo(jwt);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("unknown", response.getBody().get("sub"));
        assertEquals("", response.getBody().get("email"));
    }
}

