package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.GoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Goal Service
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
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
        // Use proper base64-encoded strings
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String base64ClientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        String base64ServerSalt = java.util.Base64.getEncoder().encodeToString("server-salt".getBytes());
        
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        testUser.setPasswordHash(base64PasswordHash);
        testUser.setClientSalt(base64ClientSalt);
        testUser.setServerSalt(base64ServerSalt);
        testUser.setPreferredCurrency("USD");
        testUser.setEnabled(true);
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

