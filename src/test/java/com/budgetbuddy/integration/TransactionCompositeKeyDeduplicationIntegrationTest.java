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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Transaction Deduplication with Composite Key
 * 
 * Tests the critical fix where transactions are deduplicated by composite key
 * (accountId + amount + date + description/merchantName) when plaidTransactionId
 * changes due to reconnection/relinking.
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionCompositeKeyDeduplicationIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserService userService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Create test user with unique email to avoid duplicates
        String uniqueEmail = "test-composite-key-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("test-hash".getBytes());
        String base64ClientSalt = java.util.Base64.getEncoder().encodeToString("test-salt".getBytes());
        testUser = userService.createUserSecure(
                uniqueEmail,
                base64PasswordHash,
                base64ClientSalt,
                "Test",
                "User"
        );

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setPlaidAccountId("plaid-account-" + UUID.randomUUID());
        testAccount.setAccountName("Test Checking");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);
    }

    @Test
    void testFindByCompositeKey_WithMatchingTransaction_ReturnsTransaction() {
        // Given - Existing transaction
        String transactionDate = LocalDate.now().toString();
        BigDecimal amount = new BigDecimal("100.00");
        String description = "Test Transaction";
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId("plaid-old-" + UUID.randomUUID()); // Old Plaid ID
        existingTransaction.setAmount(amount);
        existingTransaction.setDescription(description);
        existingTransaction.setMerchantName("Test Merchant");
        existingTransaction.setCategoryPrimary("dining");
        existingTransaction.setCategoryDetailed("dining");
        existingTransaction.setTransactionDate(transactionDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to find by composite key
        Optional<TransactionTable> found = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                amount,
                transactionDate,
                description,
                testUser.getUserId());

        // Then - Should find existing transaction
        assertTrue(found.isPresent(), "Should find transaction by composite key");
        assertEquals(existingTransaction.getTransactionId(), found.get().getTransactionId(),
                "Should return existing transaction");
        assertEquals(amount, found.get().getAmount());
        assertEquals(description, found.get().getDescription());
    }

    @Test
    void testFindByCompositeKey_WithDifferentPlaidId_ReturnsTransaction() {
        // Given - Existing transaction with old Plaid ID
        String transactionDate = LocalDate.now().toString();
        BigDecimal amount = new BigDecimal("200.00");
        String description = "Coffee Purchase";
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId("plaid-old-" + UUID.randomUUID()); // Old Plaid ID
        existingTransaction.setAmount(amount);
        existingTransaction.setDescription(description);
        existingTransaction.setMerchantName("Starbucks");
        existingTransaction.setCategoryPrimary("dining");
        existingTransaction.setCategoryDetailed("dining");
        existingTransaction.setTransactionDate(transactionDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - New transaction comes in with:
        // - Different Plaid ID (reconnection/relinking)
        // - Same composite key (accountId + amount + date + description)
        String newPlaidId = "plaid-new-" + UUID.randomUUID(); // New Plaid ID
        
        Optional<TransactionTable> found = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                amount,
                transactionDate,
                description,
                testUser.getUserId());

        // Then - Should find existing transaction even though Plaid ID is different
        assertTrue(found.isPresent(), "Should find transaction by composite key even when Plaid ID changed");
        assertEquals(existingTransaction.getTransactionId(), found.get().getTransactionId());
        assertNotEquals(newPlaidId, found.get().getPlaidTransactionId(), 
                "Existing transaction should have old Plaid ID");
    }

    @Test
    void testFindByCompositeKey_WithMerchantName_ReturnsTransaction() {
        // Given - Existing transaction with merchantName but no description
        String transactionDate = LocalDate.now().toString();
        BigDecimal amount = new BigDecimal("50.00");
        String merchantName = "Target";
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId("plaid-old-" + UUID.randomUUID());
        existingTransaction.setAmount(amount);
        existingTransaction.setDescription(""); // Empty description
        existingTransaction.setMerchantName(merchantName); // Use merchantName
        existingTransaction.setCategoryPrimary("shopping");
        existingTransaction.setCategoryDetailed("shopping");
        existingTransaction.setTransactionDate(transactionDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Find by composite key using merchantName
        Optional<TransactionTable> found = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                amount,
                transactionDate,
                merchantName, // Use merchantName as match key
                testUser.getUserId());

        // Then - Should find existing transaction
        assertTrue(found.isPresent(), "Should find transaction by composite key using merchantName");
        assertEquals(existingTransaction.getTransactionId(), found.get().getTransactionId());
    }

    @Test
    void testFindByCompositeKey_WithDifferentAmount_ReturnsEmpty() {
        // Given - Existing transaction
        String transactionDate = LocalDate.now().toString();
        BigDecimal existingAmount = new BigDecimal("100.00");
        String description = "Test Transaction";
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId("plaid-old-" + UUID.randomUUID());
        existingTransaction.setAmount(existingAmount);
        existingTransaction.setDescription(description);
        existingTransaction.setCategoryPrimary("dining");
        existingTransaction.setCategoryDetailed("dining");
        existingTransaction.setTransactionDate(transactionDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to find with different amount
        BigDecimal differentAmount = new BigDecimal("200.00");
        Optional<TransactionTable> found = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                differentAmount, // Different amount
                transactionDate,
                description,
                testUser.getUserId());

        // Then - Should not find transaction (amount doesn't match)
        assertFalse(found.isPresent(), "Should not find transaction with different amount");
    }

    @Test
    void testFindByCompositeKey_WithDifferentDate_ReturnsEmpty() {
        // Given - Existing transaction
        String existingDate = LocalDate.now().toString();
        BigDecimal amount = new BigDecimal("100.00");
        String description = "Test Transaction";
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId("plaid-old-" + UUID.randomUUID());
        existingTransaction.setAmount(amount);
        existingTransaction.setDescription(description);
        existingTransaction.setCategoryPrimary("dining");
        existingTransaction.setCategoryDetailed("dining");
        existingTransaction.setTransactionDate(existingDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - Try to find with different date
        String differentDate = LocalDate.now().minusDays(1).toString(); // Yesterday
        Optional<TransactionTable> found = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                amount,
                differentDate, // Different date
                description,
                testUser.getUserId());

        // Then - Should not find transaction (date doesn't match)
        assertFalse(found.isPresent(), "Should not find transaction with different date");
    }

    @Test
    void testTransactionDeduplication_ReconnectionScenario_PreventsDuplicate() {
        // Given - Existing transaction from first sync
        String transactionDate = LocalDate.now().toString();
        BigDecimal amount = new BigDecimal("75.00");
        String description = "Gas Station Purchase";
        String oldPlaidId = "plaid-old-" + UUID.randomUUID();
        
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUser.getUserId());
        existingTransaction.setAccountId(testAccount.getAccountId());
        existingTransaction.setPlaidTransactionId(oldPlaidId);
        existingTransaction.setAmount(amount);
        existingTransaction.setDescription(description);
        existingTransaction.setMerchantName("Shell");
        existingTransaction.setCategoryPrimary("transportation");
        existingTransaction.setCategoryDetailed("transportation");
        existingTransaction.setTransactionDate(transactionDate);
        existingTransaction.setCurrencyCode("USD");
        existingTransaction.setCreatedAt(Instant.now());
        existingTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(existingTransaction);

        // When - User reconnects account, new transaction comes in with:
        // - New Plaid ID (different from existing)
        // - Same composite key (accountId + amount + date + description)
        String newPlaidId = "plaid-new-" + UUID.randomUUID();
        
        // Simulate PlaidSyncService deduplication logic:
        // 1. Check by plaidTransactionId (will fail - new ID)
        Optional<TransactionTable> byPlaidId = transactionRepository.findByPlaidTransactionId(newPlaidId);
        assertFalse(byPlaidId.isPresent(), "Should not find by new Plaid ID");

        // 2. Check by composite key (should succeed)
        Optional<TransactionTable> byCompositeKey = transactionRepository.findByCompositeKey(
                testAccount.getAccountId(),
                amount,
                transactionDate,
                description,
                testUser.getUserId());
        assertTrue(byCompositeKey.isPresent(), "Should find by composite key even when Plaid ID changed");
        assertEquals(existingTransaction.getTransactionId(), byCompositeKey.get().getTransactionId());

        // Update existing transaction with new Plaid ID
        TransactionTable transaction = byCompositeKey.get();
        transaction.setPlaidTransactionId(newPlaidId);
        transactionRepository.save(transaction);

        // Then - Should only have one transaction
        List<TransactionTable> allTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        long countWithCompositeKey = allTransactions.stream()
                .filter(t -> testAccount.getAccountId().equals(t.getAccountId()))
                .filter(t -> amount.compareTo(t.getAmount()) == 0)
                .filter(t -> transactionDate.equals(t.getTransactionDate()))
                .filter(t -> description.equals(t.getDescription()) || description.equals(t.getMerchantName()))
                .count();
        assertEquals(1, countWithCompositeKey, "Should only have one transaction after deduplication");
        
        Optional<TransactionTable> finalTransaction = transactionRepository.findByPlaidTransactionId(newPlaidId);
        assertTrue(finalTransaction.isPresent(), "Transaction should have new Plaid ID");
        assertEquals(amount, finalTransaction.get().getAmount(), "Amount should be preserved");
    }
}

