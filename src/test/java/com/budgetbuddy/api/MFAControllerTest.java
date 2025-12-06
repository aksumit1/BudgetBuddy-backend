package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.EmailNotificationService;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.service.MFAService;
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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for MFAController
 * Tests MFA endpoints including TOTP, SMS OTP, Email OTP, and Backup Codes
 */
@ExtendWith(MockitoExtension.class)
class MFAControllerTest {

    @Mock
    private MFAService mfaService;

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private UserDetails userDetails;

    private MFAController mfaController;

    private UserTable testUser;
    private String testUserId;
    private String testEmail;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";
        
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);
        testUser.setPhoneNumber("+1234567890");
        
        // Create controller manually since it requires constructor injection
        mfaController = new MFAController(mfaService, userService, notificationService, emailNotificationService, false);
    }

    @Test
    void testSetupTOTP_WithValidUser_ReturnsSetupResult() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        
        MFAService.TOTPSetupResult setupResult = new MFAService.TOTPSetupResult("TEST_SECRET", "https://example.com/qr");
        when(mfaService.setupTOTP(testUserId, testEmail)).thenReturn(setupResult);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.setupTOTP(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("TEST_SECRET", response.getBody().get("secret"));
        assertEquals("https://example.com/qr", response.getBody().get("qrCodeUrl"));
        verify(mfaService, times(1)).setupTOTP(testUserId, testEmail);
    }

    @Test
    void testSetupTOTP_WithNullUserDetails_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            mfaController.setupTOTP(null);
        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(mfaService, never()).setupTOTP(anyString(), anyString());
    }

    @Test
    void testVerifyTOTP_WithValidCode_EnablesMFA() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyTOTP(testUserId, 123456)).thenReturn(true);
        doNothing().when(mfaService).enableMFA(testUserId);
        
        List<String> backupCodes = Arrays.asList("CODE1", "CODE2", "CODE3");
        when(mfaService.generateBackupCodes(testUserId)).thenReturn(backupCodes);

        MFAController.VerifyTOTPRequest request = new MFAController.VerifyTOTPRequest();
        request.setCode(123456);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.verifyTOTP(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(backupCodes, response.getBody().get("backupCodes"));
        verify(mfaService, times(1)).verifyTOTP(testUserId, 123456);
        verify(mfaService, times(1)).enableMFA(testUserId);
        verify(mfaService, times(1)).generateBackupCodes(testUserId);
    }

    @Test
    void testVerifyTOTP_WithInvalidCode_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyTOTP(testUserId, 123456)).thenReturn(false);

        MFAController.VerifyTOTPRequest request = new MFAController.VerifyTOTPRequest();
        request.setCode(123456);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            mfaController.verifyTOTP(userDetails, request);
        });
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(mfaService, never()).enableMFA(anyString());
    }

    @Test
    void testAuthenticateTOTP_WithValidCode_ReturnsSuccess() {
        // Given
        when(mfaService.verifyTOTP(testUserId, 123456)).thenReturn(true);

        MFAController.AuthenticateTOTPRequest request = new MFAController.AuthenticateTOTPRequest();
        request.setUserId(testUserId);
        request.setCode(123456);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.authenticateTOTP(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(mfaService, times(1)).verifyTOTP(testUserId, 123456);
    }

    @Test
    void testRemoveTOTP_WithValidUser_RemovesTOTP() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(mfaService).removeTOTP(testUserId);

        // When
        ResponseEntity<Void> response = mfaController.removeTOTP(userDetails);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(mfaService, times(1)).removeTOTP(testUserId);
    }

    @Test
    void testGenerateBackupCodes_WithValidUser_ReturnsBackupCodes() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        
        List<String> backupCodes = Arrays.asList("CODE1", "CODE2", "CODE3");
        when(mfaService.generateBackupCodes(testUserId)).thenReturn(backupCodes);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.generateBackupCodes(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(backupCodes, response.getBody().get("backupCodes"));
        verify(mfaService, times(1)).generateBackupCodes(testUserId);
    }

    @Test
    void testVerifyBackupCode_WithValidCode_ReturnsSuccess() {
        // Given
        when(mfaService.verifyBackupCode(testUserId, "CODE1")).thenReturn(true);

        MFAController.VerifyBackupCodeRequest request = new MFAController.VerifyBackupCodeRequest();
        request.setUserId(testUserId);
        request.setCode("CODE1");

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.verifyBackupCode(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(mfaService, times(1)).verifyBackupCode(testUserId, "CODE1");
    }

    @Test
    void testRequestSMSOTP_WithValidUser_SendsOTP() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(mfaService.generateOTP(testUserId, MFAService.OTPType.SMS)).thenReturn("123456");
        
        NotificationService.NotificationResult result = new NotificationService.NotificationResult();
        result.setSmsSent(true);
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(result);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.requestSMSOTP(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(mfaService, times(1)).generateOTP(testUserId, MFAService.OTPType.SMS);
        verify(notificationService, times(1)).sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testRequestSMSOTP_WithNoPhoneNumber_ThrowsException() {
        // Given
        testUser.setPhoneNumber(null);
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            mfaController.requestSMSOTP(userDetails);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(mfaService, never()).generateOTP(anyString(), any());
    }

    @Test
    void testRequestEmailOTP_WithValidUser_SendsOTP() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(mfaService.generateOTP(testUserId, MFAService.OTPType.EMAIL)).thenReturn("123456");
        when(emailNotificationService.sendEmail(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.requestEmailOTP(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(mfaService, times(1)).generateOTP(testUserId, MFAService.OTPType.EMAIL);
        verify(emailNotificationService, times(1)).sendEmail(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void testVerifySMSOTP_WithValidCode_ReturnsSuccess() {
        // Given
        when(mfaService.verifyOTP(testUserId, MFAService.OTPType.SMS, "123456")).thenReturn(true);

        MFAController.VerifyOTPRequest request = new MFAController.VerifyOTPRequest();
        request.setUserId(testUserId);
        request.setCode("123456");

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.verifySMSOTP(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(mfaService, times(1)).verifyOTP(testUserId, MFAService.OTPType.SMS, "123456");
    }

    @Test
    void testVerifyEmailOTP_WithValidCode_ReturnsSuccess() {
        // Given
        when(mfaService.verifyOTP(testUserId, MFAService.OTPType.EMAIL, "123456")).thenReturn(true);

        MFAController.VerifyOTPRequest request = new MFAController.VerifyOTPRequest();
        request.setUserId(testUserId);
        request.setCode("123456");

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.verifyEmailOTP(request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(mfaService, times(1)).verifyOTP(testUserId, MFAService.OTPType.EMAIL, "123456");
    }

    @Test
    void testGetMFAStatus_WithValidUser_ReturnsStatus() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(mfaService.isMFAEnabled(testUserId)).thenReturn(true);
        when(mfaService.hasBackupCodes(testUserId)).thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = mfaController.getMFAStatus(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("mfaEnabled"));
        assertTrue((Boolean) response.getBody().get("hasBackupCodes"));
        verify(mfaService, times(1)).isMFAEnabled(testUserId);
        verify(mfaService, times(1)).hasBackupCodes(testUserId);
    }

    @Test
    void testDisableMFA_WithValidUser_DisablesMFA() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(mfaService).disableMFA(testUserId);

        // When
        ResponseEntity<Void> response = mfaController.disableMFA(userDetails);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(mfaService, times(1)).disableMFA(testUserId);
    }
}

