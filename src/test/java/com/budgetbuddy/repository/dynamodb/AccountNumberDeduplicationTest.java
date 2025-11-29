package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for account number-based deduplication
 * Verifies that accounts are deduplicated using account number + institution name
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountNumberDeduplicationTest {

    @Autowired
    private AccountRepository accountRepository;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
    }

    @Test
    void testFindByAccountNumberAndInstitution_WithExistingAccount_ReturnsAccount() {
        // Given - Account with account number
        String accountNumber = "1234";
        String institutionName = "Test Bank";
        
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUserId);
        account.setAccountName("Checking Account");
        account.setInstitutionName(institutionName);
        account.setAccountNumber(accountNumber);
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // When - Find by account number and institution
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                accountNumber, institutionName, testUserId);

        // Then - Should find the account
        assertTrue(found.isPresent(), "Account should be found by account number and institution");
        assertEquals(accountNumber, found.get().getAccountNumber());
        assertEquals(institutionName, found.get().getInstitutionName());
        assertEquals(account.getAccountId(), found.get().getAccountId());
    }

    @Test
    void testFindByAccountNumberAndInstitution_WithDifferentInstitution_ReturnsEmpty() {
        // Given - Account with account number and institution
        String accountNumber = "1234";
        String institutionName = "Test Bank";
        
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUserId);
        account.setAccountName("Checking Account");
        account.setInstitutionName(institutionName);
        account.setAccountNumber(accountNumber);
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // When - Find by same account number but different institution
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                accountNumber, "Different Bank", testUserId);

        // Then - Should not find the account
        assertFalse(found.isPresent(), "Account should not be found with different institution");
    }

    @Test
    void testFindByAccountNumberAndInstitution_WithNullAccountNumber_ReturnsEmpty() {
        // When - Find with null account number
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                null, "Test Bank", testUserId);

        // Then - Should return empty
        assertFalse(found.isPresent(), "Should return empty for null account number");
    }

    @Test
    void testFindByAccountNumberAndInstitution_WithEmptyAccountNumber_ReturnsEmpty() {
        // When - Find with empty account number
        Optional<AccountTable> found = accountRepository.findByAccountNumberAndInstitution(
                "", "Test Bank", testUserId);

        // Then - Should return empty
        assertFalse(found.isPresent(), "Should return empty for empty account number");
    }

    @Test
    void testAccountNumber_IsStoredAndRetrieved() {
        // Given - Account with account number
        String accountNumber = "5678";
        
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUserId);
        account.setAccountName("Savings Account");
        account.setInstitutionName("Test Bank");
        account.setAccountNumber(accountNumber);
        account.setAccountType("SAVINGS");
        account.setBalance(new BigDecimal("5000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        // When - Retrieve the account
        Optional<AccountTable> retrieved = accountRepository.findById(account.getAccountId());

        // Then - Account number should be stored and retrieved
        assertTrue(retrieved.isPresent(), "Account should be found");
        assertEquals(accountNumber, retrieved.get().getAccountNumber(), 
                "Account number should be stored and retrieved correctly");
    }
}

