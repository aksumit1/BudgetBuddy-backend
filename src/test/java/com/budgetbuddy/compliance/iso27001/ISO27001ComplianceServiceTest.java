package com.budgetbuddy.compliance.iso27001;

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
 * Unit Tests for ISO27001ComplianceService
 * Tests ISO/IEC 27001 compliance functionality
 */
@ExtendWith(MockitoExtension.class)
class ISO27001ComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    @InjectMocks
    private ISO27001ComplianceService iso27001ComplianceService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
    }

    @Test
    void testLogUserRegistration_WithValidInput_LogsRegistration() {
        // Given
        String registrationType = "EMAIL";
        doNothing().when(auditLogService).logUserRegistration(anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logUserRegistration(testUserId, registrationType);

        // Then
        verify(auditLogService, times(1)).logUserRegistration(testUserId, registrationType);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAccessProvisioning_WithValidInput_LogsProvisioning() {
        // Given
        String resource = "/api/accounts";
        String accessLevel = "READ";
        doNothing().when(auditLogService).logAccessProvisioning(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logAccessProvisioning(testUserId, resource, accessLevel);

        // Then
        verify(auditLogService, times(1)).logAccessProvisioning(testUserId, resource, accessLevel);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPrivilegedAccess_WithValidInput_LogsAccess() {
        // Given
        String privilege = "ADMIN";
        String resource = "/api/admin";
        doNothing().when(auditLogService).logPrivilegedAccess(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logPrivilegedAccess(testUserId, privilege, resource);

        // Then
        verify(auditLogService, times(1)).logPrivilegedAccess(testUserId, privilege, resource);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCredentialChange_WithValidInput_LogsChange() {
        // Given
        String changeType = "PASSWORD_RESET";
        doNothing().when(auditLogService).logCredentialChange(anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logCredentialChange(testUserId, changeType);

        // Then
        verify(auditLogService, times(1)).logCredentialChange(testUserId, changeType);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReviewUserAccessRights_WithValidUserId_LogsReview() {
        // Given
        doNothing().when(auditLogService).logAccessReview(any());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.reviewUserAccessRights(testUserId);

        // Then
        verify(auditLogService, times(1)).logAccessReview(any());
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAccessRemoval_WithValidInput_LogsRemoval() {
        // Given
        String resource = "/api/accounts";
        String reason = "User terminated";
        doNothing().when(auditLogService).logAccessRemoval(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logAccessRemoval(testUserId, resource, reason);

        // Then
        verify(auditLogService, times(1)).logAccessRemoval(testUserId, resource, reason);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSecureLogon_WithSuccess_LogsSuccess() {
        // Given
        String method = "PASSWORD";
        boolean success = true;
        doNothing().when(auditLogService).logSecureLogon(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logSecureLogon(testUserId, method, success);

        // Then
        verify(auditLogService, times(1)).logSecureLogon(testUserId, method, success);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogPasswordManagement_WithValidInput_LogsActivity() {
        // Given
        String activity = "PASSWORD_CHANGED";
        doNothing().when(auditLogService).logPasswordManagement(anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logPasswordManagement(testUserId, activity);

        // Then
        verify(auditLogService, times(1)).logPasswordManagement(testUserId, activity);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSecurityEvent_WithValidInput_LogsEvent() {
        // Given
        String eventType = "UNAUTHORIZED_ACCESS";
        String severity = "HIGH";
        String details = "Multiple failed login attempts";
        doNothing().when(auditLogService).logSecurityEvent(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logSecurityEvent(eventType, severity, details);

        // Then
        verify(auditLogService, times(1)).logSecurityEvent(eventType, severity, details);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testProtectLogInformation_WithValidLogId_ProtectsLog() {
        // Given
        String logId = "log-123";
        doNothing().when(auditLogService).protectLogInformation(anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.protectLogInformation(logId);

        // Then
        verify(auditLogService, times(1)).protectLogInformation(logId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogAdministratorActivity_WithValidInput_LogsActivity() {
        // Given
        String adminId = "admin-123";
        String activity = "USER_DELETED";
        String resource = "/api/users/user-456";
        doNothing().when(auditLogService).logAdministratorActivity(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logAdministratorActivity(adminId, activity, resource);

        // Then
        verify(auditLogService, times(1)).logAdministratorActivity(adminId, activity, resource);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogClockSynchronization_LogsSynchronization() {
        // Given
        doNothing().when(auditLogService).logClockSynchronization(anyLong());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logClockSynchronization();

        // Then
        verify(auditLogService, times(1)).logClockSynchronization(anyLong());
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReportSecurityIncident_WithCriticalSeverity_LogsIncident() {
        // Given
        String incidentType = "DATA_BREACH";
        String severity = "CRITICAL";
        String details = "Unauthorized data access detected";
        doNothing().when(auditLogService).logSecurityIncident(any());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.reportSecurityIncident(incidentType, severity, details);

        // Then
        verify(auditLogService, times(1)).logSecurityIncident(any());
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogComplianceCheck_WithCompliant_LogsCompliance() {
        // Given
        String legislation = "GDPR";
        boolean compliant = true;
        doNothing().when(auditLogService).logComplianceCheck(anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logComplianceCheck(legislation, compliant);

        // Then
        verify(auditLogService, times(1)).logComplianceCheck(legislation, compliant);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogComplianceCheck_WithNonCompliant_LogsWarning() {
        // Given
        String legislation = "GDPR";
        boolean compliant = false;
        doNothing().when(auditLogService).logComplianceCheck(anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        iso27001ComplianceService.logComplianceCheck(legislation, compliant);

        // Then
        verify(auditLogService, times(1)).logComplianceCheck(legislation, compliant);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }
}

