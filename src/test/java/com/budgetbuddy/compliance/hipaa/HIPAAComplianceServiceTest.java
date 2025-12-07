package com.budgetbuddy.compliance.hipaa;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for HIPAAComplianceService
 */
@ExtendWith(MockitoExtension.class)
class HIPAAComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    private HIPAAComplianceService hipaaComplianceService;
    private String testUserId;
    private String testPhiId;

    @BeforeEach
    void setUp() {
        hipaaComplianceService = new HIPAAComplianceService(auditLogService, cloudWatchClient);
        testUserId = "user-123";
        testPhiId = "phi-123";
        
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        // Use lenient to avoid unnecessary stubbing errors
        org.mockito.Mockito.lenient().when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);
    }

    @Test
    void testLogPHIAccess_WithAuthorizedAccess_LogsAccess() {
        // Given
        doNothing().when(auditLogService).logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", true);

        // Then
        verify(auditLogService).logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHIAccess_WithUnauthorizedAccess_LogsWarning() {
        // Given
        doNothing().when(auditLogService).logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", false);

        // Then
        verify(auditLogService).logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", false);
        // Unauthorized access logs both the access and the unauthorized metric
        verify(cloudWatchClient, atLeastOnce()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testAuditPHIActivity_WithValidInput_LogsActivity() {
        // Given
        doNothing().when(auditLogService).auditPHIActivity(anyString(), anyString(), anyString(), anyString());

        // When
        hipaaComplianceService.auditPHIActivity(testUserId, testPhiId, "VIEW", "User viewed medical record");

        // Then
        verify(auditLogService).auditPHIActivity(testUserId, testPhiId, "VIEW", "User viewed medical record");
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHIModification_WithValidInput_LogsModification() {
        // Given
        doNothing().when(auditLogService).logPHIModification(anyString(), anyString(), anyString(), anyString(), anyString());

        // When
        hipaaComplianceService.logPHIModification(testUserId, testPhiId, "UPDATE", "old-value", "new-value");

        // Then
        verify(auditLogService).logPHIModification(testUserId, testPhiId, "UPDATE", "old-value", "new-value");
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHITransmission_WithEncrypted_LogsTransmission() {
        // Given
        // When
        hipaaComplianceService.logPHITransmission(testUserId, "https://api.example.com", true);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPHITransmission_WithUnencrypted_LogsViolation() {
        // Given
        // When
        hipaaComplianceService.logPHITransmission(testUserId, "https://api.example.com", false);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckSessionTimeout_WithActiveSession_DoesNothing() {
        // Given
        long lastActivityTime = Instant.now().minusSeconds(300).getEpochSecond(); // 5 minutes ago

        // When
        hipaaComplianceService.checkSessionTimeout(testUserId, lastActivityTime);

        // Then
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckSessionTimeout_WithExpiredSession_LogsTimeout() {
        // Given
        long lastActivityTime = Instant.now().minusSeconds(1000).getEpochSecond(); // 16+ minutes ago

        // When
        hipaaComplianceService.checkSessionTimeout(testUserId, lastActivityTime);

        // Then
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReportBreach_WithValidInput_ReportsBreach() {
        // Given
        String breachType = "UNAUTHORIZED_ACCESS";
        String details = "Unauthorized access to PHI";
        doNothing().when(auditLogService).logBreach(any(HIPAAComplianceService.BreachReport.class));
        doNothing().when(auditLogService).logBreachNotification(any(HIPAAComplianceService.BreachReport.class));

        // When
        hipaaComplianceService.reportBreach(testUserId, testPhiId, breachType, details);

        // Then
        verify(auditLogService).logBreach(any(HIPAAComplianceService.BreachReport.class));
        verify(auditLogService).logBreachNotification(any(HIPAAComplianceService.BreachReport.class));
        verify(cloudWatchClient, atLeast(3)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogWorkforceAccess_WithGrantedAccess_LogsAccess() {
        // Given
        doNothing().when(auditLogService).logWorkforceAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logWorkforceAccess(testUserId, "DOCTOR", "MEDICAL_RECORDS", true);

        // Then
        verify(auditLogService).logWorkforceAccess(testUserId, "DOCTOR", "MEDICAL_RECORDS", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogWorkforceAccess_WithDeniedAccess_LogsAccess() {
        // Given
        doNothing().when(auditLogService).logWorkforceAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logWorkforceAccess(testUserId, "NURSE", "MEDICAL_RECORDS", false);

        // Then
        verify(auditLogService).logWorkforceAccess(testUserId, "NURSE", "MEDICAL_RECORDS", false);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckPHIAccessPolicy_WithValidInput_ReturnsTrue() {
        // Given
        doNothing().when(auditLogService).logPHIAccess(anyString(), anyString(), anyString(), anyBoolean());

        // When
        boolean result = hipaaComplianceService.checkPHIAccessPolicy(testUserId, "MEDICAL_RECORD");

        // Then
        assertTrue(result);
        verify(auditLogService).logPHIAccess(testUserId, "MEDICAL_RECORD", "READ", true);
    }

    @Test
    void testLogAuthentication_WithSuccessfulAuth_LogsSuccess() {
        // Given
        doNothing().when(auditLogService).logAuthentication(anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logAuthentication(testUserId, "PASSWORD", true);

        // Then
        verify(auditLogService).logAuthentication(testUserId, "PASSWORD", true);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAuthentication_WithFailedAuth_LogsFailure() {
        // Given
        doNothing().when(auditLogService).logAuthentication(anyString(), anyString(), anyBoolean());

        // When
        hipaaComplianceService.logAuthentication(testUserId, "PASSWORD", false);

        // Then
        verify(auditLogService).logAuthentication(testUserId, "PASSWORD", false);
        verify(cloudWatchClient).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testBreachReport_SettersAndGetters() {
        // Given
        HIPAAComplianceService.BreachReport report = new HIPAAComplianceService.BreachReport();

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

