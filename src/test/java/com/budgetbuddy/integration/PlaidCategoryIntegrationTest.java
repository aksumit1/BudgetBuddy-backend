package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.budgetbuddy.service.TransactionService;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for Plaid category sync end-to-end Tests the full flow from Plaid sync to
 * category mapping to override
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PlaidCategoryIntegrationTest {

    private static final String DINING = "dining";

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionRepository transactionRepository;

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
    void testFullCategoryFlowFromPlaidToOverride() {
        // Given - Transaction synced from Plaid with categories
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("test-txn-" + System.currentTimeMillis());
        transaction.setUserId(testUser.getUserId());
        transaction.setAccountId(testAccount.getAccountId());
        transaction.setAmount(java.math.BigDecimal.valueOf(25.50));
        transaction.setDescription("McDonald's");
        transaction.setMerchantName("McDonald's");
        transaction.setImporterCategoryPrimary("FOOD_AND_DRINK");
        transaction.setImporterCategoryDetailed("RESTAURANTS");
        transaction.setCategoryPrimary(DINING);
        transaction.setCategoryDetailed(DINING);
        transaction.setCategoryOverridden(false);
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setPlaidTransactionId("plaid-txn-test");

        // When - Save transaction
        transactionRepository.save(transaction);

        // Then - Verify categories are stored
        final Optional<TransactionTable> saved =
                transactionRepository.findById(transaction.getTransactionId());
        assertTrue(saved.isPresent());
        assertEquals("FOOD_AND_DRINK", saved.get().getImporterCategoryPrimary());
        assertEquals("RESTAURANTS", saved.get().getImporterCategoryDetailed());
        assertEquals(DINING, saved.get().getCategoryPrimary());
        assertEquals(DINING, saved.get().getCategoryDetailed());
        assertFalse(saved.get().getCategoryOverridden());

        // When - User overrides category
        final TransactionTable updated =
                transactionService.updateTransaction(
                        testUser,
                        transaction.getTransactionId(),
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        "groceries", // categoryPrimary
                        "groceries", // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        null, // transactionType
                        false, // clearNotesIfNull = false means preserve existing notes
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then - Verify override is applied
        assertNotNull(updated);
        assertEquals("groceries", updated.getCategoryPrimary());
        assertEquals("groceries", updated.getCategoryDetailed());
        assertTrue(updated.getCategoryOverridden());
        // Original Plaid categories should be preserved
        assertEquals("FOOD_AND_DRINK", updated.getImporterCategoryPrimary());
        assertEquals("RESTAURANTS", updated.getImporterCategoryDetailed());
    }

    @Test
    void testCategoryMapperAllPlaidCategories() {
        // Test all major Plaid category mappings
        final PlaidCategoryMapper mapper = new PlaidCategoryMapper();

        // Food and Drink
        final PlaidCategoryMapper.CategoryMapping food =
                mapper.mapPlaidCategory("FOOD_AND_DRINK", "RESTAURANTS", "McDonald's", "Fast food");
        assertEquals(DINING, food.getPrimary());
        assertEquals(DINING, food.getDetailed());

        // Groceries
        final PlaidCategoryMapper.CategoryMapping groceries =
                mapper.mapPlaidCategory(
                        "FOOD_AND_DRINK", "GROCERIES", "Walmart", "Grocery shopping");
        assertEquals("groceries", groceries.getPrimary());
        assertEquals("groceries", groceries.getDetailed());

        // Transportation
        final PlaidCategoryMapper.CategoryMapping transport =
                mapper.mapPlaidCategory("TRANSPORTATION", "GAS_STATIONS", "Shell", "Gas");
        assertEquals("transportation", transport.getPrimary());
        assertEquals("transportation", transport.getDetailed());

        // Income
        final PlaidCategoryMapper.CategoryMapping income =
                mapper.mapPlaidCategory("INCOME", "SALARY", "Employer", "Salary");
        assertEquals("income", income.getPrimary());
        // Description contains "Salary", so should be categorized as specific income type "salary"
        assertEquals(
                "salary",
                income.getDetailed(),
                "Income with salary description should be categorized as salary");

        // Subscriptions
        final PlaidCategoryMapper.CategoryMapping subscription =
                mapper.mapPlaidCategory(
                        "GENERAL_SERVICES", "SOFTWARE_SUBSCRIPTIONS", "Netflix", "Subscription");
        assertEquals("subscriptions", subscription.getPrimary());
        assertEquals("subscriptions", subscription.getDetailed());
    }
}
