package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GoalMilestoneService
 */
class GoalMilestoneServiceTest {

    private GoalMilestoneService milestoneService;
    private GoalTable testGoal;

    @BeforeEach
    void setUp() {
        milestoneService = new GoalMilestoneService();
        
        testGoal = new GoalTable();
        testGoal.setGoalId("test-goal-id");
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        testGoal.setCurrentAmount(new BigDecimal("0.00"));
    }

    @Test
    void testGetMilestones_AtZero() {
        testGoal.setCurrentAmount(new BigDecimal("0.00"));
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertEquals(4, milestones.size());
        assertFalse(milestones.get(0).isReached()); // 25%
        assertFalse(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestones_At25Percent() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertTrue(milestones.get(0).isReached()); // 25%
        assertFalse(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
        assertEquals(25, milestones.get(0).getPercentage());
    }

    @Test
    void testGetMilestones_At50Percent() {
        testGoal.setCurrentAmount(new BigDecimal("500.00"));
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertTrue(milestones.get(0).isReached()); // 25%
        assertTrue(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestones_At100Percent() {
        testGoal.setCurrentAmount(new BigDecimal("1000.00"));
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertTrue(milestones.get(0).isReached()); // 25%
        assertTrue(milestones.get(1).isReached()); // 50%
        assertTrue(milestones.get(2).isReached()); // 75%
        assertTrue(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestones_Over100Percent() {
        testGoal.setCurrentAmount(new BigDecimal("1200.00"));
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertTrue(milestones.get(3).isReached()); // 100%
        assertEquals(100, milestoneService.getProgressPercentage(testGoal));
    }

    @Test
    void testCheckNewMilestoneReached_From0To25() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        BigDecimal previousAmount = new BigDecimal("0.00");
        
        GoalMilestoneService.Milestone newMilestone = milestoneService.checkNewMilestoneReached(testGoal, previousAmount);
        
        assertNotNull(newMilestone);
        assertEquals(25, newMilestone.getPercentage());
        assertTrue(newMilestone.isReached());
    }

    @Test
    void testCheckNewMilestoneReached_From25To50() {
        testGoal.setCurrentAmount(new BigDecimal("500.00"));
        BigDecimal previousAmount = new BigDecimal("250.00");
        
        GoalMilestoneService.Milestone newMilestone = milestoneService.checkNewMilestoneReached(testGoal, previousAmount);
        
        assertNotNull(newMilestone);
        assertEquals(50, newMilestone.getPercentage());
    }

    @Test
    void testCheckNewMilestoneReached_NoNewMilestone() {
        testGoal.setCurrentAmount(new BigDecimal("300.00"));
        BigDecimal previousAmount = new BigDecimal("250.00");
        
        GoalMilestoneService.Milestone newMilestone = milestoneService.checkNewMilestoneReached(testGoal, previousAmount);
        
        assertNull(newMilestone); // No new milestone crossed
    }

    @Test
    void testGetNextMilestone_AtZero() {
        testGoal.setCurrentAmount(new BigDecimal("0.00"));
        
        GoalMilestoneService.Milestone next = milestoneService.getNextMilestone(testGoal);
        
        assertNotNull(next);
        assertEquals(25, next.getPercentage());
        assertFalse(next.isReached());
    }

    @Test
    void testGetNextMilestone_At25() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        
        GoalMilestoneService.Milestone next = milestoneService.getNextMilestone(testGoal);
        
        assertNotNull(next);
        assertEquals(50, next.getPercentage());
    }

    @Test
    void testGetProgressPercentage() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        
        int percentage = milestoneService.getProgressPercentage(testGoal);
        
        assertEquals(25, percentage);
    }

    @Test
    void testGetProgressPercentage_Over100() {
        testGoal.setCurrentAmount(new BigDecimal("1200.00"));
        
        int percentage = milestoneService.getProgressPercentage(testGoal);
        
        assertEquals(100, percentage); // Capped at 100%
    }

    @Test
    void testGetMilestones_NullGoal() {
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(null);
        
        assertTrue(milestones.isEmpty());
    }

    @Test
    void testGetMilestones_NullTargetAmount() {
        testGoal.setTargetAmount(null);
        
        List<GoalMilestoneService.Milestone> milestones = milestoneService.getMilestones(testGoal);
        
        assertTrue(milestones.isEmpty());
    }
}

