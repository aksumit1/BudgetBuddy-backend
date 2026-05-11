package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.PlaidAccessTokenTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.observability.ScanRateLimiter;
import com.budgetbuddy.repository.dynamodb.PlaidAccessTokenRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
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
    private final PlaidAccessTokenRepository accessTokenRepository;
    private final UserRepository userRepository;
    private final DistributedLockService distributedLock;
    private final ScanRateLimiter scanRateLimiter;

    public PlaidSyncService(
            final PlaidSyncOrchestrator syncOrchestrator,
            final PlaidAccessTokenRepository accessTokenRepository,
            final UserRepository userRepository,
            final DistributedLockService distributedLock,
            final ScanRateLimiter scanRateLimiter) {
        this.syncOrchestrator = syncOrchestrator;
        this.accessTokenRepository = accessTokenRepository;
        this.userRepository = userRepository;
        this.distributedLock = distributedLock;
        this.scanRateLimiter = scanRateLimiter;
    }

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
     * Scheduled daily Plaid sync (02:35 UTC, staggered from the 02:00 cluster). Walks every row in
     * {@code PlaidAccessTokens}, loads the owning user, and triggers a transaction resync for that
     * (user, accessToken, itemId) tuple.
     *
     * <p>Two production safeguards layered on:
     *
     * <ul>
     *   <li><b>Distributed lock</b> — when ECS auto-scales to N tasks the cron fires on every
     *       replica; without a lock we'd re-sync every user N times, burning Plaid quota and
     *       producing duplicate-detection churn. The lock key includes the date so consecutive days
     *       are still independent.
     *   <li><b>Scan rate limiter</b> — the access-token walk is a table scan; a misconfigured cron
     *       firing hourly instead of daily could blow the AWS bill. The limiter caps concurrent
     *       scans application-wide; if it rejects, we skip the run and the next cron tick retries.
     * </ul>
     */
    @Scheduled(cron = "0 35 2 * * ?", zone = "UTC") // Staggered from 02:00 cluster
    public void scheduledSync() {
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final String lockKey = "plaidScheduledSync:" + today;
        // 4h TTL — large enough for a multi-thousand-user fan-out under Plaid's rate limits.
        distributedLock.runOnce(lockKey, 240, this::scheduledSyncInner);
    }

    private void scheduledSyncInner() {
        if (!scanRateLimiter.acquire()) {
            LOGGER.warn("Plaid scheduled sync skipped — scan rate limiter rejected permit");
            return;
        }
        int attempted = 0;
        int succeeded = 0;
        int skippedMissingUser = 0;
        try {
            LOGGER.info("Plaid scheduled sync starting (walking PlaidAccessTokens)");
            for (final PlaidAccessTokenTable row : accessTokenRepository.findAll()) {
                attempted++;
                if (row.getUserId() == null
                        || row.getAccessToken() == null
                        || row.getAccessToken().isEmpty()) {
                    continue;
                }
                try {
                    final Optional<UserTable> userOpt = userRepository.findById(row.getUserId());
                    if (userOpt.isEmpty()) {
                        skippedMissingUser++;
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Skipping orphaned access-token row userId={} itemId={}",
                                    row.getUserId(),
                                    row.getPlaidItemId());
                        }
                        continue;
                    }
                    syncOrchestrator.syncTransactionsOnly(userOpt.get(), row.getAccessToken());
                    succeeded++;
                } catch (Exception perUser) {
                    // One user's failure must not stop the rest of the fan-out. Common cases:
                    // expired access token (the user will be prompted to re-link on their next
                    // visit), Plaid rate-limit, transient AWS error. All are logged WARN so
                    // ops can see the pattern but the cron keeps going.
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Plaid scheduled sync failed for user={} item={}: {}",
                                row.getUserId(),
                                row.getPlaidItemId(),
                                perUser.getMessage());
                    }
                }
            }
            LOGGER.info(
                    "Plaid scheduled sync complete: attempted={} succeeded={} orphanedRows={}",
                    attempted,
                    succeeded,
                    skippedMissingUser);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid scheduled sync pass failed: {}", e.getMessage(), e);
            }
        } finally {
            scanRateLimiter.release();
        }
    }
}
