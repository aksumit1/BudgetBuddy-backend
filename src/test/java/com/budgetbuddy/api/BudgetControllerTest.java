package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for BudgetController */
@ExtendWith(MockitoExtension.class)
class BudgetControllerTest {

    private static final String BUDGET_1 = "budget-1";

    @Mock private BudgetService budgetService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    // Mocks for collaborators added during the A+ polish. @InjectMocks
    // populates fields by type, so declaring them here prevents the
    // "this.idempotencyService is null" NPEs that appeared when the
    // controller's constructor signature grew.
    @Mock
    private com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService;

    @Mock private com.budgetbuddy.service.BudgetSummaryService budgetSummaryService;

    @Mock private com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor;

    @Mock private com.budgetbuddy.service.correctness.IdempotencyService idempotencyService;

    @InjectMocks private BudgetController budgetController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        // IdempotencyService.runOnce invokes the supplied work and returns
        // its result. In tests we just pass through — simulating the "fresh
        // key, no cache hit" code path so the create flow runs as before.
        // Using lenient() because several test methods don't exercise the
        // POST path; strict mode would flag those as unused stubs.
        org.mockito.Mockito.lenient()
                .when(
                        idempotencyService.runOnce(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(
                                        java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
    }

    @Test
    void testGetBudgetsWithValidUserReturnsBudgets() {
        // Given
        final List<BudgetTable> mockBudgets =
                Arrays.asList(createBudget(BUDGET_1), createBudget("budget-2"));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(budgetService.getBudgets(testUser)).thenReturn(mockBudgets);

        // When
        final ResponseEntity<List<BudgetTable>> response = budgetController.getBudgets(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void testGetBudgetsWithNullUserDetailsThrowsException() {
        // When/Then - Exception thrown before userService is called
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.getBudgets(null));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetBudgetsWithUserNotFoundThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.getBudgets(userDetails));
        assertEquals(com.budgetbuddy.exception.ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithValidDataCreatesBudget() {
        // Given
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));

        final BudgetTable mockBudget = createBudget(BUDGET_1);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        // Match the actual method signature: createOrUpdateBudget(user, category, monthlyLimit,
        // budgetId, rolloverEnabled, carriedAmount, goalId)
        // createOrUpdateBudget takes 10 args (user + 9 settings fields). Use
        // any() for the trailing settings so the stub matches regardless of
        // which optional fields the controller forwards.
        when(budgetService.createOrUpdateBudget(
                        eq(testUser),
                        eq("FOOD"),
                        eq(BigDecimal.valueOf(1000.00)),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(mockBudget);
        // Controller re-fetches after create to return the authoritative row
        // (matters on the idempotency-cache-hit path). Stub it here.
        when(budgetService.getBudget(eq(testUser), any())).thenReturn(mockBudget);

        // When
        final ResponseEntity<BudgetTable> response =
                budgetController.createOrUpdateBudget(userDetails, null, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCreateOrUpdateBudgetWithNullRequestThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because null request check happens first

        // When/Then - Exception thrown for null request
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.createOrUpdateBudget(userDetails, null, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithNullLimitThrowsException() {
        // Given
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(null);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.createOrUpdateBudget(userDetails, null, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithZeroLimitAllowsZero() {
        // Given - Zero budgets are allowed for zero-based budgeting support
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.ZERO);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        final BudgetTable zeroBudget = createBudget(BUDGET_1);
        when(budgetService.createOrUpdateBudget(
                        eq(testUser),
                        eq("FOOD"),
                        eq(BigDecimal.ZERO),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(zeroBudget);
        when(budgetService.getBudget(eq(testUser), any())).thenReturn(zeroBudget);

        // When
        final ResponseEntity<BudgetTable> response =
                budgetController.createOrUpdateBudget(userDetails, null, request);

        // Then - Should succeed (zero budgets are allowed)
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCreateOrUpdateBudgetWithNegativeLimitThrowsException() {
        // Given
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.valueOf(-100));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.createOrUpdateBudget(userDetails, null, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithNullCategoryThrowsException() {
        // Given
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory(null);
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.createOrUpdateBudget(userDetails, null, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudgetWithEmptyCategoryThrowsException() {
        // Given
        final BudgetController.CreateBudgetRequest request =
                new BudgetController.CreateBudgetRequest();
        request.setCategory("");
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.createOrUpdateBudget(userDetails, null, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudgetWithValidBudgetDeletesBudget() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        doNothing().when(budgetService).deleteBudget(testUser, BUDGET_1);

        // When
        final ResponseEntity<Void> response = budgetController.deleteBudget(userDetails, BUDGET_1);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(budgetService).deleteBudget(testUser, BUDGET_1);
    }

    @Test
    void testDeleteBudgetWithNullIdThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because null ID check happens first

        // When/Then - Exception thrown for null ID
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.deleteBudget(userDetails, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudgetWithEmptyIdThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because empty ID check happens first

        // When/Then - Exception thrown for empty ID
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.deleteBudget(userDetails, ""));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudgetWithNullUserDetailsThrowsException() {
        // When/Then - Exception thrown before userService is called
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> budgetController.deleteBudget(null, BUDGET_1));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    // Helper methods
    private BudgetTable createBudget(final String id) {
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId(id);
        budget.setUserId("user-123");
        budget.setCategory("FOOD");
        budget.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        return budget;
    }
}
