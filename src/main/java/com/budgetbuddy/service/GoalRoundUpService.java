package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for round-up transactions feature Automatically rounds up transactions and contributes
 * difference to goals
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Service
public class GoalRoundUpService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalRoundUpService.class);

    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;
    private final GoalProgressService goalProgressService;
    private final TransactionService transactionService;

    public GoalRoundUpService(
            final GoalRepository goalRepository,
            final TransactionRepository transactionRepository,
            final GoalProgressService goalProgressService,
            final TransactionService transactionService) {
        this.goalRepository = goalRepository;
        this.transactionRepository = transactionRepository;
        this.goalProgressService = goalProgressService;
        this.transactionService = transactionService;
    }

    /** Calculate round-up amount for a transaction Rounds up to nearest dollar */
    public BigDecimal calculateRoundUp(final BigDecimal transactionAmount) {
        if (transactionAmount == null || transactionAmount.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO; // Only round up expenses (negative amounts)
        }

        // Convert to positive for calculation
        final BigDecimal positiveAmount = transactionAmount.abs();

        // Round up to nearest dollar
        final BigDecimal roundedUp = positiveAmount.setScale(0, RoundingMode.UP);

        // Calculate difference
        final BigDecimal roundUpAmount = roundedUp.subtract(positiveAmount);

        return roundUpAmount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Flow 6 / O6 — finish the round-up loop. Given an expense transaction and the target goal,
     * check for idempotency, then create a matching positive contribution transaction tagged to the
     * goal.
     *
     * <p>Idempotent. The generated contribution has a deterministic id derived from {@code
     * sourceTxId + goalId}, so a second call with the same inputs overwrites its previous self
     * rather than double-funding.
     */
    public void processRoundUp(final TransactionTable transaction, final String goalId) {
        if (transaction == null || goalId == null || goalId.isEmpty()) {
            return;
        }
        if (transaction.getAmount() == null
                || transaction.getAmount().compareTo(BigDecimal.ZERO) >= 0) {
            return;
        }
        if (transaction.getTransactionId() == null) {
            return;
        }
        // Don't round-up round-ups. Avoids recursion on a badly-looped ingest.
        if (transaction.getRoundUpSourceTransactionId() != null) {
            return;
        }

        final Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            LOGGER.warn("Goal {} not found for round-up", goalId);
            return;
        }
        final GoalTable goal = goalOpt.get();
        if (goal.getRoundUpEnabled() == null || !goal.getRoundUpEnabled()) {
            return;
        }
        if (goal.getDeletedAt() != null) {
            return;
        }
        if ("manual".equalsIgnoreCase(goal.getProgressMode())) {
            return;
        }

        final BigDecimal roundUpAmount = calculateRoundUp(transaction.getAmount());
        if (roundUpAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Deterministic id: if we've already generated a contribution for this source
        // + goal pair, save() overwrites the existing row with the same fields rather
        // than creating a duplicate.
        final String contributionId =
                java.util
                        .UUID
                        .nameUUIDFromBytes(
                                ("roundup:" + transaction.getTransactionId() + ":" + goalId)
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();

        // Bail if the contribution already exists with the same amount — this is the
        // common case on re-ingest of an unchanged transaction.
        final Optional<TransactionTable> existing = transactionRepository.findById(contributionId);
        if (existing.isPresent()
                && existing.get().getAmount() != null
                && existing.get().getAmount().compareTo(roundUpAmount) == 0) {
            return;
        }

        final TransactionTable contribution = new TransactionTable();
        contribution.setTransactionId(contributionId);
        contribution.setUserId(transaction.getUserId());
        contribution.setAccountId(transaction.getAccountId());
        contribution.setAmount(roundUpAmount);
        contribution.setDescription(
                "Round-up to " + (goal.getName() == null ? "goal" : goal.getName()));
        contribution.setMerchantName("BudgetBuddy Round-up");
        contribution.setCategoryPrimary("savings");
        contribution.setCategoryDetailed("round_up");
        contribution.setTransactionDate(transaction.getTransactionDate());
        contribution.setGoalId(goalId);
        contribution.setRoundUpSourceTransactionId(transaction.getTransactionId());
        contribution.setCreatedAt(java.time.Instant.now());
        contribution.setUpdatedAt(java.time.Instant.now());
        transactionRepository.save(contribution);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Created round-up contribution {} (${}) for source {} → goal {}",
                    contributionId,
                    roundUpAmount,
                    transaction.getTransactionId(),
                    goalId);
        }
    }

    /** Get total round-up contributions for a goal in a time period */
    public BigDecimal getRoundUpTotal(final GoalTable goal, final String userId, final int days) {
        // Get all transactions assigned to goal
        transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId());

        // Calculate total round-ups (simplified - would need to track round-up transactions
        // separately)
        // For now, return zero - actual implementation would sum round-up contributions
        return BigDecimal.ZERO;
    }

    /** Enable round-up for a goal */
    public void enableRoundUp(final String goalId) {
        final Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            throw new IllegalArgumentException("Goal not found: " + goalId);
        }

        final GoalTable goal = goalOpt.get();
        goal.setRoundUpEnabled(true);
        goalRepository.save(goal);
        LOGGER.info("Round-up enabled for goal: {}", goalId);
    }

    /** Disable round-up for a goal */
    public void disableRoundUp(final String goalId) {
        final Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            throw new IllegalArgumentException("Goal not found: " + goalId);
        }

        final GoalTable goal = goalOpt.get();
        goal.setRoundUpEnabled(false);
        goalRepository.save(goal);
        LOGGER.info("Round-up disabled for goal: {}", goalId);
    }
}
