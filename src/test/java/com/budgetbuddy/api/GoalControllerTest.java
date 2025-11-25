package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for GoalController
 * 
 * DISABLED: Java 25 compatibility issue - Mockito/ByteBuddy cannot mock certain dependencies
 * due to Java 25 bytecode (major version 69) not being fully supported by ByteBuddy.
 * Will be re-enabled when Mockito/ByteBuddy adds full Java 25 support.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Mockito mocking issues")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GoalControllerTest {

    @Mock
    private GoalService goalService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private GoalController goalController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGetGoals_WithValidUser_ReturnsGoals() {
        // Given
        List<GoalTable> mockGoals = Arrays.asList(
                createGoal("goal-1"),
                createGoal("goal-2")
        );
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(goalService.getActiveGoals(testUser)).thenReturn(mockGoals);

        // When
        ResponseEntity<List<GoalTable>> response = goalController.getGoals(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void testCreateGoal_WithValidData_CreatesGoal() {
        // Given
        GoalController.CreateGoalRequest request = new GoalController.CreateGoalRequest();
        request.setName("Save for vacation");
        request.setDescription("Save money for vacation");
        request.setTargetAmount(BigDecimal.valueOf(5000.00));
        request.setTargetDate(LocalDate.now().plusMonths(6));
        request.setGoalType("SAVINGS");

        GoalTable mockGoal = createGoal("goal-1");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(goalService.createGoal(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(mockGoal);

        // When
        ResponseEntity<GoalTable> response = goalController.createGoal(userDetails, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testUpdateProgress_WithValidData_UpdatesGoal() {
        // Given
        GoalController.UpdateProgressRequest request = new GoalController.UpdateProgressRequest();
        request.setAmount(BigDecimal.valueOf(100.00));

        GoalTable mockGoal = createGoal("goal-1");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(goalService.updateGoalProgress(testUser, "goal-1", BigDecimal.valueOf(100.00)))
                .thenReturn(mockGoal);

        // When
        ResponseEntity<GoalTable> response = goalController.updateProgress(userDetails, "goal-1", request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    // Helper methods
    private GoalTable createGoal(final String id) {
        GoalTable goal = new GoalTable();
        goal.setGoalId(id);
        goal.setUserId("user-123");
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(5000.00));
        goal.setCurrentAmount(BigDecimal.ZERO);
        return goal;
    }
}

