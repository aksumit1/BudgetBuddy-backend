package com.budgetbuddy.api;

import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for ComplianceController
 * Tests GDPR and DMA compliance endpoints
 */
@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock
    private GDPRComplianceService gdprComplianceService;

    @Mock
    private DMAComplianceService dmaComplianceService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private ComplianceController complianceController;

    private UserTable testUser;
    private String testUserId;
    private String testEmail;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";
        
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
    }

    @Test
    void testExportData_WithValidUser_ReturnsGDPRDataExport() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        
        GDPRComplianceService.GDPRDataExport export = new GDPRComplianceService.GDPRDataExport();
        export.setUserId(testUserId);
        when(gdprComplianceService.exportUserData(testUserId)).thenReturn(export);

        // When
        ResponseEntity<GDPRComplianceService.GDPRDataExport> response = 
                complianceController.exportData(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testUserId, response.getBody().getUserId());
        verify(gdprComplianceService, times(1)).exportUserData(testUserId);
    }

    @Test
    void testExportData_WithUserNotFound_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            complianceController.exportData(userDetails);
        });
        verify(gdprComplianceService, never()).exportUserData(anyString());
    }

    @Test
    void testExportDataPortable_WithValidUser_ReturnsJSON() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        
        String jsonData = "{\"userId\":\"" + testUserId + "\",\"data\":\"exported\"}";
        when(gdprComplianceService.exportDataPortable(testUserId)).thenReturn(jsonData);

        // When
        ResponseEntity<String> response = complianceController.exportDataPortable(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(jsonData, response.getBody());
        assertTrue(response.getHeaders().containsKey(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment; filename=user-data.json", 
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        verify(gdprComplianceService, times(1)).exportDataPortable(testUserId);
    }

    @Test
    void testDeleteData_WithConfirmation_DeletesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(gdprComplianceService).deleteUserData(testUserId);

        // When
        ResponseEntity<Void> response = complianceController.deleteData(userDetails, true);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(gdprComplianceService, times(1)).deleteUserData(testUserId);
    }

    @Test
    void testDeleteData_WithoutConfirmation_ReturnsBadRequest() {
        // When
        ResponseEntity<Void> response = complianceController.deleteData(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(gdprComplianceService, never()).deleteUserData(anyString());
    }

    @Test
    void testUpdateData_WithValidRequest_UpdatesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(gdprComplianceService).updateUserData(anyString(), any(UserTable.class));

        ComplianceController.UpdateDataRequest request = new ComplianceController.UpdateDataRequest();
        request.setFirstName("Jane");
        request.setLastName("Smith");
        request.setEmail("jane@example.com");
        request.setPhoneNumber("+1234567890");

        // When
        ResponseEntity<Void> response = complianceController.updateData(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(gdprComplianceService, times(1)).updateUserData(eq(testUserId), any(UserTable.class));
    }

    // Note: exportDataDMA() method was removed (deprecated endpoint)
    // Use DMAController.exportData() instead
    // These tests have been removed as the endpoint no longer exists
}

