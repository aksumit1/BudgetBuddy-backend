package com.budgetbuddy.integration;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Account Operations
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class AccountIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        userRepository.save(testUser);
    }

    @Test
    void testCreateAndRetrieveAccount() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        account.setBalance(BigDecimal.valueOf(1000.00));
        account.setActive(true);
        accountRepository.save(account);

        // When
        List<AccountTable> accounts = accountRepository.findByUserId(testUser.getUserId());

        // Then
        assertNotNull(accounts);
        assertTrue(accounts.stream().anyMatch(a -> a.getAccountId().equals(account.getAccountId())));
    }

    @Test
    void testFindAccountById() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        accountRepository.save(account);

        // When
        java.util.Optional<AccountTable> found = accountRepository.findById(account.getAccountId());

        // Then
        assertTrue(found.isPresent());
        assertEquals(account.getAccountId(), found.get().getAccountId());
    }
}

