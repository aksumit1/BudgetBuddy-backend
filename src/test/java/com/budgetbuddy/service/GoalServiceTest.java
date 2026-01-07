package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
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
 * Tests for GoalService
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoalServiceTest {

    @Autowired
    private GoalService goalService;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private UserTable testUser;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test-goal-" + UUID.randomUUID() + "@example.com");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setCreatedAt(Instant.now());
        testUser.setUpdatedAt(Instant.now());
    }

    @Test
    void testCreateGoal_WithValidInput_Succeeds() {
        // Given
        String name = "Test Goal";
        String description = "Test Description";
        BigDecimal targetAmount = new BigDecimal("1000.00");
        LocalDate targetDate = LocalDate.now().plusMonths(6);
        String goalType = "SAVINGS";

        // When
        GoalTable goal = goalService.createGoal(testUser, name, description, targetAmount, targetDate, goalType, null, null, null);

        // Then
        assertNotNull(goal, "Goal should be created");
        assertEquals(name, goal.getName());
        assertEquals(description, goal.getDescription());
        assertEquals(targetAmount, goal.getTargetAmount());
        assertEquals(targetDate.toString(), goal.getTargetDate());
        assertEquals(goalType, goal.getGoalType());
        assertEquals(testUser.getUserId(), goal.getUserId());
    }

    @Test
    void testCreateGoal_WithNullUser_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            goalService.createGoal(null, "Test", "Desc", BigDecimal.TEN, LocalDate.now(), "SAVINGS", null, null, null);
        }, "Should throw exception for null user");
    }

    @Test
    void testCreateGoal_WithEmptyName_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            goalService.createGoal(testUser, "", "Desc", BigDecimal.TEN, LocalDate.now(), "SAVINGS", null, null, null);
        }, "Should throw exception for empty name");
    }

    @Test
    void testCreateGoal_WithInvalidTargetAmount_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> {
            goalService.createGoal(testUser, "Test", "Desc", BigDecimal.ZERO, LocalDate.now(), "SAVINGS", null, null, null);
        }, "Should throw exception for zero target amount");
    }

    @Test
    void testGetActiveGoals_ReturnsUserGoals() {
        // Given - Create goals
        goalService.createGoal(testUser, "Goal 1", "Desc 1", 
                new BigDecimal("1000.00"), LocalDate.now().plusMonths(6), "SAVINGS", null, null, null);
        goalService.createGoal(testUser, "Goal 2", "Desc 2", 
                new BigDecimal("2000.00"), LocalDate.now().plusMonths(12), "SAVINGS", null, null, null);

        // When
        List<GoalTable> goals = goalService.getActiveGoals(testUser);

        // Then
        assertNotNull(goals, "Goals should not be null");
        assertTrue(goals.size() >= 2, "Should return at least 2 goals");
    }

    @Test
    void testGetGoal_WithValidId_ReturnsGoal() {
        // Given - Create a goal
        GoalTable goal = goalService.createGoal(testUser, "Test Goal", "Desc", 
                new BigDecimal("1000.00"), LocalDate.now().plusMonths(6), "SAVINGS", null, null, null);
        String goalId = goal.getGoalId();

        // When
        GoalTable found = goalService.getGoal(testUser, goalId);

        // Then
        assertNotNull(found, "Goal should be found");
        assertEquals(goalId, found.getGoalId());
    }

    @Test
    void testDeleteGoal_WithValidId_Succeeds() {
        // Given - Create a goal
        GoalTable goal = goalService.createGoal(testUser, "Test Goal", "Desc", 
                new BigDecimal("1000.00"), LocalDate.now().plusMonths(6), "SAVINGS", null, null, null);
        String goalId = goal.getGoalId();

        // When
        goalService.deleteGoal(testUser, goalId);

        // Then - Goal should be deleted
        Optional<GoalTable> deleted = goalRepository.findById(goalId);
        assertTrue(deleted.isEmpty(), "Goal should be deleted");
    }
}

