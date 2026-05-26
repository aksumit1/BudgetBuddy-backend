package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for goal analytics and predictions Provides "Time to Goal" calculations, on-track status,
 * and contribution recommendations
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Service
public class GoalAnalyticsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalAnalyticsService.class);

    private final TransactionRepository transactionRepository;

    public GoalAnalyticsService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** Goal projection data */
    public static class GoalProjection {
        private final LocalDate projectedCompletionDate;
        private final BigDecimal averageMonthlyContribution;
        private final String onTrackStatus; // "ON_TRACK", "BEHIND_SCHEDULE", "AHEAD_OF_SCHEDULE"
        private final BigDecimal recommendedMonthlyContribution;
        private final int monthsRemaining;
        private final String message;
        /** G-OPP-1: p50 / p90 confidence-interval bands and the EMA-weighted rate. */
        private LocalDate projectedCompletionDateP50;
        private LocalDate projectedCompletionDateP90;
        private BigDecimal emaMonthlyContribution;
        private String trend; // "ACCELERATING" | "STEADY" | "DECELERATING"

        public GoalProjection(
                final LocalDate projectedCompletionDate,
                final BigDecimal averageMonthlyContribution,
                final String onTrackStatus,
                final BigDecimal recommendedMonthlyContribution,
                final int monthsRemaining,
                final String message) {
            this.projectedCompletionDate = projectedCompletionDate;
            this.averageMonthlyContribution = averageMonthlyContribution;
            this.onTrackStatus = onTrackStatus;
            this.recommendedMonthlyContribution = recommendedMonthlyContribution;
            this.monthsRemaining = monthsRemaining;
            this.message = message;
        }

        public LocalDate getProjectedCompletionDate() {
            return projectedCompletionDate;
        }

        public BigDecimal getAverageMonthlyContribution() {
            return averageMonthlyContribution;
        }

        public String getOnTrackStatus() {
            return onTrackStatus;
        }

        public BigDecimal getRecommendedMonthlyContribution() {
            return recommendedMonthlyContribution;
        }

        public int getMonthsRemaining() {
            return monthsRemaining;
        }

        public String getMessage() {
            return message;
        }

        public LocalDate getProjectedCompletionDateP50() {
            return projectedCompletionDateP50;
        }

        public LocalDate getProjectedCompletionDateP90() {
            return projectedCompletionDateP90;
        }

        public BigDecimal getEmaMonthlyContribution() {
            return emaMonthlyContribution;
        }

        public String getTrend() {
            return trend;
        }

        void attachForecastBands(
                final LocalDate p50,
                final LocalDate p90,
                final BigDecimal emaRate,
                final String trend) {
            this.projectedCompletionDateP50 = p50;
            this.projectedCompletionDateP90 = p90;
            this.emaMonthlyContribution = emaRate;
            this.trend = trend;
        }
    }

    /** Calculate goal projection based on current contribution patterns */
    public GoalProjection calculateProjection(final GoalTable goal, final String userId) {
        if (goal == null || goal.getTargetAmount() == null || goal.getTargetDate() == null) {
            return null;
        }

        // Parse target date from string
        final LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(goal.getTargetDate());
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Invalid target date format for goal {}: {}",
                        goal.getGoalId(),
                        goal.getTargetDate());
            }
            return null;
        }

        final BigDecimal currentAmount =
                goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        final BigDecimal targetAmount = goal.getTargetAmount();
        final BigDecimal remainingAmount = targetAmount.subtract(currentAmount);

        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // Goal already reached
            return new GoalProjection(
                    LocalDate.now(),
                    BigDecimal.ZERO,
                    "COMPLETED",
                    BigDecimal.ZERO,
                    0,
                    "🎉 Goal already achieved!");
        }

        // Get transactions assigned to this goal from last 3 months
        final LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);
        final List<TransactionTable> recentTransactions =
                transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId()).stream()
                        .filter(
                                tx -> {
                                    if (tx.getTransactionDate() == null) {
                                        return false;
                                    }
                                    try {
                                        final LocalDate txDate =
                                                LocalDate.parse(tx.getTransactionDate());
                                        return txDate.isAfter(threeMonthsAgo)
                                                && txDate.isBefore(LocalDate.now().plusDays(1));
                                    } catch (Exception e) {
                                        return false;
                                    }
                                })
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                        .toList();

        // Calculate average monthly contribution
        final BigDecimal totalContributions =
                recentTransactions.stream()
                        .map(TransactionTable::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // G-RISK-2: divide by the actual span of contributions, not a
        // hardcoded 3. A user who started saving 4 weeks ago shouldn't have
        // their pace deflated to a third of reality. Span is rounded UP to
        // the next whole month so a 6-week run still divides by 2 months,
        // matching how users mentally bucket "how much per month."
        final BigDecimal averageMonthlyContribution;
        if (recentTransactions.isEmpty()) {
            averageMonthlyContribution = BigDecimal.ZERO;
        } else {
            final LocalDate earliest =
                    recentTransactions.stream()
                            .map(TransactionTable::getTransactionDate)
                            .filter(java.util.Objects::nonNull)
                            .map(
                                    d -> {
                                        try {
                                            return LocalDate.parse(d);
                                        } catch (Exception ex) {
                                            return null;
                                        }
                                    })
                            .filter(java.util.Objects::nonNull)
                            .min(LocalDate::compareTo)
                            .orElse(threeMonthsAgo);
            final long days = Math.max(1, ChronoUnit.DAYS.between(earliest, LocalDate.now()) + 1);
            // Round up to the next whole month, clamped to the 3-month window
            // we actually pulled. Floor at 1 month to avoid division by tiny
            // fractions that would inflate the projected monthly rate.
            final long months = Math.max(1, Math.min(3, (days + 29) / 30));
            averageMonthlyContribution =
                    totalContributions.divide(
                            BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }

        // Calculate projected completion date
        final LocalDate projectedDate;
        final int monthsRemaining;
        String onTrackStatus;
        final BigDecimal recommendedMonthlyContribution;
        String message;

        if (averageMonthlyContribution.compareTo(BigDecimal.ZERO) > 0) {
            // Calculate months needed at current rate
            final BigDecimal monthsNeeded =
                    remainingAmount.divide(averageMonthlyContribution, 2, RoundingMode.HALF_UP);
            monthsRemaining = Math.max(1, monthsNeeded.intValue());
            projectedDate = LocalDate.now().plusMonths(monthsRemaining);

            // Calculate months until target date
            final long monthsUntilTarget = ChronoUnit.MONTHS.between(LocalDate.now(), targetDate);

            // Determine on-track status
            if (monthsRemaining <= monthsUntilTarget) {
                onTrackStatus = "ON_TRACK";
                recommendedMonthlyContribution = averageMonthlyContribution;
                message =
                        String.format(
                                "At this rate, you'll reach your goal in %d months! 🎯",
                                monthsRemaining);
            } else {
                onTrackStatus = "BEHIND_SCHEDULE";
                // Calculate required monthly contribution to meet target date
                recommendedMonthlyContribution =
                        monthsUntilTarget > 0
                                ? remainingAmount.divide(
                                        new BigDecimal(monthsUntilTarget), 2, RoundingMode.HALF_UP)
                                : remainingAmount;
                message =
                        String.format(
                                "Increase monthly savings by $%s to meet your deadline 💪",
                                recommendedMonthlyContribution
                                        .subtract(averageMonthlyContribution)
                                        .setScale(2, RoundingMode.HALF_UP));
            }
        } else {
            // No contributions yet - calculate based on target date
            final long monthsUntilTarget = ChronoUnit.MONTHS.between(LocalDate.now(), targetDate);
            monthsRemaining = (int) Math.max(1, monthsUntilTarget);
            projectedDate = targetDate;
            onTrackStatus = "NO_CONTRIBUTIONS";
            recommendedMonthlyContribution =
                    monthsUntilTarget > 0
                            ? remainingAmount.divide(
                                    new BigDecimal(monthsUntilTarget), 2, RoundingMode.HALF_UP)
                            : remainingAmount;
            message =
                    String.format(
                            "Start saving $%s per month to reach your goal on time 📅",
                            recommendedMonthlyContribution.setScale(2, RoundingMode.HALF_UP));
        }

        // Check if ahead of schedule
        if (averageMonthlyContribution.compareTo(BigDecimal.ZERO) > 0
                && monthsRemaining < ChronoUnit.MONTHS.between(LocalDate.now(), targetDate)) {
            onTrackStatus = "AHEAD_OF_SCHEDULE";
            message =
                    String.format(
                            "You're ahead of schedule! Goal will be reached %d months early! 🚀",
                            (int)
                                    (ChronoUnit.MONTHS.between(LocalDate.now(), targetDate)
                                            - monthsRemaining));
        }

        final GoalProjection projection =
                new GoalProjection(
                        projectedDate,
                        averageMonthlyContribution,
                        onTrackStatus,
                        recommendedMonthlyContribution,
                        monthsRemaining,
                        message);

        // G-OPP-1: EMA-weighted contribution rate + p50/p90 ETA bands.
        // p50 uses the EMA rate; p90 uses one-stddev pessimism. Trend label
        // compares the EMA rate to the flat average to surface acceleration.
        attachForecastBands(projection, recentTransactions, remainingAmount, averageMonthlyContribution);
        return projection;
    }

    /**
     * Per-month aggregation → exponentially-weighted recent-bias rate +
     * standard-deviation-based pessimistic band. Skips silently if there
     * aren't at least two months of data.
     */
    private static void attachForecastBands(
            final GoalProjection projection,
            final List<TransactionTable> recentTransactions,
            final BigDecimal remainingAmount,
            final BigDecimal flatAvg) {
        if (recentTransactions.size() < 2) return;
        final java.util.Map<String, BigDecimal> perMonth = new java.util.LinkedHashMap<>();
        for (final TransactionTable t : recentTransactions) {
            if (t.getTransactionDate() == null) continue;
            try {
                final LocalDate d = LocalDate.parse(t.getTransactionDate());
                final String key = String.format("%04d-%02d", d.getYear(), d.getMonthValue());
                perMonth.merge(key, t.getAmount(), BigDecimal::add);
            } catch (java.time.format.DateTimeParseException ignored) {
                // skip
            }
        }
        if (perMonth.size() < 2) return;

        // Older → newer. EMA with alpha=0.5 — recent months count double.
        final java.util.List<BigDecimal> ordered =
                perMonth.values().stream()
                        .map(v -> v == null ? BigDecimal.ZERO : v)
                        .toList();
        final java.util.List<BigDecimal> oldestFirst = new java.util.ArrayList<>(ordered);
        // perMonth is insertion-ordered by parse sequence; sort the keys
        // explicitly to be safe in case data is out-of-order.
        oldestFirst.clear();
        new java.util.TreeMap<>(perMonth).values().forEach(oldestFirst::add);

        final double alpha = 0.5;
        double ema = oldestFirst.get(0).doubleValue();
        for (int i = 1; i < oldestFirst.size(); i++) {
            ema = alpha * oldestFirst.get(i).doubleValue() + (1 - alpha) * ema;
        }
        final BigDecimal emaRate = BigDecimal.valueOf(ema).setScale(2, RoundingMode.HALF_UP);

        // Sample std-dev for pessimistic band.
        final double mean =
                oldestFirst.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        final double variance =
                oldestFirst.stream()
                                .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                                .sum()
                        / Math.max(1, oldestFirst.size() - 1);
        final double stddev = Math.sqrt(variance);
        // p90 assumes the slower side of one stddev. Floor at 1 month/$1 to
        // avoid runaway timelines on zero/near-zero rates.
        final double p90Rate = Math.max(1.0, ema - stddev);

        if (ema > 0) {
            final long p50Months = Math.max(1, (long) Math.ceil(remainingAmount.doubleValue() / ema));
            final long p90Months =
                    Math.max(p50Months, (long) Math.ceil(remainingAmount.doubleValue() / p90Rate));
            final LocalDate p50 = LocalDate.now().plusMonths(p50Months);
            final LocalDate p90 = LocalDate.now().plusMonths(p90Months);

            final String trend;
            final double flatAvgD = flatAvg == null ? 0.0 : flatAvg.doubleValue();
            if (flatAvgD <= 0 || Math.abs(ema - flatAvgD) / Math.max(1, flatAvgD) < 0.10) {
                trend = "STEADY";
            } else if (ema > flatAvgD) {
                trend = "ACCELERATING";
            } else {
                trend = "DECELERATING";
            }
            projection.attachForecastBands(p50, p90, emaRate, trend);
        }
    }

    /** Get contribution insights */
    public static class ContributionInsights {
        private final BigDecimal totalContributions;
        private final BigDecimal averageContribution;
        private final BigDecimal largestContribution;
        private final int contributionCount;
        private final String bestContributionSource; // "ROUND_UP", "MANUAL", "INCOME"
        /** G-OPP-2: per-source breakdown so callers can render a chart. */
        private java.util.Map<String, BigDecimal> contributionsBySource;

        public ContributionInsights(
                final BigDecimal totalContributions,
                final BigDecimal averageContribution,
                final BigDecimal largestContribution,
                final int contributionCount,
                final String bestContributionSource) {
            this.totalContributions = totalContributions;
            this.averageContribution = averageContribution;
            this.largestContribution = largestContribution;
            this.contributionCount = contributionCount;
            this.bestContributionSource = bestContributionSource;
        }

        public BigDecimal getTotalContributions() {
            return totalContributions;
        }

        public BigDecimal getAverageContribution() {
            return averageContribution;
        }

        public BigDecimal getLargestContribution() {
            return largestContribution;
        }

        public int getContributionCount() {
            return contributionCount;
        }

        public String getBestContributionSource() {
            return bestContributionSource;
        }

        public java.util.Map<String, BigDecimal> getContributionsBySource() {
            return contributionsBySource;
        }

        void setContributionsBySource(final java.util.Map<String, BigDecimal> v) {
            this.contributionsBySource = v;
        }
    }

    /** Calculate contribution insights */
    public ContributionInsights getContributionInsights(final GoalTable goal, final String userId) {
        final List<TransactionTable> goalTransactions =
                transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId()).stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                        .toList();

        if (goalTransactions.isEmpty()) {
            final ContributionInsights empty =
                    new ContributionInsights(
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "NONE");
            empty.setContributionsBySource(java.util.Map.of());
            return empty;
        }

        final BigDecimal total =
                goalTransactions.stream()
                        .map(TransactionTable::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal average =
                total.divide(new BigDecimal(goalTransactions.size()), 2, RoundingMode.HALF_UP);

        final BigDecimal largest =
                goalTransactions.stream()
                        .map(TransactionTable::getAmount)
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);

        // G-OPP-2: bucket each contribution by source so the UI can show
        // "round-ups: $43, manual: $200, recurring: $500." Round-ups are
        // identified by the same stamp GoalRoundUpService writes. Recurring
        // is heuristically detected by checking the source-transaction's
        // category for income markers. Everything else falls into MANUAL.
        final java.util.Map<String, BigDecimal> bySource = new java.util.LinkedHashMap<>();
        for (final TransactionTable tx : goalTransactions) {
            final String src = classifyContributionSource(tx);
            bySource.merge(src, tx.getAmount(), BigDecimal::add);
        }

        // Best source = the one that contributed the most.
        final String bestSource =
                bySource.entrySet().stream()
                        .max(java.util.Map.Entry.comparingByValue())
                        .map(java.util.Map.Entry::getKey)
                        .orElse("MANUAL");

        final ContributionInsights insights =
                new ContributionInsights(
                        total, average, largest, goalTransactions.size(), bestSource);
        insights.setContributionsBySource(bySource);
        return insights;
    }

    private static String classifyContributionSource(final TransactionTable tx) {
        if (tx.getRoundUpSourceTransactionId() != null) return "ROUND_UP";
        final String cat =
                tx.getCategoryPrimary() != null
                        ? tx.getCategoryPrimary().toLowerCase(java.util.Locale.ROOT)
                        : "";
        if (cat.contains("income") || cat.contains("salary") || cat.contains("interest")) {
            return "INCOME";
        }
        final String detailed =
                tx.getCategoryDetailed() != null
                        ? tx.getCategoryDetailed().toLowerCase(java.util.Locale.ROOT)
                        : "";
        if (detailed.contains("transfer") || detailed.contains("recurring")) {
            return "RECURRING_TRANSFER";
        }
        return "MANUAL";
    }
}
