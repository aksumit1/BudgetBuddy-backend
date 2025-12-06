package com.budgetbuddy.compliance.soc2;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for SOC2ComplianceService
 * Tests SOC 2 Type II compliance functionality
 */
@ExtendWith(MockitoExtension.class)
class SOC2ComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    @InjectMocks
    private SOC2ComplianceService soc2ComplianceService;

    private String testUserId;
    private String testControlId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testControlId = "CC1.1";
    }

    @Test
    void testLogControlActivity_WithValidInput_LogsActivity() {
        // Given
        String activity = "ACCESS_GRANTED";
        doNothing().when(auditLogService).logControlActivity(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.logControlActivity(testControlId, activity, testUserId);

        // Then
        verify(auditLogService, times(1)).logControlActivity(testControlId, activity, testUserId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSystemChange_WithValidInput_LogsChange() {
        // Given
        String changeType = "CONFIGURATION";
        String description = "Updated security settings";
        doNothing().when(auditLogService).logSystemChange(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.logSystemChange(changeType, description, testUserId);

        // Then
        verify(auditLogService, times(1)).logSystemChange(changeType, description, testUserId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testAssessRisk_WithLowRisk_ReturnsLowRiskAssessment() {
        // Given
        String resource = "/api/users";
        String action = "GET";
        doNothing().when(auditLogService).logRiskAssessment(any());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        SOC2ComplianceService.RiskAssessment result = soc2ComplianceService.assessRisk(resource, action, testUserId);

        // Then
        assertNotNull(result);
        assertEquals(resource, result.getResource());
        assertEquals(action, result.getAction());
        assertEquals(testUserId, result.getUserId());
        assertNotNull(result.getTimestamp());
        assertTrue(result.getRiskScore() <= 40, "Should be LOW risk");
        assertEquals("LOW", result.getRiskLevel());
    }

    @Test
    void testAssessRisk_WithHighRisk_ReturnsHighRiskAssessment() {
        // Given
        String resource = "/api/admin/users";
        String action = "DELETE";
        doNothing().when(auditLogService).logRiskAssessment(any());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        SOC2ComplianceService.RiskAssessment result = soc2ComplianceService.assessRisk(resource, action, testUserId);

        // Then
        assertNotNull(result);
        assertTrue(result.getRiskScore() > 70, "Should be HIGH risk");
        assertEquals("HIGH", result.getRiskLevel());
    }

    @Test
    void testMonitorActivity_WithAnomalousActivity_DetectsAnomaly() {
        // Given
        String activityType = "LOGIN";
        String details = "unauthorized access attempt";
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.monitorActivity(activityType, details);

        // Then
        verify(cloudWatchClient, atLeast(2)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogControlActivityWithStatus_WithPassStatus_LogsSuccess() {
        // Given
        String status = "PASS";
        String details = "Control validated";
        doNothing().when(auditLogService).logControlActivity(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.logControlActivityWithStatus(testControlId, status, details);

        // Then
        verify(auditLogService, times(1)).logControlActivity(testControlId, status, details);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAccessControl_WithAllowedAccess_LogsSuccess() {
        // Given
        String resource = "/api/transactions";
        String action = "READ";
        boolean allowed = true;
        doNothing().when(auditLogService).logAccessControl(anyString(), anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.logAccessControl(resource, action, testUserId, allowed);

        // Then
        verify(auditLogService, times(1)).logAccessControl(resource, action, testUserId, allowed);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testCheckSystemHealth_ReturnsHealthMetrics() {
        // Given
        doNothing().when(auditLogService).logSystemHealth(any());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        SOC2ComplianceService.SystemHealth result = soc2ComplianceService.checkSystemHealth();

        // Then
        assertNotNull(result);
        assertNotNull(result.getTimestamp());
        assertTrue(result.getAvailability() > 0);
        assertTrue(result.getPerformance() > 0);
        assertTrue(result.getErrorRate() >= 0);
        verify(auditLogService, times(1)).logSystemHealth(any());
    }

    @Test
    void testLogChangeManagement_WithValidInput_LogsChange() {
        // Given
        String changeId = "CHG-001";
        String changeType = "DEPLOYMENT";
        String description = "Deploy new feature";
        doNothing().when(auditLogService).logChangeManagement(anyString(), anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        soc2ComplianceService.logChangeManagement(changeId, changeType, description, testUserId);

        // Then
        verify(auditLogService, times(1)).logChangeManagement(changeId, changeType, description, testUserId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }
}

