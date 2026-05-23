package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Financial Goals Recommendation Service
 *
 * <p>Recommends actionable financial goals based on user's financial health and spending patterns.
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass"})
@Service
public class FinancialGoalsRecommendationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(FinancialGoalsRecommendationService.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final GoalRepository goalRepository;
    private final com.budgetbuddy.config.InsightsThresholds thresholds;
    /** Optional — present only when app.goal-suggestions.anthropic.enabled=true. */
    private com.budgetbuddy.service.goal.GoalLlmSuggestionAdvisor llmAdvisor;

    @org.springframework.beans.factory.annotation.Autowired
    public FinancialGoalsRecommendationService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final GoalRepository goalRepository,
            final com.budgetbuddy.config.InsightsThresholds thresholds) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.goalRepository = goalRepository;
        // Defensive default for Mockito @InjectMocks paths.
        this.thresholds = thresholds != null
                ? thresholds
                : new com.budgetbuddy.config.InsightsThresholds();
    }

    /** Backwards-compat constructor for tests; uses default thresholds. */
    public FinancialGoalsRecommendationService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final GoalRepository goalRepository) {
        this(transactionRepository, accountRepository, goalRepository,
                new com.budgetbuddy.config.InsightsThresholds());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLlmAdvisor(final com.budgetbuddy.service.goal.GoalLlmSuggestionAdvisor advisor) {
        this.llmAdvisor = advisor;
    }

    /**
     * G-AI-1: hand the user's spend snapshot to the LLM advisor and
     * return its suggestions. Returns empty list when the advisor isn't
     * wired or when the LLM degrades — never throws, so the caller's
     * fallback path stays simple.
     */
    public List<com.budgetbuddy.service.goal.GoalLlmSuggestionAdvisor.SuggestedGoal>
            suggestGoals(final String userId) {
        if (llmAdvisor == null || userId == null || userId.isEmpty()) return List.of();
        try {
            final FinancialAnalysis a = analyzeFinancialHealth(userId);
            final BigDecimal disposable = a.monthlyIncome.subtract(a.monthlyExpenses);
            return llmAdvisor.suggest(
                    new com.budgetbuddy.service.goal.GoalLlmSuggestionAdvisor.SpendSnapshot(
                            userId,
                            a.monthlyIncome,
                            a.monthlyExpenses,
                            a.liquidAssets,
                            a.totalDebt,
                            disposable.signum() < 0 ? BigDecimal.ZERO : disposable));
        } catch (Exception e) {
            LOGGER.debug("Goal suggestion advisor degraded for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private int emergencyFundMonths() {
        return thresholds.getFinancialGoals().getEmergencyFundMonths();
    }
    private double minSavingsRate() {
        return thresholds.getFinancialGoals().getMinSavingsRate();
    }
    private double idealSavingsRate() {
        return thresholds.getFinancialGoals().getIdealSavingsRate();
    }
    private double wantsBudgetPercent() {
        return thresholds.getFinancialGoals().getWantsBudgetPercent();
    }
    private double debtToIncomeThreshold() {
        return thresholds.getFinancialGoals().getDebtToIncomeThreshold();
    }

    /**
     * Context-aware overload. Reads transactions and accounts from the
     * shared snapshot instead of issuing fresh repo calls. Used by the
     * /summary path; per-endpoint callers continue using the legacy
     * {@code getRecommendations(userId)} method.
     */
    public List<FinancialGoalRecommendation> getRecommendations(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        if (ctx == null) {
            return new ArrayList<>();
        }
        return buildRecommendations(
                ctx.userId(),
                analyzeFinancialHealthFromContext(ctx));
    }

    /** Get financial goal recommendations for a user */
    public List<FinancialGoalRecommendation> getRecommendations(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        return buildRecommendations(userId, analyzeFinancialHealth(userId));
    }

    private List<FinancialGoalRecommendation> buildRecommendations(
            final String userId, final FinancialAnalysis analysis) {

        LOGGER.info("Generating financial goal recommendations for user: {}", userId);

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        // 1. Emergency Fund Goal
        recommendations.addAll(recommendEmergencyFund(userId, analysis));

        // 2. Debt Payoff Goals
        recommendations.addAll(recommendDebtPayoff(userId, analysis));

        // 3. Savings Rate Goals
        recommendations.addAll(recommendSavingsRate(userId, analysis));

        // 4. Wants Budget Goal
        recommendations.addAll(recommendWantsBudget(userId, analysis));

        // 5. Retirement Goals
        recommendations.addAll(recommendRetirement(userId, analysis));

        // Sort by priority
        return recommendations.stream()
                .sorted(
                        Comparator.comparing(
                                FinancialGoalRecommendation::getPriority,
                                Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /**
     * Variant of {@link #analyzeFinancialHealth(String)} that reads
     * from a pre-fetched {@link com.budgetbuddy.service.insights.InsightsContext}
     * — no transaction or account repo calls.
     */
    private FinancialAnalysis analyzeFinancialHealthFromContext(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        final LocalDate cutoff = ctx.asOf().minusDays(90);
        final List<TransactionTable> transactions = ctx.transactions().stream()
                .filter(tx -> tx.getTransactionDate() != null
                        && tx.getTransactionDate().compareTo(cutoff.toString()) >= 0)
                .collect(Collectors.toList());
        return analyzeFromData(transactions, ctx.accounts());
    }

    /** Analyze user's financial health */
    private FinancialAnalysis analyzeFinancialHealth(final String userId) {
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(90); // Last 3 months

        final String startDateStr =
                startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        // Get transactions
        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Get accounts
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);

        return analyzeFromData(transactions, accounts);
    }

    private FinancialAnalysis analyzeFromData(
            final List<TransactionTable> transactions,
            final List<AccountTable> accounts) {

        // Calculate income and expenses
        final BigDecimal monthlyIncome =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                        .map(TransactionTable::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(
                                BigDecimal.valueOf(3),
                                2,
                                RoundingMode.HALF_UP); // Average over 3 months

        final BigDecimal monthlyExpenses =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .map(tx -> tx.getAmount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);

        // Calculate savings
        final BigDecimal monthlySavings = monthlyIncome.subtract(monthlyExpenses);
        final double savingsRate =
                monthlyIncome.compareTo(BigDecimal.ZERO) > 0
                        ? monthlySavings
                                .divide(monthlyIncome, 4, RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;

        // Calculate total liquid assets (checking + savings)
        final BigDecimal liquidAssets =
                accounts.stream()
                        .filter(
                                acc ->
                                        acc.getAccountType() != null
                                                && ("checking".equals(acc.getAccountType())
                                                        || "savings".equals(acc.getAccountType())))
                        .filter(acc -> acc.getBalance() != null)
                        .map(AccountTable::getBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate debt (credit cards, loans)
        final BigDecimal totalDebt =
                accounts.stream()
                        .filter(
                                acc ->
                                        acc.getAccountType() != null
                                                && ("creditCard".equals(acc.getAccountType())
                                                        || "autoLoan".equals(acc.getAccountType())
                                                        || "personalLoan"
                                                                .equals(acc.getAccountType())
                                                        || "studentLoan"
                                                                .equals(acc.getAccountType())
                                                        || "mortgage".equals(acc.getAccountType())))
                        .filter(acc -> acc.getBalance() != null)
                        .map(acc -> acc.getBalance().abs()) // Debt is negative balance
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate debt-to-income ratio
        final double debtToIncome =
                monthlyIncome.compareTo(BigDecimal.ZERO) > 0
                        ? totalDebt
                                .divide(
                                        monthlyIncome.multiply(BigDecimal.valueOf(12)),
                                        4,
                                        RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;

        // Calculate emergency fund adequacy
        final BigDecimal emergencyFundTarget =
                monthlyExpenses.multiply(BigDecimal.valueOf(emergencyFundMonths()));
        final BigDecimal emergencyFundGap = emergencyFundTarget.subtract(liquidAssets);
        final double emergencyFundProgress =
                emergencyFundTarget.compareTo(BigDecimal.ZERO) > 0
                        ? liquidAssets
                                .divide(emergencyFundTarget, 4, RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;

        return new FinancialAnalysis(
                monthlyIncome,
                monthlyExpenses,
                monthlySavings,
                savingsRate,
                liquidAssets,
                totalDebt,
                debtToIncome,
                emergencyFundTarget,
                emergencyFundGap,
                emergencyFundProgress);
    }

    /** Recommend emergency fund goal */
    private List<FinancialGoalRecommendation> recommendEmergencyFund(
            final String userId, final FinancialAnalysis analysis) {

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        if (analysis.emergencyFundProgress < 1.0) {
            final BigDecimal targetAmount = analysis.emergencyFundTarget;
            final BigDecimal currentAmount = analysis.liquidAssets;
            final BigDecimal gap = analysis.emergencyFundGap;

            // Calculate timeline based on savings rate
            int monthsToGoal = 0;
            if (analysis.monthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                monthsToGoal = gap.divide(analysis.monthlySavings, 0, RoundingMode.UP).intValue();
            } else {
                monthsToGoal = 24; // Default to 24 months if no savings
            }

            final LocalDate targetDate = LocalDate.now().plusMonths(Math.min(monthsToGoal, 24));

            final Priority priority =
                    analysis.emergencyFundProgress < 0.5 ? Priority.HIGH : Priority.MEDIUM;

            recommendations.add(
                    new FinancialGoalRecommendation(
                            GoalType.EMERGENCY_FUND,
                            "Build Emergency Fund",
                            String.format(
                                    "Build a %d-month emergency fund to cover unexpected expenses",
                                    emergencyFundMonths()),
                            currentAmount,
                            targetAmount,
                            targetDate,
                            priority,
                            String.format(
                                    "Save $%.2f/month to reach $%.2f emergency fund in %d months",
                                    analysis.monthlySavings.doubleValue(),
                                    targetAmount.doubleValue(),
                                    monthsToGoal),
                            gap));
        }

        return recommendations;
    }

    /** Recommend debt payoff goals */
    private List<FinancialGoalRecommendation> recommendDebtPayoff(
            final String userId, final FinancialAnalysis analysis) {

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        if (analysis.totalDebt.compareTo(BigDecimal.ZERO) > 0) {
            // Check if debt-to-income ratio is high
            if (analysis.debtToIncome > debtToIncomeThreshold()) {
                final Priority priority =
                        analysis.debtToIncome > 0.5 ? Priority.HIGH : Priority.MEDIUM;

                // Calculate payoff timeline
                final BigDecimal monthlyPayment =
                        analysis.monthlyIncome.multiply(BigDecimal.valueOf(0.1)); // 10% of income
                int monthsToPayoff = 0;
                if (monthlyPayment.compareTo(BigDecimal.ZERO) > 0) {
                    monthsToPayoff =
                            analysis.totalDebt
                                    .divide(monthlyPayment, 0, RoundingMode.UP)
                                    .intValue();
                }

                final LocalDate targetDate =
                        LocalDate.now().plusMonths(Math.min(monthsToPayoff, 60)); // Max 5 years

                recommendations.add(
                        new FinancialGoalRecommendation(
                                GoalType.DEBT_PAYOFF,
                                "Pay Off Debt",
                                String.format(
                                        "Reduce debt-to-income ratio from %.1f%% to below 36%%",
                                        analysis.debtToIncome * 100),
                                BigDecimal.ZERO,
                                analysis.totalDebt,
                                targetDate,
                                priority,
                                String.format(
                                        "Pay $%.2f/month to eliminate $%.2f debt in %d months",
                                        monthlyPayment.doubleValue(),
                                        analysis.totalDebt.doubleValue(),
                                        monthsToPayoff),
                                analysis.totalDebt));
            }
        }

        return recommendations;
    }

    /** Recommend savings rate goals */
    private List<FinancialGoalRecommendation> recommendSavingsRate(
            final String userId, final FinancialAnalysis analysis) {

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        if (analysis.savingsRate < idealSavingsRate()) {
            final BigDecimal targetMonthlySavings =
                    analysis.monthlyIncome.multiply(BigDecimal.valueOf(idealSavingsRate()));
            final BigDecimal currentSavings = analysis.monthlySavings;
            final BigDecimal gap = targetMonthlySavings.subtract(currentSavings);

            if (gap.compareTo(BigDecimal.ZERO) > 0) {
                final Priority priority =
                        analysis.savingsRate < minSavingsRate() ? Priority.HIGH : Priority.MEDIUM;

                recommendations.add(
                        new FinancialGoalRecommendation(
                                GoalType.SAVINGS_RATE,
                                "Increase Savings Rate",
                                String.format(
                                        "Increase savings rate from %.1f%% to %.1f%%",
                                        analysis.savingsRate * 100, idealSavingsRate() * 100),
                                currentSavings,
                                targetMonthlySavings,
                                LocalDate.now().plusMonths(6),
                                priority,
                                String.format(
                                        "Save an additional $%.2f/month to reach %.1f%% savings rate",
                                        gap.doubleValue(), idealSavingsRate() * 100),
                                gap));
            }
        }

        return recommendations;
    }

    /** Recommend wants budget goal */
    private List<FinancialGoalRecommendation> recommendWantsBudget(
            final String userId, final FinancialAnalysis analysis) {

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        // Calculate wants budget (20% of income after essentials)
        final BigDecimal essentials =
                analysis.monthlyExpenses.multiply(BigDecimal.valueOf(0.7)); // Assume 70% essentials
        final BigDecimal wantsBudget =
                analysis.monthlyIncome.multiply(BigDecimal.valueOf(wantsBudgetPercent()));
        final BigDecimal remainingAfterEssentials = analysis.monthlyIncome.subtract(essentials);

        if (remainingAfterEssentials.compareTo(BigDecimal.ZERO) > 0) {
            final Priority priority =
                    Priority.LOW; // Lower priority, but good for financial wellness

            recommendations.add(
                    new FinancialGoalRecommendation(
                            GoalType.WANTS_BUDGET,
                            "Allocate Wants Budget",
                            "Set aside 20% of income for guilt-free spending on wants",
                            BigDecimal.ZERO,
                            wantsBudget,
                            LocalDate.now().plusMonths(1),
                            priority,
                            String.format(
                                    "Allocate $%.2f/month for wants (20%% of income). This allows guilt-free spending while maintaining financial health.",
                                    wantsBudget.doubleValue()),
                            wantsBudget));
        }

        return recommendations;
    }

    /** Recommend retirement goals */
    private List<FinancialGoalRecommendation> recommendRetirement(
            final String userId, final FinancialAnalysis analysis) {

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();

        // Check existing retirement goals
        final List<GoalTable> existingGoals = goalRepository.findByUserId(userId);
        final boolean hasRetirementGoal =
                existingGoals.stream()
                        .anyMatch(
                                goal ->
                                        goal.getGoalType() != null
                                                && goal.getGoalType()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("retirement"));

        if (!hasRetirementGoal && analysis.monthlyIncome.compareTo(BigDecimal.ZERO) > 0) {
            // Recommend 15% of income for retirement
            final BigDecimal monthlyRetirementContribution =
                    analysis.monthlyIncome.multiply(BigDecimal.valueOf(0.15));

            // Estimate retirement need (25x annual expenses)
            final BigDecimal annualExpenses =
                    analysis.monthlyExpenses.multiply(BigDecimal.valueOf(12));
            final BigDecimal retirementTarget = annualExpenses.multiply(BigDecimal.valueOf(25));

            final Priority priority = Priority.MEDIUM;

            recommendations.add(
                    new FinancialGoalRecommendation(
                            GoalType.RETIREMENT,
                            "Start Retirement Savings",
                            "Build retirement fund for financial independence",
                            BigDecimal.ZERO,
                            retirementTarget,
                            LocalDate.now().plusYears(30), // 30-year timeline
                            priority,
                            String.format(
                                    "Contribute $%.2f/month (15%% of income) to retirement. Target: $%.2f for financial independence.",
                                    monthlyRetirementContribution.doubleValue(),
                                    retirementTarget.doubleValue()),
                            retirementTarget));
        }

        return recommendations;
    }

    // Model classes

    private static class FinancialAnalysis {
        /* default */ final BigDecimal monthlyIncome;
        /* default */ final BigDecimal monthlyExpenses;
        /* default */ final BigDecimal monthlySavings;
        /* default */ final double savingsRate;
        /* default */ final BigDecimal liquidAssets;
        /* default */ final BigDecimal totalDebt;
        /* default */ final double debtToIncome;
        /* default */ final BigDecimal emergencyFundTarget;
        /* default */ final BigDecimal emergencyFundGap;
        /* default */ final double emergencyFundProgress;

        /* default */ FinancialAnalysis(
                final BigDecimal monthlyIncome,
                final BigDecimal monthlyExpenses,
                final BigDecimal monthlySavings,
                final double savingsRate,
                final BigDecimal liquidAssets,
                final BigDecimal totalDebt,
                final double debtToIncome,
                final BigDecimal emergencyFundTarget,
                final BigDecimal emergencyFundGap,
                final double emergencyFundProgress) {
            this.monthlyIncome = monthlyIncome;
            this.monthlyExpenses = monthlyExpenses;
            this.monthlySavings = monthlySavings;
            this.savingsRate = savingsRate;
            this.liquidAssets = liquidAssets;
            this.totalDebt = totalDebt;
            this.debtToIncome = debtToIncome;
            this.emergencyFundTarget = emergencyFundTarget;
            this.emergencyFundGap = emergencyFundGap;
            this.emergencyFundProgress = emergencyFundProgress;
        }
    }

    public static class FinancialGoalRecommendation {
        private final GoalType type;
        private final String title;
        private final String description;
        private final BigDecimal currentAmount;
        private final BigDecimal targetAmount;
        private final LocalDate targetDate;
        private final Priority priority;
        private final String actionPlan;
        private final BigDecimal gap;

        public FinancialGoalRecommendation(
                final GoalType type,
                final String title,
                final String description,
                final BigDecimal currentAmount,
                final BigDecimal targetAmount,
                final LocalDate targetDate,
                final Priority priority,
                final String actionPlan,
                final BigDecimal gap) {
            this.type = type;
            this.title = title;
            this.description = description;
            this.currentAmount = currentAmount;
            this.targetAmount = targetAmount;
            this.targetDate = targetDate;
            this.priority = priority;
            this.actionPlan = actionPlan;
            this.gap = gap;
        }

        public GoalType getType() {
            return type;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public BigDecimal getCurrentAmount() {
            return currentAmount;
        }

        public BigDecimal getTargetAmount() {
            return targetAmount;
        }

        public LocalDate getTargetDate() {
            return targetDate;
        }

        public Priority getPriority() {
            return priority;
        }

        public String getActionPlan() {
            return actionPlan;
        }

        public BigDecimal getGap() {
            return gap;
        }
    }

    public enum GoalType {
        EMERGENCY_FUND,
        DEBT_PAYOFF,
        SAVINGS_RATE,
        WANTS_BUDGET,
        RETIREMENT,
        INVESTMENT,
        MAJOR_PURCHASE
    }

    public enum Priority {
        LOW(1),
        MEDIUM(2),
        HIGH(3);

        private final int value;

        Priority(final int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
