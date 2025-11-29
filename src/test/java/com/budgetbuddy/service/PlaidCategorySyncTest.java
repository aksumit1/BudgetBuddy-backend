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

/**
 * Integration tests for Plaid category sync
 * Tests that Plaid categories are properly extracted, mapped, and stored
 */
@ExtendWith(MockitoExtension.class)
class PlaidCategorySyncTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PlaidCategoryMapper categoryMapper;

    @InjectMocks
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
    }

    @Test
    void testSyncTransaction_WithPlaidPersonalFinanceCategory_StoresCorrectly() throws Exception {
        // Given - Transaction with Plaid personal_finance_category
        com.plaid.client.model.Transaction plaidTransaction = mock(com.plaid.client.model.Transaction.class);
        com.plaid.client.model.PersonalFinanceCategory pfc = mock(com.plaid.client.model.PersonalFinanceCategory.class);
        
        when(pfc.getPrimary()).thenReturn("FOOD_AND_DRINK");
        when(pfc.getDetailed()).thenReturn("RESTAURANTS");
        when(plaidTransaction.getPersonalFinanceCategory()).thenReturn(pfc);
        when(plaidTransaction.getAccountId()).thenReturn("plaid-account-123");
        when(plaidTransaction.getAmount()).thenReturn(25.50);
        when(plaidTransaction.getName()).thenReturn("McDonald's");
        when(plaidTransaction.getMerchantName()).thenReturn("McDonald's");
        when(plaidTransaction.getDate()).thenReturn(LocalDate.now());
        when(plaidTransaction.getTransactionId()).thenReturn("plaid-txn-123");
        // Note: Plaid Transaction uses getTransactionId(), not getPlaidTransactionId()
        when(plaidTransaction.getPending()).thenReturn(false);
        when(plaidTransaction.getIsoCurrencyCode()).thenReturn("USD");

        com.plaid.client.model.TransactionsGetResponse mockResponse = new com.plaid.client.model.TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Arrays.asList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString())).thenReturn(mockResponse);
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
                transactionRepository, accountRepository);
        TransactionTable updated = transactionService.updateTransaction(
                testUser, "txn-123", null, null, "groceries", "groceries");

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
        when(plaidTransaction.getPending()).thenReturn(false);
        when(plaidTransaction.getIsoCurrencyCode()).thenReturn("USD");

        com.plaid.client.model.TransactionsGetResponse mockResponse = new com.plaid.client.model.TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Arrays.asList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString())).thenReturn(mockResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString())).thenReturn(Optional.empty());
        doReturn(true).when(transactionRepository).saveIfPlaidTransactionNotExists(any(TransactionTable.class));

        PlaidCategoryMapper.CategoryMapping categoryMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
        when(categoryMapper.mapPlaidCategory(isNull(), isNull(), anyString(), anyString()))
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
        assertEquals("income", result3.getDetailed());
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

