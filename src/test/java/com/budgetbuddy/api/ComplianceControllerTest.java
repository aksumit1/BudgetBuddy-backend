package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.util.Optional;
import java.util.UUID;
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

/** Unit Tests for ComplianceController Tests GDPR and DMA compliance endpoints */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock private GDPRComplianceService gdprComplianceService;

    @Mock private DMAComplianceService dmaComplianceService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private ComplianceController complianceController;

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
    void testExportDataWithValidUserReturnsGDPRDataExport() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));

        final GDPRComplianceService.GDPRDataExport export =
                new GDPRComplianceService.GDPRDataExport();
        export.setUserId(testUserId);
        when(gdprComplianceService.exportUserData(testUserId)).thenReturn(export);

        // When
        final ResponseEntity<GDPRComplianceService.GDPRDataExport> response =
                complianceController.exportData(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testUserId, response.getBody().getUserId());
        verify(gdprComplianceService, times(1)).exportUserData(testUserId);
    }

    @Test
    void testExportDataWithUserNotFoundThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    complianceController.exportData(userDetails);
                });
        verify(gdprComplianceService, never()).exportUserData(anyString());
    }

    @Test
    void testExportDataPortableWithValidUserReturnsJSON() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));

        final String jsonData = "{\"userId\":\"" + testUserId + "\",\"data\":\"exported\"}";
        when(gdprComplianceService.exportDataPortable(testUserId)).thenReturn(jsonData);

        // When
        final ResponseEntity<String> response =
                complianceController.exportDataPortable(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(jsonData, response.getBody());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(
                "attachment; filename=user-data.json",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        verify(gdprComplianceService, times(1)).exportDataPortable(testUserId);
    }

    @Test
    void testDeleteDataWithConfirmationDeletesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(gdprComplianceService).deleteUserData(testUserId);

        // When
        final ResponseEntity<Void> response = complianceController.deleteData(userDetails, true);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(gdprComplianceService, times(1)).deleteUserData(testUserId);
    }

    @Test
    void testDeleteDataWithoutConfirmationReturnsBadRequest() {
        // When
        final ResponseEntity<Void> response = complianceController.deleteData(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(gdprComplianceService, never()).deleteUserData(anyString());
    }

    @Test
    void testUpdateDataWithValidRequestUpdatesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(gdprComplianceService).updateUserData(anyString(), any(UserTable.class));

        final ComplianceController.UpdateDataRequest request =
                new ComplianceController.UpdateDataRequest();
        request.setFirstName("Jane");
        request.setLastName("Smith");
        request.setEmail("jane@example.com");
        request.setPhoneNumber("+1234567890");

        // When
        final ResponseEntity<Void> response = complianceController.updateData(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(gdprComplianceService, times(1))
                .updateUserData(eq(testUserId), any(UserTable.class));
    }

    // Note: exportDataDMA() method was removed (deprecated endpoint)
    // Use DMAController.exportData() instead
    // These tests have been removed as the endpoint no longer exists
}
