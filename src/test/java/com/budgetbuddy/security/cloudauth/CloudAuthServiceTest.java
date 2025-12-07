package com.budgetbuddy.security.cloudauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for CloudAuth Service
 */
@ExtendWith(MockitoExtension.class)
class CloudAuthServiceTest {

    @Mock
    private CognitoIdentityProviderClient cognitoClient;

    private CloudAuthService service;
    private String userPoolId = "us-east-1_testPool";
    private String clientId = "testClientId";

    @BeforeEach
    void setUp() {
        service = new CloudAuthService(cognitoClient, userPoolId, clientId);
    }

    @Test
    void testAuthenticate_WithValidCredentials_ReturnsSuccess() {
        // Given
        AuthenticationResultType authResult = AuthenticationResultType.builder()
                .accessToken("access-token")
                .idToken("id-token")
                .refreshToken("refresh-token")
                .expiresIn(3600)
                .build();
        
        AdminInitiateAuthResponse response = AdminInitiateAuthResponse.builder()
                .authenticationResult(authResult)
                .build();
        
        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenReturn(response);
        
        // When
        CloudAuthService.CloudAuthResult result = service.authenticate("test@example.com", "password123");
        
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
    void testAuthenticate_WithInvalidCredentials_ReturnsFailure() {
        // Given
        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().build());
        
        // When
        CloudAuthService.CloudAuthResult result = service.authenticate("test@example.com", "wrongpassword");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Invalid credentials", result.getError());
    }

    @Test
    void testAuthenticate_WithException_ReturnsFailure() {
        // Given
        when(cognitoClient.adminInitiateAuth(any(AdminInitiateAuthRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        CloudAuthService.CloudAuthResult result = service.authenticate("test@example.com", "password123");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Authentication service error", result.getError());
    }

    @Test
    void testRegister_WithValidInput_ReturnsSuccess() {
        // Given
        UserType user = UserType.builder()
                .username("test@example.com")
                .build();
        
        AdminCreateUserResponse createResponse = AdminCreateUserResponse.builder()
                .user(user)
                .build();
        
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(createResponse);
        when(cognitoClient.adminSetUserPassword(any(AdminSetUserPasswordRequest.class)))
                .thenReturn(AdminSetUserPasswordResponse.builder().build());
        
        // When
        CloudAuthService.CloudAuthResult result = service.register("test@example.com", "password123", "John", "Doe");
        
        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("test@example.com", result.getUserId());
        verify(cognitoClient).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(cognitoClient).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
    }

    @Test
    void testRegister_WithExistingUser_ReturnsFailure() {
        // Given
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(UsernameExistsException.builder().build());
        
        // When
        CloudAuthService.CloudAuthResult result = service.register("test@example.com", "password123", "John", "Doe");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("User already exists", result.getError());
    }

    @Test
    void testRegister_WithException_ReturnsFailure() {
        // Given
        when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        CloudAuthService.CloudAuthResult result = service.register("test@example.com", "password123", "John", "Doe");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Registration service error", result.getError());
    }

    @Test
    void testVerifyToken_WithValidToken_ReturnsTrue() {
        // Given
        GetUserResponse response = GetUserResponse.builder()
                .username("test@example.com")
                .build();
        
        when(cognitoClient.getUser(any(GetUserRequest.class)))
                .thenReturn(response);
        
        // When
        boolean isValid = service.verifyToken("valid-token");
        
        // Then
        assertTrue(isValid);
        verify(cognitoClient).getUser(any(GetUserRequest.class));
    }

    @Test
    void testVerifyToken_WithInvalidToken_ReturnsFalse() {
        // Given
        when(cognitoClient.getUser(any(GetUserRequest.class)))
                .thenThrow(new RuntimeException("Invalid token"));
        
        // When
        boolean isValid = service.verifyToken("invalid-token");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testCloudAuthResult_SettersAndGetters() {
        // Given
        CloudAuthService.CloudAuthResult result = new CloudAuthService.CloudAuthResult();
        
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

