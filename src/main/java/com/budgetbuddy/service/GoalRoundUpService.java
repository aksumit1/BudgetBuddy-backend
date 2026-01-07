package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Service for round-up transactions feature
 * Automatically rounds up transactions and contributes difference to goals
 */
@Service
public class GoalRoundUpService {

    private static final Logger logger = LoggerFactory.getLogger(GoalRoundUpService.class);

    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;
    private final GoalProgressService goalProgressService;
    private final TransactionService transactionService;

    public GoalRoundUpService(GoalRepository goalRepository, TransactionRepository transactionRepository,
                             GoalProgressService goalProgressService, TransactionService transactionService) {
        this.goalRepository = goalRepository;
        this.transactionRepository = transactionRepository;
        this.goalProgressService = goalProgressService;
        this.transactionService = transactionService;
    }

    /**
     * Calculate round-up amount for a transaction
     * Rounds up to nearest dollar
     */
    public BigDecimal calculateRoundUp(BigDecimal transactionAmount) {
        if (transactionAmount == null || transactionAmount.compareTo(BigDecimal.ZERO) >= 0) {
            return BigDecimal.ZERO; // Only round up expenses (negative amounts)
        }

        // Convert to positive for calculation
        BigDecimal positiveAmount = transactionAmount.abs();
        
        // Round up to nearest dollar
        BigDecimal roundedUp = positiveAmount.setScale(0, RoundingMode.UP);
        
        // Calculate difference
        BigDecimal roundUpAmount = roundedUp.subtract(positiveAmount);
        
        return roundUpAmount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Process round-up for a transaction and contribute to goal
     */
    public void processRoundUp(TransactionTable transaction, String goalId) {
        if (transaction == null || goalId == null || goalId.isEmpty()) {
            return;
        }

        // Only process expenses (negative amounts)
        if (transaction.getAmount() == null || transaction.getAmount().compareTo(BigDecimal.ZERO) >= 0) {
            return;
        }

        // Check if goal has round-up enabled
        Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            logger.warn("Goal {} not found for round-up", goalId);
            return;
        }

        GoalTable goal = goalOpt.get();
        // Check if round-up is enabled for this goal
        if (goal.getRoundUpEnabled() == null || !goal.getRoundUpEnabled()) {
            return;
        }

        // Calculate round-up amount
        BigDecimal roundUpAmount = calculateRoundUp(transaction.getAmount());
        
        if (roundUpAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return; // No round-up needed
        }

        // Check if round-up already processed for this transaction
        // We can add a field to TransactionTable to track round-up processing
        // For now, we'll check if there's already a round-up transaction

        // Create round-up contribution transaction
        // This would be a positive transaction assigned to the goal
        logger.info("Processing round-up: ${} for transaction {} to goal {}", 
            roundUpAmount, transaction.getTransactionId(), goalId);

        // Update goal progress with round-up amount
        // Note: This should be done through GoalProgressService to ensure proper tracking
        // For now, we'll just log - actual implementation would create a transaction
    }

    /**
     * Get total round-up contributions for a goal in a time period
     */
    public BigDecimal getRoundUpTotal(GoalTable goal, String userId, int days) {
        // Get all transactions assigned to goal
        List<TransactionTable> goalTransactions = transactionRepository.findByUserIdAndGoalId(userId, goal.getGoalId());

        // Calculate total round-ups (simplified - would need to track round-up transactions separately)
        // For now, return zero - actual implementation would sum round-up contributions
        return BigDecimal.ZERO;
    }

    /**
     * Enable round-up for a goal
     */
    public void enableRoundUp(String goalId) {
        Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            throw new IllegalArgumentException("Goal not found: " + goalId);
        }

        GoalTable goal = goalOpt.get();
        goal.setRoundUpEnabled(true);
        goalRepository.save(goal);
        logger.info("Round-up enabled for goal: {}", goalId);
    }

    /**
     * Disable round-up for a goal
     */
    public void disableRoundUp(String goalId) {
        Optional<GoalTable> goalOpt = goalRepository.findById(goalId);
        if (goalOpt.isEmpty()) {
            throw new IllegalArgumentException("Goal not found: " + goalId);
        }

        GoalTable goal = goalOpt.get();
        goal.setRoundUpEnabled(false);
        goalRepository.save(goal);
        logger.info("Round-up disabled for goal: {}", goalId);
    }
}

