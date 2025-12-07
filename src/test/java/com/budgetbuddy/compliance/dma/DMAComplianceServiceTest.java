package com.budgetbuddy.compliance.dma;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.gdpr.GDPRComplianceService;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DMAComplianceService
 */
@ExtendWith(MockitoExtension.class)
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
    private String testUserId;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        dmaComplianceService = new DMAComplianceService(
                gdprComplianceService,
                auditLogService,
                userRepository,
                transactionRepository,
                accountRepository,
                budgetRepository,
                goalRepository
        );
        
        testUserId = "user-123";
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
    }

    @Test
    void testExportDataPortable_WithJSONFormat_ReturnsData() {
        // Given
        String expectedData = "{\"user\":\"test\"}";
        when(gdprComplianceService.exportDataPortable(testUserId)).thenReturn(expectedData);
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.exportDataPortable(testUserId, "JSON");

        // Then
        assertEquals(expectedData, result);
        verify(gdprComplianceService).exportDataPortable(testUserId);
        verify(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());
    }

    @Test
    void testExportDataPortable_WithCSVFormat_ReturnsCSV() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of());
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.exportDataPortable(testUserId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("DataType,Id,UserId"));
        verify(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());
    }

    @Test
    void testExportDataPortable_WithXMLFormat_ReturnsXML() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of());
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.exportDataPortable(testUserId, "XML");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("<?xml version=\"1.0\""));
        assertTrue(result.contains("<DMAExport"));
        verify(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());
    }

    @Test
    void testExportDataPortable_WithUnsupportedFormat_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, 
                () -> dmaComplianceService.exportDataPortable(testUserId, "UNSUPPORTED"));
    }

    @Test
    void testGetInteroperabilityEndpoint_WithValidUser_ReturnsEndpoint() {
        // When
        String endpoint = dmaComplianceService.getInteroperabilityEndpoint(testUserId);

        // Then
        assertNotNull(endpoint);
        assertTrue(endpoint.contains(testUserId));
        assertTrue(endpoint.contains("/api/dma/interoperability/"));
    }

    @Test
    void testAuthorizeThirdPartyAccess_WithValidInput_ReturnsTrue() {
        // Given
        String thirdPartyId = "third-party-123";
        String scope = "read:transactions";
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), any(), any(), any());

        // When
        boolean result = dmaComplianceService.authorizeThirdPartyAccess(testUserId, thirdPartyId, scope);

        // Then
        assertTrue(result);
        verify(auditLogService).logAction(eq(testUserId), eq("THIRD_PARTY_AUTHORIZATION"), eq("DMA"), 
                eq(thirdPartyId), any(), any(), any());
    }

    @Test
    void testShareDataWithThirdParty_WithValidInput_ReturnsData() {
        // Given
        String thirdPartyId = "third-party-123";
        String dataType = "transactions";
        String expectedData = "{\"data\":\"test\"}";
        when(gdprComplianceService.exportDataPortable(testUserId)).thenReturn(expectedData);
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.shareDataWithThirdParty(testUserId, thirdPartyId, dataType);

        // Then
        assertEquals(expectedData, result);
        verify(auditLogService).logAction(eq(testUserId), eq("DATA_SHARING"), eq("DMA"), 
                eq(thirdPartyId), any(), any(), any());
    }

    @Test
    void testShareDataWithThirdParty_WithUnauthorizedAccess_ThrowsException() {
        // Given
        String thirdPartyId = "third-party-123";
        String dataType = "transactions";
        // Mock authorizeThirdPartyAccess to return false by using a spy
        DMAComplianceService spy = spy(dmaComplianceService);
        doReturn(false).when(spy).authorizeThirdPartyAccess(testUserId, thirdPartyId, dataType);

        // When/Then
        assertThrows(IllegalStateException.class, 
                () -> spy.shareDataWithThirdParty(testUserId, thirdPartyId, dataType));
    }

    @Test
    void testExportAsCSV_WithUserData_IncludesAllData() {
        // Given
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setAmount(BigDecimal.valueOf(50));
        transaction.setTransactionDate("2024-01-01");
        
        AccountTable account = new AccountTable();
        account.setAccountId("acc-123");
        account.setAccountName("Test Account");
        account.setBalance(BigDecimal.valueOf(1000));
        
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId("budget-123");
        budget.setCategory("GROCERIES");
        budget.setMonthlyLimit(BigDecimal.valueOf(500));
        
        GoalTable goal = new GoalTable();
        goal.setGoalId("goal-123");
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(10000));
        
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of(transaction));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(account));
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of(budget));
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of(goal));
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.exportDataPortable(testUserId, "CSV");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("TRANSACTION"));
        assertTrue(result.contains("ACCOUNT"));
        assertTrue(result.contains("BUDGET"));
        assertTrue(result.contains("GOAL"));
    }

    @Test
    void testExportAsXML_WithUserData_IncludesAllData() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of());
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        doNothing().when(auditLogService).logDataExport(org.mockito.ArgumentMatchers.eq(testUserId), anyString());

        // When
        String result = dmaComplianceService.exportDataPortable(testUserId, "XML");

        // Then
        assertNotNull(result);
        assertTrue(result.contains("<User>"));
        assertTrue(result.contains("<Transactions>"));
        assertTrue(result.contains("<Accounts>"));
        assertTrue(result.contains("<Budgets>"));
        assertTrue(result.contains("<Goals>"));
    }
}

