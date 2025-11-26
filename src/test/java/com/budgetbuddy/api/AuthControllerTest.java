package com.budgetbuddy.api;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuthController
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    private AuthRequest testAuthRequest;
    private AuthResponse testAuthResponse;

    @BeforeEach
    void setUp() {
        testAuthRequest = new AuthRequest();
        testAuthRequest.setEmail("test@example.com");
        testAuthRequest.setPasswordHash("hashed-password");
        testAuthRequest.setSalt("client-salt");

        testAuthResponse = new AuthResponse(
                "access-token",
                "refresh-token",
                LocalDateTime.now().plusHours(24),
                new AuthResponse.UserInfo(
                        UUID.randomUUID().toString(),
                        "test@example.com",
                        "Test",
                        "User"
                )
        );
    }

    @Test
    void testRegister_WithValidRequest_ReturnsOk() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);
        com.budgetbuddy.model.dynamodb.UserTable testUserTable = new com.budgetbuddy.model.dynamodb.UserTable();
        testUserTable.setUserId(UUID.randomUUID().toString());
        // AuthController.register calls createUserSecure with null for firstName and lastName
        when(userService.createUserSecure(anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(testUserTable);

        // When
        ResponseEntity<AuthResponse> response = authController.register(testAuthRequest);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode()); // register returns CREATED, not OK
        assertNotNull(response.getBody());
        verify(userService, times(1)).createUserSecure(anyString(), anyString(), anyString(), isNull(), isNull());
        verify(authService, times(1)).authenticate(any(AuthRequest.class));
    }

    @Test
    void testLogin_WithValidCredentials_ReturnsOk() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);

        // When
        ResponseEntity<AuthResponse> response = authController.login(testAuthRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).authenticate(any(AuthRequest.class));
    }

    @Test
    void testRefreshToken_WithValidToken_ReturnsOk() {
        // Given
        AuthController.RefreshTokenRequest request = new AuthController.RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");
        when(authService.refreshToken("valid-refresh-token")).thenReturn(testAuthResponse);

        // When
        ResponseEntity<AuthResponse> response = authController.refreshToken(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).refreshToken("valid-refresh-token");
    }

    @Test
    void testRegister_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(Exception.class, () -> {
            authController.register(null);
        });
    }
}

