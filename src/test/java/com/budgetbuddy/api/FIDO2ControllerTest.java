package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.FIDO2Service;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for FIDO2Controller
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FIDO2ControllerTest {

    @Mock
    private FIDO2Service fido2Service;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private FIDO2Controller controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGenerateRegistrationChallenge_WithValidUser_ReturnsChallenge() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        com.yubico.webauthn.data.PublicKeyCredentialCreationOptions mockOptions = 
                mock(com.yubico.webauthn.data.PublicKeyCredentialCreationOptions.class);
        com.yubico.webauthn.data.ByteArray mockChallenge = mock(com.yubico.webauthn.data.ByteArray.class);
        when(mockChallenge.getBase64Url()).thenReturn("challenge-base64");
        when(mockOptions.getChallenge()).thenReturn(mockChallenge);
        
        FIDO2Service.RegistrationChallengeResult result = 
                new FIDO2Service.RegistrationChallengeResult(mockOptions);
        
        when(fido2Service.generateRegistrationChallenge("user-123", "test@example.com")).thenReturn(result);

        // When
        ResponseEntity<Map<String, Object>> response = controller.generateRegistrationChallenge(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("challenge"));
    }

    @Test
    void testGenerateRegistrationChallenge_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.generateRegistrationChallenge(null));
    }

    @Test
    void testVerifyRegistration_WithValidRequest_ReturnsSuccess() {
        // Given
        FIDO2Controller.RegisterPasskeyRequest request = new FIDO2Controller.RegisterPasskeyRequest();
        request.setCredentialJson("{\"credential\":\"test\"}");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(fido2Service.verifyRegistration("user-123", "{\"credential\":\"test\"}")).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = controller.verifyRegistration(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    void testVerifyRegistration_WithInvalidCredential_ThrowsException() {
        // Given
        FIDO2Controller.RegisterPasskeyRequest request = new FIDO2Controller.RegisterPasskeyRequest();
        request.setCredentialJson("{\"credential\":\"invalid\"}");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(fido2Service.verifyRegistration("user-123", "{\"credential\":\"invalid\"}")).thenReturn(false);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controller.verifyRegistration(userDetails, request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testVerifyRegistration_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.verifyRegistration(userDetails, null));
    }

    @Test
    void testGenerateAuthenticationChallenge_WithValidUserId_ReturnsChallenge() {
        // Given
        FIDO2Controller.AuthenticateChallengeRequest request = new FIDO2Controller.AuthenticateChallengeRequest();
        request.setUserId("user-123");

        com.yubico.webauthn.data.PublicKeyCredentialRequestOptions mockOptions = 
                mock(com.yubico.webauthn.data.PublicKeyCredentialRequestOptions.class);
        com.yubico.webauthn.data.ByteArray mockChallenge = mock(com.yubico.webauthn.data.ByteArray.class);
        when(mockChallenge.getBase64Url()).thenReturn("auth-challenge-base64");
        when(mockOptions.getChallenge()).thenReturn(mockChallenge);
        
        FIDO2Service.AuthenticationChallengeResult result = 
                new FIDO2Service.AuthenticationChallengeResult(mockOptions);

        when(fido2Service.generateAuthenticationChallenge("user-123")).thenReturn(result);

        // When
        ResponseEntity<Map<String, Object>> response = controller.generateAuthenticationChallenge(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("challenge"));
    }

    @Test
    void testGenerateAuthenticationChallenge_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.generateAuthenticationChallenge(null));
    }

    @Test
    void testVerifyAuthentication_WithValidRequest_ReturnsSuccess() {
        // Given
        FIDO2Controller.AuthenticatePasskeyRequest request = new FIDO2Controller.AuthenticatePasskeyRequest();
        request.setUserId("user-123");
        request.setCredentialJson("{\"credential\":\"test\"}");

        when(fido2Service.verifyAuthentication("user-123", "{\"credential\":\"test\"}")).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = controller.verifyAuthentication(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    void testVerifyAuthentication_WithInvalidCredential_ThrowsException() {
        // Given
        FIDO2Controller.AuthenticatePasskeyRequest request = new FIDO2Controller.AuthenticatePasskeyRequest();
        request.setUserId("user-123");
        request.setCredentialJson("{\"credential\":\"invalid\"}");

        when(fido2Service.verifyAuthentication("user-123", "{\"credential\":\"invalid\"}")).thenReturn(false);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controller.verifyAuthentication(request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testListPasskeys_WithValidUser_ReturnsPasskeys() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        FIDO2Service.PasskeyInfo passkey = new FIDO2Service.PasskeyInfo("cred-123", java.time.Instant.now());
        List<FIDO2Service.PasskeyInfo> passkeys = Arrays.asList(passkey);
        
        when(fido2Service.listPasskeys("user-123")).thenReturn(passkeys);

        // When
        ResponseEntity<Map<String, Object>> response = controller.listPasskeys(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void testDeletePasskey_WithValidCredentialId_ReturnsNoContent() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        doNothing().when(fido2Service).deletePasskey("user-123", "cred-123");

        // When
        ResponseEntity<Void> response = controller.deletePasskey(userDetails, "cred-123");

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(fido2Service).deletePasskey("user-123", "cred-123");
    }

    @Test
    void testDeletePasskey_WithEmptyCredentialId_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.deletePasskey(userDetails, ""));
    }
}

