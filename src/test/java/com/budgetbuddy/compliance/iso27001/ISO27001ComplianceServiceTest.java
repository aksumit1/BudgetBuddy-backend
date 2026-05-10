package com.budgetbuddy.compliance.iso27001;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

/** Comprehensive tests for ISO27001ComplianceService */
class ISO27001ComplianceServiceTest {

    private static final String USER_123 = "user-123";

    @Mock private AuditLogService auditLogService;

    @Mock private CloudWatchClient cloudWatchClient;

    private ISO27001ComplianceService iso27001ComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        iso27001ComplianceService =
                new ISO27001ComplianceService(auditLogService, cloudWatchClient);
    }

    @Test
    @DisplayName("Should log user registration")
    void testLogUserRegistration() {
        // Given
        final String userId = USER_123;
        final String registrationType = "EMAIL";

        // When
        iso27001ComplianceService.logUserRegistration(userId, registrationType);

        // Then
        verify(auditLogService).logUserRegistration(eq(userId), eq(registrationType));
    }

    @Test
    @DisplayName("Should log access provisioning")
    void testLogAccessProvisioning() {
        // Given
        final String userId = USER_123;
        final String resource = "/api/transactions";
        final String accessLevel = "READ";

        // When
        iso27001ComplianceService.logAccessProvisioning(userId, resource, accessLevel);

        // Then
        verify(auditLogService).logAccessProvisioning(eq(userId), eq(resource), eq(accessLevel));
    }

    @Test
    @DisplayName("Should log privileged access")
    void testLogPrivilegedAccess() {
        // Given
        final String userId = "admin-123";
        final String privilege = "ADMIN";
        final String resource = "/api/admin";

        // When
        iso27001ComplianceService.logPrivilegedAccess(userId, privilege, resource);

        // Then
        verify(auditLogService).logPrivilegedAccess(eq(userId), eq(privilege), eq(resource));
    }

    @Test
    @DisplayName("Should log credential change")
    void testLogCredentialChange() {
        // Given
        final String userId = USER_123;
        final String changeType = "PASSWORD_RESET";

        // When
        iso27001ComplianceService.logCredentialChange(userId, changeType);

        // Then
        verify(auditLogService).logCredentialChange(eq(userId), eq(changeType));
    }

    @Test
    @DisplayName("Should review user access rights")
    void testReviewUserAccessRights() {
        // Given
        final String userId = USER_123;

        // When
        iso27001ComplianceService.reviewUserAccessRights(userId);

        // Then
        verify(auditLogService).logAccessReview(any());
    }

    @Test
    @DisplayName("Should log access removal")
    void testLogAccessRemoval() {
        // Given
        final String userId = USER_123;
        final String resource = "/api/admin";
        final String reason = "Termination";

        // When
        iso27001ComplianceService.logAccessRemoval(userId, resource, reason);

        // Then
        verify(auditLogService).logAccessRemoval(eq(userId), eq(resource), eq(reason));
    }

    @Test
    @DisplayName("Should log secure logon")
    void testLogSecureLogon() {
        // Given
        final String userId = USER_123;
        final String method = "MFA_TOTP";

        // When
        iso27001ComplianceService.logSecureLogon(userId, method, true);

        // Then
        verify(auditLogService).logSecureLogon(eq(userId), eq(method), eq(true));
    }

    @Test
    @DisplayName("Should log password management")
    void testLogPasswordManagement() {
        // Given
        final String userId = USER_123;
        final String activity = "PASSWORD_CHANGE";

        // When
        iso27001ComplianceService.logPasswordManagement(userId, activity);

        // Then
        verify(auditLogService).logPasswordManagement(eq(userId), eq(activity));
    }

    @Test
    @DisplayName("Should log security event")
    void testLogSecurityEvent() {
        // Given
        final String eventType = "UNAUTHORIZED_ACCESS";
        final String severity = "HIGH";
        final String details = "Multiple failed login attempts";

        // When
        iso27001ComplianceService.logSecurityEvent(eventType, severity, details);

        // Then
        verify(auditLogService).logSecurityEvent(eq(eventType), eq(severity), eq(details));
    }

    @Test
    @DisplayName("Should protect log information")
    void testProtectLogInformation() {
        // Given
        final String logId = "log-123";

        // When
        iso27001ComplianceService.protectLogInformation(logId);

        // Then
        verify(auditLogService).protectLogInformation(eq(logId));
    }

    @Test
    @DisplayName("Should log administrator activity")
    void testLogAdministratorActivity() {
        // Given
        final String adminId = "admin-123";
        final String activity = "USER_DELETION";
        final String resource = "/api/users/user-456";

        // When
        iso27001ComplianceService.logAdministratorActivity(adminId, activity, resource);

        // Then
        verify(auditLogService).logAdministratorActivity(eq(adminId), eq(activity), eq(resource));
    }

    @Test
    @DisplayName("Should log clock synchronization")
    void testLogClockSynchronization() {
        // When
        iso27001ComplianceService.logClockSynchronization();

        // Then
        verify(auditLogService).logClockSynchronization(anyLong());
    }

    @Test
    @DisplayName("Should report security incident")
    void testReportSecurityIncident() {
        // Given
        final String incidentType = "DATA_BREACH";
        final String severity = "CRITICAL";
        final String details = "Unauthorized access to user data";

        // When
        iso27001ComplianceService.reportSecurityIncident(incidentType, severity, details);

        // Then
        verify(auditLogService).logSecurityIncident(any());
    }

    @Test
    @DisplayName("Should log compliance check")
    void testLogComplianceCheck() {
        // Given
        final String legislation = "GDPR";

        // When
        iso27001ComplianceService.logComplianceCheck(legislation, true);

        // Then
        verify(auditLogService).logComplianceCheck(eq(legislation), eq(true));
    }
}
