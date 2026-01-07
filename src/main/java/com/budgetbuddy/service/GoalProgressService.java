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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for automatically calculating and updating goal progress from transactions
 * and account balances
 */
@Service
public class GoalProgressService {

    private static final Logger logger = LoggerFactory.getLogger(GoalProgressService.class);

    private final GoalRepository goalRepository;
    private final GoalService goalService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

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

    /**
     * Calculate and update progress for a specific goal
     * Uses transactions assigned to the goal and account balances
     */
    public GoalTable calculateAndUpdateProgress(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        GoalTable goal = goalService.getGoal(user, goalId);
        if (goal.getActive() == null || !goal.getActive()) {
            logger.debug("Goal {} is not active, skipping progress calculation", goalId);
            return goal;
        }

        // Calculate current progress
        BigDecimal calculatedAmount = calculateCurrentProgress(goal, user.getUserId());

        // Update goal progress if it has changed
        BigDecimal currentAmount = goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        if (calculatedAmount.compareTo(currentAmount) != 0) {
            BigDecimal difference = calculatedAmount.subtract(currentAmount);
            logger.info("Updating goal {} progress: {} -> {} (difference: {})", 
                    goalId, currentAmount, calculatedAmount, difference);
            
            // Use GoalService to update progress (which will check for completion)
            return goalService.updateGoalProgress(user, goalId, difference);
        }

        // Even if amount hasn't changed, check completion status
        goalService.getGoal(user, goalId); // This will trigger completion check if needed
        
        return goal;
    }

    /**
     * Calculate current progress for a goal based on:
     * 1. Transactions explicitly assigned to the goal (via goalId field)
     * 2. Income transactions if goal has no assigned transactions
     * 3. Account balances from goal-associated accounts (or all accounts if none specified)
     */
    private BigDecimal calculateCurrentProgress(final GoalTable goal, final String userId) {
        BigDecimal progress = BigDecimal.ZERO;

        // 1. Sum transactions explicitly assigned to this goal
        // Use efficient GSI query instead of filtering all transactions in memory
        String goalId = goal.getGoalId();
        List<TransactionTable> goalTransactions = transactionRepository.findByUserIdAndGoalId(userId, goalId)
                .stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        BigDecimal transactionContributions = goalTransactions.stream()
                .map(TransactionTable::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        logger.debug("Goal {} has {} assigned transactions contributing {}", 
                goal.getGoalId(), goalTransactions.size(), transactionContributions);

        // 2. If no transactions assigned, include income transactions as fallback
        if (goalTransactions.isEmpty()) {
            List<TransactionTable> incomeTransactions = transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE)
                    .stream()
                    .filter(tx -> "INCOME".equals(tx.getTransactionType()))
                    .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            // Use a percentage of income (conservative estimate)
            BigDecimal incomeTotal = incomeTransactions.stream()
                    .map(TransactionTable::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Use 10% of total income as savings estimate (conservative)
            transactionContributions = incomeTotal.multiply(new BigDecimal("0.10"))
                    .setScale(2, RoundingMode.HALF_UP);
            
            logger.debug("Goal {} has no assigned transactions, using 10% of income: {}", 
                    goal.getGoalId(), transactionContributions);
        }

        progress = progress.add(transactionContributions);

        // 3. Add account balance contributions from goal-associated accounts
        List<AccountTable> accounts;
        if (goal.getAccountIds() != null && !goal.getAccountIds().isEmpty()) {
            // Use only goal-associated accounts
            accounts = goal.getAccountIds().stream()
                    .map(accountId -> accountRepository.findById(accountId))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .collect(Collectors.toList());
            logger.debug("Goal {} using {} associated accounts", goal.getGoalId(), accounts.size());
        } else {
            // Use all user accounts (fallback to 10% of total balance)
            accounts = accountRepository.findByUserId(userId);
            logger.debug("Goal {} has no associated accounts, using all {} user accounts", 
                    goal.getGoalId(), accounts.size());
        }

        BigDecimal accountBalance = accounts.stream()
                .filter(acc -> acc.getBalance() != null && acc.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .map(AccountTable::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // If goal has specific accounts, use their full balance
        // Otherwise, use 10% of total balance as a conservative estimate
        BigDecimal accountContribution;
        if (goal.getAccountIds() != null && !goal.getAccountIds().isEmpty()) {
            accountContribution = accountBalance;
        } else {
            accountContribution = accountBalance.multiply(new BigDecimal("0.10"))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        progress = progress.add(accountContribution);

        // Cap progress at target amount
        if (goal.getTargetAmount() != null && progress.compareTo(goal.getTargetAmount()) > 0) {
            progress = goal.getTargetAmount();
        }

        logger.debug("Goal {} calculated progress: {} (transactions: {}, accounts: {})", 
                goal.getGoalId(), progress, transactionContributions, accountContribution);

        return progress.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Recalculate progress for all active goals for a user
     * Can be called periodically or after bulk transaction imports
     */
    @Async
    public void recalculateAllGoals(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            logger.warn("Cannot recalculate goals: user is null or invalid");
            return;
        }

        try {
            List<GoalTable> activeGoals = goalRepository.findByUserId(user.getUserId())
                    .stream()
                    .filter(goal -> goal.getActive() != null && goal.getActive())
                    .collect(Collectors.toList());

            logger.info("Recalculating progress for {} active goals for user {}", 
                    activeGoals.size(), user.getUserId());

            for (GoalTable goal : activeGoals) {
                try {
                    calculateAndUpdateProgress(user, goal.getGoalId());
                } catch (Exception e) {
                    logger.error("Error recalculating progress for goal {}: {}", 
                            goal.getGoalId(), e.getMessage(), e);
                }
            }

            logger.info("Completed recalculating progress for {} goals", activeGoals.size());
        } catch (Exception e) {
            logger.error("Error recalculating goals for user {}: {}", 
                    user.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * Recalculate progress for a goal when a transaction is assigned/unassigned
     */
    public void onTransactionGoalAssignmentChanged(final String userId, final String goalId) {
        if (userId == null || userId.isEmpty() || goalId == null || goalId.isEmpty()) {
            return;
        }

        try {
            // Get user (simplified - in production might want to cache or pass UserTable)
            UserTable user = new UserTable();
            user.setUserId(userId);
            
            calculateAndUpdateProgress(user, goalId);
        } catch (Exception e) {
            logger.error("Error recalculating goal {} after transaction assignment change: {}", 
                    goalId, e.getMessage(), e);
        }
    }
}

