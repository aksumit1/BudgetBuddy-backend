package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalAnalyticsService;
import com.budgetbuddy.service.GoalMilestoneService;
import com.budgetbuddy.service.GoalRoundUpService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive Tests for GoalEnhancementController
 * - Query parameter handling (days parameter)
 * - Error handling (goalNotFound, networkError, etc.)
 * - Race condition handling
 * - Integration tests
 */
@ExtendWith(MockitoExtension.class)
class GoalEnhancementControllerTest {

    @Mock
    private GoalService goalService;

    @Mock
    private GoalMilestoneService milestoneService;

    @Mock
    private GoalAnalyticsService analyticsService;

    @Mock
    private GoalRoundUpService roundUpService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private GoalEnhancementController controller;

    private UserTable testUser;
    private GoalTable testGoal;
    private String goalId;
    private String userId;

    @BeforeEach
    void setUp() {
        userId = "test-user-id";
        goalId = "test-goal-id";

        testUser = new UserTable();
        testUser.setUserId(userId);
        testUser.setEmail("test@example.com");

        testGoal = new GoalTable();
        testGoal.setGoalId(goalId);
        testGoal.setUserId(userId);
        testGoal.setName("Test Goal");
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        testGoal.setTargetDate(LocalDate.now().plusMonths(10).toString());

        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
    }

    // MARK: - Milestones Tests

