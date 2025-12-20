package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.plaid.PlaidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for Plaid category sync
 * Tests that Plaid categories are properly extracted, mapped, and stored
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidCategorySyncTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PlaidCategoryMapper categoryMapper;

    @Mock
    private com.budgetbuddy.service.plaid.PlaidDataExtractor dataExtractor;

    @Mock
    private com.budgetbuddy.service.plaid.PlaidSyncOrchestrator syncOrchestrator;

    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testAccount = new AccountTable();
        testAccount.setAccountId("account-123");
        testAccount.setUserId("user-123");
        testAccount.setPlaidAccountId("plaid-account-123");
        testAccount.setActive(true);
        testAccount.setLastSyncedAt(null); // Ensure sync isn't skipped

        // Create real services with mocked dependencies so the actual sync logic runs
        com.budgetbuddy.service.plaid.PlaidAccountSyncService accountSyncService = 
            new com.budgetbuddy.service.plaid.PlaidAccountSyncService(
                plaidService, accountRepository, categoryMapper, dataExtractor);
        com.budgetbuddy.service.plaid.PlaidTransactionSyncService transactionSyncService = 
            new com.budgetbuddy.service.plaid.PlaidTransactionSyncService(
                plaidService, accountRepository, transactionRepository, dataExtractor);
        com.budgetbuddy.service.plaid.PlaidSyncOrchestrator realOrchestrator = 
            new com.budgetbuddy.service.plaid.PlaidSyncOrchestrator(accountSyncService, transactionSyncService);
        
        // Use doAnswer to call the real orchestrator methods so repository calls are made
        // Use nullable() for itemId since it can be null
        // Use lenient stubbing to avoid issues with tests that throw exceptions early
        lenient().doAnswer(invocation -> {
            realOrchestrator.syncAccountsOnly(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(syncOrchestrator).syncAccountsOnly(any(UserTable.class), anyString(), nullable(String.class));
        
        lenient().doAnswer(invocation -> {
            realOrchestrator.syncTransactionsOnly(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), anyString());
        
        // Manually inject the orchestrator to ensure it's set before tests run
        plaidSyncService = new PlaidSyncService(syncOrchestrator);
    }

    @Test
    void testSyncTransaction_WithPlaidPersonalFinanceCategory_StoresCorrectly() throws Exception {
        // Given - Transaction with Plaid personal_finance_category
        com.plaid.client.model.Transaction plaidTransaction = mock(com.plaid.client.model.Transaction.class);
        com.plaid.client.model.PersonalFinanceCategory pfc = mock(com.plaid.client.model.PersonalFinanceCategory.class);
        
        when(pfc.getPrimary()).thenReturn("FOOD_AND_DRINK");
        when(pfc.getDetailed()).thenReturn("RESTAURANTS");
        when(plaidTransaction.getPersonalFinanceCategory()).thenReturn(pfc);
        lenient().when(plaidTransaction.getAccountId()).thenReturn("plaid-account-123");
        when(plaidTransaction.getAmount()).thenReturn(25.50);
        when(plaidTransaction.getName()).thenReturn("McDonald's");
        when(plaidTransaction.getMerchantName()).thenReturn("McDonald's");
        when(plaidTransaction.getDate()).thenReturn(LocalDate.now());
        when(plaidTransaction.getTransactionId()).thenReturn("plaid-txn-123");
        // Note: Plaid Transaction uses getTransactionId(), not getPlaidTransactionId()
        // Use lenient stubbing for optional fields that might not be used
        lenient().when(plaidTransaction.getPending()).thenReturn(false);
        lenient().when(plaidTransaction.getIsoCurrencyCode()).thenReturn("USD");

        com.plaid.client.model.TransactionsGetResponse mockResponse = new com.plaid.client.model.TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Arrays.asList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString())).thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenReturn("plaid-account-123");
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(invocation -> {
                    Object transaction = invocation.getArgument(0);
                    if (transaction instanceof com.plaid.client.model.Transaction) {
                        return ((com.plaid.client.model.Transaction) transaction).getTransactionId();
                    }
                    return null;
                });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(invocation -> {
            TransactionTable txTable = invocation.getArgument(0);
            Object plaidTxObj = invocation.getArgument(1);
            if (plaidTxObj instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction plaidTx = (com.plaid.client.model.Transaction) plaidTxObj;
                txTable.setMerchantName(plaidTx.getMerchantName());
                txTable.setDescription(plaidTx.getName());
                if (plaidTx.getDate() != null) {
                    txTable.setTransactionDate(plaidTx.getDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                if (plaidTx.getAmount() != null) {
                    txTable.setAmount(java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                }
                
                // Extract PersonalFinanceCategory
                String plaidCategoryPrimary = null;
                String plaidCategoryDetailed = null;
                try {
                    var personalFinanceCategory = plaidTx.getPersonalFinanceCategory();
                    if (personalFinanceCategory != null) {
                        plaidCategoryPrimary = personalFinanceCategory.getPrimary();
                        plaidCategoryDetailed = personalFinanceCategory.getDetailed();
                    }
                } catch (Exception e) {
                    // Ignore
                }
                
                // Use categoryMapper to map categories
                PlaidCategoryMapper.CategoryMapping categoryMapping;
                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                    categoryMapping = categoryMapper.mapPlaidCategory(
                        plaidCategoryPrimary,
                        plaidCategoryDetailed,
                        txTable.getMerchantName(),
                        txTable.getDescription()
                    );
                } else {
                    categoryMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
                }
                
                txTable.setPlaidCategoryPrimary(plaidCategoryPrimary);
                txTable.setPlaidCategoryDetailed(plaidCategoryDetailed);
                txTable.setCategoryPrimary(categoryMapping.getPrimary());
                txTable.setCategoryDetailed(categoryMapping.getDetailed());
                txTable.setCategoryOverridden(categoryMapping.isOverridden());
            }
            return null;
        }).when(dataExtractor).updateTransactionFromPlaid(any(TransactionTable.class), any());
        when(transactionRepository.findByPlaidTransactionId(anyString())).thenReturn(Optional.empty());
        doReturn(true).when(transactionRepository).saveIfPlaidTransactionNotExists(any(TransactionTable.class));

        PlaidCategoryMapper.CategoryMapping categoryMapping = new PlaidCategoryMapper.CategoryMapping("dining", "dining", false);
        when(categoryMapper.mapPlaidCategory(eq("FOOD_AND_DRINK"), eq("RESTAURANTS"), anyString(), anyString()))
                .thenReturn(categoryMapping);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify category fields are set correctly
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(argThat(transaction -> {
            assertEquals("FOOD_AND_DRINK", transaction.getPlaidCategoryPrimary());
            assertEquals("RESTAURANTS", transaction.getPlaidCategoryDetailed());
            assertEquals("dining", transaction.getCategoryPrimary());
            assertEquals("dining", transaction.getCategoryDetailed());
            assertFalse(transaction.getCategoryOverridden());
            return true;
        }));
    }

    @Test
    void testSyncTransaction_WithCategoryOverride_StoresOverride() throws Exception {
        // Given - Transaction with category override
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId("txn-123");
        existingTransaction.setUserId("user-123");
        existingTransaction.setAccountId("account-123");
        existingTransaction.setPlaidCategoryPrimary("FOOD_AND_DRINK");
        existingTransaction.setPlaidCategoryDetailed("RESTAURANTS");
        existingTransaction.setCategoryPrimary("dining");
        existingTransaction.setCategoryDetailed("dining");
        existingTransaction.setCategoryOverridden(false);

        when(transactionRepository.findById("txn-123")).thenReturn(Optional.of(existingTransaction));
        doAnswer(invocation -> {
            TransactionTable t = invocation.getArgument(0);
            return t;
        }).when(transactionRepository).save(any(TransactionTable.class));

        // When - User overrides category via TransactionService
        TransactionService transactionService = new TransactionService(
                transactionRepository, accountRepository, new com.budgetbuddy.service.TransactionTypeDeterminer());
        TransactionTable updated = transactionService.updateTransaction(
                testUser, "txn-123", null, null, null, "groceries", "groceries", null, null, null, false);

        // Then - Verify override is stored
        assertNotNull(updated);
        assertEquals("groceries", updated.getCategoryPrimary());
        assertEquals("groceries", updated.getCategoryDetailed());
        assertTrue(updated.getCategoryOverridden());
        // Original Plaid categories should be preserved
        assertEquals("FOOD_AND_DRINK", updated.getPlaidCategoryPrimary());
        assertEquals("RESTAURANTS", updated.getPlaidCategoryDetailed());
    }

    @Test
    void testSyncTransaction_WithoutPlaidCategory_UsesDefaults() throws Exception {
        // Given - Transaction without Plaid category
        com.plaid.client.model.Transaction plaidTransaction = mock(com.plaid.client.model.Transaction.class);
        
        when(plaidTransaction.getPersonalFinanceCategory()).thenReturn(null);
        when(plaidTransaction.getAccountId()).thenReturn("plaid-account-123");
        when(plaidTransaction.getAmount()).thenReturn(25.50);
        when(plaidTransaction.getName()).thenReturn("Transaction");
        when(plaidTransaction.getDate()).thenReturn(LocalDate.now());
        when(plaidTransaction.getTransactionId()).thenReturn("plaid-txn-123");
        // Use lenient stubbing for optional fields that might not be used
        lenient().when(plaidTransaction.getPending()).thenReturn(false);
        lenient().when(plaidTransaction.getIsoCurrencyCode()).thenReturn("USD");

        com.plaid.client.model.TransactionsGetResponse mockResponse = new com.plaid.client.model.TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Arrays.asList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString())).thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenReturn("plaid-account-123");
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(invocation -> {
                    Object transaction = invocation.getArgument(0);
                    if (transaction instanceof com.plaid.client.model.Transaction) {
                        return ((com.plaid.client.model.Transaction) transaction).getTransactionId();
                    }
                    return null;
                });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(invocation -> {
            TransactionTable txTable = invocation.getArgument(0);
            Object plaidTxObj = invocation.getArgument(1);
            if (plaidTxObj instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction plaidTx = (com.plaid.client.model.Transaction) plaidTxObj;
                txTable.setMerchantName(plaidTx.getMerchantName());
                txTable.setDescription(plaidTx.getName());
                if (plaidTx.getDate() != null) {
                    txTable.setTransactionDate(plaidTx.getDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                if (plaidTx.getAmount() != null) {
                    txTable.setAmount(java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                }
                
                // Extract PersonalFinanceCategory
                String plaidCategoryPrimary = null;
                String plaidCategoryDetailed = null;
                try {
                    var personalFinanceCategory = plaidTx.getPersonalFinanceCategory();
                    if (personalFinanceCategory != null) {
                        plaidCategoryPrimary = personalFinanceCategory.getPrimary();
                        plaidCategoryDetailed = personalFinanceCategory.getDetailed();
                    }
                } catch (Exception e) {
                    // Ignore
                }
                
                // Use categoryMapper to map categories
                PlaidCategoryMapper.CategoryMapping categoryMapping;
                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                    categoryMapping = categoryMapper.mapPlaidCategory(
                        plaidCategoryPrimary,
                        plaidCategoryDetailed,
                        txTable.getMerchantName(),
                        txTable.getDescription()
                    );
                } else {
                    categoryMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
                }
                
                txTable.setPlaidCategoryPrimary(plaidCategoryPrimary);
                txTable.setPlaidCategoryDetailed(plaidCategoryDetailed);
                txTable.setCategoryPrimary(categoryMapping.getPrimary());
                txTable.setCategoryDetailed(categoryMapping.getDetailed());
                txTable.setCategoryOverridden(categoryMapping.isOverridden());
            }
            return null;
        }).when(dataExtractor).updateTransactionFromPlaid(any(TransactionTable.class), any());
        when(transactionRepository.findByPlaidTransactionId(anyString())).thenReturn(Optional.empty());
        doReturn(true).when(transactionRepository).saveIfPlaidTransactionNotExists(any(TransactionTable.class));

        // Only stub categoryMapper if it's actually used (when category is null, it may not be called)
        PlaidCategoryMapper.CategoryMapping categoryMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
        lenient().when(categoryMapper.mapPlaidCategory(isNull(), isNull(), anyString(), anyString()))
                .thenReturn(categoryMapping);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify defaults are used
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(argThat(transaction -> {
            assertNull(transaction.getPlaidCategoryPrimary());
            assertNull(transaction.getPlaidCategoryDetailed());
            assertEquals("other", transaction.getCategoryPrimary());
            assertEquals("other", transaction.getCategoryDetailed());
            return true;
        }));
    }

    @Test
    void testCategoryMapper_MapsPlaidCategoriesCorrectly() {
        // Given
        PlaidCategoryMapper mapper = new PlaidCategoryMapper();

        // When - Map Plaid categories
        PlaidCategoryMapper.CategoryMapping result1 = mapper.mapPlaidCategory(
                "FOOD_AND_DRINK", "RESTAURANTS", "McDonald's", "Fast food purchase");
        
        PlaidCategoryMapper.CategoryMapping result2 = mapper.mapPlaidCategory(
                "TRANSPORTATION", "GAS_STATIONS", "Shell", "Gas purchase");
        
        PlaidCategoryMapper.CategoryMapping result3 = mapper.mapPlaidCategory(
                "INCOME", "SALARY", "Employer", "Monthly salary");

        // Then - Verify mappings
        assertEquals("dining", result1.getPrimary());
        assertEquals("dining", result1.getDetailed());
        assertFalse(result1.isOverridden());

        assertEquals("transportation", result2.getPrimary());
        assertEquals("transportation", result2.getDetailed());
        assertFalse(result2.isOverridden());

        assertEquals("income", result3.getPrimary());
        // Description contains "salary", so should be categorized as salary, not generic income
        assertEquals("salary", result3.getDetailed(), "Income with salary description should be salary");
        assertFalse(result3.isOverridden());
    }

    @Test
    void testCategoryMapper_AppliesOverride() {
        // Given
        PlaidCategoryMapper mapper = new PlaidCategoryMapper();
        PlaidCategoryMapper.CategoryMapping original = new PlaidCategoryMapper.CategoryMapping("dining", "dining", false);

        // When - Apply override
        PlaidCategoryMapper.CategoryMapping overridden = mapper.applyOverride(original, "groceries", "groceries");

        // Then
        assertEquals("groceries", overridden.getPrimary());
        assertEquals("groceries", overridden.getDetailed());
        assertTrue(overridden.isOverridden());
    }
}

