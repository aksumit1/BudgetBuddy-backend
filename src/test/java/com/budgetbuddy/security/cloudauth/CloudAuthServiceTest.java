package com.budgetbuddy.security.cloudauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/** Unit Tests for CloudAuth Service */
@ExtendWith(MockitoExtension.class)
class CloudAuthServiceTest {

    private static final String PASSWORD123 = "password123";

    @Mock private CognitoIdentityProviderClient cognitoClient;

    private CloudAuthService service;
    private String userPoolId = "us-east-1_testPool";
    private String clientId = "testClientId";

    @BeforeEach
    void setUp() {
        service = new CloudAuthService(cognitoClient, userPoolId, clientId);
    }

    @Test
    void testAuthenticateWithValidCredentialsReturnsSuccess() {
        // Given
        final AuthenticationResultType authResult =
                AuthenticationResultType.builder()
                        .accessToken("access-token")
                        .idToken("id-token")
                        .refreshToken("refresh-token")
                        .expiresIn(3600)
                        .build();

        final AdminInitiateAuthResponse response =
                AdminInitiateAuthResponse.builder().authenticationResult(authResult).build();

        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenReturn(response);

        // When
        final CloudAuthService.CloudAuthResult result =
                service.authenticate("test@example.com", PASSWORD123);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("access-token", result.getAccessToken());
        assertEquals("id-token", result.getIdToken());
        assertEquals("refresh-token", result.getRefreshToken());
        assertEquals(3600, result.getExpiresIn());
        verify(cognitoClient).adminInitiateAuth(any(AdminInitiateAuthRequest.class));
    }

    @Test
    void testAuthenticateWithInvalidCredentialsReturnsFailure() {
        // Given
        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().build());

        // When
        final CloudAuthService.CloudAuthResult result =
                service.authenticate("test@example.com", "wrongpassword");

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Invalid credentials", result.getError());
    }

    @Test
    void testAuthenticateWithExceptionReturnsFailure() {
        // Given
        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final CloudAuthService.CloudAuthResult result =
                service.authenticate("test@example.com", PASSWORD123);

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Authentication service error", result.getError());
    }

    @Test
    void testRegisterWithValidInputReturnsSuccess() {
        // Given
        final UserType user = UserType.builder().username("test@example.com").build();

        final AdminCreateUserResponse createResponse =
                AdminCreateUserResponse.builder().user(user).build();

        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(createResponse);
        when(cognitoClient.adminSetUserPassword(any(AdminSetUserPasswordRequest.class)))
                .thenReturn(AdminSetUserPasswordResponse.builder().build());

        // When
        final CloudAuthService.CloudAuthResult result =
                service.register("test@example.com", PASSWORD123, "John", "Doe");

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("test@example.com", result.getUserId());
        verify(cognitoClient).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(cognitoClient).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
    }

    @Test
    void testRegisterWithExistingUserReturnsFailure() {
        // Given
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(UsernameExistsException.builder().build());

        // When
        final CloudAuthService.CloudAuthResult result =
                service.register("test@example.com", PASSWORD123, "John", "Doe");

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("User already exists", result.getError());
    }

    @Test
    void testRegisterWithExceptionReturnsFailure() {
        // Given
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final CloudAuthService.CloudAuthResult result =
                service.register("test@example.com", PASSWORD123, "John", "Doe");

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Registration service error", result.getError());
    }

    @Test
    void testVerifyTokenWithValidTokenReturnsTrue() {
        // Given
        final GetUserResponse response =
                GetUserResponse.builder().username("test@example.com").build();

        when(cognitoClient.getUser(any(GetUserRequest.class))).thenReturn(response);

        // When
        final boolean isValid = service.verifyToken("valid-token");

        // Then
        assertTrue(isValid);
        verify(cognitoClient).getUser(any(GetUserRequest.class));
    }

    @Test
    void testVerifyTokenWithInvalidTokenReturnsFalse() {
        // Given
        when(cognitoClient.getUser(any(GetUserRequest.class)))
                .thenThrow(new RuntimeException("Invalid token"));

        // When
        final boolean isValid = service.verifyToken("invalid-token");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testCloudAuthResultSettersAndGetters() {
        // Given
        final CloudAuthService.CloudAuthResult result = new CloudAuthService.CloudAuthResult();

        // When
        result.setSuccess(true);
        result.setUserId("user-123");
        result.setAccessToken("access-token");
        result.setIdToken("id-token");
        result.setRefreshToken("refresh-token");
        result.setExpiresIn(3600);
        result.setError("error");

        // Then
        assertTrue(result.isSuccess());
        assertEquals("user-123", result.getUserId());
        assertEquals("access-token", result.getAccessToken());
        assertEquals("id-token", result.getIdToken());
        assertEquals("refresh-token", result.getRefreshToken());
        assertEquals(3600, result.getExpiresIn());
        assertEquals("error", result.getError());
    }
}