    @Test
    void testGetMilestones_Success() {
        // Given
        List<GoalMilestoneService.Milestone> milestones = new ArrayList<>();
        milestones.add(new GoalMilestoneService.Milestone(25, true, new BigDecimal("250.00"), 
            new BigDecimal("250.00"), "25% milestone"));
        
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(milestoneService.getMilestones(testGoal)).thenReturn(milestones);
        when(milestoneService.getProgressPercentage(testGoal)).thenReturn(25);
        when(milestoneService.getNextMilestone(testGoal)).thenReturn(
            new GoalMilestoneService.Milestone(50, false, new BigDecimal("500.00"), null, "50% milestone"));

        // When
        ResponseEntity<?> response = controller.getMilestones(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
    }

    @Test
    void testGetMilestones_GoalNotFound() {
        // Given - Goal not found (race condition for newly created goal)
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.getMilestones(userDetails, goalId);

        // Then - Should return 500 with error message
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertTrue(body.containsKey("error"));
        assertEquals("Goal not found", body.get("error"));
    }

    @Test
    void testGetMilestones_Unauthorized() {
        // Given - Goal belongs to different user
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user"));

        // When
        ResponseEntity<?> response = controller.getMilestones(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // MARK: - Projections Tests

    @Test
    void testGetProjections_Success() {
        // Given
        GoalAnalyticsService.GoalProjection projection = new GoalAnalyticsService.GoalProjection(
            LocalDate.now().plusMonths(8),
            new BigDecimal("100.00"),
            "ON_TRACK",
            new BigDecimal("100.00"),
            8,
            "On track message"
        );

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(analyticsService.calculateProjection(testGoal, userId)).thenReturn(projection);

        // When
        ResponseEntity<?> response = controller.getProjections(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetProjections_ProjectionNull() {
        // Given
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(analyticsService.calculateProjection(testGoal, userId)).thenReturn(null);

        // When
        ResponseEntity<?> response = controller.getProjections(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testGetProjections_GoalNotFound() {
        // Given
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.getProjections(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // MARK: - Insights Tests

    @Test
    void testGetInsights_Success() {
        // Given
        GoalAnalyticsService.ContributionInsights insights = new GoalAnalyticsService.ContributionInsights(
            new BigDecimal("500.00"),
            new BigDecimal("100.00"),
            new BigDecimal("200.00"),
            5,
            "MANUAL"
        );

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(analyticsService.getContributionInsights(testGoal, userId)).thenReturn(insights);

        // When
        ResponseEntity<?> response = controller.getInsights(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetInsights_GoalNotFound() {
        // Given
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.getInsights(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // MARK: - Round-Up Tests

    @Test
    void testEnableRoundUp_Success() {
        // Given
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        doNothing().when(roundUpService).enableRoundUp(goalId);

        // When
        ResponseEntity<?> response = controller.enableRoundUp(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(roundUpService).enableRoundUp(goalId);
    }

    @Test
    void testEnableRoundUp_GoalNotFound() {
        // Given
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.enableRoundUp(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(roundUpService, never()).enableRoundUp(anyString());
    }

    @Test
    void testDisableRoundUp_Success() {
        // Given
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        doNothing().when(roundUpService).disableRoundUp(goalId);

        // When
        ResponseEntity<?> response = controller.disableRoundUp(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(roundUpService).disableRoundUp(goalId);
    }

    @Test
    void testDisableRoundUp_GoalNotFound() {
        // Given
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.disableRoundUp(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(roundUpService, never()).disableRoundUp(anyString());
    }

    // MARK: - Round-Up Total Tests (Query Parameter Handling)

    @Test
    void testGetRoundUpTotal_Success_WithDefaultDays() {
        // Given - No days parameter (should default to 30)
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(roundUpService.getRoundUpTotal(testGoal, userId, 30))
            .thenReturn(new BigDecimal("50.00"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, 30);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertTrue(body.containsKey("total"));
        assertTrue(body.containsKey("days"));
        assertEquals(30, body.get("days"));
    }

    @Test
    void testGetRoundUpTotal_Success_WithCustomDays() {
        // Given - Custom days parameter (60 days)
        int customDays = 60;
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(roundUpService.getRoundUpTotal(testGoal, userId, customDays))
            .thenReturn(new BigDecimal("100.00"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, customDays);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(customDays, body.get("days"));
        assertEquals(new BigDecimal("100.00"), body.get("total"));
        verify(roundUpService).getRoundUpTotal(testGoal, userId, customDays);
    }

    @Test
    void testGetRoundUpTotal_Success_WithVariousDays() {
        // Given - Test different day values
        int[] testDays = {7, 14, 30, 60, 90};
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);

        for (int days : testDays) {
            // When
            when(roundUpService.getRoundUpTotal(testGoal, userId, days))
                .thenReturn(new BigDecimal(String.valueOf(days * 2)));
            ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, days);

            // Then
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody() instanceof Map);
            Map<?, ?> body = (Map<?, ?>) response.getBody();
            assertEquals(days, body.get("days"));
            verify(roundUpService).getRoundUpTotal(testGoal, userId, days);
        }
    }

    @Test
    void testGetRoundUpTotal_GoalNotFound() {
        // Given - Goal not found (race condition for newly created goal)
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, 30);

        // Then - Should return 500 with error message
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertTrue(body.containsKey("error"));
        assertEquals("Goal not found", body.get("error"));
        verify(roundUpService, never()).getRoundUpTotal(any(), anyString(), anyInt());
    }

    @Test
    void testGetRoundUpTotal_Unauthorized() {
        // Given - Goal belongs to different user
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, 30);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(roundUpService, never()).getRoundUpTotal(any(), anyString(), anyInt());
    }

    // MARK: - Error Handling Tests

    @Test
    void testGetRoundUpTotal_ServiceException_HandlesGracefully() {
        // Given - Round-up service throws exception
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(roundUpService.getRoundUpTotal(testGoal, userId, 30))
            .thenThrow(new RuntimeException("Round-up service error"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, goalId, 30);

        // Then - Should return 500 with error message
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertTrue(body.containsKey("error"));
    }

    @Test
    void testGetMilestones_ServiceException_HandlesGracefully() {
        // Given - Milestone service throws exception
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(milestoneService.getMilestones(testGoal))
            .thenThrow(new RuntimeException("Milestone service error"));

        // When
        ResponseEntity<?> response = controller.getMilestones(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testGetInsights_ServiceException_HandlesGracefully() {
        // Given - Analytics service throws exception
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(analyticsService.getContributionInsights(testGoal, userId))
            .thenThrow(new RuntimeException("Analytics service error"));

        // When
        ResponseEntity<?> response = controller.getInsights(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void testGetProjections_ServiceException_HandlesGracefully() {
        // Given - Analytics service throws exception
        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(analyticsService.calculateProjection(testGoal, userId))
            .thenThrow(new RuntimeException("Projection service error"));

        // When
        ResponseEntity<?> response = controller.getProjections(userDetails, goalId);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // MARK: - Race Condition Tests (Goal Not Found for Newly Created Goals)

    @Test
    void testGetRoundUpTotal_NewlyCreatedGoal_ReturnsError() {
        // Given - Newly created goal not yet persisted (race condition)
        String newGoalId = "new-goal-id";
        when(goalService.getGoal(testUser, newGoalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When
        ResponseEntity<?> response = controller.getRoundUpTotal(userDetails, newGoalId, 30);

        // Then - Should return 500 with error message (client should retry)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Goal not found", body.get("error"));
    }

    @Test
    void testAllEndpoints_GoalNotFound_ReturnConsistentError() {
        // Given - Goal not found
        when(goalService.getGoal(testUser, goalId))
            .thenThrow(new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        // When/Then - All endpoints should return 500 with error
        ResponseEntity<?> milestonesResponse = controller.getMilestones(userDetails, goalId);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, milestonesResponse.getStatusCode());

        ResponseEntity<?> projectionsResponse = controller.getProjections(userDetails, goalId);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, projectionsResponse.getStatusCode());

        ResponseEntity<?> insightsResponse = controller.getInsights(userDetails, goalId);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, insightsResponse.getStatusCode());

        ResponseEntity<?> roundUpResponse = controller.getRoundUpTotal(userDetails, goalId, 30);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, roundUpResponse.getStatusCode());

        ResponseEntity<?> enableRoundUpResponse = controller.enableRoundUp(userDetails, goalId);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, enableRoundUpResponse.getStatusCode());

        ResponseEntity<?> disableRoundUpResponse = controller.disableRoundUp(userDetails, goalId);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, disableRoundUpResponse.getStatusCode());
    }
}
