package com.budgetbuddy.compliance.pcidss;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PCIDSSComplianceService
 * Tests PCI-DSS compliance functionality
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PCIDSSComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    @Mock
    private KmsClient kmsClient;

    @InjectMocks
    private PCIDSSComplianceService pciDSSComplianceService;

    private String testUserId;
    private String testPAN;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testPAN = "4111111111111111"; // Valid test card number
    }

    @Test
    void testMaskPAN_WithValidPAN_ReturnsMaskedPAN() {
        // When
        String result = pciDSSComplianceService.maskPAN(testPAN);

        // Then
        assertNotNull(result);
        assertEquals("****1111", result, "Should mask all but last 4 digits");
    }

    @Test
    void testMaskPAN_WithNullPAN_ReturnsDefaultMask() {
        // When
        String result = pciDSSComplianceService.maskPAN(null);

        // Then
        assertEquals("****", result);
    }

    @Test
    void testMaskPAN_WithShortPAN_ReturnsDefaultMask() {
        // When
        String result = pciDSSComplianceService.maskPAN("123");

        // Then
        assertEquals("****", result);
    }

    @Test
    void testIsPANMasked_WithMaskedPAN_ReturnsTrue() {
        // Given
        String maskedPAN = "****1111";

        // When
        boolean result = pciDSSComplianceService.isPANMasked(maskedPAN);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsPANMasked_WithUnmaskedPAN_ReturnsFalse() {
        // When
        boolean result = pciDSSComplianceService.isPANMasked(testPAN);

        // Then
        assertFalse(result);
    }

    @Test
    void testEncryptPAN_WithValidPAN_ReturnsEncrypted() {
        // Given
        String keyId = "key-123";
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        String result = pciDSSComplianceService.encryptPAN(testPAN, keyId);

        // Then
        assertNotNull(result);
        assertTrue(result.startsWith("encrypted_"));
        verify(auditLogService, times(1)).logCardDataAccess(anyString(), anyString(), eq(true));
    }

    @Test
    void testLogKeyAccess_WithValidInput_LogsAccess() {
        // Given
        String keyId = "key-123";
        String operation = "ENCRYPT";
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.logKeyAccess(keyId, testUserId, operation);

        // Then
        verify(auditLogService, times(1)).logCardDataAccess(eq(testUserId), eq("KEY_ACCESS"), eq(true));
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testValidateTLSConfiguration_WithValidTLS_ReturnsTrue() {
        // Given
        String tlsVersion = "TLSv1.2";
        List<String> cipherSuites = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        // When
        boolean result = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertTrue(result);
    }

    @Test
    void testValidateTLSConfiguration_WithInvalidTLS_ReturnsFalse() {
        // Given
        String tlsVersion = "TLSv1.0";
        List<String> cipherSuites = Arrays.asList("TLS_RSA_WITH_AES_256_CBC_SHA");

        // When
        boolean result = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(result);
    }

    @Test
    void testValidateTLSConfiguration_WithWeakCipher_ReturnsFalse() {
        // Given
        String tlsVersion = "TLSv1.2";
        List<String> cipherSuites = Arrays.asList("RC4-SHA");

        // When
        boolean result = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(result);
    }

    @Test
    void testCheckAccessAuthorization_WithAuthorizedAccess_ReturnsTrue() {
        // Given
        String resource = "/api/transactions";
        String action = "READ";
        doNothing().when(auditLogService).logCardholderDataAccess(anyString(), anyString(), anyBoolean());
        // Note: putMetricData is only called for unauthorized access, not for authorized access

        // When
        boolean result = pciDSSComplianceService.checkAccessAuthorization(testUserId, resource, action);

        // Then
        assertTrue(result);
        verify(auditLogService, times(1)).logCardholderDataAccess(testUserId, resource, true);
    }

    @Test
    void testCheckAccessAuthorization_WithUnauthorizedAccess_ReturnsFalse() {
        // Given
        String resource = "/api/admin/users";
        String action = "DELETE";
        doNothing().when(auditLogService).logCardholderDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        boolean result = pciDSSComplianceService.checkAccessAuthorization(testUserId, resource, action);

        // Then
        assertFalse(result);
        verify(auditLogService, times(1)).logCardholderDataAccess(testUserId, resource, false);
    }

    @Test
    void testValidatePasswordStrength_WithStrongPassword_ReturnsTrue() {
        // Given
        String password = "StrongP@ssw0rd123";

        // When
        boolean result = pciDSSComplianceService.validatePasswordStrength(password);

        // Then
        assertTrue(result);
    }

    @Test
    void testValidatePasswordStrength_WithWeakPassword_ReturnsFalse() {
        // Given
        String password = "weak";

        // When
        boolean result = pciDSSComplianceService.validatePasswordStrength(password);

        // Then
        assertFalse(result);
    }

    @Test
    void testValidatePasswordStrength_WithShortPassword_ReturnsFalse() {
        // Given
        String password = "Short1!";

        // When
        boolean result = pciDSSComplianceService.validatePasswordStrength(password);

        // Then
        assertFalse(result);
    }

    @Test
    void testLogMFAUsage_WithSuccess_LogsSuccess() {
        // Given
        String mfaMethod = "TOTP";
        boolean success = true;
        doNothing().when(auditLogService).logAuthentication(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.logMFAUsage(testUserId, mfaMethod, success);

        // Then
        verify(auditLogService, times(1)).logAuthentication(testUserId, "MFA_" + mfaMethod, success);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCardholderDataAccess_WithAuthorized_LogsAccess() {
        // Given
        String resource = "/api/accounts";
        String action = "READ";
        boolean authorized = true;
        doNothing().when(auditLogService).logCardholderDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.logCardholderDataAccess(testUserId, resource, action, authorized);

        // Then
        verify(auditLogService, times(1)).logCardholderDataAccess(testUserId, resource, authorized);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testProtectAuditTrail_WithValidLogId_ProtectsTrail() {
        // Given
        String auditLogId = "log-123";
        doNothing().when(auditLogService).protectLogInformation(anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.protectAuditTrail(auditLogId);

        // Then
        verify(auditLogService, times(1)).protectLogInformation(auditLogId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testDetectIntrusion_WithSuspiciousActivity_LogsIntrusion() {
        // Given
        String resource = "/api/admin";
        String suspiciousActivity = "Multiple failed login attempts";
        doNothing().when(auditLogService).logSecurityEvent(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.detectIntrusion(testUserId, resource, suspiciousActivity);

        // Then
        verify(auditLogService, times(1)).logSecurityEvent(eq("INTRUSION_DETECTED"), eq("CRITICAL"), eq(suspiciousActivity));
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPolicyCompliance_WithCompliant_LogsCompliance() {
        // Given
        String policyId = "POL-001";
        boolean compliant = true;
        doNothing().when(auditLogService).logComplianceCheck(anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        pciDSSComplianceService.logPolicyCompliance(policyId, compliant);

        // Then
        verify(auditLogService, times(1)).logComplianceCheck("PCI-DSS_" + policyId, compliant);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testValidateNoSensitiveDataStorage_WithCVV_ReturnsFalse() {
        // Given
        String data = "Card number: 4111111111111111, CVV: 123";

        // When
        boolean result = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(result, "Should detect CVV in data");
    }

    @Test
    void testValidateNoSensitiveDataStorage_WithTrackData_ReturnsFalse() {
        // Given
        String data = "%B4111111111111111^CARDHOLDER/NAME^";

        // When
        boolean result = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(result, "Should detect track data");
    }

    @Test
    void testValidateNoSensitiveDataStorage_WithSafeData_ReturnsTrue() {
        // Given
        String data = "Regular transaction data without sensitive information";

        // When
        boolean result = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertTrue(result);
    }
}

