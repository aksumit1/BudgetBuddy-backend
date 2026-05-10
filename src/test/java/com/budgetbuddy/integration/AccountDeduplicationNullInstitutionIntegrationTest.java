package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration Tests for Account Deduplication with Null Institution Name
 *
 * <p>Tests the critical fix where accounts are deduplicated by accountNumber even when
 * institutionName is null. This handles cases where: - Access token was regenerated (new Plaid
 * account IDs) - Multiple link sessions (different Plaid account IDs) - Institution name is missing
 * from Plaid response
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AccountDeduplicationNullInstitutionIntegrationTest {

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserService userService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // Create test user with unique email to avoid duplicates
        final String uniqueEmail = "test-null-institution-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        final String base64PasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("test-hash".getBytes(StandardCharsets.UTF_8));

        testUser = userService.createUserSecure(uniqueEmail, base64PasswordHash, "Test", "User");
    }

    @Test
    void testAccountDeduplicationWithNullInstitutionNameMatchesByAccountNumber() {
        // Given - Existing account with accountNumber but null institutionName
        final String accountNumber = "0000";
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId("plaid-old-" + UUID.randomUUID()); // Old Plaid ID
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
        final Optional<AccountTable> found =
                accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());

        // Then - Should find existing account even though institutionName is null
        assertTrue(
                found.isPresent(),
                "Should find account by accountNumber even when institutionName is null");
        assertEquals(
                existingAccount.getAccountId(),
                found.get().getAccountId(),
                "Should return existing account");
        assertNull(found.get().getInstitutionName(), "Institution name should still be null");
    }

    @Test
    void
            testAccountDeduplicationWithNullInstitutionNameNewAccountWithSameNumberDoesNotCreateDuplicate() {
        // Given - Existing account with accountNumber but null institutionName
        final String accountNumber = "1234";
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(
                "plaid-old-" + UUID.randomUUID()); // Old Plaid ID (regenerated)
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

        // When - New account comes in with:
        // - Different Plaid ID (access token regenerated)
        // - Same accountNumber
        // - Null institutionName
        final String newPlaidAccountId = "plaid-new-" + UUID.randomUUID(); // New Plaid ID
        final Optional<AccountTable> found =
                accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());

        // Then - Should find existing account, preventing duplicate
        assertTrue(
                found.isPresent(),
                "Should find existing account by accountNumber even when institutionName is null");
        assertEquals(
                existingAccount.getAccountId(),
                found.get().getAccountId(),
                "Should return existing account, not create duplicate");

        // Verify only one account exists with this accountNumber
        final List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        final long countWithAccountNumber =
                allAccounts.stream()
                        .filter(a -> accountNumber.equals(a.getAccountNumber()))
                        .count();
        assertEquals(
                1,
                countWithAccountNumber,
                "Should only have one account with this accountNumber, even when institutionName is null");
    }

    @Test
    void
            testAccountDeduplicationWithNullInstitutionNameNewAccountWithInstitutionNameUpdatesExisting() {
        // Given - Existing account with accountNumber but null institutionName
        final String accountNumber = "5678";
        final AccountTable existingAccount = new AccountTable();
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

        // When - New account comes in with:
        // - Different Plaid ID
        // - Same accountNumber
        // - Institution name now available
        final String newPlaidAccountId = "plaid-new-" + UUID.randomUUID();
        final String institutionName = "Test Bank";

        // First check by accountNumber only (since existing has null institutionName)
        final Optional<AccountTable> found =
                accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());

        // Then - Should find existing account
        assertTrue(found.isPresent(), "Should find existing account by accountNumber");

        // Update existing account with new Plaid ID and institution name
        final AccountTable account = found.get();
        account.setPlaidAccountId(newPlaidAccountId);
        account.setInstitutionName(institutionName);
        accountRepository.save(account);

        // Verify account was updated
        final Optional<AccountTable> updated = accountRepository.findById(account.getAccountId());
        assertTrue(updated.isPresent());
        assertEquals(
                newPlaidAccountId, updated.get().getPlaidAccountId(), "Plaid ID should be updated");
        assertEquals(
                institutionName,
                updated.get().getInstitutionName(),
                "Institution name should be updated");

        // Verify still only one account
        final List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        final long countWithAccountNumber =
                allAccounts.stream()
                        .filter(a -> accountNumber.equals(a.getAccountNumber()))
                        .count();
        assertEquals(1, countWithAccountNumber, "Should still only have one account");
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithNullInstitutionNameReturnsEmpty() {
        // Given - Account with null institutionName
        final String accountNumber = "9999";
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountNumber(accountNumber);
        account.setInstitutionName(null);
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // When - Try to find by accountNumber AND institutionName (both required)
        final Optional<AccountTable> found =
                accountRepository.findByAccountNumberAndInstitution(
                        accountNumber, "Test Bank", testUser.getUserId());

        // Then - Should return empty (institutionName doesn't match)
        assertFalse(
                found.isPresent(), "Should not find account when institutionName doesn't match");
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithNullInstitutionNameMatchesByAccountNumberOnly() {
        // Given - Account with null institutionName
        final String accountNumber = "8888";
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountNumber(accountNumber);
        account.setInstitutionName(null);
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // When - Try to find by accountNumber only (institutionName is null in query)
        final Optional<AccountTable> found =
                accountRepository.findByAccountNumberAndInstitution(
                        accountNumber, null, testUser.getUserId());

        // Then - Should find account by accountNumber only (institutionName is null in both)
        assertTrue(
                found.isPresent(),
                "Should find account by accountNumber when institutionName is null in both");
        assertEquals(account.getAccountId(), found.get().getAccountId());
    }

    @Test
    void testAccountDeduplicationAccessTokenRegeneratedWithNullInstitutionNamePreventsDuplicate() {
        // Given - Existing account from first sync
        final String accountNumber = "1111";
        final String oldPlaidId = "plaid-old-" + UUID.randomUUID();

        final AccountTable existingAccount = new AccountTable();
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
        final String newPlaidId = "plaid-new-" + UUID.randomUUID();

        // Simulate PlaidSyncService deduplication logic:
        // 1. Check by plaidAccountId (will fail - new ID)
        final Optional<AccountTable> byPlaidId = accountRepository.findByPlaidAccountId(newPlaidId);
        assertFalse(byPlaidId.isPresent(), "Should not find by new Plaid ID");

        // 2. Check by accountNumber only (should succeed)
        final Optional<AccountTable> byAccountNumber =
                accountRepository.findByAccountNumber(accountNumber, testUser.getUserId());
        assertTrue(
                byAccountNumber.isPresent(),
                "Should find by accountNumber even when institutionName is null");
        assertEquals(existingAccount.getAccountId(), byAccountNumber.get().getAccountId());

        // Update existing account with new Plaid ID
        final AccountTable account = byAccountNumber.get();
        account.setPlaidAccountId(newPlaidId);
        accountRepository.save(account);

        // Then - Should only have one account
        final List<AccountTable> allAccounts = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(1, allAccounts.size(), "Should only have one account after deduplication");

        final Optional<AccountTable> finalAccount =
                accountRepository.findByPlaidAccountId(newPlaidId);
        assertTrue(finalAccount.isPresent(), "Account should have new Plaid ID");
        assertEquals(
                accountNumber,
                finalAccount.get().getAccountNumber(),
                "Account number should be preserved");
    }
}
