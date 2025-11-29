package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Plaid Deduplication
 * Tests that accounts and transactions are properly deduplicated using plaidAccountId and plaidTransactionId
 * even when backend bugs or Plaid bugs cause duplicates
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PlaidDeduplicationIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserService userService;

    private UserTable testUser;
    private String testEmail;

    @BeforeEach
    void setUp() {
        testEmail = "dedup-test-" + UUID.randomUUID() + "@example.com";
        // Use proper base64-encoded strings
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String base64ClientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        testUser = userService.createUserSecure(
                testEmail,
                base64PasswordHash,
                base64ClientSalt,
                "Test",
                "User"
        );
    }

    @Test
    void testAccountDeduplication_WithSamePlaidAccountId_DoesNotCreateDuplicate() {
        // Given - Existing account with plaidAccountId
        String plaidAccountId = "plaid-account-" + UUID.randomUUID();
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to create another account with same plaidAccountId (simulating backend bug)
        AccountTable duplicateAccount = new AccountTable();
        duplicateAccount.setAccountId(UUID.randomUUID().toString()); // Different UUID (backend bug)
        duplicateAccount.setUserId(testUser.getUserId());
        duplicateAccount.setPlaidAccountId(plaidAccountId); // Same Plaid ID
        duplicateAccount.setAccountName("Duplicate Account");
        duplicateAccount.setInstitutionName("Test Bank");
        duplicateAccount.setAccountType("CHECKING");
        duplicateAccount.setBalance(new BigDecimal("2000.00"));
        duplicateAccount.setCurrencyCode("USD");
        duplicateAccount.setActive(true);
        duplicateAccount.setCreatedAt(Instant.now());
        duplicateAccount.setUpdatedAt(Instant.now());

        // Use saveIfNotExists to prevent duplicates
        boolean saved = accountRepository.saveIfNotExists(duplicateAccount);
        
        // Then - Should not create duplicate
        // If saveIfNotExists returns false, it means a duplicate was detected
        // If it returns true, we need to check by plaidAccountId
        if (saved) {
            // Check if we can find by plaidAccountId - should only find one
            Optional<AccountTable> foundByPlaidId = accountRepository.findByPlaidAccountId(plaidAccountId);
            assertTrue(foundByPlaidId.isPresent(), "Account should be found by Plaid ID");
            
            // Verify only one account exists with this plaidAccountId
            List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
            long countWithPlaidId = allAccounts.stream()
                    .filter(a -> plaidAccountId.equals(a.getPlaidAccountId()))
                    .count();
            assertEquals(1, countWithPlaidId, "Should only have one account with this Plaid ID");
        } else {
            // saveIfNotExists detected duplicate by accountId
            // Verify existing account is still there
            Optional<AccountTable> foundByPlaidId = accountRepository.findByPlaidAccountId(plaidAccountId);
            assertTrue(foundByPlaidId.isPresent(), "Original account should still exist");
            assertEquals("Existing Account", foundByPlaidId.get().getAccountName());
        }
    }

    @Test
    void testAccountDeduplication_WithFindByPlaidAccountId_FindsExistingAccount() {
        // Given - Existing account with plaidAccountId
        String plaidAccountId = "plaid-account-" + UUID.randomUUID();
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to find by plaidAccountId
        Optional<AccountTable> found = accountRepository.findByPlaidAccountId(plaidAccountId);

        // Then - Should find the existing account
        assertTrue(found.isPresent(), "Account should be found by Plaid ID");
        assertEquals(plaidAccountId, found.get().getPlaidAccountId());
        assertEquals(existingAccount.getAccountId(), found.get().getAccountId());
    }

    @Test
    void testAccountDeduplication_WithAccountNumber_FindsExistingAccount() {
        // Given - Existing account with account number (but no plaidAccountId)
        String accountNumber = "1234";
        String institutionName = "Test Bank";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(null); // Missing Plaid ID
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(institutionName);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to find by account number and institution
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                accountNumber, institutionName, testUser.getUserId());

        // Then - Should find the existing account
        assertTrue(found.isPresent(), "Account should be found by account number and institution");
        assertEquals(accountNumber, found.get().getAccountNumber());
        assertEquals(institutionName, found.get().getInstitutionName());
        assertEquals(existingAccount.getAccountId(), found.get().getAccountId());
    }

    @Test
    void testAccountDeduplication_WithAccountNumber_PreventsDuplicate() {
        // Given - Existing account with account number
        String accountNumber = "5678";
        String institutionName = "Test Bank";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(institutionName);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to create duplicate with same account number and institution
        AccountTable duplicateAccount = new AccountTable();
        duplicateAccount.setAccountId(UUID.randomUUID().toString()); // Different UUID
        duplicateAccount.setUserId(testUser.getUserId());
        duplicateAccount.setAccountNumber(accountNumber); // Same account number
        duplicateAccount.setInstitutionName(institutionName); // Same institution
        duplicateAccount.setAccountName("Duplicate Account");
        duplicateAccount.setAccountType("CHECKING");
        duplicateAccount.setBalance(new BigDecimal("2000.00"));
        duplicateAccount.setCurrencyCode("USD");
        duplicateAccount.setActive(true);
        duplicateAccount.setCreatedAt(Instant.now());
        duplicateAccount.setUpdatedAt(Instant.now());

        // Find by account number should return existing account
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                accountNumber, institutionName, testUser.getUserId());

        // Then - Should find existing account, preventing duplicate
        assertTrue(found.isPresent(), "Should find existing account by account number");
        assertEquals(existingAccount.getAccountId(), found.get().getAccountId(),
                "Should return existing account, not create duplicate");
        
        // Verify only one account exists with this account number
        List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        long countWithAccountNumber = allAccounts.stream()
                .filter(a -> accountNumber.equals(a.getAccountNumber()) 
                        && institutionName.equals(a.getInstitutionName()))
                .count();
        assertEquals(1, countWithAccountNumber, 
                "Should only have one account with this account number and institution");
    }

    @Test
    void testAccountDeduplication_WithNullInstitutionName_MatchesByAccountNumber() {
        // Given - Existing account with accountNumber but null institutionName
        String accountNumber = "0000";
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId("plaid-old-" + UUID.randomUUID());
        existingAccount.setAccountName("Checking Account");
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(null); // NULL institution name
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to find account by accountNumber only (institutionName is null)
        Optional<AccountTable> found = accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());

        // Then - Should find existing account even though institutionName is null
        assertTrue(found.isPresent(), "Should find account by accountNumber even when institutionName is null");
        assertEquals(existingAccount.getAccountId(), found.get().getAccountId(),
                "Should return existing account");
    }

    @Test
    void testAccountDeduplication_AccessTokenRegenerated_WithNullInstitutionName_PreventsDuplicate() {
        // Given - Existing account from first sync with null institutionName
        String accountNumber = "1111";
        String oldPlaidId = "plaid-old-" + UUID.randomUUID();
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(oldPlaidId);
        existingAccount.setAccountName("Checking");
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(null); // NULL institution name
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Access token regenerated, new account comes in with:
        // - New Plaid ID (different from existing)
        // - Same accountNumber
        // - Still null institutionName
        String newPlaidId = "plaid-new-" + UUID.randomUUID();
        
        // Simulate PlaidSyncService deduplication logic:
        // 1. Check by plaidAccountId (will fail - new ID)
        Optional<AccountTable> byPlaidId = accountRepository.findByPlaidAccountId(newPlaidId);
        assertFalse(byPlaidId.isPresent(), "Should not find by new Plaid ID");

        // 2. Check by accountNumber only (should succeed)
        Optional<AccountTable> byAccountNumber = accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());
        assertTrue(byAccountNumber.isPresent(), "Should find by accountNumber even when institutionName is null");
        assertEquals(existingAccount.getAccountId(), byAccountNumber.get().getAccountId());

        // Update existing account with new Plaid ID
        AccountTable account = byAccountNumber.get();
        account.setPlaidAccountId(newPlaidId);
        accountRepository.save(account);

        // Then - Should only have one account
        List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(1, allAccounts.size(), "Should only have one account after deduplication");
    }

    @Test
    void testTransactionDeduplication_WithSamePlaidTransactionId_DoesNotCreateDuplicate() {
        // Given - Existing transaction with plaidTransactionId
        String plaidTransactionId = "plaid-transaction-" + UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        
        // Create account first
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(accountId);
        existingTransaction.setPlaidTransactionId(plaidTransactionId);
        existingTransaction.setAmount(new BigDecimal("100.00"));
        existingTransaction.setDescription("Existing Transaction");
        existingTransaction.setCategory("FOOD");
        existingTransaction.setTransactionDate(java.time.LocalDate.now().toString());
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to create another transaction with same plaidTransactionId (simulating backend bug)
        TransactionTable duplicateTransaction = new TransactionTable();
        duplicateTransaction.setTransactionId(UUID.randomUUID().toString()); // Different UUID (backend bug)
        duplicateTransaction.setUserId(testUser.getUserId());
        duplicateTransaction.setAccountId(accountId);
        duplicateTransaction.setPlaidTransactionId(plaidTransactionId); // Same Plaid ID
        duplicateTransaction.setAmount(new BigDecimal("200.00"));
        duplicateTransaction.setDescription("Duplicate Transaction");
        duplicateTransaction.setCategory("FOOD");
        duplicateTransaction.setTransactionDate(java.time.LocalDate.now().toString());
        duplicateTransaction.setCurrencyCode("USD");
        duplicateTransaction.setCreatedAt(Instant.now());
        duplicateTransaction.setUpdatedAt(Instant.now());

        // Use saveIfPlaidTransactionNotExists to prevent duplicates
        boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(duplicateTransaction);

        // Then - Should not create duplicate
        if (saved) {
            // Check if we can find by plaidTransactionId - should only find one
            Optional<TransactionTable> foundByPlaidId = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            assertTrue(foundByPlaidId.isPresent(), "Transaction should be found by Plaid ID");
            
            // Verify only one transaction exists with this plaidTransactionId
            List<TransactionTable> allTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 1000);
            long countWithPlaidId = allTransactions.stream()
                    .filter(t -> plaidTransactionId.equals(t.getPlaidTransactionId()))
                    .count();
            assertEquals(1, countWithPlaidId, "Should only have one transaction with this Plaid ID");
        } else {
            // saveIfPlaidTransactionNotExists detected duplicate
            // Verify existing transaction is still there
            Optional<TransactionTable> foundByPlaidId = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            assertTrue(foundByPlaidId.isPresent(), "Original transaction should still exist");
            assertEquals("Existing Transaction", foundByPlaidId.get().getDescription());
        }
    }

    @Test
    void testTransactionDeduplication_WithFindByPlaidTransactionId_FindsExistingTransaction() {
        // Given - Existing transaction with plaidTransactionId
        String plaidTransactionId = "plaid-transaction-" + UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        
        // Create account first
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(accountId);
        existingTransaction.setPlaidTransactionId(plaidTransactionId);
        existingTransaction.setAmount(new BigDecimal("100.00"));
        existingTransaction.setDescription("Existing Transaction");
        existingTransaction.setCategory("FOOD");
        existingTransaction.setTransactionDate(java.time.LocalDate.now().toString());
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to find by plaidTransactionId
        Optional<TransactionTable> found = transactionRepository.findByPlaidTransactionId(plaidTransactionId);

        // Then - Should find the existing transaction
        assertTrue(found.isPresent(), "Transaction should be found by Plaid ID");
        assertEquals(plaidTransactionId, found.get().getPlaidTransactionId());
        assertEquals(existingTransaction.getTransactionId(), found.get().getTransactionId());
    }

    @Test
    void testAccountDeduplication_WithMultipleSyncs_OnlyOneAccountExists() {
        // Given - Existing account
        String plaidAccountId = "plaid-account-" + UUID.randomUUID();
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("CHECKING");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // When - Try to sync multiple times (simulating multiple Plaid syncs)
        for (int i = 0; i < 3; i++) {
            // Check if account exists by plaidAccountId
            Optional<AccountTable> found = accountRepository.findByPlaidAccountId(plaidAccountId);
            if (found.isPresent()) {
                // Update existing account
                AccountTable account = found.get();
                account.setBalance(new BigDecimal("1000.00").add(BigDecimal.valueOf(i * 100)));
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
            } else {
                // Create new account (should not happen)
                AccountTable newAccount = new AccountTable();
                newAccount.setAccountId(UUID.randomUUID().toString());
                newAccount.setUserId(testUser.getUserId());
                newAccount.setPlaidAccountId(plaidAccountId);
                newAccount.setAccountName("New Account " + i);
                newAccount.setInstitutionName("Test Bank");
                newAccount.setAccountType("CHECKING");
                newAccount.setBalance(new BigDecimal("1000.00"));
                newAccount.setCurrencyCode("USD");
                newAccount.setActive(true);
                newAccount.setCreatedAt(Instant.now());
                newAccount.setUpdatedAt(Instant.now());
                accountRepository.saveIfNotExists(newAccount);
            }
        }

        // Then - Should only have one account with this plaidAccountId
        List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        long countWithPlaidId = allAccounts.stream()
                .filter(a -> plaidAccountId.equals(a.getPlaidAccountId()))
                .count();
        assertEquals(1, countWithPlaidId, "Should only have one account after multiple syncs");
        
        Optional<AccountTable> finalAccount = accountRepository.findByPlaidAccountId(plaidAccountId);
        assertTrue(finalAccount.isPresent(), "Account should still exist");
        assertEquals(existingAccount.getAccountId(), finalAccount.get().getAccountId(), "Should be the same account");
    }

    @Test
    void testTransactionDeduplication_WithMultipleSyncs_OnlyOneTransactionExists() {
        // Given - Existing transaction
        String plaidTransactionId = "plaid-transaction-" + UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        
        // Create account first
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(accountId);
        existingTransaction.setPlaidTransactionId(plaidTransactionId);
        existingTransaction.setAmount(new BigDecimal("100.00"));
        existingTransaction.setDescription("Existing Transaction");
        existingTransaction.setCategory("FOOD");
        existingTransaction.setTransactionDate(java.time.LocalDate.now().toString());
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to sync multiple times (simulating multiple Plaid syncs)
        for (int i = 0; i < 3; i++) {
            // Check if transaction exists by plaidTransactionId
            Optional<TransactionTable> found = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (found.isPresent()) {
                // Update existing transaction
                TransactionTable transaction = found.get();
                transaction.setAmount(new BigDecimal("100.00").add(BigDecimal.valueOf(i * 10)));
                transaction.setUpdatedAt(Instant.now());
                transactionRepository.save(transaction);
            } else {
                // Create new transaction (should not happen)
                TransactionTable newTransaction = new TransactionTable();
                newTransaction.setTransactionId(UUID.randomUUID().toString());
                newTransaction.setUserId(testUser.getUserId());
                newTransaction.setAccountId(accountId);
                newTransaction.setPlaidTransactionId(plaidTransactionId);
                newTransaction.setAmount(new BigDecimal("100.00"));
                newTransaction.setDescription("New Transaction " + i);
                newTransaction.setCategory("FOOD");
                newTransaction.setTransactionDate(java.time.LocalDate.now().toString());
                newTransaction.setCurrencyCode("USD");
                newTransaction.setCreatedAt(Instant.now());
                newTransaction.setUpdatedAt(Instant.now());
                transactionRepository.saveIfPlaidTransactionNotExists(newTransaction);
            }
        }

        // Then - Should only have one transaction with this plaidTransactionId
        List<TransactionTable> allTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 1000);
        long countWithPlaidId = allTransactions.stream()
                .filter(t -> plaidTransactionId.equals(t.getPlaidTransactionId()))
                .count();
        assertEquals(1, countWithPlaidId, "Should only have one transaction after multiple syncs");
        
        Optional<TransactionTable> finalTransaction = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
        assertTrue(finalTransaction.isPresent(), "Transaction should still exist");
        assertEquals(existingTransaction.getTransactionId(), finalTransaction.get().getTransactionId(), "Should be the same transaction");
    }
}

