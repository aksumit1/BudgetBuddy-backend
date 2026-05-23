package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.BudgetService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Integration Tests for Budget Service */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class BudgetIntegrationTest {

    @Autowired private BudgetService budgetService;

    @Autowired private UserRepository userRepository;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        final String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);
    }

    @Test
    void testCreateAndRetrieveBudget() {
        // Given
        final BudgetTable budget =
                budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1000.00),null, null, null, null, null, null, null);

        // When
        final List<BudgetTable> budgets = budgetService.getBudgets(testUser);

        // Then
        assertNotNull(budgets);
        assertTrue(budgets.stream().anyMatch(b -> b.getBudgetId().equals(budget.getBudgetId())));
    }

    @Test
    void testUpdateBudgetUpdatesMonthlyLimit() {
        // Given
        final BudgetTable budget =
                budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1000.00),null, null, null, null, null, null, null);

        // When
        final BudgetTable updated =
                budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1500.00),null, null, null, null, null, null, null);

        // Then
        assertEquals(BigDecimal.valueOf(1500.00), updated.getMonthlyLimit());
        assertEquals(budget.getBudgetId(), updated.getBudgetId());
    }
}
