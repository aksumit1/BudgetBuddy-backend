package com.budgetbuddy.service;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.AnomalyFeedbackRepository;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.CustomMerchantMappingRepository;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.NetWorthSnapshotRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserCorrectionRepository;
import com.budgetbuddy.repository.dynamodb.UserPreferencesRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * User Deletion Service Handles secure deletion of user data, Plaid integration, and account
 * deletion
 *
 * <p>Features: - Delete all user data (keep account) - Delete Plaid integration only - Delete
 * account completely - Ensures data encryption and secure deletion - GDPR compliant
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class UserDeletionService {

    private static final String USER_ID_IS_REQUIRED = "User ID is required";

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDeletionService.class);

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
    private final DeviceTokenRepository deviceTokenRepository;
    private final AnomalyFeedbackRepository anomalyFeedbackRepository;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final UserCorrectionRepository userCorrectionRepository;
    private final CustomMerchantMappingRepository customMerchantMappingRepository;

    // GDPR coverage status. Tables swept by deleteAllUserData below:
    //   accounts, transactions, transaction-actions, budgets, goals, subscriptions,
    //   import-history, S3 objects, device-tokens, anomaly-feedback, net-worth-snapshots,
    //   user-preferences, user-corrections, custom-merchant-mappings.
    // Audit logs are anonymized (not deleted) by design for compliance.
    // FIDO2 credentials are deleted only by deleteAccountCompletely (not just data wipe).

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
            final com.budgetbuddy.service.aws.S3Service s3Service,
            final DeviceTokenRepository deviceTokenRepository,
            final AnomalyFeedbackRepository anomalyFeedbackRepository,
            final NetWorthSnapshotRepository netWorthSnapshotRepository,
            final UserPreferencesRepository userPreferencesRepository,
            final UserCorrectionRepository userCorrectionRepository,
            final CustomMerchantMappingRepository customMerchantMappingRepository) {
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
        this.deviceTokenRepository = deviceTokenRepository;
        this.anomalyFeedbackRepository = anomalyFeedbackRepository;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.userCorrectionRepository = userCorrectionRepository;
        this.customMerchantMappingRepository = customMerchantMappingRepository;
    }

    /**
     * Delete all user data but keep the account Removes: accounts, transactions, budgets, goals
     * Keeps: user account, audit logs (anonymized)
     */
    public void deleteAllUserData(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }

        LOGGER.info("Deleting all data for user: {}", userId);

        try {
            // CRITICAL FIX: Get account IDs BEFORE deleting accounts (needed for S3 file deletion)
            final List<AccountTable> accountsToDelete = accountRepository.findByUserId(userId);
            final List<String> accountIdsForS3 =
                    accountsToDelete.stream().map(AccountTable::getAccountId).toList();

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
            // CRITICAL FIX: Pass account IDs directly instead of calling findByUserId again
            // (accounts are already deleted)
            deleteS3FilesForUser(userId, accountIdsForS3);

            // 10. Delete device-token / push registrations
            deleteDeviceTokensForUser(userId);

            // 11. Delete anomaly feedback (dismissed/confirmed fingerprints) — PII-ish
            deleteAnomalyFeedbackForUser(userId);

            // 12. Delete net-worth snapshot history
            deleteNetWorthSnapshotsForUser(userId);

            // 13. Delete per-user preferences (opt-ins, notification settings)
            deleteUserPreferencesForUser(userId);

            // 14. Delete per-user ML training rows (corrections + custom-merchant mappings)
            deleteCategoryLearningForUser(userId);

            // 15. Anonymize audit logs (keep for compliance, but remove PII)
            anonymizeAuditLogsForUser(userId);

            // 16. Evict all caches for this user
            evictUserCaches(userId);

            // Log deletion action
            auditLogService.logDataDeletion(userId);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Successfully deleted all data for user: {}", userId);
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error deleting user data for user {}: {}", userId, e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete user data", null, null, e);
        }
    }

    /**
     * Delete Plaid integration only Removes: Plaid items, accounts, transactions Keeps: budgets,
     * goals, user account
     */
    public void deletePlaidIntegration(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }

        LOGGER.info("Deleting Plaid integration for user: {}", userId);

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
            auditLogService.logAction(
                    userId,
                    "DELETE_PLAID_INTEGRATION",
                    "PLAID",
                    null,
                    java.util.Map.of("message", "Plaid integration removed"),
                    null,
                    null);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Successfully deleted Plaid integration for user: {}", userId);
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error deleting Plaid integration for user {}: {}",
                        userId,
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to delete Plaid integration",
                    null,
                    null,
                    e);
        }
    }

    /** Delete account completely Removes: everything including user account This is irreversible */
    public void deleteAccountCompletely(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }

        LOGGER.info("Deleting account completely for user: {}", userId);

        try {
            // 1. Delete all user data first
            deleteAllUserData(userId);

            // 2. Delete FIDO2 credentials (only when deleting account completely)
            deleteFIDO2CredentialsForUser(userId);

            // 3. Delete user account
            userRepository.delete(userId);

            // Log account deletion
            auditLogService.logAction(
                    userId,
                    "DELETE_ACCOUNT",
                    "USER",
                    userId,
                    java.util.Map.of("message", "Account completely deleted"),
                    null,
                    null);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Successfully deleted account completely for user: {}", userId);
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error deleting account completely for user {}: {}",
                        userId,
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete account", null, null, e);
        }
    }

    /**
     * Remove all Plaid items for a user This disconnects all linked financial accounts from Plaid
     */
    private void removePlaidItemsForUser(final String userId) {
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);
        final Set<String> plaidItemIds = new HashSet<>();

        // Collect unique Plaid item IDs
        for (final AccountTable account : accounts) {
            if (account.getPlaidItemId() != null && !account.getPlaidItemId().isEmpty()) {
                plaidItemIds.add(account.getPlaidItemId());
            }
        }

        // Remove each Plaid item
        // Note: Access tokens are stored in iOS app keychain, not in backend
        // For backend deletion, we mark accounts as inactive and clear Plaid IDs
        // The iOS app should handle Plaid item removal using stored access tokens
        for (final String itemId : plaidItemIds) {
            try {
                // Clear Plaid IDs from accounts
                for (final AccountTable account : accounts) {
                    if (itemId.equals(account.getPlaidItemId())) {
                        account.setPlaidItemId(null);
                        account.setPlaidAccountId(null);
                        account.setActive(false);
                        account.setUpdatedAt(Instant.now());
                        accountRepository.save(account);
                    }
                }
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Cleared Plaid IDs for item: {} (user: {})", itemId, userId);
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to clear Plaid IDs for item {}: {}", itemId, e.getMessage());
                }
                // Continue with other items
            }
        }
    }

    /**
     * Delete all transaction actions (reminders/todos) for a user CRITICAL: This must be called
     * before deleting transactions to prevent orphaned actions
     */
    private void deleteActionsForUser(final String userId) {
        // Query all actions for this user using UserIdIndex GSI
        final List<TransactionActionTable> actions = actionRepository.findByUserId(userId);

        if (!actions.isEmpty()) {
            // Delete each action
            for (final TransactionActionTable action : actions) {
                try {
                    actionRepository.delete(action.getActionId());
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Failed to delete action {}: {}",
                                action.getActionId(),
                                e.getMessage());
                    }
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted {} transaction actions for user: {}", actions.size(), userId);
            }
        } else {
            LOGGER.debug("No transaction actions found for user: {}", userId);
        }
    }

    /**
     * Delete all transactions for a user in batches CRITICAL FIX:
     * TransactionRepository.findByUserId has a max limit of 100, so we need to loop with batch size
     * of 100 until all transactions are deleted
     *
     * <p>FIXED: Loop condition now correctly continues until no more transactions are found
     * CRITICAL FIX: Use cursor-based pagination by always starting from skip=0 after each batch
     * deletion This ensures we get the next batch correctly, avoiding issues with skip-based
     * pagination in DynamoDB
     */
    private void deleteTransactionsForUser(final String userId) {
        // CRITICAL: TransactionRepository.findByUserId caps limit at 100 (maxLimit)
        // So we must use batch size of 100 and loop until all are deleted
        final int batchSize = 100; // Must match TransactionRepository maxLimit
        int totalDeleted = 0;
        final int maxIterations = 10_000; // Safety limit (10000 * 100 = 1,000,000 transactions max)
        int iterationCount = 0;

        List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions;
        do {
            iterationCount++;
            if (iterationCount > maxIterations) {
                LOGGER.error(
                        "Reached maximum iteration limit ({}) for deleting transactions. Stopping to prevent infinite loop. User: {}",
                        maxIterations,
                        userId);
                break;
            }

            // CRITICAL FIX: Always start from skip=0 and delete the first batch
            // After deletion, the next batch will be the first batch in the remaining records
            // This avoids issues with skip-based pagination in DynamoDB
            transactions = transactionRepository.findByUserId(userId, 0, batchSize);

            // Use batch delete for efficiency
            if (!transactions.isEmpty()) {
                final List<String> transactionIds =
                        transactions.stream()
                                .map(
                                        com.budgetbuddy.model.dynamodb.TransactionTable
                                                ::getTransactionId)
                                .toList();

                try {
                    transactionRepository.batchDelete(transactionIds);
                    totalDeleted += transactionIds.size();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Deleted {} transactions (batch {}, total deleted: {})",
                                transactionIds.size(),
                                iterationCount,
                                totalDeleted);
                    }
                } catch (Exception e) {
                    // Fallback to individual deletion
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Batch delete failed, falling back to individual deletion: {}",
                                e.getMessage());
                    }
                    for (final String transactionId : transactionIds) {
                        try {
                            transactionRepository.delete(transactionId);
                            totalDeleted++;
                        } catch (Exception ex) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Failed to delete transaction {}: {}",
                                        transactionId,
                                        ex.getMessage());
                            }
                        }
                    }
                }

                // CRITICAL FIX: Continue loop to get next batch (always starting from skip=0)
                // The next query will return the next batch since we've deleted the previous batch
                // This ensures all transactions are deleted
            } else {
                // No more transactions found - we're done
                LOGGER.debug(
                        "No more transactions found (batch {}), deletion complete", iterationCount);
                break;
            }
        } while (true); // Continue until we break (empty batch or max iterations)

        LOGGER.info(
                "Deleted {} transactions for user: {} (iterations: {})",
                totalDeleted,
                userId,
                iterationCount);
    }

    /** Delete all accounts for a user */
    private void deleteAccountsForUser(final String userId) {
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);

        // Use batch delete for efficiency
        if (!accounts.isEmpty()) {
            final List<String> accountIds =
                    accounts.stream().map(AccountTable::getAccountId).toList();

            try {
                accountRepository.batchDelete(accountIds);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Deleted {} accounts for user: {}", accountIds.size(), userId);
                }
            } catch (Exception e) {
                // Fallback to individual deletion
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Batch delete failed, falling back to individual deletion: {}",
                            e.getMessage());
                }
                for (final String accountId : accountIds) {
                    try {
                        accountRepository.delete(accountId);
                    } catch (Exception ex) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Failed to delete account {}: {}", accountId, ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    /** Delete all budgets for a user */
    private void deleteBudgetsForUser(final String userId) {
        final List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets =
                budgetRepository.findByUserId(userId);

        for (final com.budgetbuddy.model.dynamodb.BudgetTable budget : budgets) {
            try {
                budgetRepository.delete(budget.getBudgetId());
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete budget {}: {}", budget.getBudgetId(), e.getMessage());
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Deleted {} budgets for user: {}", budgets.size(), userId);
        }
    }

    /** Delete all goals for a user */
    private void deleteGoalsForUser(final String userId) {
        final List<com.budgetbuddy.model.dynamodb.GoalTable> goals =
                goalRepository.findByUserId(userId);

        for (final com.budgetbuddy.model.dynamodb.GoalTable goal : goals) {
            try {
                goalRepository.delete(goal.getGoalId());
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Failed to delete goal {}: {}", goal.getGoalId(), e.getMessage());
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Deleted {} goals for user: {}", goals.size(), userId);
        }
    }

    /** Anonymize audit logs for a user Keeps logs for compliance but removes PII */
    private void anonymizeAuditLogsForUser(final String userId) {
        final long sevenYearsAgo =
                Instant.now().minusSeconds(7L * 365 * 24 * 60 * 60).getEpochSecond() * 1000;
        final long now = System.currentTimeMillis();

        final List<com.budgetbuddy.compliance.AuditLogTable> auditLogs =
                auditLogRepository.findByUserIdAndDateRange(userId, sevenYearsAgo, now);

        for (final com.budgetbuddy.compliance.AuditLogTable log : auditLogs) {
            try {
                log.setUserId(null);
                log.setIpAddress("REDACTED");
                log.setUserAgent("REDACTED");
                auditLogRepository.save(log);
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to anonymize audit log {}: {}",
                            log.getAuditLogId(),
                            e.getMessage());
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Anonymized {} audit logs for user: {}", auditLogs.size(), userId);
        }
    }

    /** Delete all subscriptions for a user */
    private void deleteSubscriptionsForUser(final String userId) {
        final List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(userId);

        for (final SubscriptionTable subscription : subscriptions) {
            try {
                subscriptionRepository.delete(subscription.getSubscriptionId());
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete subscription {}: {}",
                            subscription.getSubscriptionId(),
                            e.getMessage());
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Deleted {} subscriptions for user: {}", subscriptions.size(), userId);
        }
    }

    /**
     * Delete all FIDO2 credentials for a user Only called when deleting account completely (not
     * when just deleting data)
     */
    private void deleteFIDO2CredentialsForUser(final String userId) {
        final List<FIDO2CredentialTable> credentials =
                fido2CredentialRepository.findByUserId(userId);

        for (final FIDO2CredentialTable credential : credentials) {
            try {
                fido2CredentialRepository.delete(credential.getCredentialId());
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete FIDO2 credential {}: {}",
                            credential.getCredentialId(),
                            e.getMessage());
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Deleted {} FIDO2 credentials for user: {}", credentials.size(), userId);
        }
    }

    /** Delete push-notification device-token rows registered to this user. */
    private void deleteDeviceTokensForUser(final String userId) {
        final List<DeviceTokenTable> tokens = deviceTokenRepository.findByUserId(userId);
        int deleted = 0;
        for (final DeviceTokenTable token : tokens) {
            try {
                deviceTokenRepository.delete(token.getUserId(), token.getDeviceToken());
                deleted++;
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete device-token for user {}: {}",
                            userId,
                            e.getMessage());
                }
            }
        }
        LOGGER.info("Deleted {} device-tokens for user: {}", deleted, userId);
    }

    /** Delete anomaly-feedback rows (dismissed/confirmed fingerprints) for this user. */
    private void deleteAnomalyFeedbackForUser(final String userId) {
        try {
            final int deleted = anomalyFeedbackRepository.deleteByUserId(userId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted {} anomaly-feedback rows for user: {}", deleted, userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete anomaly-feedback for user {}: {}",
                        userId,
                        e.getMessage());
            }
        }
    }

    /** Delete net-worth snapshot history for this user. */
    private void deleteNetWorthSnapshotsForUser(final String userId) {
        try {
            final int deleted = netWorthSnapshotRepository.deleteByUserId(userId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted {} net-worth snapshots for user: {}", deleted, userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete net-worth snapshots for user {}: {}",
                        userId,
                        e.getMessage());
            }
        }
    }

    /**
     * Delete this user's category-learning footprint — per-user UserCorrection rows (records of "I
     * edited this categorization") and CustomMerchantMapping rows (the user's pinned
     * merchant→category overrides). Both contribute to the per-user ML personalization layer and
     * must be wiped on GDPR data deletion.
     */
    private void deleteCategoryLearningForUser(final String userId) {
        try {
            final int corrections = userCorrectionRepository.deleteByUserId(userId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted {} user-corrections for user: {}", corrections, userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete user-corrections for user {}: {}",
                        userId,
                        e.getMessage());
            }
        }
        try {
            final int mappings = customMerchantMappingRepository.deleteByUserId(userId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted {} custom-merchant-mappings for user: {}", mappings, userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete custom-merchant-mappings for user {}: {}",
                        userId,
                        e.getMessage());
            }
        }
    }

    /** Delete the single per-user preferences row. */
    private void deleteUserPreferencesForUser(final String userId) {
        try {
            userPreferencesRepository.deleteByUserId(userId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Deleted user-preferences row for user: {}", userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete user-preferences for user {}: {}",
                        userId,
                        e.getMessage());
            }
        }
    }

    /** Delete all import history for a user */
    private void deleteImportHistoryForUser(final String userId) {
        try {
            final List<com.budgetbuddy.model.ImportHistory> imports =
                    importHistoryService.getUserImportHistory(userId);
            if (imports.isEmpty()) {
                LOGGER.debug("No import history records found for user: {}", userId);
                return;
            }

            int successfulDeletions = 0;
            int failedDeletions = 0;
            for (final com.budgetbuddy.model.ImportHistory importHistory : imports) {
                try {
                    importHistoryService.deleteImportHistory(importHistory.getImportId());
                    successfulDeletions++;
                } catch (Exception e) {
                    failedDeletions++;
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Failed to delete import history {}: {}",
                                importHistory.getImportId(),
                                e.getMessage());
                    }
                }
            }

            if (failedDeletions == 0) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Deleted {} import history records for user: {}",
                            successfulDeletions,
                            userId);
                }
            } else {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Deleted {} of {} import history records for user: {} ({} failed)",
                            successfulDeletions,
                            imports.size(),
                            userId,
                            failedDeletions);
                }
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to delete import history for user {}: {}", userId, e.getMessage());
            }
            // Don't fail deletion if import history deletion fails
        }
    }

    /**
     * Delete all S3 files for a user (exports, imports, account attachments) CRITICAL FIX: Takes
     * accountIds as parameter instead of calling findByUserId because accounts are already deleted
     * when this is called
     */
    private void deleteS3FilesForUser(final String userId, final List<String> accountIds) {
        if (s3Service == null) {
            LOGGER.debug("S3Service not available - skipping S3 file deletion");
            return;
        }

        try {
            int totalDeleted = 0;

            // Delete user export files (e.g., "exports/user_{userId}_*.gz")
            try {
                final String exportPrefix = String.format("exports/user_%s_", userId);
                final int deletedCount = s3Service.deleteFilesByPrefix(exportPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "Deleted {} S3 export files for user: {}", deletedCount, userId);
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete S3 export files for user {}: {}",
                            userId,
                            e.getMessage());
                }
            }

            // Delete account-specific files (e.g., "accounts/{userId}/{accountId}/...")
            // CRITICAL FIX: Use accountIds parameter instead of calling findByUserId (accounts are
            // already deleted)
            for (final String accountId : accountIds) {
                try {
                    final String accountPrefix =
                            String.format("accounts/%s/%s/", userId, accountId);
                    final int deletedCount = s3Service.deleteFilesByPrefix(accountPrefix);
                    totalDeleted += deletedCount;
                    if (deletedCount > 0) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Deleted {} S3 files for account {} (user: {})",
                                    deletedCount,
                                    accountId,
                                    userId);
                        }
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Failed to delete S3 files for account {}: {}",
                                accountId,
                                e.getMessage());
                    }
                }
            }

            // Delete import files if any (e.g., "imports/{userId}/...")
            try {
                final String importPrefix = String.format("imports/%s/", userId);
                final int deletedCount = s3Service.deleteFilesByPrefix(importPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "Deleted {} S3 import files for user: {}", deletedCount, userId);
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete S3 import files for user {}: {}",
                            userId,
                            e.getMessage());
                }
            }

            // Delete any other user-specific files (e.g., "users/{userId}/...")
            try {
                final String userPrefix = String.format("users/%s/", userId);
                final int deletedCount = s3Service.deleteFilesByPrefix(userPrefix);
                totalDeleted += deletedCount;
                if (deletedCount > 0) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Deleted {} S3 user files for user: {}", deletedCount, userId);
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Failed to delete S3 user files for user {}: {}",
                            userId,
                            e.getMessage());
                }
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Completed S3 file deletion for user: {} (total files deleted: {})",
                        userId,
                        totalDeleted);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Failed to delete S3 files for user {}: {}", userId, e.getMessage());
            }
            // Don't fail deletion if S3 deletion fails
        }
    }

    /**
     * Evict all Spring caches for a user This ensures cached data doesn't persist after deletion
     */
    private void evictUserCaches(final String userId) {
        if (cacheManager == null) {
            LOGGER.debug("CacheManager not available - skipping cache eviction");
            return;
        }

        try {
            // Evict all caches that might contain user data
            final String[] cacheNames = {
                "users",
                "userProfiles",
                "accounts",
                "accountBalances",
                "transactions",
                "transactionSummaries",
                "budgets",
                "goals",
                "transactionActions",
                "subscriptions",
                "fido2Credentials",
                "analytics"
            };

            for (final String cacheName : cacheNames) {
                final org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    // Evict entries for this user
                    // Note: Cache keys vary by cache type, so we evict all entries
                    // This is safe because we're deleting all user data anyway
                    cache.clear();
                    LOGGER.debug("Evicted cache: {}", cacheName);
                }
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Evicted all caches for user: {}", userId);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Failed to evict caches for user {}: {}", userId, e.getMessage());
            }
            // Don't fail deletion if cache eviction fails
        }
    }
}
