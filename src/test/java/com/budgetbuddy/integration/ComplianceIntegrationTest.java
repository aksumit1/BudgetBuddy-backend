package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.compliance.financial.FinancialComplianceService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration Tests for Compliance Services Tests all compliance requirements (PCI-DSS, SOC2,
 * FINRA, HIPAA, GDPR, DMA)
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Compliance Integration Tests")
class ComplianceIntegrationTest {

    @Autowired private FinancialComplianceService financialComplianceService;

    @Autowired private HIPAAComplianceService hipaaComplianceService;

    @Autowired private GDPRComplianceService gdprComplianceService;

    @Autowired private DMAComplianceService dmaComplianceService;

    @Autowired private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testEmail =
                "test-compliance-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("test-password-hash".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("FINRA: Record Keeping")
    void testFINRARecordKeepingSucceeds() {
        // Given
        final String recordType = "TRANSACTION";
        final String recordId = "tx-123";
        final Instant retentionUntil = Instant.now().plusSeconds(31_536_000 * 7); // 7 years

        // When/Then - Should not throw
        assertDoesNotThrow(
                () -> {
                    financialComplianceService.logRecordKeeping(
                            recordType, recordId, retentionUntil);
                });
    }

    @Test
    @DisplayName("FINRA: Supervision")
    void testFINRASupervisionSucceeds() {
        // Given
        final String supervisorId = "supervisor-123";
        final String supervisedUserId = testUser.getUserId();
        final String activity = "TRADE_REVIEW";
        final boolean approved = true;

        // When/Then - Should not throw
        assertDoesNotThrow(
                () -> {
                    financialComplianceService.logSupervision(
                            supervisorId, supervisedUserId, activity, approved);
                });
    }

    @Test
    @DisplayName("FINRA: Suspicious Activity Reporting")
    void testFINRASuspiciousActivityReportingSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String activityType = "UNUSUAL_TRANSACTION";
        final String details = "Large transaction detected";

        // When/Then - Should not throw
        assertDoesNotThrow(
                () -> {
                    financialComplianceService.reportSuspiciousActivity(
                            userId, activityType, details);
                });
    }

    @Test
    @DisplayName("FINRA: Communication Surveillance")
    void testFINRACommunicationSurveillanceSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String customerId = "customer-123";
        final String communicationType = "EMAIL";
        final String content = "Test communication";

        // When/Then - Should not throw
        assertDoesNotThrow(
                () -> {
                    financialComplianceService.logCommunication(
                            userId, customerId, communicationType, content);
                });
    }

    @Test
    @DisplayName("HIPAA: Breach Notification")
    void testHIPAABreachNotificationSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String phiId = "phi-123";
        final String breachType = "UNAUTHORIZED_ACCESS";
        final String details = "Test breach";

        // When/Then - Should trigger notification workflow
        assertDoesNotThrow(
                () -> {
                    hipaaComplianceService.reportBreach(userId, phiId, breachType, details);
                });
    }

    @Test
    @DisplayName("GDPR: Data Export")
    void testGDPRDataExportSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final GDPRComplianceService.GDPRDataExport export =
                gdprComplianceService.exportUserData(userId);

        // Then
        assertNotNull(export);
        assertNotNull(export.getUserId());
        assertEquals(userId, export.getUserId());
        assertNotNull(export.getExportDate());
    }

    @Test
    @DisplayName("GDPR: Data Portability")
    void testGDPRDataPortabilitySucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When
        final String json = gdprComplianceService.exportDataPortable(userId);

        // Then
        assertNotNull(json);
        assertFalse(json.isEmpty());
        assertTrue(json.contains("\"userId\""), "JSON should contain userId");
    }

    @Test
    @DisplayName("GDPR: Consent Management")
    void testGDPRConsentManagementSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String consentType = "MARKETING";
        final boolean granted = true;
        final String purpose = "Email marketing";

        // When/Then - Should not throw
        assertDoesNotThrow(
                () -> {
                    gdprComplianceService.recordConsent(userId, consentType, granted, purpose);
                    gdprComplianceService.withdrawConsent(userId, consentType);
                });
    }

    @Test
    @DisplayName("DMA: Data Portability - All Formats")
    void testDMADataPortabilityAllFormatsSucceeds() {
        // Given
        final String userId = testUser.getUserId();

        // When/Then - JSON
        final String json = dmaComplianceService.exportDataPortable(userId, "JSON");
        assertNotNull(json);
        assertFalse(json.isEmpty());

        // When/Then - CSV
        final String csv = dmaComplianceService.exportDataPortable(userId, "CSV");
        assertNotNull(csv);
        assertFalse(csv.isEmpty());
        assertTrue(csv.contains("DataType"), "CSV should contain header");

        // When/Then - XML
        final String xml = dmaComplianceService.exportDataPortable(userId, "XML");
        assertNotNull(xml);
        assertFalse(xml.isEmpty());
        assertTrue(xml.contains("<?xml"), "Should be valid XML");
    }

    @Test
    @DisplayName("DMA: Third-Party Authorization")
    void testDMAThirdPartyAuthorizationSucceeds() {
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
    @DisplayName("DMA: Data Sharing")
    void testDMADataSharingSucceeds() {
        // Given
        final String userId = testUser.getUserId();
        final String thirdPartyId = "third-party-123";
        final String dataType = "transactions";

        // When - First authorize, then share
        dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, dataType);
        final String data =
                dmaComplianceService.shareDataWithThirdParty(userId, thirdPartyId, dataType);

        // Then
        assertNotNull(data);
        assertFalse(data.isEmpty());
    }
}
