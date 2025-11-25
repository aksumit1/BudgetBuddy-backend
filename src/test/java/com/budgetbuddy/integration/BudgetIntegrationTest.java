package com.budgetbuddy.integration;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.TransactionService;
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
 * Integration Tests for Budget Service
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class BudgetIntegrationTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionService transactionService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);
    }

    @Test
    void testCreateAndRetrieveBudget() {
        // Given
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                "FOOD",
                BigDecimal.valueOf(1000.00)
        );

        // When
        List<BudgetTable> budgets = budgetService.getBudgets(testUser);

        // Then
        assertNotNull(budgets);
        assertTrue(budgets.stream().anyMatch(b -> b.getBudgetId().equals(budget.getBudgetId())));
    }

    @Test
    void testUpdateBudget_UpdatesMonthlyLimit() {
        // Given
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                "FOOD",
                BigDecimal.valueOf(1000.00)
        );

        // When
        BudgetTable updated = budgetService.createOrUpdateBudget(
                testUser,
                "FOOD",
                BigDecimal.valueOf(1500.00)
        );

        // Then
        assertEquals(BigDecimal.valueOf(1500.00), updated.getMonthlyLimit());
        assertEquals(budget.getBudgetId(), updated.getBudgetId());
    }
}

