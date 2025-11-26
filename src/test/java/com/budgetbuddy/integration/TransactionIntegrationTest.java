package com.budgetbuddy.integration;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Transaction Service
 * Tests with real DynamoDB (LocalStack)
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Create test user
        String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        accountRepository.save(testAccount);
    }

    @Test
    void testCreateAndRetrieveTransaction() {
        // Given
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test transaction",
                "FOOD"
        );

        // When
        List<TransactionTable> transactions = transactionService.getTransactions(testUser, 0, 10);

        // Then
        assertNotNull(transactions);
        assertTrue(transactions.size() > 0);
        assertTrue(transactions.stream().anyMatch(t -> t.getTransactionId().equals(transaction.getTransactionId())));
    }

    @Test
    void testGetTransactionsInRange() {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();

        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now().minusDays(3),
                "Recent transaction",
                "FOOD"
        );

        // When
        List<TransactionTable> transactions = transactionService.getTransactionsInRange(testUser, startDate, endDate);

        // Then
        assertNotNull(transactions);
        assertTrue(transactions.size() > 0);
    }

    @Test
    void testGetTotalSpending() {
        // Given
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Transaction 1",
                "FOOD"
        );
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(50.00),
                LocalDate.now(),
                "Transaction 2",
                "FOOD"
        );

        // When
        BigDecimal total = transactionService.getTotalSpending(
                testUser,
                LocalDate.now().minusDays(1),
                LocalDate.now()
        );

        // Then
        assertEquals(BigDecimal.valueOf(150.00), total);
    }
}

