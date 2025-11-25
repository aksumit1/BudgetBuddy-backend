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
 */
@ExtendWith(MockitoExtension.class)
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
    void testRegisterUser_WithValidRequest_ReturnsCreated() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);

        // When
        ResponseEntity<AuthResponse> response = authController.registerUser(testAuthRequest);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(userService, times(1)).createUserSecure(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testAuthenticateUser_WithValidCredentials_ReturnsOk() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);

        // When
        ResponseEntity<AuthResponse> response = authController.authenticateUser(testAuthRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).authenticate(any(AuthRequest.class));
    }

    @Test
    void testRefreshToken_WithValidToken_ReturnsOk() {
        // Given
        String refreshToken = "valid-refresh-token";
        when(authService.refreshToken(refreshToken)).thenReturn(testAuthResponse);

        // When
        ResponseEntity<AuthResponse> response = authController.refreshToken(refreshToken);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).refreshToken(refreshToken);
    }

    @Test
    void testRegisterUser_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(Exception.class, () -> {
            authController.registerUser(null);
        });
    }
}

