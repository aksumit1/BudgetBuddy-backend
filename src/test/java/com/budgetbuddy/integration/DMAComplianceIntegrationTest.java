package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for DMA Compliance
 * Tests DMA compliance service and data export
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DMA Compliance Integration Tests")
class DMAComplianceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DMAComplianceIntegrationTest.class);

    @Autowired
    private DMAComplianceService dmaComplianceService;

    @Autowired
    private UserService userService;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testEmail = "test-dma-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash = java.util.Base64.getEncoder().encodeToString("test-password-hash".getBytes());
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - JSON Format")
    void testDMA_ExportDataJSON_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        String data = dmaComplianceService.exportDataPortable(userId, "JSON");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("\"userId\""), "JSON should contain userId");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - CSV Format")
    void testDMA_ExportDataCSV_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        String data = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("DataType"), "CSV should contain header");
        assertTrue(data.contains("USER"), "CSV should contain user data");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - XML Format")
    void testDMA_ExportDataXML_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        String data = dmaComplianceService.exportDataPortable(userId, "XML");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("<?xml"), "Should be valid XML");
        assertTrue(data.contains("<DMAExport"), "Should contain DMAExport root element");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - Unsupported Format")
    void testDMA_ExportDataUnsupportedFormat_ThrowsException() {
        // Given
        String userId = testUser.getUserId();

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            dmaComplianceService.exportDataPortable(userId, "UNSUPPORTED");
        }, "Should throw for unsupported format");
    }

    @Test
    @DisplayName("DMA Article 7: Interoperability Endpoint")
    void testDMA_GetInteroperabilityEndpoint_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        String endpoint = dmaComplianceService.getInteroperabilityEndpoint(userId);

        // Then
        assertNotNull(endpoint);
        assertFalse(endpoint.isEmpty());
        assertTrue(endpoint.contains("/api/dma/interoperability/"), "Should contain interoperability path");
        assertTrue(endpoint.contains(userId), "Should contain userId");
    }

    @Test
    @DisplayName("DMA Article 8: Fair Access - Authorize Third Party")
    void testDMA_AuthorizeThirdParty_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String thirdPartyId = "third-party-123";
        String scope = "read:transactions";

        // When
        boolean authorized = dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, scope);

        // Then
        assertTrue(authorized, "Third-party access should be authorized");
    }

    @Test
    @DisplayName("DMA Article 9: Data Sharing")
    void testDMA_ShareDataWithThirdParty_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String thirdPartyId = "third-party-123";
        String dataType = "transactions";

        // When - First authorize, then share
        dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, dataType);
        String data = dmaComplianceService.shareDataWithThirdParty(userId, thirdPartyId, dataType);

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
    }
}

