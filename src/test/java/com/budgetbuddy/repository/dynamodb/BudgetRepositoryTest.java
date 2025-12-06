package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BudgetRepository
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetRepositoryTest {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private String testUserId;
    private BudgetTable testBudget;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        
        testBudget = new BudgetTable();
        testBudget.setBudgetId(UUID.randomUUID().toString());
        testBudget.setUserId(testUserId);
        testBudget.setCategory("FOOD_AND_DRINK");
        testBudget.setMonthlyLimit(new BigDecimal("500.00"));
        testBudget.setCurrentSpent(BigDecimal.ZERO);
        testBudget.setCurrencyCode("USD");
        testBudget.setCreatedAt(Instant.now());
        testBudget.setUpdatedAt(Instant.now());
    }

    @Test
    void testSave_WithValidBudget_Succeeds() {
        // When
        budgetRepository.save(testBudget);

        // Then
        Optional<BudgetTable> found = budgetRepository.findById(testBudget.getBudgetId());
        assertTrue(found.isPresent(), "Budget should be saved");
        assertEquals(testBudget.getCategory(), found.get().getCategory());
    }

    @Test
    void testFindById_WithExistingBudget_ReturnsBudget() {
        // Given
        budgetRepository.save(testBudget);

        // When
        Optional<BudgetTable> found = budgetRepository.findById(testBudget.getBudgetId());

        // Then
        assertTrue(found.isPresent(), "Budget should be found");
        assertEquals(testBudget.getBudgetId(), found.get().getBudgetId());
    }

    @Test
    void testFindById_WithNonExistentBudget_ReturnsEmpty() {
        // When
        Optional<BudgetTable> found = budgetRepository.findById(UUID.randomUUID().toString());

        // Then
        assertTrue(found.isEmpty(), "Should return empty for non-existent budget");
    }

    @Test
    void testFindByUserId_ReturnsUserBudgets() {
        // Given
        budgetRepository.save(testBudget);
        
        BudgetTable budget2 = new BudgetTable();
        budget2.setBudgetId(UUID.randomUUID().toString());
        budget2.setUserId(testUserId);
        budget2.setCategory("TRANSPORTATION");
        budget2.setMonthlyLimit(new BigDecimal("200.00"));
        budget2.setCurrentSpent(BigDecimal.ZERO);
        budget2.setCurrencyCode("USD");
        budget2.setCreatedAt(Instant.now());
        budget2.setUpdatedAt(Instant.now());
        budgetRepository.save(budget2);

        // When
        List<BudgetTable> budgets = budgetRepository.findByUserId(testUserId);

        // Then
        assertNotNull(budgets, "Budgets should not be null");
        assertTrue(budgets.size() >= 2, "Should return at least 2 budgets");
    }

    @Test
    void testFindByUserIdAndCategory_ReturnsMatchingBudget() {
        // Given
        budgetRepository.save(testBudget);

        // When
        Optional<BudgetTable> found = budgetRepository.findByUserIdAndCategory(testUserId, "FOOD_AND_DRINK");

        // Then
        assertTrue(found.isPresent(), "Budget should be found");
        assertEquals("FOOD_AND_DRINK", found.get().getCategory());
    }

    @Test
    void testDelete_WithValidId_RemovesBudget() {
        // Given
        budgetRepository.save(testBudget);
        String budgetId = testBudget.getBudgetId();

        // When
        budgetRepository.delete(budgetId);

        // Then
        Optional<BudgetTable> found = budgetRepository.findById(budgetId);
        assertTrue(found.isEmpty(), "Budget should be deleted");
    }
}

