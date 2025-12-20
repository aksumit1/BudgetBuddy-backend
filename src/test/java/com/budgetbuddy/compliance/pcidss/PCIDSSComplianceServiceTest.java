package com.budgetbuddy.compliance.pcidss;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for PCIDSSComplianceService
 */
class PCIDSSComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    @Mock
    private KmsClient kmsClient;

    private PCIDSSComplianceService pciDSSComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pciDSSComplianceService = new PCIDSSComplianceService(
                auditLogService, cloudWatchClient, kmsClient);
    }

    @Test
    @DisplayName("Should mask PAN showing only last 4 digits")
    void testMaskPAN() {
        // Given
        String pan = "4111111111111111";

        // When
        String masked = pciDSSComplianceService.maskPAN(pan);

        // Then
        assertEquals("****1111", masked);
        assertTrue(pciDSSComplianceService.isPANMasked(masked));
    }

    @Test
    @DisplayName("Should return all stars for invalid PAN")
    void testMaskPAN_Invalid() {
        // Given
        String pan = "123";

        // When
        String masked = pciDSSComplianceService.maskPAN(pan);

        // Then
        assertEquals("****", masked);
    }

    @Test
    @DisplayName("Should handle null PAN")
    void testMaskPAN_Null() {
        // When
        String masked = pciDSSComplianceService.maskPAN(null);

        // Then
        assertEquals("****", masked);
    }

    @Test
    @DisplayName("Should encrypt PAN")
    void testEncryptPAN() {
        // Given
        String pan = "4111111111111111";
        String keyId = "key-123";

        // When
        String encrypted = pciDSSComplianceService.encryptPAN(pan, keyId);

        // Then
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("encrypted_"));
        verify(auditLogService).logCardDataAccess(eq("SYSTEM"), anyString(), eq(true));
    }

    @Test
    @DisplayName("Should validate TLS configuration")
    void testValidateTLSConfiguration() {
        // Given
        String tlsVersion = "TLSv1.2";
        List<String> cipherSuites = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        // When
        boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should reject weak TLS version")
    void testValidateTLSConfiguration_WeakVersion() {
        // Given
        String tlsVersion = "TLSv1.0";
        List<String> cipherSuites = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        // When
        boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should reject weak cipher suites")
    void testValidateTLSConfiguration_WeakCipher() {
        // Given
        String tlsVersion = "TLSv1.2";
        List<String> cipherSuites = Arrays.asList("RC4-SHA");

        // When
        boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should validate password strength")
    void testValidatePasswordStrength() {
        // Given
        String strongPassword = "StrongP@ssw0rd123";

        // When
        boolean valid = pciDSSComplianceService.validatePasswordStrength(strongPassword);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should reject weak password")
    void testValidatePasswordStrength_Weak() {
        // Given
        String weakPassword = "weak";

        // When
        boolean valid = pciDSSComplianceService.validatePasswordStrength(weakPassword);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should log MFA usage")
    void testLogMFAUsage() {
        // Given
        String userId = "user-123";
        String mfaMethod = "TOTP";

        // When
        pciDSSComplianceService.logMFAUsage(userId, mfaMethod, true);

        // Then
        verify(auditLogService).logAuthentication(eq(userId), eq("MFA_TOTP"), eq(true));
    }

    @Test
    @DisplayName("Should check access authorization")
    void testCheckAccessAuthorization() {
        // Given
        String userId = "user-123";
        String resource = "/api/transactions";
        String action = "GET";

        // When
        boolean authorized = pciDSSComplianceService.checkAccessAuthorization(userId, resource, action);

        // Then
        assertTrue(authorized);
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(true));
    }

    @Test
    @DisplayName("Should detect unauthorized access")
    void testCheckAccessAuthorization_Unauthorized() {
        // Given
        String userId = "user-123";
        String resource = "/api/admin/users";
        String action = "DELETE";

        // When
        boolean authorized = pciDSSComplianceService.checkAccessAuthorization(userId, resource, action);

        // Then
        assertFalse(authorized);
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(false));
    }

    @Test
    @DisplayName("Should validate no sensitive data storage")
    void testValidateNoSensitiveDataStorage() {
        // Given
        String data = "Normal transaction data";

        // When
        boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should detect CVV in stored data")
    void testValidateNoSensitiveDataStorage_CVV() {
        // Given
        String data = "Card number 4111111111111111 CVV 123";

        // When
        boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should detect track data")
    void testValidateNoSensitiveDataStorage_TrackData() {
        // Given
        String data = "%B4111111111111111^TEST";

        // When
        boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should log cardholder data access")
    void testLogCardholderDataAccess() {
        // Given
        String userId = "user-123";
        String resource = "/api/transactions";
        String action = "GET";

        // When
        pciDSSComplianceService.logCardholderDataAccess(userId, resource, action, true);

        // Then
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(true));
    }

    @Test
    @DisplayName("Should protect audit trail")
    void testProtectAuditTrail() {
        // Given
        String auditLogId = "log-123";

        // When
        pciDSSComplianceService.protectAuditTrail(auditLogId);

        // Then
        verify(auditLogService).protectLogInformation(eq(auditLogId));
    }

    @Test
    @DisplayName("Should detect intrusion")
    void testDetectIntrusion() {
        // Given
        String userId = "user-123";
        String resource = "/api/admin";
        String suspiciousActivity = "Multiple failed login attempts";

        // When
        pciDSSComplianceService.detectIntrusion(userId, resource, suspiciousActivity);

        // Then
        verify(auditLogService).logSecurityEvent(eq("INTRUSION_DETECTED"), eq("CRITICAL"), eq(suspiciousActivity));
    }

    @Test
    @DisplayName("Should log policy compliance")
    void testLogPolicyCompliance() {
        // Given
        String policyId = "POL-001";

        // When
        pciDSSComplianceService.logPolicyCompliance(policyId, true);

        // Then
        verify(auditLogService).logComplianceCheck(eq("PCI-DSS_POL-001"), eq(true));
    }
}
