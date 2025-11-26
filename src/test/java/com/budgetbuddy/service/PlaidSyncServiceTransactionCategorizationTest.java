package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidSyncService - Transaction Categorization
 * Tests that merchant names and categories are properly stored
 * This ensures iOS app can correctly categorize transactions (KFC, autopayment, etc.)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidSyncServiceTransactionCategorizationTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");
        
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync
    }

    @Test
    void testSyncTransactions_WithKFCMerchant_StoresMerchantName() throws Exception {
        // Given - KFC transaction
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-kfc-123",
                "KFC",
                "KFC Purchase",
                new BigDecimal("15.99"),
                Arrays.asList("Food and Drink", "Restaurants")
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify merchant name is stored
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        assertEquals("KFC", savedTransaction.getMerchantName());
        assertTrue(savedTransaction.getCategory().contains("Food") || 
                  savedTransaction.getCategory().contains("Restaurant"));
    }

    @Test
    void testSyncTransactions_WithAutopayment_StoresCorrectCategory() throws Exception {
        // Given - Autopayment transaction (should NOT be income)
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-autopay-123",
                "AUTOPAYMENT",
                "Automatic Payment - Utilities",
                new BigDecimal("100.00"),
                Arrays.asList("General Services", "Utilities")
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify autopayment is stored with correct category (NOT income)
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        assertEquals("AUTOPAYMENT", savedTransaction.getMerchantName());
        // Category should NOT be income - it should be utilities or other
        assertFalse(savedTransaction.getCategory().toLowerCase().contains("income"));
        assertTrue(savedTransaction.getCategory().contains("Utilities") || 
                  savedTransaction.getCategory().contains("General Services"));
    }

    @Test
    void testSyncTransactions_WithNullCategory_DefaultsToOther() throws Exception {
        // Given - Transaction with null category
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-null-cat-123",
                "Merchant",
                "Transaction Description",
                new BigDecimal("50.00"),
                null // Null category
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify category defaults to "Other"
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        assertEquals("Other", savedTransaction.getCategory());
    }

    @Test
    void testSyncTransactions_WithIncomeTransaction_StoresCorrectly() throws Exception {
        // Given - Income transaction (salary, not autopayment)
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-income-123",
                "EMPLOYER CORP",
                "Direct Deposit - Salary",
                new BigDecimal("-5000.00"), // Negative = income in Plaid
                Arrays.asList("Transfer", "Deposit")
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify income transaction is stored
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        assertEquals("EMPLOYER CORP", savedTransaction.getMerchantName());
        // Amount should be stored as-is (negative for income in Plaid)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("-5000.00")) == 0);
    }

    /**
     * Helper method to create a mock Plaid transaction
     */
    private Transaction createMockPlaidTransaction(
            String transactionId,
            String merchantName,
            String name,
            BigDecimal amount,
            List<String> categories) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        transaction.setMerchantName(merchantName);
        transaction.setName(name);
        transaction.setAmount(amount.doubleValue()); // Convert BigDecimal to Double
        transaction.setCategory(categories);
        transaction.setDate(LocalDate.now());
        transaction.setAccountId("account-123");
        transaction.setPending(false);
        return transaction;
    }
}

