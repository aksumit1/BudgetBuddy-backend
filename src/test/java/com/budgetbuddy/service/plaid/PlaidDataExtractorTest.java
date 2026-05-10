package com.budgetbuddy.service.plaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.Item;
import com.plaid.client.model.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit Tests for PlaidDataExtractor Tests data extraction from Plaid SDK objects */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidDataExtractorTest {

    @Mock private PlaidCategoryMapper categoryMapper;

    @Mock private AccountRepository accountRepository;

    private PlaidDataExtractor dataExtractor;

    @BeforeEach
    void setUp() {
        dataExtractor =
                new PlaidDataExtractor(
                        accountRepository,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.TransactionTypeCategoryService.class));
    }

    @Test
    void testExtractAccountIdWithValidAccountReturnsAccountId() {
        // Given
        final AccountBase account = new AccountBase();
        account.setAccountId("plaid-account-123");

        // When
        final String accountId = dataExtractor.extractAccountId(account);

        // Then
        assertEquals("plaid-account-123", accountId);
    }

    @Test
    void testExtractAccountIdWithNullAccountIdReturnsNull() {
        // Given
        final AccountBase account = new AccountBase();
        account.setAccountId(null);

        // When
        final String accountId = dataExtractor.extractAccountId(account);

        // Then
        assertNull(accountId);
    }

    @Test
    void testExtractTransactionIdWithValidTransactionReturnsTransactionId() {
        // Given
        final Transaction transaction = new Transaction();
        transaction.setTransactionId("plaid-txn-456");

        // When
        final String transactionId = dataExtractor.extractTransactionId(transaction);

        // Then
        assertEquals("plaid-txn-456", transactionId);
    }

    @Test
    void testExtractTransactionIdWithNullTransactionIdReturnsNull() {
        // Given
        final Transaction transaction = new Transaction();
        transaction.setTransactionId(null);

        // When
        final String transactionId = dataExtractor.extractTransactionId(transaction);

        // Then
        assertNull(transactionId);
    }

    @Test
    void testExtractItemIdWithValidItemReturnsItemId() {
        // Given
        final Item item = new Item();
        item.setItemId("plaid-item-789");

        // When
        final String itemId = dataExtractor.extractItemId(item);

        // Then
        assertEquals("plaid-item-789", itemId);
    }

    @Test
    void testExtractItemIdWithNullItemReturnsNull() {
        // When
        final String itemId = dataExtractor.extractItemId(null);

        // Then
        assertNull(itemId);
    }

    @Test
    void testUpdateAccountFromPlaidWithValidAccountUpdatesAccount() {
        // Given
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());

        final AccountBase plaidAccount = new AccountBase();
        plaidAccount.setAccountId("plaid-account-1");
        plaidAccount.setName("Test Account");
        plaidAccount.setOfficialName("Test Account Official");
        plaidAccount.setMask("1234");
        plaidAccount.setType(AccountType.DEPOSITORY);
        plaidAccount.setSubtype(AccountSubtype.CHECKING);

        final AccountBalance balance = new AccountBalance();
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
    void testUpdateAccountFromPlaidWithNullBalanceUsesDefaults() {
        // Given
        final AccountTable account = new AccountTable();
        final AccountBase plaidAccount = new AccountBase();
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
    void testUpdateTransactionFromPlaidWithValidTransactionUpdatesTransaction() {
        // Given
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setUserId(UUID.randomUUID().toString());

        final Transaction plaidTransaction = new Transaction();
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
        // CRITICAL: Plaid amounts are normalized (sign reversed) - Plaid +100.0 becomes -100.0
        // after normalization
        assertEquals(BigDecimal.valueOf(-100.0), transaction.getAmount());
        assertEquals("Test Transaction", transaction.getDescription());
        assertEquals("USD", transaction.getCurrencyCode());
    }

    @Test
    void testExtractAccountIdFromTransactionWithValidTransactionReturnsAccountId() {
        // Given
        final Transaction transaction = new Transaction();
        transaction.setAccountId("plaid-account-1");

        // When
        final String accountId = dataExtractor.extractAccountIdFromTransaction(transaction);

        // Then
        assertEquals("plaid-account-1", accountId);
    }

    // ========== Plaid Amount Normalization Tests ==========

    @Test
    void testNormalizePlaidAmountWithNullAmountReturnsNull() {
        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(null, null);

        // Then
        assertNull(result);
    }

    @Test
    void testNormalizePlaidAmountWithNullAccountReversesSign() {
        // Given
        final BigDecimal rawAmount = new BigDecimal("100.00");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, null);

        // Then - Should reverse sign even without account info
        assertEquals(new BigDecimal("-100.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithPositiveExpenseReversesToNegative() {
        // Given - Plaid sends expenses as positive
        final BigDecimal rawAmount = new BigDecimal("50.00");
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to negative (backend convention)
        assertEquals(new BigDecimal("-50.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithNegativeIncomeReversesToPositive() {
        // Given - Plaid sends income as negative
        final BigDecimal rawAmount = new BigDecimal("-5000.00");
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to positive (backend convention)
        assertEquals(new BigDecimal("5000.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithZeroAmountReturnsZero() {
        // Given
        final BigDecimal rawAmount = BigDecimal.ZERO;
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Zero should remain zero
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testNormalizePlaidAmountWithCreditCardAccountReversesSign() {
        // Given - Credit card transaction from Plaid
        final BigDecimal rawAmount = new BigDecimal("100.00");
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit card");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-100.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithLoanAccountReversesSign() {
        // Given - Loan transaction from Plaid
        final BigDecimal rawAmount = new BigDecimal("500.00");
        final AccountTable account = new AccountTable();
        account.setAccountType("loan");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-500.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithInvestmentAccountReversesSign() {
        // Given - Investment transaction from Plaid
        final BigDecimal rawAmount = new BigDecimal("1000.00");
        final AccountTable account = new AccountTable();
        account.setAccountType("investment");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign for all account types
        assertEquals(new BigDecimal("-1000.00"), result);
    }

    @Test
    void testNormalizePlaidAmountWithVeryLargeAmountHandlesCorrectly() {
        // Given - Very large amount
        final BigDecimal rawAmount = new BigDecimal("999999999.99");
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign correctly
        assertEquals(new BigDecimal("-999999999.99"), result);
    }

    @Test
    void testNormalizePlaidAmountWithVerySmallAmountHandlesCorrectly() {
        // Given - Very small amount
        final BigDecimal rawAmount = new BigDecimal("0.01");
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse sign correctly
        assertEquals(new BigDecimal("-0.01"), result);
    }

    @Test
    void testNormalizePlaidAmountWithNegativeVeryLargeAmountHandlesCorrectly() {
        // Given - Very large negative amount (income)
        final BigDecimal rawAmount = new BigDecimal("-999999999.99");
        final AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should reverse to positive
        assertEquals(new BigDecimal("999999999.99"), result);
    }

    @Test
    void testNormalizePlaidAmountWithAccountNullTypeReversesSign() {
        // Given - Account with null type
        final BigDecimal rawAmount = new BigDecimal("100.00");
        final AccountTable account = new AccountTable();
        account.setAccountType(null);

        // When
        final BigDecimal result = dataExtractor.normalizePlaidAmount(rawAmount, account);

        // Then - Should still reverse sign
        assertEquals(new BigDecimal("-100.00"), result);
    }
}
