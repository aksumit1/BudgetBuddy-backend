package com.budgetbuddy.integration;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.GoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Goal Service
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class GoalIntegrationTest {

    @Autowired
    private GoalService goalService;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private UserRepository userRepository;

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
    void testCreateAndRetrieveGoal() {
        // Given
        GoalTable goal = goalService.createGoal(
                testUser,
                "Save for vacation",
                "Save money for summer vacation",
                BigDecimal.valueOf(5000.00),
                LocalDate.now().plusMonths(6),
                "SAVINGS"
        );

        // When
        List<GoalTable> goals = goalService.getActiveGoals(testUser);

        // Then
        assertNotNull(goals);
        assertTrue(goals.stream().anyMatch(g -> g.getGoalId().equals(goal.getGoalId())));
    }

    @Test
    void testUpdateGoalProgress() {
        // Given
        GoalTable goal = goalService.createGoal(
                testUser,
                "Save for vacation",
                "Save money",
                BigDecimal.valueOf(5000.00),
                LocalDate.now().plusMonths(6),
                "SAVINGS"
        );

        // When
        GoalTable updated = goalService.updateGoalProgress(testUser, goal.getGoalId(), BigDecimal.valueOf(500.00));

        // Then
        assertEquals(BigDecimal.valueOf(500.00), updated.getCurrentAmount());
    }
}

