package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.service.TaxExportService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit tests for TaxExportController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@DisplayName("Tax Export Controller Tests")
class TaxExportControllerTest {

    private static final String USER123 = "user123";

    @Mock private TaxExportService taxExportService;

    @Mock private UserDetails userDetails;

    @InjectMocks private TaxExportController taxExportController;

    private TaxExportService.TaxExportResult mockResult;
    private TaxExportService.TaxSummary mockSummary;

    @BeforeEach
    void setUp() {
        // Create mock tax export result
        mockResult = new TaxExportService.TaxExportResult();
        mockSummary = new TaxExportService.TaxSummary();
        mockSummary.setTotalSalary(new BigDecimal("50000.00"));
        mockSummary.setTotalInterest(new BigDecimal("250.00"));
        mockSummary.setTotalCharity(new BigDecimal("500.00"));

        // Note: TaxExportResult doesn't have setters, so we'll use reflection or mock the service
    }

    @Test
    @DisplayName("Should export tax data as CSV")
    void testExportTaxDataCSVSuccess() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final String csvContent = "Tax Year: 2024\nSUMMARY\n...";

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, year, null, null, null, null))
                .thenReturn(mockResult);
        when(taxExportService.exportToCSV(mockResult, year)).thenReturn(csvContent);

        // When
        final ResponseEntity<String> response =
                taxExportController.exportTaxDataCSV(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Tax Year: 2024"));
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().toString().contains("text/csv"));
    }

    @Test
    @DisplayName("Should export tax data as JSON")
    void testExportTaxDataJSONSuccess() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final String jsonContent = "{\"taxYear\": 2024, \"summary\": {...}}";

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, year, null, null, null, null))
                .thenReturn(mockResult);
        when(taxExportService.exportToJSON(mockResult, year)).thenReturn(jsonContent);

        // When
        final ResponseEntity<String> response =
                taxExportController.exportTaxDataJSON(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"taxYear\""));
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().toString().contains("application/json"));
    }

    @Test
    @DisplayName("Should return tax summary")
    void testGetTaxSummarySuccess() {
        // Given
        final String userId = USER123;
        final int year = 2024;

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, year, null, null, null, null))
                .thenReturn(mockResult);

        // When
        final ResponseEntity<TaxExportService.TaxSummary> response =
                taxExportController.getTaxSummary(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("Should use current year when year is 0")
    void testExportTaxDataCSVCurrentYear() {
        // Given
        final String userId = USER123;
        final int year = 0; // Should default to current year
        final int currentYear = java.time.Year.now().getValue();
        final String csvContent = "Tax Year: " + currentYear + "\n...";

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, currentYear, null, null, null, null))
                .thenReturn(mockResult);
        when(taxExportService.exportToCSV(mockResult, currentYear)).thenReturn(csvContent);

        // When
        final ResponseEntity<String> response =
                taxExportController.exportTaxDataCSV(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(taxExportService).generateTaxExport(userId, currentYear, null, null, null, null);
    }

    @Test
    @DisplayName("Should handle errors gracefully")
    void testExportTaxDataCSVErrorHandling() {
        // Given
        final String userId = USER123;
        final int year = 2024;

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, year, null, null, null, null))
                .thenThrow(new RuntimeException("Database error"));

        // When
        final ResponseEntity<String> response =
                taxExportController.exportTaxDataCSV(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Error generating tax export"));
    }

    @Test
    @DisplayName("Should handle JSON export errors gracefully")
    void testExportTaxDataJSONErrorHandling() {
        // Given
        final String userId = USER123;
        final int year = 2024;

        when(userDetails.getUsername()).thenReturn(userId);
        when(taxExportService.generateTaxExport(userId, year, null, null, null, null))
                .thenThrow(new RuntimeException("Database error"));

        // When
        final ResponseEntity<String> response =
                taxExportController.exportTaxDataJSON(year, null, null, null, null, userDetails);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("error"));
    }
}
