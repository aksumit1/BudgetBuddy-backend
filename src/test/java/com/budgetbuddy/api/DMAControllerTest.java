package com.budgetbuddy.api;

import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DMAController
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DMAControllerTest {

    @Mock
    private DMAComplianceService dmaComplianceService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private DMAController controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testExportData_WithJSONFormat_ReturnsJSON() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "JSON")).thenReturn("{\"data\":\"test\"}");

        // When
        ResponseEntity<String> response = controller.exportData(userDetails, "JSON");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("user-data.json"));
    }

    @Test
    void testExportData_WithCSVFormat_ReturnsCSV() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "CSV")).thenReturn("col1,col2\nval1,val2");

        // When
        ResponseEntity<String> response = controller.exportData(userDetails, "CSV");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.parseMediaType("text/csv"), response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("user-data.csv"));
    }

    @Test
    void testExportData_WithXMLFormat_ReturnsXML() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "XML")).thenReturn("<data>test</data>");

        // When
        ResponseEntity<String> response = controller.exportData(userDetails, "XML");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_XML, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("user-data.xml"));
    }

    @Test
    void testExportData_WithDefaultFormat_ReturnsJSON() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.exportDataPortable("user-123", "JSON")).thenReturn("{\"data\":\"test\"}");

        // When - Pass "JSON" explicitly (default value from @RequestParam)
        ResponseEntity<String> response = controller.exportData(userDetails, "JSON");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void testExportData_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.exportData(null, "JSON"));
    }

    @Test
    void testGetInteroperabilityEndpoint_WithValidUser_ReturnsEndpoint() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.getInteroperabilityEndpoint("user-123")).thenReturn("https://api.example.com/interop/user-123");

        // When
        ResponseEntity<Map<String, Object>> response = controller.getInteroperabilityEndpoint(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("https://api.example.com/interop/user-123", response.getBody().get("endpoint"));
        assertEquals("user-123", response.getBody().get("userId"));
    }

    @Test
    void testGetInteroperabilityEndpoint_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.getInteroperabilityEndpoint(null));
    }

    @Test
    void testAuthorizeThirdPartyAccess_WithValidRequest_ReturnsAuthorized() {
        // Given
        DMAController.AuthorizeThirdPartyRequest request = new DMAController.AuthorizeThirdPartyRequest();
        request.setThirdPartyId("third-party-123");
        request.setScope("read:transactions");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.authorizeThirdPartyAccess("user-123", "third-party-123", "read:transactions"))
                .thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = controller.authorizeThirdPartyAccess(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("authorized"));
        assertEquals("third-party-123", response.getBody().get("thirdPartyId"));
    }

    @Test
    void testAuthorizeThirdPartyAccess_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.authorizeThirdPartyAccess(userDetails, null));
    }

    @Test
    void testAuthorizeThirdPartyAccess_WithMissingFields_ThrowsException() {
        // Given
        DMAController.AuthorizeThirdPartyRequest request = new DMAController.AuthorizeThirdPartyRequest();
        request.setThirdPartyId(null);
        request.setScope("read:transactions");

        // When/Then
        assertThrows(AppException.class, () -> controller.authorizeThirdPartyAccess(userDetails, request));
    }

    @Test
    void testShareDataWithThirdParty_WithValidRequest_ReturnsData() {
        // Given
        DMAController.ShareDataRequest request = new DMAController.ShareDataRequest();
        request.setThirdPartyId("third-party-123");
        request.setDataType("transactions");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(dmaComplianceService.shareDataWithThirdParty("user-123", "third-party-123", "transactions"))
                .thenReturn("{\"transactions\":[]}");

        // When
        ResponseEntity<Map<String, Object>> response = controller.shareDataWithThirdParty(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("third-party-123", response.getBody().get("thirdPartyId"));
        assertEquals("transactions", response.getBody().get("dataType"));
    }

    @Test
    void testShareDataWithThirdParty_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.shareDataWithThirdParty(userDetails, null));
    }

    @Test
    void testShareDataWithThirdParty_WithMissingFields_ThrowsException() {
        // Given
        DMAController.ShareDataRequest request = new DMAController.ShareDataRequest();
        request.setThirdPartyId(null);
        request.setDataType("transactions");

        // When/Then
        assertThrows(AppException.class, () -> controller.shareDataWithThirdParty(userDetails, request));
    }
}

