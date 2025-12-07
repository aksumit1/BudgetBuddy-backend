package com.budgetbuddy.compliance;

import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService;
import com.budgetbuddy.compliance.soc2.SOC2ComplianceService;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuditLogService
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ObjectMapper objectMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, objectMapper);
    }

    @Test
    void testConstructor_WithNullRepository_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AuditLogService(null, objectMapper);
        });
    }

    @Test
    void testConstructor_WithNullObjectMapper_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new AuditLogService(auditLogRepository, null);
        });
    }

    @Test
    void testLogAction_WithValidInput_LogsAction() {
        // Given
        String userId = "user-123";
        String action = "CREATE_ACCOUNT";
        String resourceType = "ACCOUNT";
        String resourceId = "acc-123";
        Map<String, Object> details = Map.of("key", "value");
        String ipAddress = "127.0.0.1";
        String userAgent = "Mozilla/5.0";

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"key\":\"value\"}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAction(userId, action, resourceType, resourceId, details, ipAddress, userAgent);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAction_WithNullAction_SkipsLogging() {
        // When
        auditLogService.logAction("user-123", null, "ACCOUNT", "acc-123", Map.of(), null, null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogAction_WithEmptyAction_SkipsLogging() {
        // When
        auditLogService.logAction("user-123", "", "ACCOUNT", "acc-123", Map.of(), null, null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogControlActivity_WithValidInput_LogsActivity() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logControlActivity("control-123", "activity", "user-123");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSystemChange_WithValidInput_LogsChange() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSystemChange("DEPLOYMENT", "System update", "user-123");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogRiskAssessment_WithValidAssessment_LogsAssessment() {
        // Given
        SOC2ComplianceService.RiskAssessment assessment = new SOC2ComplianceService.RiskAssessment();
        assessment.setUserId("user-123");
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
    void testLogRiskAssessment_WithNullAssessment_SkipsLogging() {
        // When
        auditLogService.logRiskAssessment(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogAccessControl_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessControl("resource-123", "READ", "user-123", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSystemHealth_WithValidHealth_LogsHealth() {
        // Given
        SOC2ComplianceService.SystemHealth health = new SOC2ComplianceService.SystemHealth();
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
    void testLogSystemHealth_WithNullHealth_SkipsLogging() {
        // When
        auditLogService.logSystemHealth(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogPHIAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPHIAccess("user-123", "MEDICAL_RECORD", "READ", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testAuditPHIActivity_WithValidInput_LogsActivity() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.auditPHIActivity("user-123", "phi-123", "VIEW", "Details");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPHIModification_WithValidInput_LogsModification() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPHIModification("user-123", "phi-123", "UPDATE", "old", "new");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogBreach_WithValidReport_LogsBreach() {
        // Given
        HIPAAComplianceService.BreachReport report = new HIPAAComplianceService.BreachReport();
        report.setUserId("user-123");
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
    void testLogBreach_WithNullReport_SkipsLogging() {
        // When
        auditLogService.logBreach(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogWorkforceAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logWorkforceAccess("user-123", "ADMIN", "SYSTEM", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAuthentication_WithValidInput_LogsAuthentication() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAuthentication("user-123", "PASSWORD", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogUserRegistration_WithValidInput_LogsRegistration() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logUserRegistration("user-123", "EMAIL");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAccessProvisioning_WithValidInput_LogsProvisioning() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessProvisioning("user-123", "ACCOUNT", "READ");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPrivilegedAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPrivilegedAccess("user-123", "ADMIN", "SYSTEM");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogCredentialChange_WithValidInput_LogsChange() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCredentialChange("user-123", "PASSWORD_RESET");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogAccessReview_WithValidReview_LogsReview() {
        // Given
        ISO27001ComplianceService.AccessReview review = new ISO27001ComplianceService.AccessReview();
        review.setUserId("user-123");
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
    void testLogAccessReview_WithNullReview_SkipsLogging() {
        // When
        auditLogService.logAccessReview(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogAccessRemoval_WithValidInput_LogsRemoval() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logAccessRemoval("user-123", "ACCOUNT", "Terminated");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecureLogon_WithValidInput_LogsLogon() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logSecureLogon("user-123", "MFA", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogPasswordManagement_WithValidInput_LogsManagement() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logPasswordManagement("user-123", "RESET");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogSecurityEvent_WithValidInput_LogsEvent() {
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
    void testProtectLogInformation_WithValidInput_LogsProtection() {
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
    void testLogAdministratorActivity_WithValidInput_LogsActivity() {
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
    void testLogClockSynchronization_WithValidInput_LogsSynchronization() {
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
    void testLogSecurityIncident_WithValidIncident_LogsIncident() {
        // Given
        ISO27001ComplianceService.SecurityIncident incident = new ISO27001ComplianceService.SecurityIncident();
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
    void testLogSecurityIncident_WithNullIncident_SkipsLogging() {
        // When
        auditLogService.logSecurityIncident(null);

        // Then
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void testLogComplianceCheck_WithValidInput_LogsCheck() {
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
    void testLogCardDataAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCardDataAccess("user-123", "1234", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogCardholderDataAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logCardholderDataAccess("user-123", "PAYMENT", true);

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogFinancialDataAccess_WithValidInput_LogsAccess() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logFinancialDataAccess("user-123", "TRANSACTION", "READ");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }

    @Test
    void testLogFinancialDataModification_WithValidInput_LogsModification() {
        // Given
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Should not happen in test
        }
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));

        // When
        auditLogService.logFinancialDataModification("user-123", "ACCOUNT", "old", "new");

        // Then
        verify(auditLogRepository).save(any(AuditLogTable.class));
    }
}

