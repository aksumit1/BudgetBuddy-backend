package com.budgetbuddy.compliance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService;
import com.budgetbuddy.compliance.soc2.SOC2ComplianceService;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for AuditLogService */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    private static final String USER_123 = "user-123";

    @Mock private AuditLogRepository auditLogRepository;

    @Mock private ObjectMapper objectMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, objectMapper);
    }

    @Test
    void testConstructorWithNullRepositoryThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new AuditLogService(null, objectMapper);
                });
    }

    @Test
    void testConstructorWithNullObjectMapperThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new AuditLogService(auditLogRepository, null);
                });
    }

    @Test
    void testLogActionWithValidInputLogsAction() {
        // Given
        final String userId = USER_123;
        final String action = "CREATE_ACCOUNT";
        final String resourceType = "ACCOUNT";
        final String resourceId = "acc-123";
        final Map<String, Object> details = Map.of("key", "value");
        final String ipAddress = "127.0.0.1";
        final String userAgent = "Mozilla/5.0";

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"key\":\"value\"}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAction(
                userId, action, resourceType, resourceId, details, ipAddress, userAgent);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogActionWithNullActionSkipsLogging() {
        // When
        auditLogService.logAction(USER_123, null, "ACCOUNT", "acc-123", Map.of(), null, null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogActionWithEmptyActionSkipsLogging() {
        // When
        auditLogService.logAction(USER_123, "", "ACCOUNT", "acc-123", Map.of(), null, null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogControlActivityWithValidInputLogsActivity() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logControlActivity("control-123", "activity", USER_123);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSystemChangeWithValidInputLogsChange() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSystemChange("DEPLOYMENT", "System update", USER_123);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogRiskAssessmentWithValidAssessmentLogsAssessment() {
        // Given
        final SOC2ComplianceService.RiskAssessment assessment =
                new SOC2ComplianceService.RiskAssessment();
        assessment.setUserId(USER_123);
        assessment.setResource("resource-123");
        assessment.setRiskScore(75);
        assessment.setRiskLevel("HIGH");

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logRiskAssessment(assessment);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogRiskAssessmentWithNullAssessmentSkipsLogging() {
        // When
        auditLogService.logRiskAssessment(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogAccessControlWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessControl("resource-123", "READ", USER_123, true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSystemHealthWithValidHealthLogsHealth() {
        // Given
        final SOC2ComplianceService.SystemHealth health = new SOC2ComplianceService.SystemHealth();
        health.setAvailability(99.9);
        health.setPerformance(95.0);
        health.setErrorRate(0.1);

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSystemHealth(health);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSystemHealthWithNullHealthSkipsLogging() {
        // When
        auditLogService.logSystemHealth(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogPHIAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPHIAccess(USER_123, "MEDICAL_RECORD", "READ", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testAuditPHIActivityWithValidInputLogsActivity() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.auditPHIActivity(USER_123, "phi-123", "VIEW", "Details");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPHIModificationWithValidInputLogsModification() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPHIModification(USER_123, "phi-123", "UPDATE", "old", "new");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogBreachWithValidReportLogsBreach() {
        // Given
        final HIPAAComplianceService.BreachReport report =
                new HIPAAComplianceService.BreachReport();
        report.setUserId(USER_123);
        report.setPhiId("phi-123");
        report.setBreachType("UNAUTHORIZED_ACCESS");
        report.setDetails("Breach details");

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logBreach(report);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogBreachWithNullReportSkipsLogging() {
        // When
        auditLogService.logBreach(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogWorkforceAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logWorkforceAccess(USER_123, "ADMIN", "SYSTEM", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAuthenticationWithValidInputLogsAuthentication() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAuthentication(USER_123, "PASSWORD", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogUserRegistrationWithValidInputLogsRegistration() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logUserRegistration(USER_123, "EMAIL");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAccessProvisioningWithValidInputLogsProvisioning() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessProvisioning(USER_123, "ACCOUNT", "READ");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPrivilegedAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPrivilegedAccess(USER_123, "ADMIN", "SYSTEM");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogCredentialChangeWithValidInputLogsChange() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCredentialChange(USER_123, "PASSWORD_RESET");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAccessReviewWithValidReviewLogsReview() {
        // Given
        final ISO27001ComplianceService.AccessReview review =
                new ISO27001ComplianceService.AccessReview();
        review.setUserId(USER_123);
        review.setStatus("APPROVED");

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessReview(review);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAccessReviewWithNullReviewSkipsLogging() {
        // When
        auditLogService.logAccessReview(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogAccessRemovalWithValidInputLogsRemoval() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessRemoval(USER_123, "ACCOUNT", "Terminated");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecureLogonWithValidInputLogsLogon() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSecureLogon(USER_123, "MFA", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPasswordManagementWithValidInputLogsManagement() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPasswordManagement(USER_123, "RESET");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecurityEventWithValidInputLogsEvent() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSecurityEvent("INTRUSION", "HIGH", "Details");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testProtectLogInformationWithValidInputLogsProtection() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.protectLogInformation("log-123");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAdministratorActivityWithValidInputLogsActivity() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAdministratorActivity("admin-123", "CONFIG_CHANGE", "SYSTEM");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogClockSynchronizationWithValidInputLogsSynchronization() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logClockSynchronization(System.currentTimeMillis());

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecurityIncidentWithValidIncidentLogsIncident() {
        // Given
        final ISO27001ComplianceService.SecurityIncident incident =
                new ISO27001ComplianceService.SecurityIncident();
        incident.setIncidentType("BREACH");
        incident.setSeverity("HIGH");
        incident.setDetails("Details");
        incident.setStatus("ACTIVE");

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSecurityIncident(incident);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecurityIncidentWithNullIncidentSkipsLogging() {
        // When
        auditLogService.logSecurityIncident(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogComplianceCheckWithValidInputLogsCheck() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logComplianceCheck("GDPR", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogCardDataAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCardDataAccess(USER_123, "1234", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogCardholderDataAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCardholderDataAccess(USER_123, "PAYMENT", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogFinancialDataAccessWithValidInputLogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logFinancialDataAccess(USER_123, "TRANSACTION", "READ");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogFinancialDataModificationWithValidInputLogsModification() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logFinancialDataModification(USER_123, "ACCOUNT", "old", "new");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }
}
