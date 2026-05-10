package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Unit Tests for GoalController */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GoalControllerTest {

    private static final String GOAL_1 = "goal-1";

    @Mock private GoalService goalService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    // New collaborators on GoalController since the correctness/audit pass.
    // @InjectMocks binds by type, so declaring these avoids NPEs inside the
    // createGoal flow where the controller delegates to idempotencyService.
    @Mock private com.budgetbuddy.service.GoalProgressService goalProgressService;

    @Mock
    private com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService;

    @Mock private com.budgetbuddy.service.FinancialGoalsRecommendationService recommendationService;
    @Mock private com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor;
    @Mock private com.budgetbuddy.service.correctness.IdempotencyService idempotencyService;

    @InjectMocks private GoalController goalController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");

        // Pass-through the idempotency layer so tests exercise the actual
        // create flow. Lenient because read-only tests don't hit this path.
        when(idempotencyService.runOnce(anyString(), any(), any(java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
    }

    @Test
    void testGetGoalsWithValidUserReturnsGoals() {
        // Given
        final List<GoalTable> mockGoals = Arrays.asList(createGoal(GOAL_1), createGoal("goal-2"));
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(goalService.getActiveGoals(testUser)).thenReturn(mockGoals);

        // When
        final ResponseEntity<List<GoalTable>> response = goalController.getGoals(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void testCreateGoalWithValidDataCreatesGoal() {
        // Given
        final GoalController.CreateGoalRequest request = new GoalController.CreateGoalRequest();
        request.setName("Save for vacation");
        request.setDescription("Save money for vacation");
        request.setTargetAmount(BigDecimal.valueOf(5000.00));
        request.setTargetDate(LocalDate.now().plusMonths(6));
        request.setGoalType("SAVINGS");

        final GoalTable mockGoal = createGoal(GOAL_1);
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(goalService.createGoal(
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any()))
                .thenReturn(mockGoal);
        // Controller re-fetches after create to return the authoritative row
        // (matters on the idempotency-cache-hit path).
        when(goalService.getGoal(eq(testUser), any())).thenReturn(mockGoal);

        // When
        final ResponseEntity<GoalTable> response =
                goalController.createGoal(userDetails, null, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testUpdateProgressWithValidDataUpdatesGoal() {
        // Given
        final GoalController.UpdateProgressRequest request =
                new GoalController.UpdateProgressRequest();
        request.setAmount(BigDecimal.valueOf(100.00));

        final GoalTable mockGoal = createGoal(GOAL_1);
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(goalService.updateGoalProgress(testUser, GOAL_1, BigDecimal.valueOf(100.00)))
                .thenReturn(mockGoal);

        // When
        final ResponseEntity<GoalTable> response =
                goalController.updateProgress(userDetails, GOAL_1, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    // Helper methods
    private GoalTable createGoal(final String id) {
        final GoalTable goal = new GoalTable();
        goal.setGoalId(id);
        goal.setUserId("user-123");
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(5000.00));
        goal.setCurrentAmount(BigDecimal.ZERO);
        return goal;
    }
}
