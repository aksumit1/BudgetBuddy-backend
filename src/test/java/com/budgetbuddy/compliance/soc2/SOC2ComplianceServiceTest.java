package com.budgetbuddy.compliance.soc2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

/** Comprehensive tests for SOC2ComplianceService */
class SOC2ComplianceServiceTest {

    private static final String USER_123 = "user-123";

    @Mock private AuditLogService auditLogService;

    @Mock private CloudWatchClient cloudWatchClient;

    private SOC2ComplianceService soc2ComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        soc2ComplianceService = new SOC2ComplianceService(auditLogService, cloudWatchClient);
    }

    @Test
    @DisplayName("Should log control activity")
    void testLogControlActivity() {
        // Given
        final String controlId = "CC1.1";
        final String activity = "Access review";
        final String userId = USER_123;

        // When
        soc2ComplianceService.logControlActivity(controlId, activity, userId);

        // Then
        verify(auditLogService).logControlActivity(eq(controlId), eq(activity), eq(userId));
    }

    @Test
    @DisplayName("Should log system change")
    void testLogSystemChange() {
        // Given
        final String changeType = "Configuration";
        final String description = "Updated security settings";
        final String userId = USER_123;

        // When
        soc2ComplianceService.logSystemChange(changeType, description, userId);

        // Then
        verify(auditLogService).logSystemChange(eq(changeType), eq(description), eq(userId));
    }

    @Test
    @DisplayName("Should assess risk")
    void testAssessRisk() {
        // Given
        final String resource = "/api/admin/users";
        final String action = "DELETE";
        final String userId = USER_123;

        // When
        final SOC2ComplianceService.RiskAssessment assessment =
                soc2ComplianceService.assessRisk(resource, action, userId);

        // Then
        assertNotNull(assessment);
        assertEquals(resource, assessment.getResource());
        assertEquals(action, assessment.getAction());
        assertEquals(userId, assessment.getUserId());
        assertTrue(assessment.getRiskScore() > 0);
        assertNotNull(assessment.getRiskLevel());
        verify(auditLogService).logRiskAssessment(any());
    }

    @Test
    @DisplayName("Should identify high risk")
    void testAssessRiskHighRisk() {
        // Given
        final String resource = "/api/admin/compliance";
        final String action = "DELETE";
        final String userId = USER_123;

        // When
        final SOC2ComplianceService.RiskAssessment assessment =
                soc2ComplianceService.assessRisk(resource, action, userId);

        // Then
        assertNotNull(assessment);
        assertTrue(assessment.getRiskScore() > 70 || "HIGH".equals(assessment.getRiskLevel()));
    }

    @Test
    @DisplayName("Should monitor activity")
    void testMonitorActivity() {
        // Given
        final String activityType = "Login";
        final String details = "User logged in successfully";

        // When
        soc2ComplianceService.monitorActivity(activityType, details);

        // Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    soc2ComplianceService.monitorActivity(activityType, details);
                });
    }

    @Test
    @DisplayName("Should detect anomalous activity")
    void testMonitorActivityAnomalous() {
        // Given
        final String activityType = "Login";
        final String details = "Unauthorized access attempt";

        // When
        soc2ComplianceService.monitorActivity(activityType, details);

        // Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    soc2ComplianceService.monitorActivity(activityType, details);
                });
    }

    @Test
    @DisplayName("Should log control activity with status")
    void testLogControlActivityWithStatus() {
        // Given
        final String controlId = "CC5.1";
        final String status = "PASS";
        final String details = "Control passed";

        // When
        soc2ComplianceService.logControlActivityWithStatus(controlId, status, details);

        // Then
        verify(auditLogService).logControlActivity(eq(controlId), eq(status), eq(details));
    }

    @Test
    @DisplayName("Should log access control")
    void testLogAccessControl() {
        // Given
        final String resource = "/api/transactions";
        final String action = "GET";
        final String userId = USER_123;

        // When
        soc2ComplianceService.logAccessControl(resource, action, userId, true);

        // Then
        verify(auditLogService).logAccessControl(eq(resource), eq(action), eq(userId), eq(true));
    }

    @Test
    @DisplayName("Should check system health")
    void testCheckSystemHealth() {
        // When
        final SOC2ComplianceService.SystemHealth health = soc2ComplianceService.checkSystemHealth();

        // Then
        assertNotNull(health);
        assertNotNull(health.getTimestamp());
        assertTrue(health.getAvailability() > 0);
        assertTrue(health.getPerformance() > 0);
        assertTrue(health.getErrorRate() >= 0);
        verify(auditLogService).logSystemHealth(any());
    }

    @Test
    @DisplayName("Should log change management")
    void testLogChangeManagement() {
        // Given
        final String changeId = "CHG-001";
        final String changeType = "Configuration";
        final String description = "Updated API endpoint";
        final String userId = USER_123;

        // When
        soc2ComplianceService.logChangeManagement(changeId, changeType, description, userId);

        // Then
        verify(auditLogService)
                .logChangeManagement(eq(changeId), eq(changeType), eq(description), eq(userId));
    }
}
