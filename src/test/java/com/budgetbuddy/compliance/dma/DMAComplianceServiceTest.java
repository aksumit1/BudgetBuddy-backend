package com.budgetbuddy.compliance.dma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Comprehensive tests for DMAComplianceService */
class DMAComplianceServiceTest {

    private static final String USER_123 = "user-123";

    @Mock private GDPRComplianceService gdprComplianceService;

    @Mock private AuditLogService auditLogService;

    @Mock private UserRepository userRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @Mock private BudgetRepository budgetRepository;

    @Mock private GoalRepository goalRepository;

    private DMAComplianceService dmaComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dmaComplianceService =
                new DMAComplianceService(
                        gdprComplianceService,
                        auditLogService,
                        userRepository,
                        transactionRepository,
                        accountRepository,
                        budgetRepository,
                        goalRepository);
    }

    @Test
    @DisplayName("Should export data in JSON format")
    void testExportDataPortableJSON() {
        // Given
        final String userId = USER_123;
        final String jsonData = "{\"userId\":\"user-123\"}";
        when(gdprComplianceService.exportDataPortable(userId)).thenReturn(jsonData);

        // When
        final String result = dmaComplianceService.exportDataPortable(userId, "JSON");

        // Then
        assertEquals(jsonData, result);
        verify(auditLogService).logDataExport(eq(userId), anyString());
        verify(gdprComplianceService).exportDataPortable(userId);
    }

    @Test
    @DisplayName("Should export data in CSV format")
    void testExportDataPortableCSV() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10_000)).thenReturn(Arrays.asList());
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        final String result = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("DataType,Id,UserId"));
        verify(auditLogService).logDataExport(eq(userId), anyString());
    }

    @Test
    @DisplayName("Should export data in XML format")
    void testExportDataPortableXML() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10_000)).thenReturn(Arrays.asList());
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        final String result = dmaComplianceService.exportDataPortable(userId, "XML");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("<?xml version=\"1.0\""));
        assertTrue(result.contains("<DMAExport"));
        verify(auditLogService).logDataExport(eq(userId), anyString());
    }

    @Test
    @DisplayName("Should throw exception for unsupported format")
    void testExportDataPortableUnsupportedFormat() {
        // Given
        final String userId = USER_123;

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    dmaComplianceService.exportDataPortable(userId, "YAML");
                });
    }

    @Test
    @DisplayName("Should get interoperability endpoint")
    void testGetInteroperabilityEndpoint() {
        // Given
        final String userId = USER_123;

        // When
        final String endpoint = dmaComplianceService.getInteroperabilityEndpoint(userId);

        // Then
        assertNotNull(endpoint);
        assertTrue(endpoint.contains(userId));
        assertTrue(endpoint.contains("/api/dma/interoperability/"));
    }

    @Test
    @DisplayName("Should authorize third-party access")
    void testAuthorizeThirdPartyAccess() {
        // Given
        final String userId = USER_123;
        final String thirdPartyId = "third-party-456";
        final String scope = "transactions";

        // When
        final boolean result =
                dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, scope);

        // Then
        assertTrue(result);
        verify(auditLogService)
                .logAction(
                        eq(userId),
                        eq("THIRD_PARTY_AUTHORIZATION"),
                        eq("DMA"),
                        eq(thirdPartyId),
                        any(),
                        isNull(),
                        isNull());
    }

    @Test
    @DisplayName("Should share data with authorized third party")
    void testShareDataWithThirdParty() {
        // Given
        final String userId = USER_123;
        final String thirdPartyId = "third-party-456";
        final String dataType = "transactions";
        final String jsonData = "{\"data\":\"test\"}";

        when(gdprComplianceService.exportDataPortable(userId)).thenReturn(jsonData);

        // When
        final String result =
                dmaComplianceService.shareDataWithThirdParty(userId, thirdPartyId, dataType);

        // Then
        assertEquals(jsonData, result);
        verify(auditLogService)
                .logAction(
                        eq(userId),
                        eq("DATA_SHARING"),
                        eq("DMA"),
                        eq(thirdPartyId),
                        any(),
                        isNull(),
                        isNull());
    }

    @Test
    @DisplayName("Should export CSV with transactions")
    void testExportAsCSVWithTransactions() {
        // Given
        final String userId = USER_123;
        final UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);
        transaction.setAmount(new BigDecimal("100.00"));
        transaction.setTransactionDate("2024-01-01");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10_000))
                .thenReturn(Arrays.asList(transaction));
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        final String result = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("TRANSACTION"));
        assertTrue(result.contains("txn-123"));
    }
}
