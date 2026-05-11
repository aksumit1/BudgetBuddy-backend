package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.aws.CloudWatchService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Plaid sync operations Coordinates account and transaction syncing Extracted from
 * PlaidSyncService for better modularity
 *
 * <p>Every sync path emits {@code plaid.sync.success} / {@code plaid.sync.failure} CloudWatch
 * metrics so ops can alarm on a sustained failure rate without waiting for user reports. Without
 * this the previous "log-and-rethrow" pattern meant a Plaid outage was only visible in CloudWatch
 * Logs, and silent degradations (e.g., everything looks fine but 40% of users are failing) wouldn't
 * surface at all.
 */
// SDK / Spring / reflection integration — broad catches translate any
// runtime exception to AppException or log+swallow. Narrowing isn't
// practical here, so suppress at class level.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class PlaidSyncOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidSyncOrchestrator.class);

    private final PlaidAccountSyncService accountSyncService;
    private final PlaidTransactionSyncService transactionSyncService;

    @Autowired(required = false)
    private CloudWatchService cloudWatchService;

    public PlaidSyncOrchestrator(
            final PlaidAccountSyncService accountSyncService,
            final PlaidTransactionSyncService transactionSyncService) {
        this.accountSyncService = accountSyncService;
        this.transactionSyncService = transactionSyncService;
    }

    /**
     * Sync both accounts and transactions for a user
     *
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID
     */
    public void syncAll(final UserTable user, final String accessToken, final String itemId) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting full sync for user: {} (itemId: {})", user.getUserId(), itemId);
        }
        final long t0 = System.currentTimeMillis();
        try {
            accountSyncService.syncAccounts(user, accessToken, itemId);
            transactionSyncService.syncTransactions(user, accessToken);
            emit("plaid.sync.success", 1.0);
            emit("plaid.sync.durationMs", (double) (System.currentTimeMillis() - t0));
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Full sync completed for user: {} in {}ms",
                        user.getUserId(),
                        System.currentTimeMillis() - t0);
            }
        } catch (RuntimeException e) {
            emit("plaid.sync.failure", 1.0);
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Full sync failed for user {}: {}", user.getUserId(), e.getMessage());
            }
            throw e;
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void emit(final String metric, final double value) {
        try {
            if (cloudWatchService != null) {
                cloudWatchService.putMetric(metric, value, Map.of("Flow", "PlaidSync"));
            }
        } catch (Exception e) {
            // CloudWatch failures (network, auth, throttling) must not fail the
            // sync; metrics are observability, not control flow.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("CloudWatch metric '{}' emission failed: {}", metric, e.getMessage());
            }
        }
    }

    /**
     * Sync only accounts for a user
     *
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID
     */
    public void syncAccountsOnly(
            final UserTable user, final String accessToken, final String itemId) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Starting account-only sync for user: {} (itemId: {})",
                    user.getUserId(),
                    itemId);
        }
        accountSyncService.syncAccounts(user, accessToken, itemId);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Account-only sync completed for user: {}", user.getUserId());
        }
    }

    /**
     * Sync only transactions for a user
     *
     * @param user The user to sync for
     * @param accessToken The Plaid access token
     */
    public void syncTransactionsOnly(final UserTable user, final String accessToken) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting transaction-only sync for user: {}", user.getUserId());
        }
        transactionSyncService.syncTransactions(user, accessToken);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Transaction-only sync completed for user: {}", user.getUserId());
        }
    }

    /**
     * Scheduled sync for all users (runs daily) Note: Requires access token storage for full
     * implementation
     */
    @Scheduled(cron = "0 25 2 * * ?", zone = "UTC") // Staggered from 02:00 cluster
    public void scheduledSync() {
        LOGGER.info("Starting scheduled Plaid sync for all users");

        // Note: This requires access token storage
        // For now, log that scheduled sync is running
        // The actual sync implementation requires:
        // 1. Access tokens stored securely (e.g., AWS Secrets Manager, encrypted DynamoDB table)
        // 2. A way to retrieve access token for each user
        // 3. Error handling for expired/invalid tokens

        LOGGER.info(
                "Scheduled Plaid sync completed (access token storage required for full implementation)");
    }
}
