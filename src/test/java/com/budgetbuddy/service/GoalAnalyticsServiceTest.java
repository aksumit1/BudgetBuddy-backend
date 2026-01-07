package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GoalAnalyticsService
 */
@ExtendWith(MockitoExtension.class)
class GoalAnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private GoalAnalyticsService analyticsService;
    private GoalTable testGoal;
    private String userId;

    @BeforeEach
    void setUp() {
        analyticsService = new GoalAnalyticsService(transactionRepository);
        
        userId = "test-user-id";
        testGoal = new GoalTable();
        testGoal.setGoalId("test-goal-id");
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        testGoal.setCurrentAmount(new BigDecimal("200.00"));
        testGoal.setTargetDate(LocalDate.now().plusMonths(10).toString()); // 10 months from now
    }

    @Test
    void testCalculateProjection_GoalAlreadyReached() {
        testGoal.setCurrentAmount(new BigDecimal("1000.00"));
        
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(testGoal, userId);
        
        assertNotNull(projection);
        assertEquals("COMPLETED", projection.getOnTrackStatus());
        assertEquals(0, projection.getMonthsRemaining());
    }

    @Test
    void testCalculateProjection_WithContributions_OnTrack() {
        // Create transactions from last 3 months (use recent dates)
        // Set target date to 8 months from now so 100/month will be exactly on track
        testGoal.setTargetDate(LocalDate.now().plusMonths(8).toString());
        LocalDate now = LocalDate.now();
        // Ensure transactions are clearly within the 3-month window
        // The filter uses isAfter(threeMonthsAgo), so dates must be strictly after threeMonthsAgo
        // Use dates that are definitely within the last 2.5 months to ensure they pass the filter
        LocalDate twoMonthsAgo = now.minusMonths(2);
        LocalDate oneMonthAgo = now.minusMonths(1);
        LocalDate twoWeeksAgo = now.minusDays(14);
        
        TransactionTable tx1 = createTransaction("tx1", twoMonthsAgo.toString(), new BigDecimal("100.00"));
        TransactionTable tx2 = createTransaction("tx2", oneMonthAgo.toString(), new BigDecimal("100.00"));
        TransactionTable tx3 = createTransaction("tx3", twoWeeksAgo.toString(), new BigDecimal("100.00"));
        List<TransactionTable> transactions = Arrays.asList(tx1, tx2, tx3);
        
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(transactions);
        
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(testGoal, userId);
        
        assertNotNull(projection);
        // With 200 current, 1000 target, 800 remaining, and 100/month = 8 months, 
        // and target date is 8 months away, should be ON_TRACK (or AHEAD_OF_SCHEDULE if slightly ahead)
        // Note: The calculation divides total by 3, so 300/3 = 100/month average
        assertTrue("ON_TRACK".equals(projection.getOnTrackStatus()) || 
                   "AHEAD_OF_SCHEDULE".equals(projection.getOnTrackStatus()),
                   "Expected ON_TRACK or AHEAD_OF_SCHEDULE but got: " + projection.getOnTrackStatus());
        assertTrue(projection.getAverageMonthlyContribution().compareTo(new BigDecimal("100.00")) == 0,
                   "Expected average monthly contribution of 100.00 but got: " + projection.getAverageMonthlyContribution());
        assertTrue(projection.getMonthsRemaining() > 0);
    }

    @Test
    void testCalculateProjection_WithContributions_BehindSchedule() {
        // Small contributions - won't meet deadline (use recent dates)
        // Ensure transactions are clearly within the 3-month window
        LocalDate now = LocalDate.now();
        LocalDate twoMonthsAgo = now.minusMonths(2);
        LocalDate oneMonthAgo = now.minusMonths(1);
        LocalDate twoWeeksAgo = now.minusDays(14);
        
        TransactionTable tx1 = createTransaction("tx1", twoMonthsAgo.toString(), new BigDecimal("10.00"));
        TransactionTable tx2 = createTransaction("tx2", oneMonthAgo.toString(), new BigDecimal("10.00"));
        TransactionTable tx3 = createTransaction("tx3", twoWeeksAgo.toString(), new BigDecimal("10.00"));
        List<TransactionTable> transactions = Arrays.asList(tx1, tx2, tx3);
        
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(transactions);
        
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(testGoal, userId);
        
        assertNotNull(projection);
        // With 200 current, 1000 target, 800 remaining, and 10/month = 80 months needed,
        // but target date is only 10 months away, so should be BEHIND_SCHEDULE
        assertEquals("BEHIND_SCHEDULE", projection.getOnTrackStatus(),
                    "Expected BEHIND_SCHEDULE but got: " + projection.getOnTrackStatus());
        assertTrue(projection.getRecommendedMonthlyContribution().compareTo(new BigDecimal("10.00")) > 0);
    }

    @Test
    void testCalculateProjection_NoContributions() {
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(new ArrayList<>());
        
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(testGoal, userId);
        
        assertNotNull(projection);
        assertEquals("NO_CONTRIBUTIONS", projection.getOnTrackStatus());
        assertTrue(projection.getRecommendedMonthlyContribution().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testCalculateProjection_NullGoal() {
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(null, userId);
        
        assertNull(projection);
    }

    @Test
    void testCalculateProjection_NullTargetDate() {
        testGoal.setTargetDate(null);
        
        GoalAnalyticsService.GoalProjection projection = analyticsService.calculateProjection(testGoal, userId);
        
        assertNull(projection);
    }

    @Test
    void testGetContributionInsights_WithTransactions() {
        LocalDate now = LocalDate.now();
        TransactionTable tx1 = createTransaction("tx1", now.minusMonths(2).toString(), new BigDecimal("100.00"));
        TransactionTable tx2 = createTransaction("tx2", now.minusMonths(1).toString(), new BigDecimal("200.00"));
        TransactionTable tx3 = createTransaction("tx3", now.minusDays(15).toString(), new BigDecimal("50.00"));
        List<TransactionTable> transactions = Arrays.asList(tx1, tx2, tx3);
        
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(transactions);
        
        GoalAnalyticsService.ContributionInsights insights = analyticsService.getContributionInsights(testGoal, userId);
        
        assertNotNull(insights);
        assertEquals(3, insights.getContributionCount());
        assertTrue(insights.getTotalContributions().compareTo(new BigDecimal("350.00")) == 0);
        assertTrue(insights.getLargestContribution().compareTo(new BigDecimal("200.00")) == 0);
    }

    @Test
    void testGetContributionInsights_NoTransactions() {
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(new ArrayList<>());
        
        GoalAnalyticsService.ContributionInsights insights = analyticsService.getContributionInsights(testGoal, userId);
        
        assertNotNull(insights);
        assertEquals(0, insights.getContributionCount());
        assertTrue(insights.getTotalContributions().compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void testGetContributionInsights_FiltersNegativeAmounts() {
        LocalDate now = LocalDate.now();
        TransactionTable tx1 = createTransaction("tx1", now.minusMonths(2).toString(), new BigDecimal("100.00"));
        TransactionTable tx2 = createTransaction("tx2", now.minusMonths(1).toString(), new BigDecimal("-50.00")); // Negative
        TransactionTable tx3 = createTransaction("tx3", now.minusDays(15).toString(), new BigDecimal("50.00"));
        List<TransactionTable> transactions = Arrays.asList(tx1, tx2, tx3);
        
        when(transactionRepository.findByUserIdAndGoalId(userId, testGoal.getGoalId()))
            .thenReturn(transactions);
        
        GoalAnalyticsService.ContributionInsights insights = analyticsService.getContributionInsights(testGoal, userId);
        
        assertNotNull(insights);
        assertEquals(2, insights.getContributionCount()); // Only positive amounts
        assertTrue(insights.getTotalContributions().compareTo(new BigDecimal("150.00")) == 0);
    }

    // Helper method
    private TransactionTable createTransaction(String transactionId, String date, BigDecimal amount) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(transactionId);
        tx.setTransactionDate(date);
        tx.setAmount(amount);
        return tx;
    }
}

