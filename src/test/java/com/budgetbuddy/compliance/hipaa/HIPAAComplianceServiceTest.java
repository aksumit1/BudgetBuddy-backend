package com.budgetbuddy.compliance.hipaa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.security.zerotrust.identity.IdentityVerificationService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

/** Unit Tests for HIPAAComplianceService */
@ExtendWith(MockitoExtension.class)
class HIPAAComplianceServiceTest {

    private static final String FINANCIAL = "financial";

    @Mock private AuditLogService auditLogService;

    @Mock private CloudWatchClient cloudWatchClient;

    private HIPAAComplianceService hipaaComplianceService;
    private String testUserId;
    private String testPhiId;

    @Mock private IdentityVerificationService identityVerificationService;

    @BeforeEach
    void setUp() {
        hipaaComplianceService =
                new HIPAAComplianceService(
                        auditLogService, cloudWatchClient, identityVerificationService);
        testUserId = "user-123";
        testPhiId = "phi-123";

        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        // Use lenient to avoid unnecessary stubbing errors
        org.mockito.Mockito.lenient()
                .when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(response);
    }

    @Test
    void testLogPHIAccessWithAuthorizedAccessLogsAccess() {
        // Given
        doNothing()
                .when(auditLogService)
                .logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", true);

        // Then
        verify(auditLogService).logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHIAccessWithUnauthorizedAccessLogsWarning() {
        // Given
        doNothing()
                .when(auditLogService)
                .logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", false);

        // Then
        verify(auditLogService).logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", false);
        // Unauthorized access logs both the access and the unauthorized metric
        verify(cloudWatchClient, atLeastOnce()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testAuditPHIActivityWithValidInputLogsActivity() {
        // Given
        doNothing()
                .when(auditLogService)
                .auditPHIActivity(anyString(), anyString(), anyString(), anyString());

        // When
        hipaaComplianceService.auditPHIActivity(
                testUserId, testPhiId, "VIEW", "User viewed medical record");

        // Then
        verify(auditLogService)
                .auditPHIActivity(testUserId, testPhiId, "VIEW", "User viewed medical record");
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHIModificationWithValidInputLogsModification() {
        // Given
        doNothing()
                .when(auditLogService)
                .logPHIModification(
                        anyString(), anyString(), anyString(), anyString(), anyString());

        // When
        hipaaComplianceService.logPHIModification(
                testUserId, testPhiId, "UPDATE", "old-value", "new-value");

        // Then
        verify(auditLogService)
                .logPHIModification(testUserId, testPhiId, "UPDATE", "old-value", "new-value");
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHITransmissionWithEncryptedLogsTransmission() {
        // Given
        // When
        hipaaComplianceService.logPHITransmission(testUserId, "https://api.example.com", true);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHITransmissionWithUnencryptedLogsViolation() {
        // Given
        // When
        hipaaComplianceService.logPHITransmission(testUserId, "https://api.example.com", false);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckSessionTimeoutWithActiveSessionDoesNothing() {
        // Given
        final long lastActivityTime =
                Instant.now().minusSeconds(300).getEpochSecond(); // 5 minutes ago

        // When
        hipaaComplianceService.checkSessionTimeout(testUserId, lastActivityTime);

        // Then
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckSessionTimeoutWithExpiredSessionLogsTimeout() {
        // Given
        final long lastActivityTime =
                Instant.now().minusSeconds(1000).getEpochSecond(); // 16+ minutes ago

        // When
        hipaaComplianceService.checkSessionTimeout(testUserId, lastActivityTime);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReportBreachWithValidInputReportsBreach() {
        // Given
        final String breachType = "UNAUTHORIZED_ACCESS";
        final String details = "Unauthorized access to PHI";
        doNothing().when(auditLogService).logBreach(any(HIPAAComplianceService.BreachReport.class));
        doNothing()
                .when(auditLogService)
                .logBreachNotification(any(HIPAAComplianceService.BreachReport.class));

        // When
        hipaaComplianceService.reportBreach(testUserId, testPhiId, breachType, details);

        // Then
        verify(auditLogService).logBreach(any(HIPAAComplianceService.BreachReport.class));
        verify(auditLogService)
                .logBreachNotification(any(HIPAAComplianceService.BreachReport.class));
        verify(cloudWatchClient, atLeast(3)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogWorkforceAccessWithGrantedAccessLogsAccess() {
        // Given
        doNothing()
                .when(auditLogService)
                .logWorkforceAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logWorkforceAccess(testUserId, "DOCTOR", "MEDICAL_RECORDS", true);

        // Then
        verify(auditLogService).logWorkforceAccess(testUserId, "DOCTOR", "MEDICAL_RECORDS", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogWorkforceAccessWithDeniedAccessLogsAccess() {
        // Given
        doNothing()
                .when(auditLogService)
                .logWorkforceAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logWorkforceAccess(testUserId, "NURSE", "MEDICAL_RECORDS", false);

        // Then
        verify(auditLogService).logWorkforceAccess(testUserId, "NURSE", "MEDICAL_RECORDS", false);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckPHIAccessPolicyAdminUserHasFullAccess() {
        // Given - Admin user
        when(identityVerificationService.getUserRoles(testUserId))
                .thenReturn(java.util.Set.of("ADMIN", "USER"));
        doNothing()
                .when(auditLogService)
                .logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        final boolean result = hipaaComplianceService.checkPHIAccessPolicy(testUserId, FINANCIAL);

        // Then
        assertTrue(result, "Admin should have full access");
        verify(auditLogService).logPHIAccess(testUserId, FINANCIAL, "READ", true);
    }

    @Test
    void testCheckPHIAccessPolicyRegularUserHasAccessToOwnData() {
        // Given - Regular user
        when(identityVerificationService.getUserRoles(testUserId))
                .thenReturn(java.util.Set.of("USER"));
        doNothing()
                .when(auditLogService)
                .logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        final boolean result = hipaaComplianceService.checkPHIAccessPolicy(testUserId, FINANCIAL);

        // Then
        assertTrue(result, "Regular user should have access to their own financial data");
        verify(auditLogService).logPHIAccess(testUserId, FINANCIAL, "READ", true);
    }

    @Test
    void testCheckPHIAccessPolicyUserWithNoRolesDeniedAccess() {
        // Given - User with no roles
        when(identityVerificationService.getUserRoles(testUserId)).thenReturn(java.util.Set.of());
        doNothing()
                .when(auditLogService)
                .logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        final boolean result = hipaaComplianceService.checkPHIAccessPolicy(testUserId, FINANCIAL);

        // Then
        assertFalse(result, "User with no roles should be denied access");
        verify(auditLogService).logPHIAccess(testUserId, FINANCIAL, "READ", false);
    }

    @Test
    void testLogAuthenticationWithSuccessfulAuthLogsSuccess() {
        // Given
        doNothing().when(auditLogService).logAuthentication(anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logAuthentication(testUserId, "PASSWORD", true);

        // Then
        verify(auditLogService).logAuthentication(testUserId, "PASSWORD", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAuthenticationWithFailedAuthLogsFailure() {
        // Given
        doNothing().when(auditLogService).logAuthentication(anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logAuthentication(testUserId, "PASSWORD", false);

        // Then
        verify(auditLogService).logAuthentication(testUserId, "PASSWORD", false);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testBreachReportSettersAndGetters() {
        // Given
        final HIPAAComplianceService.BreachReport report =
                new HIPAAComplianceService.BreachReport();

        // When
        report.setUserId(testUserId);
        report.setPhiId(testPhiId);
        report.setBreachType("UNAUTHORIZED_ACCESS");
        report.setDetails("Test breach");
        report.setTimestamp(Instant.now());
        report.setReported(true);

        // Then
        assertEquals(testUserId, report.getUserId());
        assertEquals(testPhiId, report.getPhiId());
        assertEquals("UNAUTHORIZED_ACCESS", report.getBreachType());
        assertEquals("Test breach", report.getDetails());
        assertNotNull(report.getTimestamp());
        assertTrue(report.isReported());
    }
}
