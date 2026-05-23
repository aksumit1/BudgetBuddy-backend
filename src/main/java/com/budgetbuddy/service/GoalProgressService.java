package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service for automatically calculating and updating goal progress from transactions and account
 * balances
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class GoalProgressService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalProgressService.class);

    private final GoalRepository goalRepository;
    private final GoalService goalService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    /** Optional — set via setter so existing tests that pass 4 args still work. */
    private UserService userService;

    public GoalProgressService(
            final GoalRepository goalRepository,
            final GoalService goalService,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository) {
        this.goalRepository = goalRepository;
        this.goalService = goalService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserService(final UserService userService) {
        this.userService = userService;
    }

    /**
     * Calculate and update progress for a specific goal Uses transactions assigned to the goal and
     * account balances
     */
    public GoalTable calculateAndUpdateProgress(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        final GoalTable goal = goalService.getGoal(user, goalId);
        if (goal.getActive() == null || !goal.getActive()) {
            LOGGER.debug("Goal {} is not active, skipping progress calculation", goalId);
            return goal;
        }

        // Flow 6 / O2: manual goals opt out of server-side recalc. The user is the
        // source of truth for currentAmount; we'd silently stomp their value otherwise.
        if ("manual".equalsIgnoreCase(goal.getProgressMode())) {
            LOGGER.debug("Goal {} is in manual progress mode — skipping recalc", goalId);
            return goal;
        }

        // Soft-deleted goals don't track progress either.
        if (goal.getDeletedAt() != null) {
            LOGGER.debug("Goal {} is soft-deleted — skipping recalc", goalId);
            return goal;
        }

        // Calculate current progress
        final BigDecimal calculatedAmount = calculateCurrentProgress(goal, user.getUserId());

        // Update goal progress if it has changed
        final BigDecimal currentAmount =
                goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        if (calculatedAmount.compareTo(currentAmount) != 0) {
            final BigDecimal difference = calculatedAmount.subtract(currentAmount);
            LOGGER.info(
                    "Updating goal {} progress: {} -> {} (difference: {})",
                    goalId,
                    currentAmount,
                    calculatedAmount,
                    difference);

            // Use GoalService to update progress (which will check for completion)
            return goalService.updateGoalProgress(user, goalId, difference);
        }

        // Even if amount hasn't changed, check completion status
        goalService.getGoal(user, goalId); // This will trigger completion check if needed

        return goal;
    }

    /**
     * Calculate current progress for a goal based on: 1. Transactions explicitly assigned to the
     * goal (via goalId field) 2. Income transactions if goal has no assigned transactions 3.
     * Account balances from goal-associated accounts (or all accounts if none specified)
     */
    private BigDecimal calculateCurrentProgress(final GoalTable goal, final String userId) {
        BigDecimal progress = BigDecimal.ZERO;

        // 1. Sum transactions explicitly assigned to this goal
        // Use efficient GSI query instead of filtering all transactions in memory
        final String goalId = goal.getGoalId();
        final List<TransactionTable> goalTransactions =
                transactionRepository.findByUserIdAndGoalId(userId, goalId).stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                        .collect(Collectors.toList());

        final BigDecimal transactionContributions =
                goalTransactions.stream()
                        .map(TransactionTable::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Goal {} has {} assigned transactions contributing {}",
                    goal.getGoalId(),
                    goalTransactions.size(),
                    transactionContributions);
        }

        // Flow 6 / O13 — the pre-change code fabricated progress when a goal had no
        // tagged transactions ("10% of total income") and no linked accounts
        // ("10% of total balance"). Users saw phantom progress they hadn't earned.
        // New contract: progress comes from real tagged transactions + real linked
        // account balances. If there's nothing to credit, progress is zero — the UI
        // will prompt the user to tag a transaction or link an account.

        progress = progress.add(transactionContributions);

        // 2. Linked-account balances (if any). Soft-deleted accounts must NOT contribute —
        // their balance lingers in DynamoDB but represents data the user has marked for
        // removal; including it inflates the goal progress UI with phantom funding.
        BigDecimal accountContribution = BigDecimal.ZERO;
        if (goal.getAccountIds() != null && !goal.getAccountIds().isEmpty()) {
            final List<AccountTable> accounts =
                    goal.getAccountIds().stream()
                            .map(accountId -> accountRepository.findById(accountId))
                            .filter(java.util.Optional::isPresent)
                            .map(java.util.Optional::get)
                            .filter(acc -> acc.getDeletedAt() == null)
                            .collect(Collectors.toList());
            accountContribution =
                    accounts.stream()
                            .filter(
                                    acc ->
                                            acc.getBalance() != null
                                                    && acc.getBalance().compareTo(BigDecimal.ZERO)
                                                            > 0)
                            .map(AccountTable::getBalance)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Goal {} linked-account contribution: {}",
                        goal.getGoalId(),
                        accountContribution);
            }
        }

        progress = progress.add(accountContribution);

        // Cap progress at target amount
        if (goal.getTargetAmount() != null && progress.compareTo(goal.getTargetAmount()) > 0) {
            progress = goal.getTargetAmount();
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Goal {} calculated progress: {} (transactions: {}, accounts: {})",
                    goal.getGoalId(),
                    progress,
                    transactionContributions,
                    accountContribution);
        }

        return progress.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Recalculate progress for all active goals for a user Can be called periodically or after bulk
     * transaction imports
     */
    @Async
    public void recalculateAllGoals(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            LOGGER.warn("Cannot recalculate goals: user is null or invalid");
            return;
        }

        try {
            final List<GoalTable> activeGoals =
                    goalRepository.findByUserId(user.getUserId()).stream()
                            .filter(goal -> goal.getActive() != null && goal.getActive())
                            .collect(Collectors.toList());

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Recalculating progress for {} active goals for user {}",
                        activeGoals.size(),
                        user.getUserId());
            }

            for (final GoalTable goal : activeGoals) {
                try {
                    calculateAndUpdateProgress(user, goal.getGoalId());
                } catch (Exception e) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Error recalculating progress for goal {}: {}",
                                goal.getGoalId(),
                                e.getMessage(),
                                e);
                    }
                }
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Completed recalculating progress for {} goals", activeGoals.size());
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error recalculating goals for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
        }
    }

    /** Recalculate progress for a goal when a transaction is assigned/unassigned */
    public void onTransactionGoalAssignmentChanged(final String userId, final String goalId) {
        if (userId == null || userId.isEmpty() || goalId == null || goalId.isEmpty()) {
            return;
        }

        try {
            // G-BUG-2: never fabricate a partial UserTable. Fetch the
            // real one when UserService is wired (production); fall back
            // to a userId-only stub only in unit-test contexts where the
            // user service isn't injected, and log it so any production
            // miswiring surfaces in logs rather than silently masking.
            final UserTable user;
            if (userService != null) {
                final java.util.Optional<UserTable> resolved = userService.findById(userId);
                if (resolved.isEmpty()) {
                    LOGGER.warn(
                            "Skipping goal progress recalc — user {} not found", userId);
                    return;
                }
                user = resolved.get();
            } else {
                LOGGER.warn(
                        "UserService not wired into GoalProgressService — falling back to "
                                + "userId-only UserTable for goal {}. Expected only in tests.",
                        goalId);
                user = new UserTable();
                user.setUserId(userId);
            }

            calculateAndUpdateProgress(user, goalId);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error recalculating goal {} after transaction assignment change: {}",
                        goalId,
                        e.getMessage(),
                        e);
            }
        }
    }
}
