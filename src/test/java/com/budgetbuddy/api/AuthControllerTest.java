package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit Tests for AuthController */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthControllerTest {

    @Mock private AuthService authService;

    @Mock private UserService userService;

    @Mock private com.budgetbuddy.service.PasswordResetService passwordResetService;

    @Mock private com.budgetbuddy.service.ChallengeService challengeService;

    @InjectMocks private AuthController authController;

    private AuthRequest testAuthRequest;
    private AuthResponse testAuthResponse;

    @BeforeEach
    void setUp() {
        testAuthRequest = new AuthRequest();
        testAuthRequest.setEmail("test@example.com");
        testAuthRequest.setPasswordHash("hashed-password");
        testAuthRequest.setChallenge("test-challenge-nonce"); // PAKE2: Challenge required
        // BREAKING CHANGE: Client salt removed

        testAuthResponse =
                new AuthResponse(
                        "access-token",
                        "refresh-token",
                        LocalDateTime.now().plusHours(24),
                        new AuthResponse.UserInfo(
                                UUID.randomUUID().toString(), "test@example.com", "Test", "User"));

        // Mock challenge service to allow verification
        final com.budgetbuddy.service.ChallengeService.ChallengeResponse challengeResponse =
                new com.budgetbuddy.service.ChallengeService.ChallengeResponse(
                        "test-challenge-nonce",
                        java.time.Instant.now().plus(5, java.time.temporal.ChronoUnit.MINUTES));
        when(challengeService.generateChallenge(anyString())).thenReturn(challengeResponse);
        doNothing().when(challengeService).verifyAndConsumeChallenge(anyString(), anyString());
    }

    @Test
    void testRegisterWithValidRequestReturnsOk() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);
        final com.budgetbuddy.model.dynamodb.UserTable testUserTable =
                new com.budgetbuddy.model.dynamodb.UserTable();
        testUserTable.setUserId(UUID.randomUUID().toString());
        // AuthController.register calls createUserSecure with null for firstName and lastName
        when(userService.createUserSecure(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(testUserTable);

        // When
        final ResponseEntity<AuthResponse> response = authController.register(testAuthRequest);

        // Then
        assertEquals(
                HttpStatus.CREATED, response.getStatusCode()); // register returns CREATED, not OK
        assertNotNull(response.getBody());
        verify(userService, times(1))
                .createUserSecure(anyString(), anyString(), isNull(), isNull());
        verify(authService, times(1)).authenticate(any(AuthRequest.class));
    }

    @Test
    void testLoginWithValidCredentialsReturnsOk() {
        // Given
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(testAuthResponse);

        // When
        final ResponseEntity<AuthResponse> response = authController.login(testAuthRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).authenticate(any(AuthRequest.class));
    }

    @Test
    void testRefreshTokenWithValidTokenReturnsOk() {
        // Given
        final AuthController.RefreshTokenRequest request = new AuthController.RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");
        when(authService.refreshToken("valid-refresh-token")).thenReturn(testAuthResponse);

        // When
        final ResponseEntity<AuthResponse> response = authController.refreshToken(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(authService, times(1)).refreshToken("valid-refresh-token");
    }

    @Test
    void testRegisterWithNullRequestThrowsException() {
        // When/Then
        assertThrows(
                Exception.class,
                () -> {
                    authController.register(null);
                });
    }
}
