package com.budgetbuddy.service;

import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.dto.SyncAllResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sync Service
 * Provides optimized sync endpoints for mobile apps
 * - /api/sync/all: Returns all data (for first sync)
 * - /api/sync/incremental: Returns only changed data (for periodic sync)
 */
@Service
public class SyncService {

    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final TransactionActionRepository transactionActionRepository;

    public SyncService(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final TransactionActionRepository transactionActionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.transactionActionRepository = transactionActionRepository;
    }

    /**
     * Get all user data for first sync or force refresh
     * Returns all accounts, transactions, budgets, goals, and actions
     */
    public SyncAllResponse getAllData(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Fetching all data for user: {}", userId);

        try {
            // Fetch all data in parallel (repository calls are thread-safe)
            List<AccountTable> accounts = accountRepository.findByUserId(userId);
            List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE);
            List<BudgetTable> budgets = budgetRepository.findByUserId(userId);
            List<GoalTable> goals = goalRepository.findByUserId(userId);
            List<TransactionActionTable> actions = transactionActionRepository.findByUserId(userId);

            Long syncTimestamp = Instant.now().getEpochSecond();

            logger.info("Fetched all data for user {}: {} accounts, {} transactions, {} budgets, {} goals, {} actions",
                    userId, accounts.size(), transactions.size(), budgets.size(), goals.size(), actions.size());

            return new SyncAllResponse(accounts, transactions, budgets, goals, actions, syncTimestamp);
        } catch (Exception e) {
            logger.error("Error fetching all data for user {}: {}", userId, e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to fetch user data", null, null, e);
        }
    }

    /**
     * Get incremental changes since specified timestamp
     * Returns only items that were created or updated after the timestamp
     * Optimized for periodic sync to minimize data transfer
     */
    public IncrementalSyncResponse getIncrementalChanges(final String userId, final Long sinceTimestamp) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        if (sinceTimestamp == null || sinceTimestamp <= 0) {
            // If no timestamp provided, return all data (fallback to full sync)
            logger.warn("Invalid sinceTimestamp for user {}, falling back to full sync", userId);
            SyncAllResponse allData = getAllData(userId);
            return new IncrementalSyncResponse(
                    allData.getAccounts(),
                    allData.getTransactions(),
                    allData.getBudgets(),
                    allData.getGoals(),
                    allData.getActions(),
                    allData.getSyncTimestamp(),
                    false
            );
        }

        Instant sinceInstant = Instant.ofEpochSecond(sinceTimestamp);
        logger.info("Fetching incremental changes for user {} since {}", userId, sinceInstant);

        try {
            // Fetch all data and filter by updatedAt or createdAt
            // Note: For production, consider adding GSI on updatedAt for better performance
            List<AccountTable> allAccounts = accountRepository.findByUserId(userId);
            List<TransactionTable> allTransactions = transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE);
            List<BudgetTable> allBudgets = budgetRepository.findByUserId(userId);
            List<GoalTable> allGoals = goalRepository.findByUserId(userId);
            List<TransactionActionTable> allActions = transactionActionRepository.findByUserId(userId);

            // Filter to only items changed since timestamp
            // Use updatedAt if available, otherwise use createdAt
            List<AccountTable> changedAccounts = allAccounts.stream()
                    .filter(account -> {
                        Instant updatedAt = account.getUpdatedAt() != null ? account.getUpdatedAt() : account.getCreatedAt();
                        return updatedAt != null && updatedAt.isAfter(sinceInstant);
                    })
                    .collect(Collectors.toList());

            List<TransactionTable> changedTransactions = allTransactions.stream()
                    .filter(transaction -> {
                        Instant updatedAt = transaction.getUpdatedAt() != null ? transaction.getUpdatedAt() : transaction.getCreatedAt();
                        return updatedAt != null && updatedAt.isAfter(sinceInstant);
                    })
                    .collect(Collectors.toList());

            List<BudgetTable> changedBudgets = allBudgets.stream()
                    .filter(budget -> {
                        Instant updatedAt = budget.getUpdatedAt() != null ? budget.getUpdatedAt() : budget.getCreatedAt();
                        return updatedAt != null && updatedAt.isAfter(sinceInstant);
                    })
                    .collect(Collectors.toList());

            List<GoalTable> changedGoals = allGoals.stream()
                    .filter(goal -> {
                        Instant updatedAt = goal.getUpdatedAt() != null ? goal.getUpdatedAt() : goal.getCreatedAt();
                        return updatedAt != null && updatedAt.isAfter(sinceInstant);
                    })
                    .collect(Collectors.toList());

            List<TransactionActionTable> changedActions = allActions.stream()
                    .filter(action -> {
                        Instant updatedAt = action.getUpdatedAt() != null ? action.getUpdatedAt() : action.getCreatedAt();
                        return updatedAt != null && updatedAt.isAfter(sinceInstant);
                    })
                    .collect(Collectors.toList());

            Long syncTimestamp = Instant.now().getEpochSecond();

            logger.info("Fetched incremental changes for user {}: {} accounts, {} transactions, {} budgets, {} goals, {} actions",
                    userId, changedAccounts.size(), changedTransactions.size(), changedBudgets.size(), changedGoals.size(), changedActions.size());

            // For now, we don't implement pagination, so hasMore is always false
            // In the future, if response is too large, we can implement pagination
            boolean hasMore = false;

            return new IncrementalSyncResponse(
                    changedAccounts,
                    changedTransactions,
                    changedBudgets,
                    changedGoals,
                    changedActions,
                    syncTimestamp,
                    hasMore
            );
        } catch (Exception e) {
            logger.error("Error fetching incremental changes for user {}: {}", userId, e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to fetch incremental changes", null, null, e);
        }
    }
}

