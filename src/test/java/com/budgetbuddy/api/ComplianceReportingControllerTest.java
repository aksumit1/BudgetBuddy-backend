package com.budgetbuddy.api;

import com.budgetbuddy.compliance.financial.FinancialComplianceService;
import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService;
import com.budgetbuddy.compliance.soc2.SOC2ComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for ComplianceReportingController
 */
@ExtendWith(MockitoExtension.class)
class ComplianceReportingControllerTest {

    @Mock
    private SOC2ComplianceService soc2ComplianceService;

    @Mock
    private HIPAAComplianceService hipaaComplianceService;

    @Mock
    private ISO27001ComplianceService iso27001ComplianceService;

    @Mock
    private FinancialComplianceService financialComplianceService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private ComplianceReportingController controller;

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
    void testGetSOC2Report_WithAdminAccess_ReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");
        
        SOC2ComplianceService.SystemHealth health = mock(SOC2ComplianceService.SystemHealth.class);
        when(soc2ComplianceService.checkSystemHealth()).thenReturn(health);

        // When
        ResponseEntity<SOC2ComplianceService.SystemHealth> response = controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(soc2ComplianceService).checkSystemHealth();
    }

    @Test
    void testGetSOC2Report_WithUserAccess_ReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<SOC2ComplianceService.SystemHealth> response = controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(soc2ComplianceService, never()).checkSystemHealth();
    }

    @Test
    void testGetHIPAABreaches_WithAdminAccess_ReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        ResponseEntity<HIPAAComplianceService.BreachReport> response = 
                controller.getHIPAABreaches(userDetails, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetHIPAABreaches_WithUserAccess_ReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<HIPAAComplianceService.BreachReport> response = 
                controller.getHIPAABreaches(userDetails, null);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetISO27001Incidents_WithAdminAccess_ReturnsReport() {
        // Given
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        ResponseEntity<ISO27001ComplianceService.SecurityIncident> response = 
                controller.getISO27001Incidents(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetISO27001Incidents_WithUserAccess_ReturnsForbidden() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<ISO27001ComplianceService.SecurityIncident> response = 
                controller.getISO27001Incidents(userDetails);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetFinancialComplianceReport_WithAdminAccess_ReturnsReport() {
        // Given
        Instant startDate = Instant.now().minusSeconds(86400);
        Instant endDate = Instant.now();
        when(userService.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetails.getUsername()).thenReturn("admin@example.com");

        // When
        ResponseEntity<ComplianceReportingController.FinancialComplianceReport> response = 
                controller.getFinancialComplianceReport(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(startDate, response.getBody().getStartDate());
        assertEquals(endDate, response.getBody().getEndDate());
        assertTrue(response.getBody().isCompliant());
    }

    @Test
    void testGetFinancialComplianceReport_WithUserAccess_ReturnsForbidden() {
        // Given
        Instant startDate = Instant.now().minusSeconds(86400);
        Instant endDate = Instant.now();
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<ComplianceReportingController.FinancialComplianceReport> response = 
                controller.getFinancialComplianceReport(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void testGetSOC2Report_WithComplianceRole_ReturnsReport() {
        // Given
        UserTable complianceUser = new UserTable();
        complianceUser.setUserId("compliance-123");
        complianceUser.setEmail("compliance@example.com");
        complianceUser.setRoles(java.util.Set.of("COMPLIANCE"));
        
        when(userService.findByEmail("compliance@example.com")).thenReturn(Optional.of(complianceUser));
        when(userDetails.getUsername()).thenReturn("compliance@example.com");
        
        SOC2ComplianceService.SystemHealth health = mock(SOC2ComplianceService.SystemHealth.class);
        when(soc2ComplianceService.checkSystemHealth()).thenReturn(health);

        // When
        ResponseEntity<SOC2ComplianceService.SystemHealth> response = controller.getSOC2Report(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}

