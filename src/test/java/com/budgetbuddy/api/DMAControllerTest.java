package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for DMAController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DMAControllerTest {

    @Mock private DMAComplianceService dmaComplianceService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private DMAController controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testExportDataWithJSONFormatReturnsJSON() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "JSON"))
                .thenReturn("{\"data\":\"test\"}");

        // When
        final ResponseEntity<String> response = controller.exportData(userDetails, "JSON");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertTrue(
                response.getHeaders()
                        .getContentDisposition()
                        .toString()
                        .contains("user-data.json"));
    }

    @Test
    void testExportDataWithCSVFormatReturnsCSV() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "CSV"))
                .thenReturn("col1,col2\nval1,val2");

        // When
        final ResponseEntity<String> response = controller.exportData(userDetails, "CSV");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.parseMediaType("text/csv"), response.getHeaders().getContentType());
        assertTrue(
                response.getHeaders().getContentDisposition().toString().contains("user-data.csv"));
    }

    @Test
    void testExportDataWithXMLFormatReturnsXML() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "XML"))
                .thenReturn("<data>test</data>");

        // When
        final ResponseEntity<String> response = controller.exportData(userDetails, "XML");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_XML, response.getHeaders().getContentType());
        assertTrue(
                response.getHeaders().getContentDisposition().toString().contains("user-data.xml"));
    }

    @Test
    void testExportDataWithDefaultFormatReturnsJSON() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "JSON"))
                .thenReturn("{\"data\":\"test\"}");

        // When - Pass "JSON" explicitly (default value from @RequestParam)
        final ResponseEntity<String> response = controller.exportData(userDetails, "JSON");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void testExportDataWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.exportData(null, "JSON"));
    }

    @Test
    void testGetInteroperabilityEndpointWithValidUserReturnsEndpoint() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.getInteroperabilityEndpoint("user-123"))
                .thenReturn("https://api.example.com/interop/user-123");

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.getInteroperabilityEndpoint(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "https://api.example.com/interop/user-123", response.getBody().get("endpoint"));
        assertEquals("user-123", response.getBody().get("userId"));
    }

    @Test
    void testGetInteroperabilityEndpointWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.getInteroperabilityEndpoint(null));
    }

    @Test
    void testAuthorizeThirdPartyAccessWithValidRequestReturnsAuthorized() {
        // Given
        final DMAController.AuthorizeThirdPartyRequest request =
                new DMAController.AuthorizeThirdPartyRequest();
        request.setThirdPartyId("third-party-123");
        request.setScope("read:transactions");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.authorizeThirdPartyAccess(
                        "user-123", "third-party-123", "read:transactions"))
                .thenReturn(true);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.authorizeThirdPartyAccess(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("authorized"));
        assertEquals("third-party-123", response.getBody().get("thirdPartyId"));
    }

    @Test
    void testAuthorizeThirdPartyAccessWithNullRequestThrowsException() {
        // When/Then
        assertThrows(
                AppException.class, () -> controller.authorizeThirdPartyAccess(userDetails, null));
    }

    @Test
    void testAuthorizeThirdPartyAccessWithMissingFieldsThrowsException() {
        // Given
        final DMAController.AuthorizeThirdPartyRequest request =
                new DMAController.AuthorizeThirdPartyRequest();
        request.setThirdPartyId(null);
        request.setScope("read:transactions");

        // When/Then
        assertThrows(
                AppException.class,
                () -> controller.authorizeThirdPartyAccess(userDetails, request));
    }

    @Test
    void testShareDataWithThirdPartyWithValidRequestReturnsData() {
        // Given
        final DMAController.ShareDataRequest request = new DMAController.ShareDataRequest();
        request.setThirdPartyId("third-party-123");
        request.setDataType("transactions");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.shareDataWithThirdParty(
                        "user-123", "third-party-123", "transactions"))
                .thenReturn("{\"transactions\":[]}");

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.shareDataWithThirdParty(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("third-party-123", response.getBody().get("thirdPartyId"));
        assertEquals("transactions", response.getBody().get("dataType"));
    }

    @Test
    void testShareDataWithThirdPartyWithNullRequestThrowsException() {
        // When/Then
        assertThrows(
                AppException.class, () -> controller.shareDataWithThirdParty(userDetails, null));
    }

    @Test
    void testShareDataWithThirdPartyWithMissingFieldsThrowsException() {
        // Given
        final DMAController.ShareDataRequest request = new DMAController.ShareDataRequest();
        request.setThirdPartyId(null);
        request.setDataType("transactions");

        // When/Then
        assertThrows(
                AppException.class, () -> controller.shareDataWithThirdParty(userDetails, request));
    }
}
