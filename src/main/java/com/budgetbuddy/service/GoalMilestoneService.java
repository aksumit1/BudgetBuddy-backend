package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for managing goal milestones and celebrations Provides milestone detection, celebration
 * triggers, and milestone data
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class GoalMilestoneService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalMilestoneService.class);

    // Milestone percentages
    private static final BigDecimal MILESTONE_25 = new BigDecimal("0.25");
    private static final BigDecimal MILESTONE_50 = new BigDecimal("0.50");
    private static final BigDecimal MILESTONE_75 = new BigDecimal("0.75");
    private static final BigDecimal MILESTONE_100 = new BigDecimal("1.00");

    /** Milestone data structure */
    public static class Milestone {
        private final int percentage;
        private final boolean reached;
        private final BigDecimal targetAmount;
        private final BigDecimal reachedAt;
        private final String message;

        public Milestone(
                final int percentage,
                final boolean reached,
                final BigDecimal targetAmount,
                final BigDecimal reachedAt,
                final String message) {
            this.percentage = percentage;
            this.reached = reached;
            this.targetAmount = targetAmount;
            this.reachedAt = reachedAt;
            this.message = message;
        }

        public int getPercentage() {
            return percentage;
        }

        public boolean isReached() {
            return reached;
        }

        public BigDecimal getTargetAmount() {
            return targetAmount;
        }

        public BigDecimal getReachedAt() {
            return reachedAt;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Get all milestones for a goal */
    public List<Milestone> getMilestones(final GoalTable goal) {
        if (goal == null || goal.getTargetAmount() == null) {
            return new ArrayList<>();
        }

        final BigDecimal currentAmount =
                goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        final BigDecimal targetAmount = goal.getTargetAmount();
        final BigDecimal progress =
                targetAmount.compareTo(BigDecimal.ZERO) > 0
                        ? currentAmount.divide(targetAmount, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

        final List<Milestone> milestones = new ArrayList<>();

        // 25% milestone
        final BigDecimal milestone25Amount =
                targetAmount.multiply(MILESTONE_25).setScale(2, RoundingMode.HALF_UP);
        final boolean reached25 = currentAmount.compareTo(milestone25Amount) >= 0;
        milestones.add(
                new Milestone(
                        25,
                        reached25,
                        milestone25Amount,
                        reached25 ? milestone25Amount : null,
                        "You're a quarter of the way there! 🎯"));

        // 50% milestone
        final BigDecimal milestone50Amount =
                targetAmount.multiply(MILESTONE_50).setScale(2, RoundingMode.HALF_UP);
        final boolean reached50 = currentAmount.compareTo(milestone50Amount) >= 0;
        milestones.add(
                new Milestone(
                        50,
                        reached50,
                        milestone50Amount,
                        reached50 ? milestone50Amount : null,
                        "Halfway there! You're doing amazing! 💪"));

        // 75% milestone
        final BigDecimal milestone75Amount =
                targetAmount.multiply(MILESTONE_75).setScale(2, RoundingMode.HALF_UP);
        final boolean reached75 = currentAmount.compareTo(milestone75Amount) >= 0;
        milestones.add(
                new Milestone(
                        75,
                        reached75,
                        milestone75Amount,
                        reached75 ? milestone75Amount : null,
                        "Almost there! Just 25% to go! 🚀"));

        // 100% milestone (completion)
        final boolean reached100 = currentAmount.compareTo(targetAmount) >= 0;
        milestones.add(
                new Milestone(
                        100,
                        reached100,
                        targetAmount,
                        reached100 ? targetAmount : null,
                        "Goal achieved! Congratulations! 🎉"));

        return milestones;
    }

    /**
     * Check if a new milestone was just reached Compares current progress with previous progress to
     * detect milestone crossing
     */
    public Milestone checkNewMilestoneReached(final GoalTable goal, final BigDecimal previousAmount) {
        if (goal == null || goal.getTargetAmount() == null) {
            return null;
        }

        final BigDecimal currentAmount =
                goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        final BigDecimal targetAmount = goal.getTargetAmount();
        final BigDecimal previousProgress =
                targetAmount.compareTo(BigDecimal.ZERO) > 0
                        ? previousAmount.divide(targetAmount, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
        final BigDecimal currentProgress =
                targetAmount.compareTo(BigDecimal.ZERO) > 0
                        ? currentAmount.divide(targetAmount, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

        // Check each milestone
        if (previousProgress.compareTo(MILESTONE_25) < 0
                && currentProgress.compareTo(MILESTONE_25) >= 0) {
            final BigDecimal milestoneAmount =
                    targetAmount.multiply(MILESTONE_25).setScale(2, RoundingMode.HALF_UP);
            return new Milestone(
                    25,
                    true,
                    milestoneAmount,
                    milestoneAmount,
                    "🎯 You're 25% to your goal! Keep it up!");
        }

        if (previousProgress.compareTo(MILESTONE_50) < 0
                && currentProgress.compareTo(MILESTONE_50) >= 0) {
            final BigDecimal milestoneAmount =
                    targetAmount.multiply(MILESTONE_50).setScale(2, RoundingMode.HALF_UP);
            return new Milestone(
                    50,
                    true,
                    milestoneAmount,
                    milestoneAmount,
                    "💪 Halfway there! You're doing amazing!");
        }

        if (previousProgress.compareTo(MILESTONE_75) < 0
                && currentProgress.compareTo(MILESTONE_75) >= 0) {
            final BigDecimal milestoneAmount =
                    targetAmount.multiply(MILESTONE_75).setScale(2, RoundingMode.HALF_UP);
            return new Milestone(
                    75, true, milestoneAmount, milestoneAmount, "🚀 Almost there! Just 25% to go!");
        }

        if (previousProgress.compareTo(MILESTONE_100) < 0
                && currentProgress.compareTo(MILESTONE_100) >= 0) {
            return new Milestone(
                    100, true, targetAmount, targetAmount, "🎉 Goal achieved! Congratulations!");
        }

        return null; // No new milestone reached
    }

    /** Get the next milestone to reach */
    public Milestone getNextMilestone(final GoalTable goal) {
        final List<Milestone> milestones = getMilestones(goal);
        return milestones.stream().filter(m -> !m.isReached()).findFirst().orElse(null);
    }

    /** Get progress percentage (0-100) */
    public int getProgressPercentage(final GoalTable goal) {
        if (goal == null || goal.getTargetAmount() == null) {
            return 0;
        }

        final BigDecimal currentAmount =
                goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        final BigDecimal targetAmount = goal.getTargetAmount();

        if (targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        final BigDecimal progress =
                currentAmount
                        .divide(targetAmount, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(0, RoundingMode.HALF_UP);

        return Math.min(progress.intValue(), 100);
    }
}
