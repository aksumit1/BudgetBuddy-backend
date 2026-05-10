package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.plaid.PlaidDataExtractor;
import com.budgetbuddy.util.IdGenerator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Transaction Sync Service Handles real-time and scheduled transaction synchronization
 *
 * <p>Features: - Full and incremental sync - Async processing - Error handling - Transaction
 * mapping
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class TransactionSyncService {

    private static final String A_6BA7B811_9DAD_11D1_80B4_00C04FD430C8 = "6ba7b811-9dad-11d1-80b4-00c04fd430c8";

    private static final String THIS_MATCHES_I_OS_APP_FALLBACK_BEHAVIOR = "This matches iOS app fallback behavior.";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionSyncService.class);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_SYNC_DAYS = 30;

    private final PlaidService plaidService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final PlaidDataExtractor dataExtractor;
    private final SubscriptionService subscriptionService;

    public TransactionSyncService(
            final PlaidService plaidService,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final PlaidDataExtractor dataExtractor,
            final SubscriptionService subscriptionService) {
        this.plaidService = plaidService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.dataExtractor = dataExtractor;
        this.subscriptionService = subscriptionService;
    }

    /** Sync transactions for a user Fetches transactions from Plaid and updates local database */
    @Async
    public CompletableFuture<SyncResult> syncTransactions(final String userId, final String accessToken) {
        if (userId == null || userId.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            LOGGER.error(
                    "Invalid parameters for transaction sync: userId={}, accessToken={}",
                    userId != null ? "present" : "null",
                    accessToken != null ? "present" : "null");
            final SyncResult errorResult = new SyncResult();
            errorResult.setErrorCount(1);
            return CompletableFuture.completedFuture(errorResult);
        }

        try {
            LOGGER.info("Starting transaction sync for user: {}", userId);

            // Get transactions from last 30 days
            final LocalDate endDate = LocalDate.now();
            final LocalDate startDate = endDate.minusDays(DEFAULT_SYNC_DAYS);

            final var plaidResponse =
                    plaidService.getTransactions(
                            accessToken,
                            startDate.format(DATE_FORMATTER),
                            endDate.format(DATE_FORMATTER));

            if (plaidResponse == null || plaidResponse.getTransactions() == null) {
                LOGGER.warn("No transactions returned from Plaid for user: {}", userId);
                final SyncResult result = new SyncResult();
                result.setTotalProcessed(0);
                return CompletableFuture.completedFuture(result);
            }

            int newCount = 0;
            int updatedCount = 0;
            int errorCount = 0;

            for (final var plaidTransaction : plaidResponse.getTransactions()) {
                try {
                    final String plaidTransactionId = extractPlaidTransactionId(plaidTransaction);
                    if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                        LOGGER.warn("Plaid transaction ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }

                    // CRITICAL FIX: Check if transaction with this Plaid ID already exists
                    final Optional<TransactionTable> existingByPlaidId =
                            transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                    if (existingByPlaidId.isPresent()) {
                        // Transaction with this Plaid ID already exists - update it
                        final TransactionTable existing = existingByPlaidId.get();
                        updateTransactionFromPlaid(existing, plaidTransaction);
                        transactionRepository.save(existing);
                        updatedCount++;
                        LOGGER.debug(
                                "Updated existing transaction with Plaid ID: {}",
                                plaidTransactionId);
                        continue;
                    }

                    // CRITICAL FIX: Check for matching imported transaction by composite key
                    // This prevents creating duplicates when an imported transaction (no Plaid ID)
                    // matches a Plaid transaction
                    // Extract transaction details for matching BEFORE creating the transaction
                    // object
                    final String accountId = extractAccountIdFromTransaction(plaidTransaction);
                    final java.math.BigDecimal amount = extractAmountFromPlaid(plaidTransaction);
                    final String transactionDate = extractDateFromPlaid(plaidTransaction);
                    final String description = extractDescriptionFromPlaid(plaidTransaction);

                    if (accountId != null
                            && amount != null
                            && transactionDate != null
                            && description != null) {
                        // Look up account to get the backend account ID
                        final Optional<AccountTable> accountOpt =
                                accountRepository.findByPlaidAccountId(accountId);
                        if (accountOpt.isPresent()) {
                            final String backendAccountId = accountOpt.get().getAccountId();

                            // Check for matching transaction by composite key (amount, date,
                            // description)
                            final Optional<TransactionTable> matchingTransaction =
                                    transactionRepository.findByCompositeKey(
                                            backendAccountId,
                                            amount,
                                            transactionDate,
                                            description,
                                            userId);

                            if (matchingTransaction.isPresent()) {
                                final TransactionTable existing = matchingTransaction.get();

                                // Only update if existing transaction doesn't have a Plaid ID
                                // This prevents overwriting Plaid transactions with different Plaid
                                // IDs
                                if (existing.getPlaidTransactionId() == null
                                        || existing.getPlaidTransactionId().isEmpty()) {
                                    LOGGER.info(
                                            "🔄 Found matching imported transaction (no Plaid ID) - updating with Plaid ID: accountId={}, amount={}, date={}, description={}...",
                                            backendAccountId,
                                            amount,
                                            transactionDate,
                                            description.length() > 50
                                                    ? description.substring(0, 50) + "..."
                                                    : description);

                                    // Update existing transaction with Plaid data and ID
                                    existing.setPlaidTransactionId(plaidTransactionId);
                                    updateTransactionFromPlaid(existing, plaidTransaction);
                                    transactionRepository.save(existing);
                                    updatedCount++;
                                    continue;
                                } else {
                                    LOGGER.debug(
                                            "Matching transaction found but already has Plaid ID: {} (different from new: {}), creating new transaction",
                                            existing.getPlaidTransactionId(),
                                            plaidTransactionId);
                                }
                            }
                        }
                    }

                    // No matching transaction found - create new one
                    TransactionTable transaction =
                            createTransactionFromPlaid(
                                    userId, plaidTransaction, plaidTransactionId);
                    // plaidTransactionId is always set in createTransactionFromPlaid, so use Plaid
                    // deduplication
                    final boolean saved =
                            transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                    if (saved) {
                        newCount++;
                    } else {
                        // Race condition: transaction was created between our check and save
                        // Try to retrieve and update it
                        final Optional<TransactionTable> existing =
                                transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                        if (existing.isPresent()) {
                            transaction = existing.get();
                            updateTransactionFromPlaid(transaction, plaidTransaction);
                            transactionRepository.save(transaction);
                            updatedCount++;
                        } else {
                            LOGGER.warn(
                                    "Transaction with Plaid ID {} already exists but could not be retrieved",
                                    plaidTransactionId);
                            errorCount++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to sync transaction: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            final SyncResult result = new SyncResult();
            result.setNewCount(newCount);
            result.setUpdatedCount(updatedCount);
            result.setErrorCount(errorCount);
            result.setTotalProcessed(newCount + updatedCount + errorCount);

            LOGGER.info(
                    "Transaction sync completed for user: {} - New: {}, Updated: {}, Errors: {}",
                    userId,
                    newCount,
                    updatedCount,
                    errorCount);

            // CRITICAL FIX: Automatically detect subscriptions after Plaid sync
            // Run asynchronously to avoid blocking the response
            final int finalNewCount = newCount; // Capture for lambda
            if (finalNewCount > 0) {
                try {
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    final java.util.List<com.budgetbuddy.model.Subscription> detected =
                                            subscriptionService.detectSubscriptions(userId);
                                    if (!detected.isEmpty()) {
                                        subscriptionService.saveSubscriptions(userId, detected);
                                        LOGGER.info(
                                                "Detected {} subscriptions after Plaid sync ({} new transactions)",
                                                detected.size(),
                                                finalNewCount);
                                    }
                                } catch (Exception e) {
                                    LOGGER.warn(
                                            "Failed to detect subscriptions after Plaid sync: {}",
                                            e.getMessage());
                                    // Don't fail the sync if subscription detection fails
                                }
                            });
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to trigger subscription detection after Plaid sync: {}",
                            e.getMessage());
                    // Don't fail the sync if subscription detection fails
                }
            }

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            LOGGER.error("Transaction sync failed for user {}: {}", userId, e.getMessage(), e);
            final SyncResult result = new SyncResult();
            result.setErrorCount(1);
            result.setErrorMessage(e.getMessage());
            return CompletableFuture.completedFuture(result);
        }
    }

    /** Sync transactions incrementally (only new/updated) */
    @Async
    public CompletableFuture<SyncResult> syncIncremental(
            final String userId, final String accessToken, LocalDate sinceDate) {
        if (userId == null || userId.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            LOGGER.error("Invalid parameters for incremental sync");
            final SyncResult errorResult = new SyncResult();
            errorResult.setErrorCount(1);
            return CompletableFuture.completedFuture(errorResult);
        }

        if (sinceDate == null) {
            sinceDate = LocalDate.now().minusDays(DEFAULT_SYNC_DAYS);
        }

        try {
            LOGGER.info(
                    "Starting incremental transaction sync for user: {} since: {}",
                    userId,
                    sinceDate);

            final LocalDate endDate = LocalDate.now();

            final var plaidResponse =
                    plaidService.getTransactions(
                            accessToken,
                            sinceDate.format(DATE_FORMATTER),
                            endDate.format(DATE_FORMATTER));

            if (plaidResponse == null || plaidResponse.getTransactions() == null) {
                LOGGER.warn("No transactions returned from Plaid for incremental sync");
                final SyncResult result = new SyncResult();
                result.setTotalProcessed(0);
                return CompletableFuture.completedFuture(result);
            }

            int newCount = 0;
            int updatedCount = 0;

            for (final var plaidTransaction : plaidResponse.getTransactions()) {
                try {
                    final String plaidTransactionId = extractPlaidTransactionId(plaidTransaction);
                    if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                        continue;
                    }

                    // CRITICAL FIX: Check if transaction with this Plaid ID already exists
                    final Optional<TransactionTable> existingByPlaidId =
                            transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                    if (existingByPlaidId.isPresent()) {
                        // Transaction with this Plaid ID already exists - update it
                        final TransactionTable existing = existingByPlaidId.get();
                        updateTransactionFromPlaid(existing, plaidTransaction);
                        transactionRepository.save(existing);
                        updatedCount++;
                        LOGGER.debug(
                                "Updated existing transaction with Plaid ID: {}",
                                plaidTransactionId);
                        continue;
                    }

                    // CRITICAL FIX: Check for matching imported transaction by composite key
                    // This prevents creating duplicates when an imported transaction (no Plaid ID)
                    // matches a Plaid transaction
                    final String accountId = extractAccountIdFromTransaction(plaidTransaction);
                    final java.math.BigDecimal amount = extractAmountFromPlaid(plaidTransaction);
                    final String transactionDate = extractDateFromPlaid(plaidTransaction);
                    final String description = extractDescriptionFromPlaid(plaidTransaction);

                    if (accountId != null
                            && amount != null
                            && transactionDate != null
                            && description != null) {
                        // Look up account to get the backend account ID
                        final Optional<AccountTable> accountOpt =
                                accountRepository.findByPlaidAccountId(accountId);
                        if (accountOpt.isPresent()) {
                            final String backendAccountId = accountOpt.get().getAccountId();

                            // Check for matching transaction by composite key (amount, date,
                            // description)
                            final Optional<TransactionTable> matchingTransaction =
                                    transactionRepository.findByCompositeKey(
                                            backendAccountId,
                                            amount,
                                            transactionDate,
                                            description,
                                            userId);

                            if (matchingTransaction.isPresent()) {
                                final TransactionTable existing = matchingTransaction.get();

                                // Only update if existing transaction doesn't have a Plaid ID
                                if (existing.getPlaidTransactionId() == null
                                        || existing.getPlaidTransactionId().isEmpty()) {
                                    LOGGER.info(
                                            "🔄 Found matching imported transaction (no Plaid ID) - updating with Plaid ID: accountId={}, amount={}, date={}, description={}...",
                                            backendAccountId,
                                            amount,
                                            transactionDate,
                                            description.length() > 50
                                                    ? description.substring(0, 50) + "..."
                                                    : description);

                                    // Update existing transaction with Plaid data and ID
                                    existing.setPlaidTransactionId(plaidTransactionId);
                                    updateTransactionFromPlaid(existing, plaidTransaction);
                                    transactionRepository.save(existing);
                                    updatedCount++;
                                    continue;
                                } else {
                                    LOGGER.debug(
                                            "Matching transaction found but already has Plaid ID: {} (different from new: {}), creating new transaction",
                                            existing.getPlaidTransactionId(),
                                            plaidTransactionId);
                                }
                            }
                        }
                    }

                    // No matching transaction found - create new one
                    TransactionTable transaction =
                            createTransactionFromPlaid(
                                    userId, plaidTransaction, plaidTransactionId);
                    // plaidTransactionId is always set in createTransactionFromPlaid, so use Plaid
                    // deduplication
                    final boolean saved =
                            transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                    if (saved) {
                        newCount++;
                    } else {
                        // Race condition: transaction was created between our check and save
                        // Try to retrieve and update it
                        final Optional<TransactionTable> existing =
                                transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                        if (existing.isPresent()) {
                            transaction = existing.get();
                            updateTransactionFromPlaid(transaction, plaidTransaction);
                            transactionRepository.save(transaction);
                            updatedCount++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error(
                            "Failed to sync transaction in incremental sync: {}", e.getMessage());
                }
            }

            final SyncResult result = new SyncResult();
            result.setNewCount(newCount);
            result.setUpdatedCount(updatedCount);
            result.setTotalProcessed(newCount + updatedCount);

            // CRITICAL FIX: Automatically detect subscriptions after incremental Plaid sync
            // Run asynchronously to avoid blocking the response
            final int finalNewCount = newCount; // Capture for lambda
            if (finalNewCount > 0) {
                try {
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    final java.util.List<com.budgetbuddy.model.Subscription> detected =
                                            subscriptionService.detectSubscriptions(userId);
                                    if (!detected.isEmpty()) {
                                        subscriptionService.saveSubscriptions(userId, detected);
                                        LOGGER.info(
                                                "Detected {} subscriptions after incremental Plaid sync ({} new transactions)",
                                                detected.size(),
                                                finalNewCount);
                                    }
                                } catch (Exception e) {
                                    LOGGER.warn(
                                            "Failed to detect subscriptions after incremental Plaid sync: {}",
                                            e.getMessage());
                                    // Don't fail the sync if subscription detection fails
                                }
                            });
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to trigger subscription detection after incremental Plaid sync: {}",
                            e.getMessage());
                    // Don't fail the sync if subscription detection fails
                }
            }

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            LOGGER.error("Incremental sync failed for user {}: {}", userId, e.getMessage(), e);
            final SyncResult result = new SyncResult();
            result.setErrorCount(1);
            result.setErrorMessage(e.getMessage());
            return CompletableFuture.completedFuture(result);
        }
    }

    /** Extract Plaid transaction ID from Plaid transaction object */
    private String extractPlaidTransactionId(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                return transaction.getTransactionId();
            }

            // Fallback: try reflection
            final java.lang.reflect.Method getTransactionId =
                    plaidTransaction.getClass().getMethod("getTransactionId");
            final Object transactionId = getTransactionId.invoke(plaidTransaction);
            if (transactionId != null) {
                return transactionId.toString();
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Could not extract transaction ID from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract account ID from Plaid transaction Plaid transactions have an account_id field that
     * references the Plaid account
     */
    private String extractAccountIdFromTransaction(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                return transaction.getAccountId();
            }

            // Fallback: try reflection
            final java.lang.reflect.Method getAccountId =
                    plaidTransaction.getClass().getMethod("getAccountId");
            final Object accountId = getAccountId.invoke(plaidTransaction);
            if (accountId != null) {
                return accountId.toString();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not extract account ID from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /** Extract amount from Plaid transaction */
    private java.math.BigDecimal extractAmountFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getAmount() != null) {
                    return java.math.BigDecimal.valueOf(transaction.getAmount());
                }
            }

            // Fallback: try reflection
            final java.lang.reflect.Method getAmount = plaidTransaction.getClass().getMethod("getAmount");
            final Object amount = getAmount.invoke(plaidTransaction);
            if (amount != null) {
                if (amount instanceof Double) {
                    return java.math.BigDecimal.valueOf((Double) amount);
                } else if (amount instanceof Number) {
                    return java.math.BigDecimal.valueOf(((Number) amount).doubleValue());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not extract amount from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /** Extract date from Plaid transaction (returns YYYY-MM-DD format) */
    private String extractDateFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getDate() != null) {
                    // Plaid date is a LocalDate - convert to YYYY-MM-DD format
                    return transaction.getDate().format(DATE_FORMATTER);
                }
            }

            // Fallback: try reflection
            final java.lang.reflect.Method getDate = plaidTransaction.getClass().getMethod("getDate");
            final Object date = getDate.invoke(plaidTransaction);
            if (date != null) {
                if (date instanceof LocalDate) {
                    return ((LocalDate) date).format(DATE_FORMATTER);
                } else {
                    return date.toString();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not extract date from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /** Extract description from Plaid transaction (name or merchantName) */
    private String extractDescriptionFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                final String name = transaction.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
                final String merchantName = transaction.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    return merchantName;
                }
            }

            // Fallback: try reflection
            try {
                final java.lang.reflect.Method getName = plaidTransaction.getClass().getMethod("getName");
                final Object name = getName.invoke(plaidTransaction);
                if (name != null && !name.toString().isEmpty()) {
                    return name.toString();
                }
            } catch (Exception e) {
                // Try merchantName instead
            }

            try {
                final java.lang.reflect.Method getMerchantName =
                        plaidTransaction.getClass().getMethod("getMerchantName");
                final Object merchantName = getMerchantName.invoke(plaidTransaction);
                if (merchantName != null && !merchantName.toString().isEmpty()) {
                    return merchantName.toString();
                }
            } catch (Exception e) {
                // Ignore
            }
        } catch (Exception e) {
            LOGGER.warn("Could not extract description from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Create TransactionTable from Plaid transaction CRITICAL: Uses
     * IdGenerator.generateTransactionId to ensure consistency with iOS app Format:
     * institutionName:accountId:plaidTransactionId (UUID v5) Sets plaidTransactionId to enable
     * proper Plaid-based deduplication
     */
    private TransactionTable createTransactionFromPlaid(
            final String userId, final Object plaidTransaction, final String plaidTransactionId) {
        final TransactionTable transaction = new TransactionTable();

        // CRITICAL: Generate transaction ID using: bank name + account id + transaction id
        // This ensures consistency between app and backend (matches PlaidSyncService and iOS app)
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            // Extract account ID from Plaid transaction
            final String plaidAccountId = extractAccountIdFromTransaction(plaidTransaction);

            if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                // Look up account to get institution name and generated account ID
                final Optional<AccountTable> accountOpt =
                        accountRepository.findByPlaidAccountId(plaidAccountId);

                if (accountOpt.isPresent()) {
                    final AccountTable account = accountOpt.get();
                    final String institutionName = account.getInstitutionName();
                    final String accountId = account.getAccountId();

                    if (institutionName != null
                            && !institutionName.isEmpty()
                            && accountId != null
                            && !accountId.isEmpty()) {
                        try {
                            // Use IdGenerator.generateTransactionId (matches iOS app and
                            // PlaidSyncService)
                            final String generatedTransactionId =
                                    IdGenerator.generateTransactionId(
                                            institutionName, accountId, plaidTransactionId);
                            transaction.setTransactionId(generatedTransactionId);
                            transaction.setAccountId(
                                    accountId); // Set account ID for the transaction
                            LOGGER.debug(
                                    "Generated transaction ID: {} from institution: {}, account: {}, Plaid TX: {}",
                                    generatedTransactionId,
                                    institutionName,
                                    accountId,
                                    plaidTransactionId);
                        } catch (IllegalArgumentException e) {
                            // Fallback: generate deterministic UUID from Plaid transaction ID if ID
                            // generation fails
                            // CRITICAL: Use TRANSACTION_NAMESPACE (6ba7b811) not ACCOUNT_NAMESPACE
                            // (6ba7b810)
                            // This ensures consistency with PlaidSyncService and iOS app fallback
                            // logic
                            // This matches iOS app's
                            // generateTransactionIdFallback(plaidTransactionId:) method
                            LOGGER.warn(
                                    "⚠️ Failed to generate transaction ID, using deterministic UUID fallback: {}. "
                                            + THIS_MATCHES_I_OS_APP_FALLBACK_BEHAVIOR,
                                    e.getMessage());
                            final java.util.UUID namespaceUUID =
                                    java.util.UUID.fromString(
                                            A_6BA7B811_9DAD_11D1_80B4_00C04FD430C8); // TRANSACTION_NAMESPACE
                            transaction.setTransactionId(
                                    IdGenerator.generateDeterministicUUID(
                                            namespaceUUID, plaidTransactionId));
                            transaction.setAccountId(accountId);
                            LOGGER.debug(
                                    "Generated fallback transaction ID: {} from Plaid ID: {}",
                                    transaction.getTransactionId(),
                                    plaidTransactionId);
                        }
                    } else {
                        // Missing institution name or account ID - use fallback
                        // CRITICAL: Use TRANSACTION_NAMESPACE (6ba7b811) not ACCOUNT_NAMESPACE
                        // (6ba7b810)
                        // This matches iOS app's generateTransactionIdFallback(plaidTransactionId:)
                        // method
                        LOGGER.warn(
                                "⚠️ Institution name or account ID missing for account {}, using deterministic UUID fallback. "
                                        + THIS_MATCHES_I_OS_APP_FALLBACK_BEHAVIOR,
                                plaidAccountId);
                        final java.util.UUID namespaceUUID =
                                java.util.UUID.fromString(
                                        A_6BA7B811_9DAD_11D1_80B4_00C04FD430C8); // TRANSACTION_NAMESPACE
                        transaction.setTransactionId(
                                IdGenerator.generateDeterministicUUID(
                                        namespaceUUID, plaidTransactionId));
                        transaction.setAccountId(
                                accountId != null
                                        ? accountId
                                        : java.util.UUID.randomUUID().toString());
                        LOGGER.debug(
                                "Generated fallback transaction ID: {} from Plaid ID: {} (missing institution/account)",
                                transaction.getTransactionId(),
                                plaidTransactionId);
                    }
                } else {
                    // Account not found - use fallback
                    // CRITICAL: Both iOS app and backend use the same deterministic UUID fallback
                    // iOS app's generateTransactionIdFallback(plaidTransactionId:) uses the same
                    // logic:
                    // generateDeterministicUUID(TRANSACTION_NAMESPACE, plaidTransactionId)
                    // This ensures ID consistency between app and backend
                    LOGGER.info(
                            "Account with Plaid ID {} not found, using deterministic UUID fallback from Plaid ID for transaction ID. "
                                    + "Both iOS app and backend will generate the same ID. Plaid transaction ID: {}",
                            plaidAccountId,
                            plaidTransactionId);
                    final java.util.UUID namespaceUUID =
                            java.util.UUID.fromString(
                                    A_6BA7B811_9DAD_11D1_80B4_00C04FD430C8); // TRANSACTION_NAMESPACE
                    transaction.setTransactionId(
                            IdGenerator.generateDeterministicUUID(
                                    namespaceUUID, plaidTransactionId));
                    transaction.setAccountId(
                            java.util.UUID.randomUUID().toString()); // Temporary account ID
                    LOGGER.debug(
                            "Generated fallback transaction ID: {} from Plaid ID: {} (account not found)",
                            transaction.getTransactionId(),
                            plaidTransactionId);
                }
            } else {
                // No account ID in Plaid transaction - use fallback
                // CRITICAL: Use TRANSACTION_NAMESPACE (6ba7b811) not ACCOUNT_NAMESPACE (6ba7b810)
                // This matches iOS app's generateTransactionIdFallback(plaidTransactionId:) method
                LOGGER.warn(
                        "⚠️ Account ID not found in Plaid transaction, using deterministic UUID fallback. "
                                + THIS_MATCHES_I_OS_APP_FALLBACK_BEHAVIOR);
                final java.util.UUID namespaceUUID =
                        java.util.UUID.fromString(
                                A_6BA7B811_9DAD_11D1_80B4_00C04FD430C8); // TRANSACTION_NAMESPACE
                transaction.setTransactionId(
                        IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
                transaction.setAccountId(
                        java.util.UUID.randomUUID().toString()); // Temporary account ID
                LOGGER.debug(
                        "Generated fallback transaction ID: {} from Plaid ID: {} (no account ID in transaction)",
                        transaction.getTransactionId(),
                        plaidTransactionId);
            }
        } else {
            // Fallback: generate random UUID if Plaid ID is missing
            // CRITICAL: This matches iOS app's generateTransactionIdRandomFallback() which uses
            // UUID()
            // Both backend and iOS use random UUID when Plaid transaction ID is unavailable
            transaction.setTransactionId(java.util.UUID.randomUUID().toString());
            LOGGER.warn(
                    "⚠️ Plaid transaction ID is null or empty, generated random UUID: {}. "
                            + THIS_MATCHES_I_OS_APP_FALLBACK_BEHAVIOR,
                    transaction.getTransactionId());
            transaction.setAccountId(
                    java.util.UUID.randomUUID().toString()); // Temporary account ID
        }

        transaction.setUserId(userId);
        transaction.setPlaidTransactionId(
                plaidTransactionId); // CRITICAL: Set Plaid ID for deduplication
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());

        // CRITICAL: Use PlaidDataExtractor to properly extract and map transaction fields
        // This ensures categories are properly mapped (including ACH credit detection)
        dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction);

        return transaction;
    }

    /**
     * Update TransactionTable from Plaid transaction CRITICAL: Recalculates categories to ensure
     * ACH credits are properly categorized as income
     */
    private void updateTransactionFromPlaid(
            final TransactionTable transaction, final Object plaidTransaction) {
        // CRITICAL: Use PlaidDataExtractor to properly update transaction fields and recalculate
        // categories
        // This ensures that if a transaction was initially saved with wrong category (e.g.,
        // expense),
        // it will be re-categorized correctly (e.g., income for ACH credits) when updated
        dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction);
    }

    /** Sync Result DTO */
    public static class SyncResult {
        private int newCount;
        private int updatedCount;
        private int errorCount;
        private int totalProcessed;
        private String errorMessage;

        public int getNewCount() {
            return newCount;
        }

        public void setNewCount(final int newCount) {
            this.newCount = newCount;
        }

        public int getUpdatedCount() {
            return updatedCount;
        }

        public void setUpdatedCount(final int updatedCount) {
            this.updatedCount = updatedCount;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public void setErrorCount(final int errorCount) {
            this.errorCount = errorCount;
        }

        public int getTotalProcessed() {
            return totalProcessed;
        }

        public void setTotalProcessed(final int totalProcessed) {
            this.totalProcessed = totalProcessed;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
