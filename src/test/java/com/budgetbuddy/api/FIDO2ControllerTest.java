package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.FIDO2Service;
import com.budgetbuddy.service.UserService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for FIDO2Controller */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FIDO2ControllerTest {

    private static final String USER_123 = "user-123";

    @Mock private FIDO2Service fido2Service;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private FIDO2Controller controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(USER_123);
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGenerateRegistrationChallengeWithValidUserReturnsChallenge() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        final com.yubico.webauthn.data.PublicKeyCredentialCreationOptions mockOptions =
                mock(com.yubico.webauthn.data.PublicKeyCredentialCreationOptions.class);
        final com.yubico.webauthn.data.ByteArray mockChallenge =
                mock(com.yubico.webauthn.data.ByteArray.class);
        when(mockChallenge.getBase64Url()).thenReturn("challenge-base64");
        when(mockOptions.getChallenge()).thenReturn(mockChallenge);

        final FIDO2Service.RegistrationChallengeResult result =
                new FIDO2Service.RegistrationChallengeResult(mockOptions);

        when(fido2Service.generateRegistrationChallenge(USER_123, "test@example.com"))
                .thenReturn(result);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.generateRegistrationChallenge(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("challenge"));
    }

    @Test
    void testGenerateRegistrationChallengeWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.generateRegistrationChallenge(null));
    }

    @Test
    void testVerifyRegistrationWithValidRequestReturnsSuccess() {
        // Given
        final FIDO2Controller.RegisterPasskeyRequest request =
                new FIDO2Controller.RegisterPasskeyRequest();
        request.setCredentialJson("{\"credential\":\"test\"}");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(fido2Service.verifyRegistration(USER_123, "{\"credential\":\"test\"}"))
                .thenReturn(true);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.verifyRegistration(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    void testVerifyRegistrationWithInvalidCredentialThrowsException() {
        // Given
        final FIDO2Controller.RegisterPasskeyRequest request =
                new FIDO2Controller.RegisterPasskeyRequest();
        request.setCredentialJson("{\"credential\":\"invalid\"}");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(fido2Service.verifyRegistration(USER_123, "{\"credential\":\"invalid\"}"))
                .thenReturn(false);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> controller.verifyRegistration(userDetails, request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testVerifyRegistrationWithNullRequestThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.verifyRegistration(userDetails, null));
    }

    @Test
    void testGenerateAuthenticationChallengeWithValidUserIdReturnsChallenge() {
        // Given
        final FIDO2Controller.AuthenticateChallengeRequest request =
                new FIDO2Controller.AuthenticateChallengeRequest();
        request.setUserId(USER_123);

        final com.yubico.webauthn.data.PublicKeyCredentialRequestOptions mockOptions =
                mock(com.yubico.webauthn.data.PublicKeyCredentialRequestOptions.class);
        final com.yubico.webauthn.data.ByteArray mockChallenge =
                mock(com.yubico.webauthn.data.ByteArray.class);
        when(mockChallenge.getBase64Url()).thenReturn("auth-challenge-base64");
        when(mockOptions.getChallenge()).thenReturn(mockChallenge);

        final FIDO2Service.AuthenticationChallengeResult result =
                new FIDO2Service.AuthenticationChallengeResult(mockOptions);

        when(fido2Service.generateAuthenticationChallenge(USER_123)).thenReturn(result);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.generateAuthenticationChallenge(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("challenge"));
    }

    @Test
    void testGenerateAuthenticationChallengeWithNullRequestThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.generateAuthenticationChallenge(null));
    }

    @Test
    void testVerifyAuthenticationWithValidRequestReturnsSuccess() {
        // Given
        final FIDO2Controller.AuthenticatePasskeyRequest request =
                new FIDO2Controller.AuthenticatePasskeyRequest();
        request.setUserId(USER_123);
        request.setCredentialJson("{\"credential\":\"test\"}");

        when(fido2Service.verifyAuthentication(USER_123, "{\"credential\":\"test\"}"))
                .thenReturn(true);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.verifyAuthentication(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    void testVerifyAuthenticationWithInvalidCredentialThrowsException() {
        // Given
        final FIDO2Controller.AuthenticatePasskeyRequest request =
                new FIDO2Controller.AuthenticatePasskeyRequest();
        request.setUserId(USER_123);
        request.setCredentialJson("{\"credential\":\"invalid\"}");

        when(fido2Service.verifyAuthentication(USER_123, "{\"credential\":\"invalid\"}"))
                .thenReturn(false);

        // When/Then
        final AppException exception =
                assertThrows(AppException.class, () -> controller.verifyAuthentication(request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testListPasskeysWithValidUserReturnsPasskeys() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        final FIDO2Service.PasskeyInfo passkey =
                new FIDO2Service.PasskeyInfo("cred-123", java.time.Instant.now());
        final List<FIDO2Service.PasskeyInfo> passkeys = Arrays.asList(passkey);

        when(fido2Service.listPasskeys(USER_123)).thenReturn(passkeys);

        // When
        final ResponseEntity<Map<String, Object>> response = controller.listPasskeys(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void testDeletePasskeyWithValidCredentialIdReturnsNoContent() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        doNothing().when(fido2Service).deletePasskey(USER_123, "cred-123");

        // When
        final ResponseEntity<Void> response = controller.deletePasskey(userDetails, "cred-123");

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(fido2Service).deletePasskey(USER_123, "cred-123");
    }

    @Test
    void testDeletePasskeyWithEmptyCredentialIdThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.deletePasskey(userDetails, ""));
    }
}
