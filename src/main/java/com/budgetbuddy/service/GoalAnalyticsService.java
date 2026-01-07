package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for goal analytics and predictions
 * Provides "Time to Goal" calculations, on-track status, and contribution recommendations
 */
@Service
public class GoalAnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(GoalAnalyticsService.class);

    private final TransactionRepository transactionRepository;

    public GoalAnalyticsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Goal projection data
     */
    public static class GoalProjection {
        private final LocalDate projectedCompletionDate;
        private final BigDecimal averageMonthlyContribution;
        private final String onTrackStatus; // "ON_TRACK", "BEHIND_SCHEDULE", "AHEAD_OF_SCHEDULE"
        private final BigDecimal recommendedMonthlyContribution;
        private final int monthsRemaining;
        private final String message;

        public GoalProjection(LocalDate projectedCompletionDate, BigDecimal averageMonthlyContribution,
                             String onTrackStatus, BigDecimal recommendedMonthlyContribution,
                             int monthsRemaining, String message) {
            this.projectedCompletionDate = projectedCompletionDate;
            this.averageMonthlyContribution = averageMonthlyContribution;
            this.onTrackStatus = onTrackStatus;
            this.recommendedMonthlyContribution = recommendedMonthlyContribution;
            this.monthsRemaining = monthsRemaining;
            this.message = message;
        }

        public LocalDate getProjectedCompletionDate() { return projectedCompletionDate; }
        public BigDecimal getAverageMonthlyContribution() { return averageMonthlyContribution; }
        public String getOnTrackStatus() { return onTrackStatus; }
        public BigDecimal getRecommendedMonthlyContribution() { return recommendedMonthlyContribution; }
        public int getMonthsRemaining() { return monthsRemaining; }
        public String getMessage() { return message; }
    }

    /**
     * Calculate goal projection based on current contribution patterns
     */
    public GoalProjection calculateProjection(GoalTable goal, String userId) {
        if (goal == null || goal.getTargetAmount() == null || goal.getTargetDate() == null) {
            return null;
        }

        // Parse target date from string
        LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(goal.getTargetDate());
        } catch (Exception e) {
            logger.warn("Invalid target date format for goal {}: {}", goal.getGoalId(), goal.getTargetDate());
            return null;
        }

        BigDecimal currentAmount = goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        BigDecimal targetAmount = goal.getTargetAmount();
        BigDecimal remainingAmount = targetAmount.subtract(currentAmount);

        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // Goal already reached
            return new GoalProjection(
                LocalDate.now(),
                BigDecimal.ZERO,
                "COMPLETED",
                BigDecimal.ZERO,
                0,
                "🎉 Goal already achieved!"
            );
        }

        // Get transactions assigned to this goal from last 3 months
        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);
        List<TransactionTable> recentTransactions = transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId())
            .stream()
            .filter(tx -> {
                if (tx.getTransactionDate() == null) return false;
                try {
                    LocalDate txDate = LocalDate.parse(tx.getTransactionDate());
                    return txDate.isAfter(threeMonthsAgo) && txDate.isBefore(LocalDate.now().plusDays(1));
                } catch (Exception e) {
                    return false;
                }
            })
            .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());

        // Calculate average monthly contribution
        BigDecimal totalContributions = recentTransactions.stream()
            .map(TransactionTable::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageMonthlyContribution = recentTransactions.isEmpty()
            ? BigDecimal.ZERO
            : totalContributions.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

        // Calculate projected completion date
        LocalDate projectedDate;
        int monthsRemaining;
        String onTrackStatus;
        BigDecimal recommendedMonthlyContribution;
        String message;

        if (averageMonthlyContribution.compareTo(BigDecimal.ZERO) > 0) {
            // Calculate months needed at current rate
            BigDecimal monthsNeeded = remainingAmount.divide(averageMonthlyContribution, 2, RoundingMode.HALF_UP);
            monthsRemaining = Math.max(1, monthsNeeded.intValue());
            projectedDate = LocalDate.now().plusMonths(monthsRemaining);

            // Calculate months until target date
            long monthsUntilTarget = ChronoUnit.MONTHS.between(LocalDate.now(), targetDate);

            // Determine on-track status
            if (monthsRemaining <= monthsUntilTarget) {
                onTrackStatus = "ON_TRACK";
                recommendedMonthlyContribution = averageMonthlyContribution;
                message = String.format("At this rate, you'll reach your goal in %d months! 🎯", monthsRemaining);
            } else {
                onTrackStatus = "BEHIND_SCHEDULE";
                // Calculate required monthly contribution to meet target date
                recommendedMonthlyContribution = monthsUntilTarget > 0
                    ? remainingAmount.divide(new BigDecimal(monthsUntilTarget), 2, RoundingMode.HALF_UP)
                    : remainingAmount;
                message = String.format("Increase monthly savings by $%s to meet your deadline 💪",
                    recommendedMonthlyContribution.subtract(averageMonthlyContribution).setScale(2, RoundingMode.HALF_UP));
            }
        } else {
            // No contributions yet - calculate based on target date
            long monthsUntilTarget = ChronoUnit.MONTHS.between(LocalDate.now(), targetDate);
            monthsRemaining = (int) Math.max(1, monthsUntilTarget);
            projectedDate = targetDate;
            onTrackStatus = "NO_CONTRIBUTIONS";
            recommendedMonthlyContribution = monthsUntilTarget > 0
                ? remainingAmount.divide(new BigDecimal(monthsUntilTarget), 2, RoundingMode.HALF_UP)
                : remainingAmount;
            message = String.format("Start saving $%s per month to reach your goal on time 📅",
                recommendedMonthlyContribution.setScale(2, RoundingMode.HALF_UP));
        }

        // Check if ahead of schedule
        if (averageMonthlyContribution.compareTo(BigDecimal.ZERO) > 0 && monthsRemaining < ChronoUnit.MONTHS.between(LocalDate.now(), targetDate)) {
            onTrackStatus = "AHEAD_OF_SCHEDULE";
            message = String.format("You're ahead of schedule! Goal will be reached %d months early! 🚀",
                (int) (ChronoUnit.MONTHS.between(LocalDate.now(), targetDate) - monthsRemaining));
        }

        return new GoalProjection(
            projectedDate,
            averageMonthlyContribution,
            onTrackStatus,
            recommendedMonthlyContribution,
            monthsRemaining,
            message
        );
    }

    /**
     * Get contribution insights
     */
    public static class ContributionInsights {
        private final BigDecimal totalContributions;
        private final BigDecimal averageContribution;
        private final BigDecimal largestContribution;
        private final int contributionCount;
        private final String bestContributionSource; // "ROUND_UP", "MANUAL", "INCOME"

        public ContributionInsights(BigDecimal totalContributions, BigDecimal averageContribution,
                                   BigDecimal largestContribution, int contributionCount,
                                   String bestContributionSource) {
            this.totalContributions = totalContributions;
            this.averageContribution = averageContribution;
            this.largestContribution = largestContribution;
            this.contributionCount = contributionCount;
            this.bestContributionSource = bestContributionSource;
        }

        public BigDecimal getTotalContributions() { return totalContributions; }
        public BigDecimal getAverageContribution() { return averageContribution; }
        public BigDecimal getLargestContribution() { return largestContribution; }
        public int getContributionCount() { return contributionCount; }
        public String getBestContributionSource() { return bestContributionSource; }
    }

    /**
     * Calculate contribution insights
     */
    public ContributionInsights getContributionInsights(GoalTable goal, String userId) {
        List<TransactionTable> goalTransactions = transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId())
            .stream()
            .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());

        if (goalTransactions.isEmpty()) {
            return new ContributionInsights(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "NONE"
            );
        }

        BigDecimal total = goalTransactions.stream()
            .map(TransactionTable::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal average = total.divide(new BigDecimal(goalTransactions.size()), 2, RoundingMode.HALF_UP);

        BigDecimal largest = goalTransactions.stream()
            .map(TransactionTable::getAmount)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        // Determine best contribution source (simplified - can be enhanced)
        String bestSource = "MANUAL"; // Default
        // Could analyze transaction descriptions to detect round-ups, income, etc.

        return new ContributionInsights(total, average, largest, goalTransactions.size(), bestSource);
    }
}

