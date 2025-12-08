package com.budgetbuddy.compliance;

import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.security.zerotrust.identity.IdentityVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HIPAAComplianceService
 * Tests role-based access control for PHI access
 */
@ExtendWith(MockitoExtension.class)
class HIPAAComplianceServiceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CloudWatchClient cloudWatchClient;

    @Mock
    private IdentityVerificationService identityVerificationService;

    private HIPAAComplianceService hipaaComplianceService;

    private final String TEST_USER_ID = "test-user-123";
    private final String ADMIN_USER_ID = "admin-user-456";

    @BeforeEach
    void setUp() {
        hipaaComplianceService = new HIPAAComplianceService(
                auditLogService,
                cloudWatchClient,
                identityVerificationService
        );
    }

    @Test
    void checkPHIAccessPolicy_AdminUser_HasFullAccess() {
        // Given - Admin user
        when(identityVerificationService.getUserRoles(ADMIN_USER_ID))
                .thenReturn(Set.of("ADMIN", "USER"));

        // When - Check access to financial PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(ADMIN_USER_ID, "financial");

        // Then - Admin should have access
        assertTrue(hasAccess, "Admin should have full access to all PHI types");
        verify(auditLogService).logPHIAccess(ADMIN_USER_ID, "financial", "READ", true);
    }

    @Test
    void checkPHIAccessPolicy_RegularUser_HasAccessToOwnFinancialData() {
        // Given - Regular user
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER"));

        // When - Check access to financial PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "financial");

        // Then - User should have access to their own financial data
        assertTrue(hasAccess, "Regular user should have access to their own financial data");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "financial", "READ", true);
    }

    @Test
    void checkPHIAccessPolicy_RegularUser_HasAccessToOwnPersonalData() {
        // Given - Regular user
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER"));

        // When - Check access to personal PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "personal");

        // Then - User should have access to their own personal data
        assertTrue(hasAccess, "Regular user should have access to their own personal data");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "personal", "READ", true);
    }

    @Test
    void checkPHIAccessPolicy_RegularUser_NoAccessToHealthDataWithoutPermission() {
        // Given - Regular user without health access permission
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER"));

        // When - Check access to health PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "health");

        // Then - User should NOT have access without HEALTH_ACCESS role
        assertFalse(hasAccess, "Regular user should not have access to health data without permission");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "health", "READ", false);
    }

    @Test
    void checkPHIAccessPolicy_UserWithHealthAccess_HasAccessToHealthData() {
        // Given - User with health access permission
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER", "HEALTH_ACCESS"));

        // When - Check access to health PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "health");

        // Then - User should have access
        assertTrue(hasAccess, "User with HEALTH_ACCESS role should have access to health data");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "health", "READ", true);
    }

    @Test
    void checkPHIAccessPolicy_UserWithNoRoles_DeniedAccess() {
        // Given - User with no roles
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of());

        // When - Check access to financial PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "financial");

        // Then - User should NOT have access
        assertFalse(hasAccess, "User with no roles should be denied access");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "financial", "READ", false);
    }

    @Test
    void checkPHIAccessPolicy_UserWithNullRoles_DeniedAccess() {
        // Given - User with null roles
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(null);

        // When - Check access to financial PHI
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "financial");

        // Then - User should NOT have access
        assertFalse(hasAccess, "User with null roles should be denied access");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "financial", "READ", false);
    }

    @Test
    void checkPHIAccessPolicy_UnknownPHIType_DeniedAccess() {
        // Given - Regular user
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER"));

        // When - Check access to unknown PHI type
        boolean hasAccess = hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "unknown");

        // Then - User should NOT have access to unknown types
        assertFalse(hasAccess, "User should be denied access to unknown PHI types");
        verify(auditLogService).logPHIAccess(TEST_USER_ID, "unknown", "READ", false);
    }

    @Test
    void checkPHIAccessPolicy_AlwaysLogsAccessAttempt() {
        // Given - Regular user
        when(identityVerificationService.getUserRoles(TEST_USER_ID))
                .thenReturn(Set.of("USER"));

        // When - Check access
        hipaaComplianceService.checkPHIAccessPolicy(TEST_USER_ID, "financial");

        // Then - Should always log the access attempt
        verify(auditLogService, times(1)).logPHIAccess(
                eq(TEST_USER_ID),
                eq("financial"),
                eq("READ"),
                anyBoolean()
        );
    }
}

