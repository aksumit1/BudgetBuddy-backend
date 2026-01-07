package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.budgetbuddy.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for investment subcategory categorization
 * Tests the full flow from Plaid sync to specific investment type categorization
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class InvestmentSubcategoryIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlaidCategoryMapper categoryMapper;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Setup test user and account
        testUser = new UserTable();
        testUser.setUserId("test-user-" + System.currentTimeMillis());
        testUser.setEmail("test@example.com");

        testAccount = new AccountTable();
        testAccount.setAccountId("test-account-" + System.currentTimeMillis());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setPlaidAccountId("plaid-account-test");
        testAccount.setActive(true);
        accountRepository.save(testAccount);
    }

    @Test
    void testCDDeposit_EndToEnd_CategorizedAsCD() {
        // Given - CD deposit transaction from Plaid
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-cd-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(10000.00));
        transaction.setDescription("CD Deposit");
        transaction.setMerchantName("Bank");
        transaction.setImporterCategoryPrimary("ENTERTAINMENT"); // Incorrectly categorized by Plaid
        transaction.setImporterCategoryDetailed("ENTERTAINMENT");
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-cd-test");

        // When - Map category using PlaidCategoryMapper
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            transaction.getImporterCategoryPrimary(),
            transaction.getImporterCategoryDetailed(),
            transaction.getMerchantName(),
            transaction.getDescription(),
            null, // paymentChannel
            transaction.getAmount()
        );

        // Then - Should be categorized as CD, not entertainment
        assertEquals("investment", mapping.getPrimary());
        assertEquals("cd", mapping.getDetailed(), "CD deposit should be categorized as 'cd' subcategory");

        // When - Save transaction with mapped category
        transaction.setCategoryPrimary(mapping.getPrimary());
        transaction.setCategoryDetailed(mapping.getDetailed());
        transaction.setCategoryOverridden(false);
        transactionRepository.save(transaction);

        // Then - Verify transaction is saved with correct category
        Optional<TransactionTable> saved = transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("investment", saved.get().getCategoryPrimary());
        assertEquals("cd", saved.get().getCategoryDetailed());
    }

    @Test
    void testStockPurchase_EndToEnd_CategorizedAsStocks() {
        // Given - Stock purchase transaction
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-stock-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(-1000.00));
        transaction.setDescription("Stock Purchase - AAPL");
        transaction.setMerchantName("Brokerage");
        transaction.setImporterCategoryPrimary(null);
        transaction.setImporterCategoryDetailed(null);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-stock-test");

        // When - Map category
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            transaction.getImporterCategoryPrimary(),
            transaction.getImporterCategoryDetailed(),
            transaction.getMerchantName(),
            transaction.getDescription(),
            null,
            transaction.getAmount()
        );

        // Then - Should be categorized as stocks
        assertEquals("investment", mapping.getPrimary());
        assertEquals("stocks", mapping.getDetailed(), "Stock purchase should be categorized as 'stocks'");

        // When - Save transaction
        transaction.setCategoryPrimary(mapping.getPrimary());
        transaction.setCategoryDetailed(mapping.getDetailed());
        transaction.setCategoryOverridden(false);
        transactionRepository.save(transaction);

        // Then - Verify
        Optional<TransactionTable> saved = transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("investment", saved.get().getCategoryPrimary());
        assertEquals("stocks", saved.get().getCategoryDetailed());
    }

    @Test
    void test401kContribution_EndToEnd_CategorizedAsFourZeroOneK() {
        // Given - 401k contribution
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-401k-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(-500.00));
        transaction.setDescription("401k Contribution");
        transaction.setMerchantName("Retirement Plan");
        transaction.setImporterCategoryPrimary(null);
        transaction.setImporterCategoryDetailed(null);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-401k-test");

        // When - Map category
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            transaction.getImporterCategoryPrimary(),
            transaction.getImporterCategoryDetailed(),
            transaction.getMerchantName(),
            transaction.getDescription(),
            null,
            transaction.getAmount()
        );

        // Then - Should be categorized as fourZeroOneK
        assertEquals("investment", mapping.getPrimary());
        assertEquals("fourZeroOneK", mapping.getDetailed(), "401k should be categorized as 'fourZeroOneK'");

        // When - Save transaction
        transaction.setCategoryPrimary(mapping.getPrimary());
        transaction.setCategoryDetailed(mapping.getDetailed());
        transaction.setCategoryOverridden(false);
        transactionRepository.save(transaction);

        // Then - Verify
        Optional<TransactionTable> saved = transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("investment", saved.get().getCategoryPrimary());
        assertEquals("fourZeroOneK", saved.get().getCategoryDetailed());
    }

    @Test
    void testCryptoInvestment_EndToEnd_CategorizedAsCrypto() {
        // Given - Crypto investment
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-crypto-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(-2000.00));
        transaction.setDescription("Bitcoin Purchase");
        transaction.setMerchantName("Crypto Exchange");
        transaction.setImporterCategoryPrimary(null);
        transaction.setImporterCategoryDetailed(null);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-crypto-test");

        // When - Map category
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            transaction.getImporterCategoryPrimary(),
            transaction.getImporterCategoryDetailed(),
            transaction.getMerchantName(),
            transaction.getDescription(),
            null,
            transaction.getAmount()
        );

        // Then - Should be categorized as crypto
        assertEquals("investment", mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Bitcoin should be categorized as 'crypto'");

        // When - Save transaction
        transaction.setCategoryPrimary(mapping.getPrimary());
        transaction.setCategoryDetailed(mapping.getDetailed());
        transaction.setCategoryOverridden(false);
        transactionRepository.save(transaction);

        // Then - Verify
        Optional<TransactionTable> saved = transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("investment", saved.get().getCategoryPrimary());
        assertEquals("crypto", saved.get().getCategoryDetailed());
    }

    @Test
    void testInvestmentCategoryOverride_PreservesSubcategory() {
        // Given - CD deposit transaction
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-override-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(10000.00));
        transaction.setDescription("CD Deposit");
        transaction.setMerchantName("Bank");
        transaction.setImporterCategoryPrimary("ENTERTAINMENT");
        transaction.setImporterCategoryDetailed("ENTERTAINMENT");
        transaction.setCategoryPrimary("investment");
        transaction.setCategoryDetailed("cd");
        transaction.setCategoryOverridden(false);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-override-test");
        transactionRepository.save(transaction);

        // When - User overrides to different investment type (e.g., stocks)
        TransactionTable updated = transactionService.updateTransaction(
            testUser,
            transaction.getTransactionId(),
            null, // plaidTransactionId
            null, // amount
            null, // notes
            "investment", // categoryPrimary
            "stocks", // categoryDetailed
            null, // reviewStatus
            null, // isHidden
            null, // transactionType
            false, // clearNotesIfNull = false means preserve existing notes
            null  // goalId
        );

        // Then - Verify override is applied
        assertNotNull(updated);
        assertEquals("investment", updated.getCategoryPrimary());
        assertEquals("stocks", updated.getCategoryDetailed());
        assertTrue(updated.getCategoryOverridden());
        // Original Plaid categories should be preserved
        assertEquals("ENTERTAINMENT", updated.getImporterCategoryPrimary());
        assertEquals("ENTERTAINMENT", updated.getImporterCategoryDetailed());
    }
}

