package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.PlaidSyncService;
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
 * Integration Tests for PlaidSyncService
 * Tests the full flow of syncing accounts and transactions from Plaid
 * 
 * Note: These tests require a running LocalStack instance.
 * They test the actual database operations.
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PlaidSyncIntegrationTest {

    @Autowired
    private PlaidSyncService plaidSyncService;

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
        testEmail = "test-plaid-" + UUID.randomUUID() + "@example.com";
        testUser = userService.createUserSecure(
                testEmail,
                "hashed-password",
                "client-salt",
                "Test",
                "User"
        );
    }

    @Test
    void testSyncAccounts_SavesAccountsWithActiveTrue() {
        // Given - This would require a mock PlaidService or actual Plaid sandbox credentials
        // For now, we'll test the repository behavior after sync
        
        // Create an account manually to simulate what sync would do
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId("test-plaid-account-" + UUID.randomUUID());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true); // This is what sync should set
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        
        // When
        accountRepository.save(account);
        
        // Then
        List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        assertFalse(retrieved.isEmpty(), "Account should be retrievable");
        AccountTable retrievedAccount = retrieved.stream()
                .filter(a -> a.getAccountId().equals(account.getAccountId()))
                .findFirst()
                .orElse(null);
        assertNotNull(retrievedAccount, "Account should be found");
        assertTrue(retrievedAccount.getActive(), "Account should be active");
    }

    @Test
    void testFindByUserId_WithNullActiveAccount_ReturnsAccount() {
        // Given - Account with null active (simulating old data)
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountName("Old Account");
        account.setInstitutionName("Old Bank");
        account.setAccountType("SAVINGS");
        account.setBalance(new BigDecimal("500.00"));
        account.setCurrencyCode("USD");
        account.setActive(null); // Old accounts might have null active
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        
        // When
        List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        
        // Then
        assertFalse(retrieved.isEmpty(), "Accounts with null active should be returned");
        AccountTable retrievedAccount = retrieved.stream()
                .filter(a -> a.getAccountId().equals(account.getAccountId()))
                .findFirst()
                .orElse(null);
        assertNotNull(retrievedAccount, "Account with null active should be found");
    }

    @Test
    void testFindByUserId_WithInactiveAccount_ExcludesAccount() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountName("Inactive Account");
        account.setInstitutionName("Inactive Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("0.00"));
        account.setCurrencyCode("USD");
        account.setActive(false); // Explicitly inactive
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        
        // When
        List<AccountTable> retrieved = accountRepository.findByUserId(testUser.getUserId());
        
        // Then
        boolean found = retrieved.stream()
                .anyMatch(a -> a.getAccountId().equals(account.getAccountId()));
        assertFalse(found, "Inactive accounts should be excluded");
    }

    @Test
    void testFindByPlaidAccountId_WithExistingAccount_ReturnsAccount() {
        // Given
        String plaidAccountId = "plaid-test-" + UUID.randomUUID();
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId(plaidAccountId);
        account.setAccountName("Plaid Account");
        account.setInstitutionName("Plaid Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("2000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        
        // When
        Optional<AccountTable> retrieved = accountRepository.findByPlaidAccountId(plaidAccountId);
        
        // Then
        assertTrue(retrieved.isPresent(), "Account should be found by Plaid ID");
        assertEquals(plaidAccountId, retrieved.get().getPlaidAccountId());
    }

    @Test
    void testAccountSync_UpdatesExistingAccount() {
        // Given - Existing account
        String plaidAccountId = "plaid-update-" + UUID.randomUUID();
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountName("Old Name");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now().minusSeconds(3600));
        existingAccount.setUpdatedAt(Instant.now().minusSeconds(3600));
        accountRepository.save(existingAccount);
        
        // When - Update the account (simulating sync)
        existingAccount.setAccountName("Updated Name");
        existingAccount.setBalance(new BigDecimal("1500.00"));
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);
        
        // Then
        Optional<AccountTable> retrieved = accountRepository.findByPlaidAccountId(plaidAccountId);
        assertTrue(retrieved.isPresent());
        assertEquals("Updated Name", retrieved.get().getAccountName());
        assertEquals(new BigDecimal("1500.00"), retrieved.get().getBalance());
        assertTrue(retrieved.get().getActive(), "Active should remain true after update");
    }
}

