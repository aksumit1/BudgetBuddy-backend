package com.budgetbuddy.integration;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Integration Tests for Plaid Reconnect Sync Flow Tests that accounts and transactions are synced
 * during token exchange (reconnect scenario)
 *
 * <p>This tests the critical fix where backend syncs accounts/transactions during token exchange,
 * and the app should fetch this data immediately after reconnect without requiring
 * sign-out/sign-in.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaidReconnectSyncIntegrationTest {

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private String testEmail;
    private String testItemId;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testEmail = "test-reconnect-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));
        final String base64ClientSalt =
                java.util.Base64.getEncoder().encodeToString("client-salt".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Simulate item ID from token exchange
        testItemId = "test-item-id-" + UUID.randomUUID();
    }

    @Test
    void testReconnectSyncAccountsSyncedDuringTokenExchangeAreRetrievable() {
        // Given - Simulate accounts being synced during token exchange (reconnect scenario)
        // This is what happens in PlaidController.exchangePublicToken() after token exchange

        final AccountTable account1 = new AccountTable();
        account1.setAccountId(UUID.randomUUID().toString());
        account1.setUserId(testUser.getUserId());
        account1.setPlaidAccountId("plaid-acc-1-" + UUID.randomUUID());
        account1.setPlaidItemId(testItemId);
        account1.setAccountName("Checking Account");
        account1.setInstitutionName("Test Bank");
        account1.setAccountType("CHECKING");
        account1.setAccountNumber("1234");
        account1.setBalance(new BigDecimal("1000.00"));
        account1.setCurrencyCode("USD");
        account1.setActive(true);
        account1.setCreatedAt(Instant.now());
        account1.setUpdatedAt(Instant.now());
        account1.setLastSyncedAt(Instant.now());

        final AccountTable account2 = new AccountTable();
        account2.setAccountId(UUID.randomUUID().toString());
        account2.setUserId(testUser.getUserId());
        account2.setPlaidAccountId("plaid-acc-2-" + UUID.randomUUID());
        account2.setPlaidItemId(testItemId);
        account2.setAccountName("Savings Account");
        account2.setInstitutionName("Test Bank");
        account2.setAccountType("SAVINGS");
        account2.setAccountNumber("5678");
        account2.setBalance(new BigDecimal("5000.00"));
        account2.setCurrencyCode("USD");
        account2.setActive(true);
        account2.setCreatedAt(Instant.now());
        account2.setUpdatedAt(Instant.now());
        account2.setLastSyncedAt(Instant.now());

        // When - Save accounts (simulating sync during token exchange)
        accountRepository.save(account1);
        accountRepository.save(account2);

        // Then - Accounts should be retrievable immediately
        final List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(2, retrieved.size(), "Should have 2 accounts after reconnect sync");

        // Verify account details
        final AccountTable retrievedAccount1 =
                retrieved.stream()
                        .filter(a -> a.getAccountId().equals(account1.getAccountId()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(retrievedAccount1, "Account 1 should be found");
        assertEquals("Checking Account", retrievedAccount1.getAccountName());
        assertEquals(testItemId, retrievedAccount1.getPlaidItemId());
        assertNotNull(retrievedAccount1.getLastSyncedAt(), "Account should have lastSyncedAt set");

        final AccountTable retrievedAccount2 =
                retrieved.stream()
                        .filter(a -> a.getAccountId().equals(account2.getAccountId()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(retrievedAccount2, "Account 2 should be found");
        assertEquals("Savings Account", retrievedAccount2.getAccountName());
        assertEquals(testItemId, retrievedAccount2.getPlaidItemId());
    }

    @Test
    void testReconnectSyncTransactionsSyncedDuringTokenExchangeAreRetrievable() {
        // Given - Simulate transactions being synced during token exchange
        // First create an account (required for transactions)
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId("plaid-acc-" + UUID.randomUUID());
        account.setPlaidItemId(testItemId);
        account.setAccountName("Checking Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // Create transactions (simulating sync during token exchange)
        final TransactionTable transaction1 = new TransactionTable();
        transaction1.setTransactionId(UUID.randomUUID().toString());
        transaction1.setUserId(testUser.getUserId());
        transaction1.setAccountId(account.getAccountId());
        transaction1.setPlaidTransactionId("plaid-tx-1-" + UUID.randomUUID());
        transaction1.setAmount(new BigDecimal("-50.00"));
        transaction1.setDescription("Grocery Store");
        transaction1.setMerchantName("Grocery Store");
        transaction1.setCategoryPrimary("groceries");
        transaction1.setCategoryDetailed("groceries");
        transaction1.setTransactionDate(java.time.LocalDate.now().toString());
        transaction1.setCurrencyCode("USD");
        transaction1.setCreatedAt(Instant.now());
        transaction1.setUpdatedAt(Instant.now());

        final TransactionTable transaction2 = new TransactionTable();
        transaction2.setTransactionId(UUID.randomUUID().toString());
        transaction2.setUserId(testUser.getUserId());
        transaction2.setAccountId(account.getAccountId());
        transaction2.setPlaidTransactionId("plaid-tx-2-" + UUID.randomUUID());
        transaction2.setAmount(new BigDecimal("-25.00"));
        transaction2.setDescription("Coffee Shop");
        transaction2.setMerchantName("Coffee Shop");
        transaction2.setCategoryPrimary("dining");
        transaction2.setCategoryDetailed("dining");
        transaction2.setTransactionDate(java.time.LocalDate.now().toString());
        transaction2.setCurrencyCode("USD");
        transaction2.setCreatedAt(Instant.now());
        transaction2.setUpdatedAt(Instant.now());

        // When - Save transactions (simulating sync during token exchange)
        transactionRepository.save(transaction1);
        transactionRepository.save(transaction2);

        // Then - Transactions should be retrievable immediately
        final List<TransactionTable> retrieved =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, retrieved.size(), "Should have 2 transactions after reconnect sync");

        // Verify transaction details
        final TransactionTable retrievedTx1 =
                retrieved.stream()
                        .filter(t -> t.getTransactionId().equals(transaction1.getTransactionId()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(retrievedTx1, "Transaction 1 should be found");
        assertEquals("Grocery Store", retrievedTx1.getDescription());
        assertEquals(
                0,
                new BigDecimal("-50.00").compareTo(retrievedTx1.getAmount()),
                "Amount should be -50.00");

        final TransactionTable retrievedTx2 =
                retrieved.stream()
                        .filter(t -> t.getTransactionId().equals(transaction2.getTransactionId()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(retrievedTx2, "Transaction 2 should be found");
        assertEquals("Coffee Shop", retrievedTx2.getDescription());
        assertEquals(
                0,
                new BigDecimal("-25.00").compareTo(retrievedTx2.getAmount()),
                "Amount should be -25.00");
    }

    @Test
    void testReconnectSyncMultipleAccountsAddedAllAccountsRetrievable() {
        // Given - Simulate reconnect where user adds more accounts
        // First, create existing account
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId("plaid-acc-existing");
        existingAccount.setPlaidItemId("old-item-id");
        existingAccount.setAccountName("Old Checking");
        existingAccount.setInstitutionName("Old Bank");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("500.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now().minusSeconds(86_400)); // 1 day ago
        existingAccount.setUpdatedAt(Instant.now().minusSeconds(86_400));
        accountRepository.save(existingAccount);

        // When - Reconnect and add new accounts (simulating sync during token exchange)
        final AccountTable newAccount1 = new AccountTable();
        newAccount1.setAccountId(UUID.randomUUID().toString());
        newAccount1.setUserId(testUser.getUserId());
        newAccount1.setPlaidAccountId("plaid-acc-new-1");
        newAccount1.setPlaidItemId(testItemId);
        newAccount1.setAccountName("New Checking");
        newAccount1.setInstitutionName("New Bank");
        newAccount1.setAccountType("CHECKING");
        newAccount1.setBalance(new BigDecimal("2000.00"));
        newAccount1.setCurrencyCode("USD");
        newAccount1.setActive(true);
        newAccount1.setCreatedAt(Instant.now());
        newAccount1.setUpdatedAt(Instant.now());
        newAccount1.setLastSyncedAt(Instant.now());

        final AccountTable newAccount2 = new AccountTable();
        newAccount2.setAccountId(UUID.randomUUID().toString());
        newAccount2.setUserId(testUser.getUserId());
        newAccount2.setPlaidAccountId("plaid-acc-new-2");
        newAccount2.setPlaidItemId(testItemId);
        newAccount2.setAccountName("New Savings");
        newAccount2.setInstitutionName("New Bank");
        newAccount2.setAccountType("SAVINGS");
        newAccount2.setBalance(new BigDecimal("10000.00"));
        newAccount2.setCurrencyCode("USD");
        newAccount2.setActive(true);
        newAccount2.setCreatedAt(Instant.now());
        newAccount2.setUpdatedAt(Instant.now());
        newAccount2.setLastSyncedAt(Instant.now());

        accountRepository.save(newAccount1);
        accountRepository.save(newAccount2);

        // Then - All accounts (old + new) should be retrievable
        final List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(3, retrieved.size(), "Should have 3 accounts total (1 old + 2 new)");

        // Verify all accounts are present
        assertTrue(
                retrieved.stream().anyMatch(a -> "Old Checking".equals(a.getAccountName())),
                "Old account should be present");
        assertTrue(
                retrieved.stream().anyMatch(a -> "New Checking".equals(a.getAccountName())),
                "New account 1 should be present");
        assertTrue(
                retrieved.stream().anyMatch(a -> "New Savings".equals(a.getAccountName())),
                "New account 2 should be present");
    }

    @Test
    void testReconnectSyncAccountsHaveLastSyncedAtSetAfterSync() {
        // Given - Account synced during reconnect
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId("plaid-acc-" + UUID.randomUUID());
        account.setPlaidItemId(testItemId);
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        account.setLastSyncedAt(Instant.now()); // Set during sync

        // When - Save account
        accountRepository.save(account);

        // Then - Account should have lastSyncedAt set
        final List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(1, retrieved.size());
        final AccountTable retrievedAccount = retrieved.get(0);
        assertNotNull(
                retrievedAccount.getLastSyncedAt(),
                "Account should have lastSyncedAt set after reconnect sync");
        assertTrue(
                retrievedAccount.getLastSyncedAt().isAfter(Instant.now().minusSeconds(60)),
                "lastSyncedAt should be recent (within last minute)");
    }
}
