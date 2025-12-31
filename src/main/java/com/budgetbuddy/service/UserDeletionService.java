package com.budgetbuddy.service;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import com.budgetbuddy.repository.dynamodb.*;
import org.springframework.cache.CacheManager;
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
    private final TransactionActionRepository actionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FIDO2CredentialRepository fido2CredentialRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final CacheManager cacheManager;
    private final ImportHistoryService importHistoryService;
    private final com.budgetbuddy.service.aws.S3Service s3Service;

    public UserDeletionService(
            final UserRepository userRepository,
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final TransactionActionRepository actionRepository,
            final SubscriptionRepository subscriptionRepository,
            final FIDO2CredentialRepository fido2CredentialRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final AuditLogRepository auditLogRepository,
            final AuditLogService auditLogService,
            final CacheManager cacheManager,
            final ImportHistoryService importHistoryService,
            final com.budgetbuddy.service.aws.S3Service s3Service) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.actionRepository = actionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.fido2CredentialRepository = fido2CredentialRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
        this.cacheManager = cacheManager;
        this.importHistoryService = importHistoryService;
        this.s3Service = s3Service;
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
            // CRITICAL FIX: Get account IDs BEFORE deleting accounts (needed for S3 file deletion)
            List<AccountTable> accountsToDelete = accountRepository.findByUserId(userId);
            List<String> accountIdsForS3 = accountsToDelete.stream()
                    .map(AccountTable::getAccountId)
                    .toList();
            
            // 1. Remove Plaid items first (before deleting accounts)
            removePlaidItemsForUser(userId);

            // 2. Delete transaction actions (reminders/todos) before deleting transactions
            deleteActionsForUser(userId);

            // 3. Delete transactions in batches (with improved pagination)
            deleteTransactionsForUser(userId);

            // 4. Delete accounts
            deleteAccountsForUser(userId);

            // 5. Delete budgets
            deleteBudgetsForUser(userId);

            // 6. Delete goals
            deleteGoalsForUser(userId);

            // 7. Delete subscriptions
            deleteSubscriptionsForUser(userId);

            // 8. Delete import history
            deleteImportHistoryForUser(userId);

            // 9. Delete S3 files (exports, imports, account attachments)
            // CRITICAL FIX: Pass account IDs directly instead of calling findByUserId again (accounts are already deleted)
            deleteS3FilesForUser(userId, accountIdsForS3);

            // 10. Anonymize audit logs (keep for compliance, but remove PII)
            anonymizeAuditLogsForUser(userId);

            // 11. Evict all caches for this user
            evictUserCaches(userId);

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

            // 2. Delete transaction actions (reminders/todos) before deleting transactions
            deleteActionsForUser(userId);

            // 3. Delete transactions in batches
            deleteTransactionsForUser(userId);

            // 4. Delete accounts
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

            // 2. Delete FIDO2 credentials (only when deleting account completely)
            deleteFIDO2CredentialsForUser(userId);

            // 3. Delete user account
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
     * Delete all transaction actions (reminders/todos) for a user
     * CRITICAL: This must be called before deleting transactions to prevent orphaned actions
     */
    private void deleteActionsForUser(final String userId) {
        // Query all actions for this user using UserIdIndex GSI
        List<TransactionActionTable> actions = actionRepository.findByUserId(userId);
        
        if (!actions.isEmpty()) {
            // Delete each action
            for (TransactionActionTable action : actions) {
                try {
                    actionRepository.delete(action.getActionId());
                } catch (Exception e) {
                    logger.warn("Failed to delete action {}: {}", action.getActionId(), e.getMessage());
                }
            }
            logger.info("Deleted {} transaction actions for user: {}", actions.size(), userId);
        } else {
            logger.debug("No transaction actions found for user: {}", userId);
        }
    }

    /**
     * Delete all transactions for a user in batches
     * CRITICAL FIX: TransactionRepository.findByUserId has a max limit of 100,
     * so we need to loop with batch size of 100 until all transactions are deleted
     * 
     * FIXED: Loop condition now correctly continues until no more transactions are found
     * CRITICAL FIX: Use cursor-based pagination by always starting from skip=0 after each batch deletion
     * This ensures we get the next batch correctly, avoiding issues with skip-based pagination in DynamoDB
     */
    private void deleteTransactionsForUser(final String userId) {
        // CRITICAL: TransactionRepository.findByUserId caps limit at 100 (maxLimit)
        // So we must use batch size of 100 and loop until all are deleted
        int batchSize = 100; // Must match TransactionRepository maxLimit
        int totalDeleted = 0;
        final int maxIterations = 10000; // Safety limit (10000 * 100 = 1,000,000 transactions max)
        int iterationCount = 0;

        List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions;
        do {
            iterationCount++;
            if (iterationCount > maxIterations) {
                logger.error("Reached maximum iteration limit ({}) for deleting transactions. Stopping to prevent infinite loop. User: {}", maxIterations, userId);
                break;
            }
            
            // CRITICAL FIX: Always start from skip=0 and delete the first batch
            // After deletion, the next batch will be the first batch in the remaining records
            // This avoids issues with skip-based pagination in DynamoDB
            transactions = transactionRepository.findByUserId(userId, 0, batchSize);
            
            // Use batch delete for efficiency
            if (!transactions.isEmpty()) {
                List<String> transactionIds = transactions.stream()
                        .map(com.budgetbuddy.model.dynamodb.TransactionTable::getTransactionId)
                        .toList();
                
                try {
                    transactionRepository.batchDelete(transactionIds);
                    totalDeleted += transactionIds.size();
                    logger.debug("Deleted {} transactions (batch {}, total deleted: {})", transactionIds.size(), iterationCount, totalDeleted);
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
                
                // CRITICAL FIX: Continue loop to get next batch (always starting from skip=0)
                // The next query will return the next batch since we've deleted the previous batch
                // This ensures all transactions are deleted
            } else {
                // No more transactions found - we're done
                logger.debug("No more transactions found (batch {}), deletion complete", iterationCount);
                break;
            }
        } while (true); // Continue until we break (empty batch or max iterations)

        logger.info("Deleted {} transactions for user: {} (iterations: {})", totalDeleted, userId, iterationCount);
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

    /**
     * Delete all subscriptions for a user
     */
    private void deleteSubscriptionsForUser(final String userId) {
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(userId);
        
        for (SubscriptionTable subscription : subscriptions) {
            try {
                subscriptionRepository.delete(subscription.getSubscriptionId());
            } catch (Exception e) {
                logger.warn("Failed to delete subscription {}: {}", subscription.getSubscriptionId(), e.getMessage());
            }
        }
        
        logger.info("Deleted {} subscriptions for user: {}", subscriptions.size(), userId);
    }

    /**
     * Delete all FIDO2 credentials for a user
     * Only called when deleting account completely (not when just deleting data)
     */
    private void deleteFIDO2CredentialsForUser(final String userId) {
        List<FIDO2CredentialTable> credentials = fido2CredentialRepository.findByUserId(userId);
        
        for (FIDO2CredentialTable credential : credentials) {
            try {
                fido2CredentialRepository.delete(credential.getCredentialId());
            } catch (Exception e) {
                logger.warn("Failed to delete FIDO2 credential {}: {}", credential.getCredentialId(), e.getMessage());
            }
        }
        
        logger.info("Deleted {} FIDO2 credentials for user: {}", credentials.size(), userId);
    }

    /**
     * Delete all import history for a user
     */
    private void deleteImportHistoryForUser(final String userId) {
        try {
            List<com.budgetbuddy.model.ImportHistory> imports = importHistoryService.getUserImportHistory(userId);
            for (com.budgetbuddy.model.ImportHistory importHistory : imports) {
                try {
                    importHistoryService.deleteImportHistory(importHistory.getImportId());
                } catch (Exception e) {
                    logger.warn("Failed to delete import history {}: {}", importHistory.getImportId(), e.getMessage());
                }
            }
            logger.info("Deleted {} import history records for user: {}", imports.size(), userId);
        } catch (Exception e) {
            logger.warn("Failed to delete import history for user {}: {}", userId, e.getMessage());
            // Don't fail deletion if import history deletion fails
        }
    }

    /**
     * Delete all S3 files for a user (exports, imports, account attachments)
     * CRITICAL FIX: Takes accountIds as parameter instead of calling findByUserId
     * because accounts are already deleted when this is called
     */
    private void deleteS3FilesForUser(final String userId, final List<String> accountIds) {
        if (s3Service == null) {
            logger.debug("S3Service not available - skipping S3 file deletion");
            return;
        }

        try {
            int totalDeleted = 0;

            // Delete user export files (e.g., "exports/user_{userId}_*.gz")
            try {
                String exportPrefix = String.format("exports/user_%s_", userId);
                int deletedCount = s3Service.deleteFilesByPrefix(exportPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    logger.info("Deleted {} S3 export files for user: {}", deletedCount, userId);
                }
            } catch (Exception e) {
                logger.warn("Failed to delete S3 export files for user {}: {}", userId, e.getMessage());
            }

            // Delete account-specific files (e.g., "accounts/{userId}/{accountId}/...")
            // CRITICAL FIX: Use accountIds parameter instead of calling findByUserId (accounts are already deleted)
            for (String accountId : accountIds) {
                try {
                    String accountPrefix = String.format("accounts/%s/%s/", userId, accountId);
                    int deletedCount = s3Service.deleteFilesByPrefix(accountPrefix);
                    totalDeleted += deletedCount;
                    if (deletedCount > 0) {
                        logger.debug("Deleted {} S3 files for account {} (user: {})", deletedCount, accountId, userId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to delete S3 files for account {}: {}", accountId, e.getMessage());
                }
            }

            // Delete import files if any (e.g., "imports/{userId}/...")
            try {
                String importPrefix = String.format("imports/%s/", userId);
                int deletedCount = s3Service.deleteFilesByPrefix(importPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    logger.info("Deleted {} S3 import files for user: {}", deletedCount, userId);
                }
            } catch (Exception e) {
                logger.warn("Failed to delete S3 import files for user {}: {}", userId, e.getMessage());
            }

            // Delete any other user-specific files (e.g., "users/{userId}/...")
            try {
                String userPrefix = String.format("users/%s/", userId);
                int deletedCount = s3Service.deleteFilesByPrefix(userPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    logger.info("Deleted {} S3 user files for user: {}", deletedCount, userId);
                }
            } catch (Exception e) {
                logger.warn("Failed to delete S3 user files for user {}: {}", userId, e.getMessage());
            }

            logger.info("Completed S3 file deletion for user: {} (total files deleted: {})", userId, totalDeleted);
        } catch (Exception e) {
            logger.warn("Failed to delete S3 files for user {}: {}", userId, e.getMessage());
            // Don't fail deletion if S3 deletion fails
        }
    }

    /**
     * Evict all Spring caches for a user
     * This ensures cached data doesn't persist after deletion
     */
    private void evictUserCaches(final String userId) {
        if (cacheManager == null) {
            logger.debug("CacheManager not available - skipping cache eviction");
            return;
        }

        try {
            // Evict all caches that might contain user data
            String[] cacheNames = {
                "users", "userProfiles",
                "accounts", "accountBalances",
                "transactions", "transactionSummaries",
                "budgets", "goals",
                "transactionActions",
                "subscriptions",
                "fido2Credentials",
                "analytics"
            };

            for (String cacheName : cacheNames) {
                org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    // Evict entries for this user
                    // Note: Cache keys vary by cache type, so we evict all entries
                    // This is safe because we're deleting all user data anyway
                    cache.clear();
                    logger.debug("Evicted cache: {}", cacheName);
                }
            }
            
            logger.info("Evicted all caches for user: {}", userId);
        } catch (Exception e) {
            logger.warn("Failed to evict caches for user {}: {}", userId, e.getMessage());
            // Don't fail deletion if cache eviction fails
        }
    }
}

