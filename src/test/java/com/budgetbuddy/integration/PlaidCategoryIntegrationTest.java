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

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Plaid category sync end-to-end
 * Tests the full flow from Plaid sync to category mapping to override
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PlaidCategoryIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

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
    }

    @Test
    void testFullCategoryFlow_FromPlaidToOverride() {
        // Given - Transaction synced from Plaid with categories
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(java.math.BigDecimal.valueOf(25.50));
        transaction.setDescription("McDonald's");
        transaction.setMerchantName("McDonald's");
        transaction.setPlaidCategoryPrimary("FOOD_AND_DRINK");
        transaction.setPlaidCategoryDetailed("RESTAURANTS");
        transaction.setCategoryPrimary("dining");
        transaction.setCategoryDetailed("dining");
        transaction.setCategoryOverridden(false);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-test");

        // When - Save transaction
        transactionRepository.save(transaction);

        // Then - Verify categories are stored
        Optional<TransactionTable> saved = transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("FOOD_AND_DRINK", saved.get().getPlaidCategoryPrimary());
        assertEquals("RESTAURANTS", saved.get().getPlaidCategoryDetailed());
        assertEquals("dining", saved.get().getCategoryPrimary());
        assertEquals("dining", saved.get().getCategoryDetailed());
        assertFalse(saved.get().getCategoryOverridden());

        // When - User overrides category
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transaction.getTransactionId(),
                null, // plaidTransactionId
                null, // amount
                null, // notes
                "groceries", // categoryPrimary
                "groceries", // categoryDetailed
                null, // isAudited
                null  // isHidden
        );

        // Then - Verify override is applied
        assertNotNull(updated);
        assertEquals("groceries", updated.getCategoryPrimary());
        assertEquals("groceries", updated.getCategoryDetailed());
        assertTrue(updated.getCategoryOverridden());
        // Original Plaid categories should be preserved
        assertEquals("FOOD_AND_DRINK", updated.getPlaidCategoryPrimary());
        assertEquals("RESTAURANTS", updated.getPlaidCategoryDetailed());
    }

    @Test
    void testCategoryMapper_AllPlaidCategories() {
        // Test all major Plaid category mappings
        PlaidCategoryMapper mapper = new PlaidCategoryMapper();

        // Food and Drink
        PlaidCategoryMapper.CategoryMapping food = mapper.mapPlaidCategory(
                "FOOD_AND_DRINK", "RESTAURANTS", "McDonald's", "Fast food");
        assertEquals("dining", food.getPrimary());
        assertEquals("dining", food.getDetailed());

        // Groceries
        PlaidCategoryMapper.CategoryMapping groceries = mapper.mapPlaidCategory(
                "FOOD_AND_DRINK", "GROCERIES", "Walmart", "Grocery shopping");
        assertEquals("groceries", groceries.getPrimary());
        assertEquals("groceries", groceries.getDetailed());

        // Transportation
        PlaidCategoryMapper.CategoryMapping transport = mapper.mapPlaidCategory(
                "TRANSPORTATION", "GAS_STATIONS", "Shell", "Gas");
        assertEquals("transportation", transport.getPrimary());
        assertEquals("transportation", transport.getDetailed());

        // Income
        PlaidCategoryMapper.CategoryMapping income = mapper.mapPlaidCategory(
                "INCOME", "SALARY", "Employer", "Salary");
        assertEquals("income", income.getPrimary());
        assertEquals("income", income.getDetailed());

        // Subscriptions
        PlaidCategoryMapper.CategoryMapping subscription = mapper.mapPlaidCategory(
                "GENERAL_SERVICES", "SOFTWARE_SUBSCRIPTIONS", "Netflix", "Subscription");
        assertEquals("subscriptions", subscription.getPrimary());
        assertEquals("subscriptions", subscription.getDetailed());
    }
}

