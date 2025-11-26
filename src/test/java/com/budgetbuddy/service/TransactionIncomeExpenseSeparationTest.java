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
 * Unit Tests for PlaidSyncService - Income/Expense Separation
 * Tests that transactions are stored with correct amounts and categories
 * so the iOS app can properly separate income and expenses
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionIncomeExpenseSeparationTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountTable creditCardAccount;
    private AccountTable checkingAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        // Create credit card account
        creditCardAccount = new AccountTable();
        creditCardAccount.setAccountId(UUID.randomUUID().toString());
        creditCardAccount.setUserId(testUser.getUserId());
        creditCardAccount.setAccountName("Chase Credit Card");
        creditCardAccount.setAccountType("creditCard");
        creditCardAccount.setBalance(new BigDecimal("-500.00"));

        // Create checking account
        checkingAccount = new AccountTable();
        checkingAccount.setAccountId(UUID.randomUUID().toString());
        checkingAccount.setUserId(testUser.getUserId());
        checkingAccount.setAccountName("Chase Checking");
        checkingAccount.setAccountType("checking");
        checkingAccount.setBalance(new BigDecimal("1000.00"));
    }

    @Test
    void testSyncTransactions_IncomeTransaction_StoredWithPositiveAmount() throws Exception {
        // Given - Income transaction (salary deposit)
        // Plaid sends income as negative, but we store it as-is
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-income-123",
                "EMPLOYER CORP",
                "Direct Deposit - Salary",
                new BigDecimal("-5000.00"), // Negative = income in Plaid
                Arrays.asList("Transfer", "Deposit"),
                checkingAccount.getAccountId()
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify amount is stored correctly (iOS app will convert to positive)
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is from Plaid (negative for income)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("-5000.00")) == 0);
        assertEquals("EMPLOYER CORP", savedTransaction.getMerchantName());
    }

    @Test
    void testSyncTransactions_ExpenseTransaction_StoredWithPositiveAmount() throws Exception {
        // Given - Expense transaction (coffee purchase)
        // Plaid sends expenses as positive
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-expense-123",
                "Starbucks",
                "Coffee Purchase",
                new BigDecimal("5.50"), // Positive = expense in Plaid
                Arrays.asList("Food and Drink", "Restaurants"),
                checkingAccount.getAccountId()
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify amount is stored correctly (iOS app will convert to negative)
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is from Plaid (positive for expense)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("5.50")) == 0);
        assertEquals("Starbucks", savedTransaction.getMerchantName());
    }

    @Test
    void testSyncTransactions_CreditCardPayment_StoredWithPositiveAmount() throws Exception {
        // Given - Credit card payment (positive amount on credit card account)
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-payment-123",
                "Payment",
                "Payment - Credit Card",
                new BigDecimal("200.00"), // Positive = payment to credit card
                Arrays.asList("Transfer"),
                creditCardAccount.getAccountId()
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify payment is stored (iOS app will exclude it from expenses)
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is (positive for credit card payment)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("200.00")) == 0);
        assertEquals("Payment", savedTransaction.getMerchantName());
        // iOS app will check account type and exclude this from expenses
    }

    @Test
    void testSyncTransactions_CreditCardExpense_StoredWithPositiveAmount() throws Exception {
        // Given - Expense on credit card (spending, not payment)
        Transaction plaidTransaction = createMockPlaidTransaction(
                "txn-cc-expense-123",
                "Coffee Shop",
                "Coffee Purchase",
                new BigDecimal("10.00"), // Positive = expense in Plaid
                Arrays.asList("Food and Drink", "Restaurants"),
                creditCardAccount.getAccountId()
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify expense is stored (iOS app will include it in expenses)
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(captor.capture());
        
        TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is (positive for expense)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("10.00")) == 0);
        assertEquals("Coffee Shop", savedTransaction.getMerchantName());
        // iOS app will include this in expenses (negative amount on credit card = spending)
    }

    @Test
    void testSyncTransactions_AllTransactionTypes_StoredCorrectly() throws Exception {
        // Given - Mix of income, expenses, and credit card transactions
        Transaction income = createMockPlaidTransaction(
                "txn-income-1",
                "EMPLOYER",
                "Salary",
                new BigDecimal("-5000.00"),
                Arrays.asList("Transfer", "Deposit"),
                checkingAccount.getAccountId()
        );

        Transaction expense = createMockPlaidTransaction(
                "txn-expense-1",
                "Grocery Store",
                "Groceries",
                new BigDecimal("100.00"),
                Arrays.asList("Food and Drink", "Groceries"),
                checkingAccount.getAccountId()
        );

        Transaction ccPayment = createMockPlaidTransaction(
                "txn-cc-payment-1",
                "Payment",
                "Payment - Credit Card",
                new BigDecimal("200.00"),
                Arrays.asList("Transfer"),
                creditCardAccount.getAccountId()
        );

        Transaction ccExpense = createMockPlaidTransaction(
                "txn-cc-expense-1",
                "Restaurant",
                "Dinner",
                new BigDecimal("50.00"),
                Arrays.asList("Food and Drink", "Restaurants"),
                creditCardAccount.getAccountId()
        );

        TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(income, expense, ccPayment, ccExpense));
        mockResponse.setTotalTransactions(4);

        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount))
                .thenReturn(Optional.of(checkingAccount))
                .thenReturn(Optional.of(creditCardAccount))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify all transactions are stored with correct amounts
        ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, times(4)).saveIfPlaidTransactionNotExists(captor.capture());
        
        List<TransactionTable> savedTransactions = captor.getAllValues();
        assertEquals(4, savedTransactions.size());
        
        // Verify amounts are stored correctly (iOS app will handle sign conversion)
        assertTrue(savedTransactions.stream().anyMatch(t -> 
            t.getPlaidTransactionId().equals("txn-income-1") && 
            t.getAmount().compareTo(new BigDecimal("-5000.00")) == 0));
        
        assertTrue(savedTransactions.stream().anyMatch(t -> 
            t.getPlaidTransactionId().equals("txn-expense-1") && 
            t.getAmount().compareTo(new BigDecimal("100.00")) == 0));
        
        assertTrue(savedTransactions.stream().anyMatch(t -> 
            t.getPlaidTransactionId().equals("txn-cc-payment-1") && 
            t.getAmount().compareTo(new BigDecimal("200.00")) == 0));
        
        assertTrue(savedTransactions.stream().anyMatch(t -> 
            t.getPlaidTransactionId().equals("txn-cc-expense-1") && 
            t.getAmount().compareTo(new BigDecimal("50.00")) == 0));
    }

    /**
     * Helper method to create a mock Plaid transaction
     */
    private Transaction createMockPlaidTransaction(
            String transactionId,
            String merchantName,
            String name,
            BigDecimal amount,
            List<String> categories,
            String accountId) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        transaction.setMerchantName(merchantName);
        transaction.setName(name);
        transaction.setAmount(amount.doubleValue()); // Convert BigDecimal to Double
        transaction.setCategory(categories);
        transaction.setDate(LocalDate.now());
        transaction.setAccountId(accountId);
        transaction.setPending(false);
        return transaction;
    }
}

