package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.GoalTable;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for GoalMilestoneService */
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
    void testGetMilestonesAtZero() {
        testGoal.setCurrentAmount(new BigDecimal("0.00"));

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertEquals(4, milestones.size());
        assertFalse(milestones.get(0).isReached()); // 25%
        assertFalse(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestonesAt25Percent() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertTrue(milestones.get(0).isReached()); // 25%
        assertFalse(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
        assertEquals(25, milestones.get(0).getPercentage());
    }

    @Test
    void testGetMilestonesAt50Percent() {
        testGoal.setCurrentAmount(new BigDecimal("500.00"));

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertTrue(milestones.get(0).isReached()); // 25%
        assertTrue(milestones.get(1).isReached()); // 50%
        assertFalse(milestones.get(2).isReached()); // 75%
        assertFalse(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestonesAt100Percent() {
        testGoal.setCurrentAmount(new BigDecimal("1000.00"));

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertTrue(milestones.get(0).isReached()); // 25%
        assertTrue(milestones.get(1).isReached()); // 50%
        assertTrue(milestones.get(2).isReached()); // 75%
        assertTrue(milestones.get(3).isReached()); // 100%
    }

    @Test
    void testGetMilestonesOver100Percent() {
        testGoal.setCurrentAmount(new BigDecimal("1200.00"));

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertTrue(milestones.get(3).isReached()); // 100%
        assertEquals(100, milestoneService.getProgressPercentage(testGoal));
    }

    @Test
    void testCheckNewMilestoneReachedFrom0To25() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));
        final BigDecimal previousAmount = new BigDecimal("0.00");

        final GoalMilestoneService.Milestone newMilestone =
                milestoneService.checkNewMilestoneReached(testGoal, previousAmount);

        assertNotNull(newMilestone);
        assertEquals(25, newMilestone.getPercentage());
        assertTrue(newMilestone.isReached());
    }

    @Test
    void testCheckNewMilestoneReachedFrom25To50() {
        testGoal.setCurrentAmount(new BigDecimal("500.00"));
        final BigDecimal previousAmount = new BigDecimal("250.00");

        final GoalMilestoneService.Milestone newMilestone =
                milestoneService.checkNewMilestoneReached(testGoal, previousAmount);

        assertNotNull(newMilestone);
        assertEquals(50, newMilestone.getPercentage());
    }

    @Test
    void testCheckNewMilestoneReachedNoNewMilestone() {
        testGoal.setCurrentAmount(new BigDecimal("300.00"));
        final BigDecimal previousAmount = new BigDecimal("250.00");

        final GoalMilestoneService.Milestone newMilestone =
                milestoneService.checkNewMilestoneReached(testGoal, previousAmount);

        assertNull(newMilestone); // No new milestone crossed
    }

    @Test
    void testGetNextMilestoneAtZero() {
        testGoal.setCurrentAmount(new BigDecimal("0.00"));

        final GoalMilestoneService.Milestone next = milestoneService.getNextMilestone(testGoal);

        assertNotNull(next);
        assertEquals(25, next.getPercentage());
        assertFalse(next.isReached());
    }

    @Test
    void testGetNextMilestoneAt25() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));

        final GoalMilestoneService.Milestone next = milestoneService.getNextMilestone(testGoal);

        assertNotNull(next);
        assertEquals(50, next.getPercentage());
    }

    @Test
    void testGetProgressPercentage() {
        testGoal.setCurrentAmount(new BigDecimal("250.00"));

        final int percentage = milestoneService.getProgressPercentage(testGoal);

        assertEquals(25, percentage);
    }

    @Test
    void testGetProgressPercentageOver100() {
        testGoal.setCurrentAmount(new BigDecimal("1200.00"));

        final int percentage = milestoneService.getProgressPercentage(testGoal);

        assertEquals(100, percentage); // Capped at 100%
    }

    @Test
    void testGetMilestonesNullGoal() {
        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(null);

        assertTrue(milestones.isEmpty());
    }

    @Test
    void testGetMilestonesNullTargetAmount() {
        testGoal.setTargetAmount(null);

        final List<GoalMilestoneService.Milestone> milestones =
                milestoneService.getMilestones(testGoal);

        assertTrue(milestones.isEmpty());
    }
}
