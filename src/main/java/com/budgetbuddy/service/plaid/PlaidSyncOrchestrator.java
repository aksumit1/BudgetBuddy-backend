package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Plaid sync operations
 * Coordinates account and transaction syncing
 * Extracted from PlaidSyncService for better modularity
 */
@Service
public class PlaidSyncOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(PlaidSyncOrchestrator.class);

    private final PlaidAccountSyncService accountSyncService;
    private final PlaidTransactionSyncService transactionSyncService;

    public PlaidSyncOrchestrator(
            final PlaidAccountSyncService accountSyncService,
            final PlaidTransactionSyncService transactionSyncService) {
        this.accountSyncService = accountSyncService;
        this.transactionSyncService = transactionSyncService;
    }

    /**
     * Sync both accounts and transactions for a user
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID
     */
    public void syncAll(final UserTable user, final String accessToken, final String itemId) {
        logger.info("Starting full sync for user: {} (itemId: {})", user.getUserId(), itemId);
        
        // Sync accounts first
        accountSyncService.syncAccounts(user, accessToken, itemId);
        
        // Then sync transactions
        transactionSyncService.syncTransactions(user, accessToken);
        
        logger.info("Full sync completed for user: {}", user.getUserId());
    }

    /**
     * Sync only accounts for a user
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID
     */
    public void syncAccountsOnly(final UserTable user, final String accessToken, final String itemId) {
        logger.info("Starting account-only sync for user: {} (itemId: {})", user.getUserId(), itemId);
        accountSyncService.syncAccounts(user, accessToken, itemId);
        logger.info("Account-only sync completed for user: {}", user.getUserId());
    }

    /**
     * Sync only transactions for a user
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     */
    public void syncTransactionsOnly(final UserTable user, final String accessToken) {
        logger.info("Starting transaction-only sync for user: {}", user.getUserId());
        transactionSyncService.syncTransactions(user, accessToken);
        logger.info("Transaction-only sync completed for user: {}", user.getUserId());
    }

    /**
     * Scheduled sync for all users (runs daily)
     * Note: Requires access token storage for full implementation
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void scheduledSync() {
        logger.info("Starting scheduled Plaid sync for all users");
        
        // Note: This requires access token storage
        // For now, log that scheduled sync is running
        // The actual sync implementation requires:
        // 1. Access tokens stored securely (e.g., AWS Secrets Manager, encrypted DynamoDB table)
        // 2. A way to retrieve access token for each user
        // 3. Error handling for expired/invalid tokens
        
        logger.info("Scheduled Plaid sync completed (access token storage required for full implementation)");
    }
}

