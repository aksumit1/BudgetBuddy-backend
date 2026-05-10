package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for BudgetService */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class BudgetServiceTest {

    @Mock private BudgetRepository budgetRepository;

    @Mock private TransactionService transactionService;

    @InjectMocks private BudgetService budgetService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPreferredCurrency("USD");
    }

    @Test
    void testCreateOrUpdateBudgetWithNewBudgetCreatesBudget() {
        // Given
        when(budgetRepository.findByUserIdAndCategory("user-123", "FOOD"))
                .thenReturn(Optional.empty());
        when(budgetRepository.findByUserId(anyString())).thenReturn(Collections.emptyList());
        doNothing().when(budgetRepository).save(any(BudgetTable.class));
        when(transactionService.getTransactionsByCategory(any(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        // When
        final BudgetTable result =
                budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1000.00));

        // Then
        assertNotNull(result);
        assertEquals("FOOD", result.getCategory());
        assertEquals(BigDecimal.valueOf(1000.00), result.getMonthlyLimit());
        verify(budgetRepository).saveWithLock(any(BudgetTable.class));
    }

    @Test
    void testCreateOrUpdateBudgetWithExistingBudgetUpdatesBudget() {
        // Given
        final BudgetTable existingBudget = new BudgetTable();
        existingBudget.setBudgetId("budget-123");
        existingBudget.setUserId("user-123");
        existingBudget.setCategory("FOOD");
        existingBudget.setMonthlyLimit(BigDecimal.valueOf(500.00));

        when(budgetRepository.findByUserIdAndCategory("user-123", "FOOD"))
                .thenReturn(Optional.of(existingBudget));
        when(transactionService.getTransactionsByCategory(any(), anyString(), any()))
                .thenReturn(Collections.emptyList());
        doNothing().when(budgetRepository).save(any(BudgetTable.class));

        // When
        final BudgetTable result =
                budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1000.00));

        // Then
        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(1000.00), result.getMonthlyLimit());
        verify(budgetRepository).saveWithLock(any(BudgetTable.class));
    }

    @Test
    void testCreateOrUpdateBudgetWithNullUserThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () ->
                                budgetService.createOrUpdateBudget(
                                        null, "FOOD", BigDecimal.valueOf(1000.00)));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithInvalidLimitThrowsException() {
        // When/Then - Zero limit is allowed (zero-based budgeting support)
        // Zero limit should NOT throw exception
        when(budgetRepository.findByUserIdAndCategory("user-123", "FOOD"))
                .thenReturn(Optional.empty());
        when(budgetRepository.findByUserId(anyString())).thenReturn(Collections.emptyList());
        doNothing().when(budgetRepository).save(any(BudgetTable.class));
        when(transactionService.getTransactionsByCategory(any(), anyString(), any()))
                .thenReturn(Collections.emptyList());

        final BudgetTable result = budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.ZERO);
        assertNotNull(result);
        assertEquals(BigDecimal.ZERO, result.getMonthlyLimit());

        // When/Then - Negative limit should throw exception
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () ->
                                budgetService.createOrUpdateBudget(
                                        testUser, "FOOD", BigDecimal.valueOf(-100)));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetBudgetsWithValidUserReturnsBudgets() {
        // Given
        final List<BudgetTable> mockBudgets =
                Arrays.asList(
                        createBudget("budget-1", "FOOD"),
                        createBudget("budget-2", "TRANSPORTATION"));
        when(budgetRepository.findByUserId("user-123")).thenReturn(mockBudgets);

        // When
        final List<BudgetTable> result = budgetService.getBudgets(testUser);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void testGetBudgetWithUnauthorizedUserThrowsException() {
        // Given
        final BudgetTable budget = createBudget("budget-1", "FOOD");
        budget.setUserId("other-user");
        when(budgetRepository.findById("budget-1")).thenReturn(Optional.of(budget));

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class, () -> budgetService.getBudget(testUser, "budget-1"));
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testDeleteBudgetWithValidBudgetDeletesBudget() {
        // Given
        final BudgetTable budget = createBudget("budget-1", "FOOD");
        when(budgetRepository.findById("budget-1")).thenReturn(Optional.of(budget));
        doNothing().when(budgetRepository).delete("budget-1");

        // When
        budgetService.deleteBudget(testUser, "budget-1");

        // Then
        verify(budgetRepository).delete("budget-1");
    }

    // Helper methods
    private BudgetTable createBudget(final String id, final String category) {
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId(id);
        budget.setUserId("user-123");
        budget.setCategory(category);
        budget.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        budget.setCurrentSpent(BigDecimal.ZERO);
        return budget;
    }
}
