package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidDataExtractor
 * Tests data extraction from Plaid SDK objects
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidDataExtractorTest {

    @Mock
    private PlaidCategoryMapper categoryMapper;

    @Mock
    private AccountRepository accountRepository;

    private PlaidDataExtractor dataExtractor;

    @BeforeEach
    void setUp() {
        dataExtractor = new PlaidDataExtractor(accountRepository, org.mockito.Mockito.mock(com.budgetbuddy.service.TransactionTypeCategoryService.class));
    }

    @Test
    void testExtractAccountId_WithValidAccount_ReturnsAccountId() {
        // Given
        AccountBase account = new AccountBase();
        account.setAccountId("plaid-account-123");

        // When
        String accountId = dataExtractor.extractAccountId(account);

        // Then
        assertEquals("plaid-account-123", accountId);
    }

    @Test
    void testExtractAccountId_WithNullAccountId_ReturnsNull() {
        // Given
        AccountBase account = new AccountBase();
        account.setAccountId(null);

        // When
        String accountId = dataExtractor.extractAccountId(account);

        // Then
        assertNull(accountId);
    }

    @Test
    void testExtractTransactionId_WithValidTransaction_ReturnsTransactionId() {
        // Given
        Transaction transaction = new Transaction();
        transaction.setTransactionId("plaid-txn-456");

        // When
        String transactionId = dataExtractor.extractTransactionId(transaction);

        // Then
        assertEquals("plaid-txn-456", transactionId);
    }

    @Test
    void testExtractTransactionId_WithNullTransactionId_ReturnsNull() {
        // Given
        Transaction transaction = new Transaction();
        transaction.setTransactionId(null);

        // When
        String transactionId = dataExtractor.extractTransactionId(transaction);

        // Then
        assertNull(transactionId);
    }

    @Test
    void testExtractItemId_WithValidItem_ReturnsItemId() {
        // Given
        Item item = new Item();
        item.setItemId("plaid-item-789");

        // When
        String itemId = dataExtractor.extractItemId(item);

        // Then
        assertEquals("plaid-item-789", itemId);
    }

    @Test
    void testExtractItemId_WithNullItem_ReturnsNull() {
        // When
        String itemId = dataExtractor.extractItemId(null);

        // Then
        assertNull(itemId);
    }

    @Test
    void testUpdateAccountFromPlaid_WithValidAccount_UpdatesAccount() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        
        AccountBase plaidAccount = new AccountBase();
        plaidAccount.setAccountId("plaid-account-1");
        plaidAccount.setName("Test Account");
        plaidAccount.setOfficialName("Test Account Official");
        plaidAccount.setMask("1234");
        plaidAccount.setType(AccountType.DEPOSITORY);
        plaidAccount.setSubtype(AccountSubtype.CHECKING);
        
        AccountBalance balance = new AccountBalance();
        balance.setAvailable(1000.0);
        balance.setCurrent(1000.0);
        balance.setIsoCurrencyCode("USD");
        plaidAccount.setBalances(balance);

        // When
        dataExtractor.updateAccountFromPlaid(account, plaidAccount);

        // Then
        assertNotNull(account.getUpdatedAt());
        assertEquals("Test Account Official", account.getAccountName());
        assertEquals("1234", account.getAccountNumber());
        assertEquals(BigDecimal.valueOf(1000.0), account.getBalance());
        assertEquals("USD", account.getCurrencyCode());
    }

    @Test
    void testUpdateAccountFromPlaid_WithNullBalance_UsesDefaults() {
        // Given
        AccountTable account = new AccountTable();
        AccountBase plaidAccount = new AccountBase();
        plaidAccount.setName("Test Account");
        plaidAccount.setBalances(null);

        // When
        dataExtractor.updateAccountFromPlaid(account, plaidAccount);

        // Then
        assertNotNull(account.getUpdatedAt());
        assertEquals(BigDecimal.ZERO, account.getBalance());
        assertEquals("USD", account.getCurrencyCode());
    }

    @Test
    void testUpdateTransactionFromPlaid_WithValidTransaction_UpdatesTransaction() {
        // Given
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setUserId(UUID.randomUUID().toString());
        
        Transaction plaidTransaction = new Transaction();
        plaidTransaction.setTransactionId("plaid-txn-1");
        plaidTransaction.setAccountId("plaid-account-1");
        plaidTransaction.setAmount(100.0);
        plaidTransaction.setName("Test Transaction");
        plaidTransaction.setDate(LocalDate.now());
        plaidTransaction.setIsoCurrencyCode("USD");
        
        when(categoryMapper.mapPlaidCategory(any(), any(), any(), any()))
                .thenReturn(new PlaidCategoryMapper.CategoryMapping("other", "other", false));

        // When
        dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction);

        // Then
        assertNotNull(transaction.getUpdatedAt());
        // CRITICAL: Plaid amounts are normalized (sign reversed) - Plaid +100.0 becomes -100.0 after normalization
        assertEquals(BigDecimal.valueOf(-100.0), transaction.getAmount());
        assertEquals("Test Transaction", transaction.getDescription());
        assertEquals("USD", transaction.getCurrencyCode());
    }

    @Test
    void testExtractAccountIdFromTransaction_WithValidTransaction_ReturnsAccountId() {
        // Given
        Transaction transaction = new Transaction();
        transaction.setAccountId("plaid-account-1");

        // When
        String accountId = dataExtractor.extractAccountIdFromTransaction(transaction);

        // Then
        assertEquals("plaid-account-1", accountId);
    }

    // ========== Plaid Amount Normalization Tests ==========

    @Test
    void testNormalizePlaidAmount_WithNullAmount_ReturnsNull() {
        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(null, null);

        // Then
        assertNull(result);
    }

    @Test
    void testNormalizePlaidAmount_WithNullAccount_ReversesSign() {
        // Given
        BigDecimal rawAmount = new BigDecimal("100.00");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, null);

        // Then - Should reverse sign even without account info
        assertEquals(new BigDecimal("-100.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithPositiveExpense_ReversesToNegative() {
        // Given - Plaid sends expenses as positive
        BigDecimal rawAmount = new BigDecimal("50.00");
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to negative (backend convention)
        assertEquals(new BigDecimal("-50.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithNegativeIncome_ReversesToPositive() {
        // Given - Plaid sends income as negative
        BigDecimal rawAmount = new BigDecimal("-5000.00");
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to positive (backend convention)
        assertEquals(new BigDecimal("5000.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithZeroAmount_ReturnsZero() {
        // Given
        BigDecimal rawAmount = BigDecimal.ZERO;
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Zero should remain zero
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testNormalizePlaidAmount_WithCreditCardAccount_ReversesSign() {
        // Given - Credit card transaction from Plaid
        BigDecimal rawAmount = new BigDecimal("100.00");
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit card");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-100.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithLoanAccount_ReversesSign() {
        // Given - Loan transaction from Plaid
        BigDecimal rawAmount = new BigDecimal("500.00");
        AccountTable account = new AccountTable();
        account.setAccountType("loan");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-500.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithInvestmentAccount_ReversesSign() {
        // Given - Investment transaction from Plaid
        BigDecimal rawAmount = new BigDecimal("1000.00");
        AccountTable account = new AccountTable();
        account.setAccountType("investment");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-1000.00"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithVeryLargeAmount_HandlesCorrectly() {
        // Given - Very large amount
        BigDecimal rawAmount = new BigDecimal("999999999.99");
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign correctly
        assertEquals(new BigDecimal("-999999999.99"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithVerySmallAmount_HandlesCorrectly() {
        // Given - Very small amount
        BigDecimal rawAmount = new BigDecimal("0.01");
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign correctly
        assertEquals(new BigDecimal("-0.01"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithNegativeVeryLargeAmount_HandlesCorrectly() {
        // Given - Very large negative amount (income)
        BigDecimal rawAmount = new BigDecimal("-999999999.99");
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to positive
        assertEquals(new BigDecimal("999999999.99"), result);
    }

    @Test
    void testNormalizePlaidAmount_WithAccountNullType_ReversesSign() {
        // Given - Account with null type
        BigDecimal rawAmount = new BigDecimal("100.00");
        AccountTable account = new AccountTable();
        account.setAccountType(null);

        // When
        BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should still reverse sign
        assertEquals(new BigDecimal("-100.00"), result);
    }
}

