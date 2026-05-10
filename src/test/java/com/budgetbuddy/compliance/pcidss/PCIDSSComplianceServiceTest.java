package com.budgetbuddy.compliance.pcidss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.compliance.AuditLogService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kms.KmsClient;

/** Comprehensive tests for PCIDSSComplianceService */
class PCIDSSComplianceServiceTest {

    @Mock private AuditLogService auditLogService;

    @Mock private CloudWatchClient cloudWatchClient;

    @Mock private KmsClient kmsClient;

    private PCIDSSComplianceService pciDSSComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pciDSSComplianceService =
                new PCIDSSComplianceService(auditLogService, cloudWatchClient, kmsClient);
    }

    @Test
    @DisplayName("Should mask PAN showing only last 4 digits")
    void testMaskPAN() {
        // Given
        final String pan = "4111111111111111";

        // When
        final String masked = pciDSSComplianceService.maskPAN(pan);

        // Then
        assertEquals("****1111", masked);
        assertTrue(pciDSSComplianceService.isPANMasked(masked));
    }

    @Test
    @DisplayName("Should return all stars for invalid PAN")
    void testMaskPANInvalid() {
        // Given
        final String pan = "123";

        // When
        final String masked = pciDSSComplianceService.maskPAN(pan);

        // Then
        assertEquals("****", masked);
    }

    @Test
    @DisplayName("Should handle null PAN")
    void testMaskPANNull() {
        // When
        final String masked = pciDSSComplianceService.maskPAN(null);

        // Then
        assertEquals("****", masked);
    }

    @Test
    @DisplayName("Should encrypt PAN")
    void testEncryptPAN() {
        // Given
        final String pan = "4111111111111111";
        final String keyId = "key-123";

        // When
        final String encrypted = pciDSSComplianceService.encryptPAN(pan, keyId);

        // Then
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("encrypted_"));
        verify(auditLogService).logCardDataAccess(eq("SYSTEM"), anyString(), eq(true));
    }

    @Test
    @DisplayName("Should validate TLS configuration")
    void testValidateTLSConfiguration() {
        // Given
        final String tlsVersion = "TLSv1.2";
        final List<String> cipherSuites = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        // When
        final boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should reject weak TLS version")
    void testValidateTLSConfigurationWeakVersion() {
        // Given
        final String tlsVersion = "TLSv1.0";
        final List<String> cipherSuites = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        // When
        final boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should reject weak cipher suites")
    void testValidateTLSConfigurationWeakCipher() {
        // Given
        final String tlsVersion = "TLSv1.2";
        final List<String> cipherSuites = Arrays.asList("RC4-SHA");

        // When
        final boolean valid = pciDSSComplianceService.validateTLSConfiguration(tlsVersion, cipherSuites);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should validate password strength")
    void testValidatePasswordStrength() {
        // Given
        final String strongPassword = "StrongP@ssw0rd123";

        // When
        final boolean valid = pciDSSComplianceService.validatePasswordStrength(strongPassword);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should reject weak password")
    void testValidatePasswordStrengthWeak() {
        // Given
        final String weakPassword = "weak";

        // When
        final boolean valid = pciDSSComplianceService.validatePasswordStrength(weakPassword);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should log MFA usage")
    void testLogMFAUsage() {
        // Given
        final String userId = "user-123";
        final String mfaMethod = "TOTP";

        // When
        pciDSSComplianceService.logMFAUsage(userId, mfaMethod, true);

        // Then
        verify(auditLogService).logAuthentication(eq(userId), eq("MFA_TOTP"), eq(true));
    }

    @Test
    @DisplayName("Should check access authorization")
    void testCheckAccessAuthorization() {
        // Given
        final String userId = "user-123";
        final String resource = "/api/transactions";
        final String action = "GET";

        // When
        final boolean authorized =
                pciDSSComplianceService.checkAccessAuthorization(userId, resource, action);

        // Then
        assertTrue(authorized);
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(true));
    }

    @Test
    @DisplayName("Should detect unauthorized access")
    void testCheckAccessAuthorizationUnauthorized() {
        // Given
        final String userId = "user-123";
        final String resource = "/api/admin/users";
        final String action = "DELETE";

        // When
        final boolean authorized =
                pciDSSComplianceService.checkAccessAuthorization(userId, resource, action);

        // Then
        assertFalse(authorized);
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(false));
    }

    @Test
    @DisplayName("Should validate no sensitive data storage")
    void testValidateNoSensitiveDataStorage() {
        // Given
        final String data = "Normal transaction data";

        // When
        final boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertTrue(valid);
    }

    @Test
    @DisplayName("Should detect CVV in stored data")
    void testValidateNoSensitiveDataStorageCVV() {
        // Given
        final String data = "Card number 4111111111111111 CVV 123";

        // When
        final boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should detect track data")
    void testValidateNoSensitiveDataStorageTrackData() {
        // Given
        final String data = "%B4111111111111111^TEST";

        // When
        final boolean valid = pciDSSComplianceService.validateNoSensitiveDataStorage(data);

        // Then
        assertFalse(valid);
    }

    @Test
    @DisplayName("Should log cardholder data access")
    void testLogCardholderDataAccess() {
        // Given
        final String userId = "user-123";
        final String resource = "/api/transactions";
        final String action = "GET";

        // When
        pciDSSComplianceService.logCardholderDataAccess(userId, resource, action, true);

        // Then
        verify(auditLogService).logCardholderDataAccess(eq(userId), eq(resource), eq(true));
    }

    @Test
    @DisplayName("Should protect audit trail")
    void testProtectAuditTrail() {
        // Given
        final String auditLogId = "log-123";

        // When
        pciDSSComplianceService.protectAuditTrail(auditLogId);

        // Then
        verify(auditLogService).protectLogInformation(eq(auditLogId));
    }

    @Test
    @DisplayName("Should detect intrusion")
    void testDetectIntrusion() {
        // Given
        final String userId = "user-123";
        final String resource = "/api/admin";
        final String suspiciousActivity = "Multiple failed login attempts";

        // When
        pciDSSComplianceService.detectIntrusion(userId, resource, suspiciousActivity);

        // Then
        verify(auditLogService)
                .logSecurityEvent(eq("INTRUSION_DETECTED"), eq("CRITICAL"), eq(suspiciousActivity));
    }

    @Test
    @DisplayName("Should log policy compliance")
    void testLogPolicyCompliance() {
        // Given
        final String policyId = "POL-001";

        // When
        pciDSSComplianceService.logPolicyCompliance(policyId, true);

        // Then
        verify(auditLogService).logComplianceCheck(eq("PCI-DSS_POL-001"), eq(true));
    }
}
