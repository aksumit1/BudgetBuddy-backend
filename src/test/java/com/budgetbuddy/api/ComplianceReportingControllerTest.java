package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.financial.FinancialComplianceService;
import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService;
import com.budgetbuddy.compliance.soc2.SOC2ComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for ComplianceReportingController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class ComplianceReportingControllerTest {

    @Mock private SOC2ComplianceService soc2ComplianceService;

    @Mock private HIPAAComplianceService hipaaComplianceService;

    @Mock private ISO27001ComplianceService iso27001ComplianceService;

    @Mock private FinancialComplianceService financialComplianceService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private ComplianceReportingController controller;

    private UserTable testUser;
    private UserTable adminUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setRoles(java.util.Set.of("USER"));

        adminUser = new UserTable();
        adminUser.setUserId("admin-123");
        adminUser.setEmail("admin@example.com");
        adminUser.setRoles(java.util.Set.of("ADMIN", "COMPLIANCE"));

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGetSOC2ReportWithAdminAccessReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        final SOC2ComplianceService.SystemHealth health =
                mock(SOC2ComplianceService.SystemHealth.class);
        when(soc2ComplianceService.checkSystemHealth()).thenReturn(health);

        // When
        final ResponseEntity<SOC2ComplianceService.SystemHealth> response =
                controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(soc2ComplianceService).checkSystemHealth();
    }

    @Test
    void testGetSOC2ReportWithUserAccessReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<SOC2ComplianceService.SystemHealth> response =
                controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(soc2ComplianceService, never()).checkSystemHealth();
    }

    @Test
    void testGetHIPAABreachesWithAdminAccessReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        final ResponseEntity<HIPAAComplianceService.BreachReport> response =
                controller.getHIPAABreaches(userDetails, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetHIPAABreachesWithUserAccessReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<HIPAAComplianceService.BreachReport> response =
                controller.getHIPAABreaches(userDetails, null);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetISO27001IncidentsWithAdminAccessReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        final ResponseEntity<ISO27001ComplianceService.SecurityIncident> response =
                controller.getISO27001Incidents(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetISO27001IncidentsWithUserAccessReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<ISO27001ComplianceService.SecurityIncident> response =
                controller.getISO27001Incidents(userDetails);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetFinancialComplianceReportWithAdminAccessReturnsReport() {
        // Given
        final Instant startDate = Instant.now().minusSeconds(86_400);
        final Instant endDate = Instant.now();
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        final ResponseEntity<ComplianceReportingController.FinancialComplianceReport> response =
                controller.getFinancialComplianceReport(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(startDate, response.getBody().getStartDate());
        assertEquals(endDate, response.getBody().getEndDate());
        assertTrue(response.getBody().isCompliant());
    }

    @Test
    void testGetFinancialComplianceReportWithUserAccessReturnsForbidden() {
        // Given
        final Instant startDate = Instant.now().minusSeconds(86_400);
        final Instant endDate = Instant.now();
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<ComplianceReportingController.FinancialComplianceReport> response =
                controller.getFinancialComplianceReport(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetSOC2ReportWithComplianceRoleReturnsReport() {
        // Given
        final UserTable complianceUser = new UserTable();
        complianceUser.setUserId("compliance-123");
        complianceUser.setEmail("compliance@example.com");
        complianceUser.setRoles(java.util.Set.of("COMPLIANCE"));

        when(userService.findByEmail("compliance@example.com"))
                .thenReturn(Optional.of(complianceUser));
        when(userDetails.getUsername()).thenReturn("compliance@example.com");

        final SOC2ComplianceService.SystemHealth health =
                mock(SOC2ComplianceService.SystemHealth.class);
        when(soc2ComplianceService.checkSystemHealth()).thenReturn(health);

        // When
        final ResponseEntity<SOC2ComplianceService.SystemHealth> response =
                controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
