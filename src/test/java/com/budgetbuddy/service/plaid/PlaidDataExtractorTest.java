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
        dataExtractor = new PlaidDataExtractor(categoryMapper, accountRepository, new com.budgetbuddy.service.TransactionTypeDeterminer());
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
        assertEquals(BigDecimal.valueOf(100.0), transaction.getAmount());
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
}

