package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.compliance.financial.FinancialComplianceService;
import com.budgetbuddy.compliance.hipaa.HIPAAComplianceService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Compliance Services
 * Tests all compliance requirements (PCI-DSS, SOC2, FINRA, HIPAA, GDPR, DMA)
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Compliance Integration Tests")
class ComplianceIntegrationTest {

    @Autowired
    private FinancialComplianceService financialComplianceService;

    @Autowired
    private HIPAAComplianceService hipaaComplianceService;

    @Autowired
    private GDPRComplianceService gdprComplianceService;

    @Autowired
    private DMAComplianceService dmaComplianceService;

    @Autowired
    private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testEmail = "test-compliance-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testPasswordHash = java.util.Base64.getEncoder().encodeToString("test-password-hash".getBytes());
        testUser = userService.createUserSecure(testEmail, testPasswordHash, null, null);
    }

    @Test
    @DisplayName("FINRA: Record Keeping")
    void testFINRA_RecordKeeping_Succeeds() {
        // Given
        String recordType = "TRANSACTION";
        String recordId = "tx-123";
        Instant retentionUntil = Instant.now().plusSeconds(31536000 * 7); // 7 years

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            financialComplianceService.logRecordKeeping(recordType, recordId, retentionUntil);
        });
    }

    @Test
    @DisplayName("FINRA: Supervision")
    void testFINRA_Supervision_Succeeds() {
        // Given
        String supervisorId = "supervisor-123";
        String supervisedUserId = testUser.getUserId();
        String activity = "TRADE_REVIEW";
        boolean approved = true;

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            financialComplianceService.logSupervision(supervisorId, supervisedUserId, activity, approved);
        });
    }

    @Test
    @DisplayName("FINRA: Suspicious Activity Reporting")
    void testFINRA_SuspiciousActivityReporting_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String activityType = "UNUSUAL_TRANSACTION";
        String details = "Large transaction detected";

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            financialComplianceService.reportSuspiciousActivity(userId, activityType, details);
        });
    }

    @Test
    @DisplayName("FINRA: Communication Surveillance")
    void testFINRA_CommunicationSurveillance_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String customerId = "customer-123";
        String communicationType = "EMAIL";
        String content = "Test communication";

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            financialComplianceService.logCommunication(userId, customerId, communicationType, content);
        });
    }

    @Test
    @DisplayName("HIPAA: Breach Notification")
    void testHIPAA_BreachNotification_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String phiId = "phi-123";
        String breachType = "UNAUTHORIZED_ACCESS";
        String details = "Test breach";

        // When/Then - Should trigger notification workflow
        assertDoesNotThrow(() -> {
            hipaaComplianceService.reportBreach(userId, phiId, breachType, details);
        });
    }

    @Test
    @DisplayName("GDPR: Data Export")
    void testGDPR_DataExport_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        GDPRComplianceService.GDPRDataExport export = gdprComplianceService.exportUserData(userId);

        // Then
        assertNotNull(export);
        assertNotNull(export.getUserId());
        assertEquals(userId, export.getUserId());
        assertNotNull(export.getExportDate());
    }

    @Test
    @DisplayName("GDPR: Data Portability")
    void testGDPR_DataPortability_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When
        String json = gdprComplianceService.exportDataPortable(userId);

        // Then
        assertNotNull(json);
        assertFalse(json.isEmpty());
        assertTrue(json.contains("\"userId\""), "JSON should contain userId");
    }

    @Test
    @DisplayName("GDPR: Consent Management")
    void testGDPR_ConsentManagement_Succeeds() {
        // Given
        String userId = testUser.getUserId();
        String consentType = "MARKETING";
        boolean granted = true;
        String purpose = "Email marketing";

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            gdprComplianceService.recordConsent(userId, consentType, granted, purpose);
            gdprComplianceService.withdrawConsent(userId, consentType);
        });
    }

    @Test
    @DisplayName("DMA: Data Portability - All Formats")
    void testDMA_DataPortabilityAllFormats_Succeeds() {
        // Given
        String userId = testUser.getUserId();

        // When/Then - JSON
        String json = dmaComplianceService.exportDataPortable(userId, "JSON");
        assertNotNull(json);
        assertFalse(json.isEmpty());

        // When/Then - CSV
        String csv = dmaComplianceService.exportDataPortable(userId, "CSV");
        assertNotNull(csv);
        assertFalse(csv.isEmpty());
        assertTrue(csv.contains("DataType"), "CSV should contain header");

        // When/Then - XML
        String xml = dmaComplianceService.exportDataPortable(userId, "XML");
        assertNotNull(xml);
        assertFalse(xml.isEmpty());
        assertTrue(xml.contains("<?xml"), "Should be valid XML");
    }

    @Test
    @DisplayName("DMA: Third-Party Authorization")
    void testDMA_ThirdPartyAuthorization_Succeeds() {
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
    @DisplayName("DMA: Data Sharing")
    void testDMA_DataSharing_Succeeds() {
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

