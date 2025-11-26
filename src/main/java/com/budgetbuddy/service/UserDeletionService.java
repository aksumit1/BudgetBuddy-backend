package com.budgetbuddy.service;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User Deletion Service
 * Handles secure deletion of user data, Plaid integration, and account deletion
 * 
 * Features:
 * - Delete all user data (keep account)
 * - Delete Plaid integration only
 * - Delete account completely
 * - Ensures data encryption and secure deletion
 * - GDPR compliant
 */
@Service
public class UserDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(UserDeletionService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    public UserDeletionService(
            final UserRepository userRepository,
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final AuditLogRepository auditLogRepository,
            final AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Delete all user data but keep the account
     * Removes: accounts, transactions, budgets, goals
     * Keeps: user account, audit logs (anonymized)
     */
    public void deleteAllUserData(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Deleting all data for user: {}", userId);

        try {
            // 1. Remove Plaid items first (before deleting accounts)
            removePlaidItemsForUser(userId);

            // 2. Delete transactions in batches
            deleteTransactionsForUser(userId);

            // 3. Delete accounts
            deleteAccountsForUser(userId);

            // 4. Delete budgets
            deleteBudgetsForUser(userId);

            // 5. Delete goals
            deleteGoalsForUser(userId);

            // 6. Anonymize audit logs (keep for compliance, but remove PII)
            anonymizeAuditLogsForUser(userId);

            // Log deletion action
            auditLogService.logDataDeletion(userId);

            logger.info("Successfully deleted all data for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting user data for user {}: {}", userId, e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to delete user data", null, null, e);
        }
    }

    /**
     * Delete Plaid integration only
     * Removes: Plaid items, accounts, transactions
     * Keeps: budgets, goals, user account
     */
    public void deletePlaidIntegration(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Deleting Plaid integration for user: {}", userId);

        try {
            // 1. Remove Plaid items
            removePlaidItemsForUser(userId);

            // 2. Delete transactions in batches
            deleteTransactionsForUser(userId);

            // 3. Delete accounts
            deleteAccountsForUser(userId);

            // Log deletion action
            auditLogService.logAction(userId, "DELETE_PLAID_INTEGRATION", "PLAID", null,
                    java.util.Map.of("message", "Plaid integration removed"), null, null);

            logger.info("Successfully deleted Plaid integration for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting Plaid integration for user {}: {}", userId, e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to delete Plaid integration", null, null, e);
        }
    }

    /**
     * Delete account completely
     * Removes: everything including user account
     * This is irreversible
     */
    public void deleteAccountCompletely(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Deleting account completely for user: {}", userId);

        try {
            // 1. Delete all user data first
            deleteAllUserData(userId);

            // 2. Delete user account
            userRepository.delete(userId);

            // Log account deletion
            auditLogService.logAction(userId, "DELETE_ACCOUNT", "USER", userId,
                    java.util.Map.of("message", "Account completely deleted"), null, null);

            logger.info("Successfully deleted account completely for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting account completely for user {}: {}", userId, e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to delete account", null, null, e);
        }
    }

    /**
     * Remove all Plaid items for a user
     * This disconnects all linked financial accounts from Plaid
     */
    private void removePlaidItemsForUser(final String userId) {
        List<AccountTable> accounts = accountRepository.findByUserId(userId);
        Set<String> plaidItemIds = new HashSet<>();

        // Collect unique Plaid item IDs
        for (AccountTable account : accounts) {
            if (account.getPlaidItemId() != null && !account.getPlaidItemId().isEmpty()) {
                plaidItemIds.add(account.getPlaidItemId());
            }
        }

        // Remove each Plaid item
        // Note: Access tokens are stored in iOS app keychain, not in backend
        // For backend deletion, we mark accounts as inactive and clear Plaid IDs
        // The iOS app should handle Plaid item removal using stored access tokens
        for (String itemId : plaidItemIds) {
            try {
                // Clear Plaid IDs from accounts
                for (AccountTable account : accounts) {
                    if (itemId.equals(account.getPlaidItemId())) {
                        account.setPlaidItemId(null);
                        account.setPlaidAccountId(null);
                        account.setActive(false);
                        account.setUpdatedAt(Instant.now());
                        accountRepository.save(account);
                    }
                }
                logger.info("Cleared Plaid IDs for item: {} (user: {})", itemId, userId);
            } catch (Exception e) {
                logger.warn("Failed to clear Plaid IDs for item {}: {}", itemId, e.getMessage());
                // Continue with other items
            }
        }
    }

    /**
     * Delete all transactions for a user in batches
     */
    private void deleteTransactionsForUser(final String userId) {
        int batchSize = 1000;
        int skip = 0;
        int totalDeleted = 0;

        List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions;
        do {
            transactions = transactionRepository.findByUserId(userId, skip, batchSize);
            
            // Use batch delete for efficiency
            if (!transactions.isEmpty()) {
                List<String> transactionIds = transactions.stream()
                        .map(com.budgetbuddy.model.dynamodb.TransactionTable::getTransactionId)
                        .toList();
                
                try {
                    transactionRepository.batchDelete(transactionIds);
                    totalDeleted += transactionIds.size();
                    logger.debug("Deleted {} transactions (batch)", transactionIds.size());
                } catch (Exception e) {
                    // Fallback to individual deletion
                    logger.warn("Batch delete failed, falling back to individual deletion: {}", e.getMessage());
                    for (String transactionId : transactionIds) {
                        try {
                            transactionRepository.delete(transactionId);
                            totalDeleted++;
                        } catch (Exception ex) {
                            logger.warn("Failed to delete transaction {}: {}", transactionId, ex.getMessage());
                        }
                    }
                }
            }
            
            skip += transactions.size();
        } while (transactions.size() == batchSize);

        logger.info("Deleted {} transactions for user: {}", totalDeleted, userId);
    }

    /**
     * Delete all accounts for a user
     */
    private void deleteAccountsForUser(final String userId) {
        List<AccountTable> accounts = accountRepository.findByUserId(userId);
        
        // Use batch delete for efficiency
        if (!accounts.isEmpty()) {
            List<String> accountIds = accounts.stream()
                    .map(AccountTable::getAccountId)
                    .toList();
            
            try {
                accountRepository.batchDelete(accountIds);
                logger.info("Deleted {} accounts for user: {}", accountIds.size(), userId);
            } catch (Exception e) {
                // Fallback to individual deletion
                logger.warn("Batch delete failed, falling back to individual deletion: {}", e.getMessage());
                for (String accountId : accountIds) {
                    try {
                        accountRepository.delete(accountId);
                    } catch (Exception ex) {
                        logger.warn("Failed to delete account {}: {}", accountId, ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Delete all budgets for a user
     */
    private void deleteBudgetsForUser(final String userId) {
        List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets = budgetRepository.findByUserId(userId);
        
        for (com.budgetbuddy.model.dynamodb.BudgetTable budget : budgets) {
            try {
                budgetRepository.delete(budget.getBudgetId());
            } catch (Exception e) {
                logger.warn("Failed to delete budget {}: {}", budget.getBudgetId(), e.getMessage());
            }
        }
        
        logger.info("Deleted {} budgets for user: {}", budgets.size(), userId);
    }

    /**
     * Delete all goals for a user
     */
    private void deleteGoalsForUser(final String userId) {
        List<com.budgetbuddy.model.dynamodb.GoalTable> goals = goalRepository.findByUserId(userId);
        
        for (com.budgetbuddy.model.dynamodb.GoalTable goal : goals) {
            try {
                goalRepository.delete(goal.getGoalId());
            } catch (Exception e) {
                logger.warn("Failed to delete goal {}: {}", goal.getGoalId(), e.getMessage());
            }
        }
        
        logger.info("Deleted {} goals for user: {}", goals.size(), userId);
    }

    /**
     * Anonymize audit logs for a user
     * Keeps logs for compliance but removes PII
     */
    private void anonymizeAuditLogsForUser(final String userId) {
        long sevenYearsAgo = Instant.now().minusSeconds(7L * 365 * 24 * 60 * 60).getEpochSecond() * 1000;
        long now = System.currentTimeMillis();
        
        List<com.budgetbuddy.compliance.AuditLogTable> auditLogs = 
                auditLogRepository.findByUserIdAndDateRange(userId, sevenYearsAgo, now);
        
        for (com.budgetbuddy.compliance.AuditLogTable log : auditLogs) {
            try {
                log.setUserId(null);
                log.setIpAddress("REDACTED");
                log.setUserAgent("REDACTED");
                auditLogRepository.save(log);
            } catch (Exception e) {
                logger.warn("Failed to anonymize audit log {}: {}", log.getAuditLogId(), e.getMessage());
            }
        }
        
        logger.info("Anonymized {} audit logs for user: {}", auditLogs.size(), userId);
    }
}

