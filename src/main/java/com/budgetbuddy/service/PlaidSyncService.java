package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Service for syncing data from Plaid
 *
 * <p>REFACTORED: This service now delegates to specialized services: - PlaidAccountSyncService:
 * Account synchronization - PlaidTransactionSyncService: Transaction synchronization -
 * PlaidSyncOrchestrator: Coordinates sync operations
 *
 * <p>This class is kept for backward compatibility but delegates to the new services.
 *
 * <p>Note: DynamoDB doesn't use Spring's @Transactional. Use DynamoDB TransactWriteItems for
 * transactions.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class PlaidSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidSyncService.class);

    private final PlaidSyncOrchestrator syncOrchestrator;

    public PlaidSyncService(final PlaidSyncOrchestrator syncOrchestrator) {
        this.syncOrchestrator = syncOrchestrator;
    }

    // scheduledSync() below is a documented stub awaiting access-token storage; no distributed
    // lock needed because the body has no side effects. When real sync wiring lands, wrap the
    // body in `distributedLock.runOnce("plaidScheduledSync:" + today, 120, this::doSync)`.

    /**
     * Sync accounts for a user Delegates to PlaidAccountSyncService for better modularity
     *
     * @param user The user to sync accounts for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID - if provided, checks for existing accounts before
     *     making API call
     */
    public void syncAccounts(final UserTable user, final String accessToken, final String itemId) {
        // Validate inputs before delegating
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }
        syncOrchestrator.syncAccountsOnly(user, accessToken, itemId);
    }

    /**
     * Sync transactions for a user Delegates to PlaidTransactionSyncService for better modularity
     *
     * @param user The user to sync transactions for
     * @param accessToken The Plaid access token
     */
    public void syncTransactions(final UserTable user, final String accessToken) {
        // Validate inputs before delegating
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }
        syncOrchestrator.syncTransactionsOnly(user, accessToken);
    }

    /**
     * Scheduled sync for all users (runs daily) Syncs transactions for all users with active Plaid
     * accounts
     *
     * <p>Note: This implementation requires access tokens to be stored securely. In production,
     * maintain a mapping of userId -> accessToken in a secure storage.
     */
    @Scheduled(cron = "0 35 2 * * ?", zone = "UTC") // Staggered from 02:00 cluster
    public void scheduledSync() {
        LOGGER.info("Starting scheduled Plaid sync for all users");

        try {
            // Find all unique user IDs that have accounts with plaidItemId (indicating active Plaid
            // connection)
            // Note: Implementation requires access token storage - see comments below

            // OPTIMIZED: GSI on plaidItemId is now implemented in AccountRepository
            // Use accountRepository.findByPlaidItemId() to find accounts by Plaid item ID (uses
            // GSI)
            // For finding users with Plaid accounts, query accounts and extract unique userIds
            // Note: For access token storage, consider:
            // 1. Maintaining a separate table/mapping of userId -> accessToken
            // 2. Using DynamoDB Streams to maintain a list of active connections

            LOGGER.info("Scheduled sync: Scanning for users with active Plaid connections");

            // For now, we'll log that scheduled sync is running
            // The actual sync implementation requires:
            // 1. Access tokens stored securely (e.g., AWS Secrets Manager, encrypted DynamoDB
            // table)
            // 2. A way to retrieve access token for each user
            // 3. Error handling for expired/invalid tokens

            // Example implementation structure (commented out until access token storage is
            // implemented):
            /*
            for (String userId : usersWithPlaidAccounts) {
                try {
                    String accessToken = getAccessTokenForUser(userId); // Retrieve from secure storage
                    if (accessToken != null && !accessToken.isEmpty()) {
                        UserTable user = userRepository.findById(userId).orElse(null);
                        if (user != null) {
                            syncTransactions(user, accessToken);
                            LOGGER.debug("Scheduled sync completed for user: {}", userId);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to sync user {} in scheduled sync: {}", userId, e.getMessage());
                }
            }
            */

            LOGGER.info(
                    "Scheduled Plaid sync completed (access token storage required for full implementation)");

        } catch (Exception e) {
            LOGGER.error("Error in scheduled Plaid sync: {}", e.getMessage(), e);
        }
    }
}
