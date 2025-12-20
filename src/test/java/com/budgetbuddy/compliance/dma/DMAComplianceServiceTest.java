package com.budgetbuddy.compliance.dma;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DMAComplianceService
 */
class DMAComplianceServiceTest {

    @Mock
    private GDPRComplianceService gdprComplianceService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private GoalRepository goalRepository;

    private DMAComplianceService dmaComplianceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dmaComplianceService = new DMAComplianceService(
                gdprComplianceService, auditLogService, userRepository,
                transactionRepository, accountRepository, budgetRepository, goalRepository);
    }

    @Test
    @DisplayName("Should export data in JSON format")
    void testExportDataPortable_JSON() {
        // Given
        String userId = "user-123";
        String jsonData = "{\"userId\":\"user-123\"}";
        when(gdprComplianceService.exportDataPortable(userId)).thenReturn(jsonData);

        // When
        String result = dmaComplianceService.exportDataPortable(userId, "JSON");

        // Then
        assertEquals(jsonData, result);
        verify(auditLogService).logDataExport(eq(userId), anyString());
        verify(gdprComplianceService).exportDataPortable(userId);
    }

    @Test
    @DisplayName("Should export data in CSV format")
    void testExportDataPortable_CSV() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10000)).thenReturn(Arrays.asList());
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        String result = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("DataType,Id,UserId"));
        verify(auditLogService).logDataExport(eq(userId), anyString());
    }

    @Test
    @DisplayName("Should export data in XML format")
    void testExportDataPortable_XML() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10000)).thenReturn(Arrays.asList());
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        String result = dmaComplianceService.exportDataPortable(userId, "XML");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("<?xml version=\"1.0\""));
        assertTrue(result.contains("<DMAExport"));
        verify(auditLogService).logDataExport(eq(userId), anyString());
    }

    @Test
    @DisplayName("Should throw exception for unsupported format")
    void testExportDataPortable_UnsupportedFormat() {
        // Given
        String userId = "user-123";

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            dmaComplianceService.exportDataPortable(userId, "YAML");
        });
    }

    @Test
    @DisplayName("Should get interoperability endpoint")
    void testGetInteroperabilityEndpoint() {
        // Given
        String userId = "user-123";

        // When
        String endpoint = dmaComplianceService.getInteroperabilityEndpoint(userId);

        // Then
        assertNotNull(endpoint);
        assertTrue(endpoint.contains(userId));
        assertTrue(endpoint.contains("/api/dma/interoperability/"));
    }

    @Test
    @DisplayName("Should authorize third-party access")
    void testAuthorizeThirdPartyAccess() {
        // Given
        String userId = "user-123";
        String thirdPartyId = "third-party-456";
        String scope = "transactions";

        // When
        boolean result = dmaComplianceService.authorizeThirdPartyAccess(userId, thirdPartyId, scope);

        // Then
        assertTrue(result);
        verify(auditLogService).logAction(eq(userId), eq("THIRD_PARTY_AUTHORIZATION"), eq("DMA"),
                eq(thirdPartyId), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("Should share data with authorized third party")
    void testShareDataWithThirdParty() {
        // Given
        String userId = "user-123";
        String thirdPartyId = "third-party-456";
        String dataType = "transactions";
        String jsonData = "{\"data\":\"test\"}";

        when(gdprComplianceService.exportDataPortable(userId)).thenReturn(jsonData);

        // When
        String result = dmaComplianceService.shareDataWithThirdParty(userId, thirdPartyId, dataType);

        // Then
        assertEquals(jsonData, result);
        verify(auditLogService).logAction(eq(userId), eq("DATA_SHARING"), eq("DMA"),
                eq(thirdPartyId), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("Should export CSV with transactions")
    void testExportAsCSV_WithTransactions() {
        // Given
        String userId = "user-123";
        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setEmail("test@example.com");

        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);
        transaction.setAmount(new BigDecimal("100.00"));
        transaction.setTransactionDate("2024-01-01");

        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(transactionRepository.findByUserId(userId, 0, 10000)).thenReturn(Arrays.asList(transaction));
        when(accountRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(budgetRepository.findByUserId(userId)).thenReturn(Arrays.asList());
        when(goalRepository.findByUserId(userId)).thenReturn(Arrays.asList());

        // When
        String result = dmaComplianceService.exportDataPortable(userId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("TRANSACTION"));
        assertTrue(result.contains("txn-123"));
    }
}
