package com.budgetbuddy.integration;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Integration Tests for DMA Compliance Tests DMA compliance service and data export */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DMA Compliance Integration Tests")
class DMAComplianceIntegrationTest {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DMAComplianceIntegrationTest.class);

    @Autowired private DMAComplianceService dmaComplianceService;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        // Wait for DynamoDB client to be available (Spring context might not be fully initialized
        // yet)
        final int maxAttempts = 10;
        int attempt = 0;
        while (dynamoDbClient == null && attempt < maxAttempts) {
            try {
                Thread.sleep(500); // Wait 500ms for Spring context to initialize
                attempt++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for DynamoDB client", e);
            }
        }

        if (dynamoDbClient == null) {
            LOGGER.error("DynamoDB client not available after {} attempts", maxAttempts);
            throw new IllegalStateException(
                    "DynamoDB client not available after " + maxAttempts + " attempts");
        }

        // Now ensure tables are initialized (including AuditLogs which is required for DMA
        // compliance)
        try {
            TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
            LOGGER.info("✅ Tables initialized and verified for DMA compliance tests");
        } catch (Exception e) {
            LOGGER.error("❌ Failed to initialize tables: {}", e.getMessage(), e);
            throw new RuntimeException(
                    "Failed to initialize DynamoDB tables for DMA compliance tests", e);
        }
    }

    @BeforeEach
    void setUp() {
        // CRITICAL: Verify tables still exist before each test
        // Spring might create new contexts or DynamoDB clients between @BeforeAll and @BeforeEach
        // Re-verify tables exist to handle cases where context is recreated
        try {
            // Quick verification - if this fails, tables don't exist and we need to re-initialize
            TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
            LOGGER.debug("✅ Tables verified before test setup");
        } catch (Exception e) {
            LOGGER.warn(
                    "⚠️ Table verification failed in @BeforeEach, attempting re-initialization: {}",
                    e.getMessage());
            // Try to re-initialize tables
            try {
                TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
                LOGGER.info("✅ Tables re-initialized in @BeforeEach");
            } catch (Exception e2) {
                LOGGER.error(
                        "❌ Failed to re-initialize tables in @BeforeEach: {}", e2.getMessage(), e2);
                throw new RuntimeException("Failed to ensure tables exist before test", e2);
            }
        }

        testEmail = "test-dma-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash =
                java.util.Base64.getEncoder().encodeToString("test-password-hash".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - JSON Format")
    void testDMAExportDataJSONSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final String data = dmaComplianceService.exportDataPortable(userId, "JSON");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("\"userId\""), "JSON should contain userId");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - CSV Format")
    void testDMAExportDataCSVSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final String data = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("DataType"), "CSV should contain header");
        assertTrue(data.contains("USER"), "CSV should contain user data");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - XML Format")
    void testDMAExportDataXMLSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final String data = dmaComplianceService.exportDataPortable(userId, "XML");

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
        assertTrue(data.contains("<?xml"), "Should be valid XML");
        assertTrue(data.contains("<DMAExport"), "Should contain DMAExport root element");
    }

    @Test
    @DisplayName("DMA Article 6: Data Portability - Unsupported Format")
    void testDMAExportDataUnsupportedFormatThrowsException() {
        // Given
        final String userId = testUser.getUserId();

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    dmaComplianceService.exportDataPortable(userId, "UNSUPPORTED");
                },
                "Should throw for unsupported format");
    }

    @Test
    @DisplayName("DMA Article 7: Interoperability Endpoint")
    void testDMAGetInteroperabilityEndpointSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final String endpoint = dmaComplianceService.getInteroperabilityEndpoint(userId);

        // Then
        assertNotNull(endpoint);
        assertFalse(endpoint.isEmpty());
        assertTrue(
                endpoint.contains("/api/dma/interoperability/"),
                "Should contain interoperability path");
        assertTrue(endpoint.contains(userId), "Should contain userId");
    }

    @Test
    @DisplayName("DMA Article 8: Fair Access - Authorize Third Party")
    void testDMAAuthorizeThirdPartySucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String thirdPartyId = "third-party-123";
        final String scope = "read:transactions";

        // When
        final boolean authorized =
                dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, scope);

        // Then
        assertTrue(authorized, "Third-party access should be authorized");
    }

    @Test
    @DisplayName("DMA Article 9: Data Sharing")
    void testDMAShareDataWithThirdPartySucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String thirdPartyId = "third-party-123";
        final String dataType = "transactions";

        // When - First authorize, then share
        dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, dataType);
        final String data = dmaComplianceService.shareDataWithThirdParty(userId, thirdPartyId, dataType);

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
    }
}
