package com.budgetbuddy.service.plaid;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.util.IdGenerator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for syncing transactions from Plaid Extracted from PlaidSyncService for better modularity
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class PlaidTransactionSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidTransactionSyncService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PlaidService plaidService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PlaidDataExtractor dataExtractor;

    public PlaidTransactionSyncService(
            final PlaidService plaidService,
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final PlaidDataExtractor dataExtractor) {
        this.plaidService = plaidService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.dataExtractor = dataExtractor;
    }

    /** Sync transactions for a user OPTIMIZED: Batches all accounts into a single Plaid API call */
    public void syncTransactions(final UserTable user, final String accessToken) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            LOGGER.info("Starting batched transaction sync for user: {}", user.getUserId());

            final LocalDate endDate = LocalDate.now();

            // Get all user accounts to sync
            final var userAccounts = accountRepository.findByUserId(user.getUserId());

            if (userAccounts.isEmpty()) {
                LOGGER.warn("No accounts found for user: {}", user.getUserId());
                return;
            }

            // Bypass-cooldown probe: if the user has zero transactions in the DB,
            // we are in a post-link recovery window. The 5-minute cooldown is
            // designed to throttle ongoing background syncs, not to block the
            // FIRST hydration of a freshly-linked account. Without this bypass,
            // an iOS-side retry after exchange-token would silently no-op and
            // leave the UI stuck in "accounts present, no transactions". This
            // path is what bit ska@yahoo.com.
            final List<TransactionTable> existingTxnsProbe =
                    transactionRepository.findByUserId(user.getUserId(), 0, 1);
            final boolean userHasNoTransactions =
                    existingTxnsProbe == null || existingTxnsProbe.isEmpty();

            // Filter out accounts that were recently synced (within 5 minutes),
            // unless the user has no transactions yet (post-link recovery).
            final var accountsToSync = new ArrayList<AccountTable>();
            for (final var account : userAccounts) {
                if (account.getLastSyncedAt() != null && !userHasNoTransactions) {
                    final java.time.Instant now = java.time.Instant.now();
                    final java.time.Duration timeSinceLastSync =
                            java.time.Duration.between(account.getLastSyncedAt(), now);
                    if (timeSinceLastSync.toMinutes() < 5) {
                        LOGGER.info(
                                "Skipping transaction sync for account {} - last synced {} minutes ago",
                                account.getAccountId(),
                                timeSinceLastSync.toMinutes());
                        continue;
                    }
                }
                accountsToSync.add(account);
            }
            if (userHasNoTransactions) {
                LOGGER.info(
                        "Bypassing 5-minute cooldown for user {} - no transactions yet (post-link recovery)",
                        user.getUserId());
            }

            if (accountsToSync.isEmpty()) {
                LOGGER.info("All accounts were recently synced - skipping transaction sync");
                return;
            }

            LOGGER.info("Syncing transactions for {} accounts", accountsToSync.size());

            // Find the earliest lastSyncedAt across all accounts
            LocalDate earliestStartDate = null;
            for (final var account : accountsToSync) {
                if (account.getLastSyncedAt() == null) {
                    earliestStartDate = endDate.minusYears(2);
                    break;
                } else {
                    final java.time.ZonedDateTime lastSyncedZoned =
                            account.getLastSyncedAt().atZone(java.time.ZoneId.of("UTC"));
                    final LocalDate accountStartDate = lastSyncedZoned.toLocalDate();
                    if (earliestStartDate == null || accountStartDate.isBefore(earliestStartDate)) {
                        earliestStartDate = accountStartDate;
                    }
                }
            }

            if (earliestStartDate == null) {
                earliestStartDate = endDate.minusYears(2);
            }

            LOGGER.info(
                    "Batched transaction sync: fetching transactions from {} to {} for {} accounts",
                    earliestStartDate,
                    endDate,
                    accountsToSync.size());

            // Make ONE API call for all accounts
            final var allTransactionsResponse =
                    plaidService.getTransactions(
                            accessToken,
                            earliestStartDate.format(DATE_FORMATTER),
                            endDate.format(DATE_FORMATTER));

            // Plaid SDK returns @NonNull response — only check the list.
            if (allTransactionsResponse.getTransactions() == null
                    || allTransactionsResponse.getTransactions().isEmpty()) {
                LOGGER.warn("No transactions returned from Plaid for user: {}", user.getUserId());
                updateLastSyncedAtForAccounts(accountsToSync);
                return;
            }

            final int totalTransactions = allTransactionsResponse.getTransactions().size();
            LOGGER.info(
                    "Plaid returned {} total transactions (batched for {} accounts)",
                    totalTransactions,
                    accountsToSync.size());

            // Group transactions by account ID
            final Map<String, List<Object>> transactionsByAccount = new HashMap<>();
            final List<Object> unassignedTransactions = new ArrayList<>();

            for (final var plaidTransaction : allTransactionsResponse.getTransactions()) {
                final String plaidAccountId =
                        dataExtractor.extractAccountIdFromTransaction(plaidTransaction);
                if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                    transactionsByAccount
                            .computeIfAbsent(plaidAccountId, k -> new ArrayList<>())
                            .add(plaidTransaction);
                } else {
                    unassignedTransactions.add(plaidTransaction);
                }
            }

            LOGGER.info(
                    "Grouped transactions: {} accounts have transactions, {} unassigned",
                    transactionsByAccount.size(),
                    unassignedTransactions.size());

            // Process transactions per account
            int totalSyncedCount = 0;
            int totalErrorCount = 0;
            int totalDuplicateCount = 0;

            for (final var account : accountsToSync) {
                try {
                    List<Object> accountTransactions = new ArrayList<>();

                    if (account.getPlaidAccountId() != null
                            && !account.getPlaidAccountId().isEmpty()) {
                        accountTransactions =
                                transactionsByAccount.getOrDefault(
                                        account.getPlaidAccountId(), new ArrayList<>());
                    }

                    if (accountTransactions.isEmpty()) {
                        LOGGER.debug(
                                "No transactions found for account {} (Plaid account ID: {})",
                                account.getAccountId(),
                                account.getPlaidAccountId());
                        continue;
                    }

                    // Filter transactions based on account's specific lastSyncedAt
                    final List<Object> filteredTransactions =
                            filterTransactionsByLastSyncedAt(
                                    accountTransactions, account.getLastSyncedAt(), endDate);

                    if (filteredTransactions.isEmpty()) {
                        LOGGER.debug(
                                "No new transactions for account {} after filtering",
                                account.getAccountId());
                        continue;
                    }

                    LOGGER.info(
                            "Processing {} transactions for account {} (filtered from {} total)",
                            filteredTransactions.size(),
                            account.getAccountId(),
                            accountTransactions.size());

                    // Process transactions for this account
                    final var result =
                            processTransactionsForAccount(
                                    user,
                                    account,
                                    filteredTransactions,
                                    account.getLastSyncedAt() == null);
                    totalSyncedCount += result.syncedCount;
                    totalErrorCount += result.errorCount;
                    totalDuplicateCount += result.duplicateCount;

                } catch (Exception e) {
                    LOGGER.error(
                            "Failed to process transactions for account {}: {}",
                            account.getAccountId(),
                            e.getMessage(),
                            e);
                    totalErrorCount++;
                }
            }

            // Update lastSyncedAt for all successfully synced accounts
            updateLastSyncedAtForAccounts(accountsToSync);

            LOGGER.info(
                    "Batched transaction sync completed: {} synced, {} duplicates, {} errors across {} accounts",
                    totalSyncedCount,
                    totalDuplicateCount,
                    totalErrorCount,
                    accountsToSync.size());

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error(
                    "Error syncing transactions for user {}: {}",
                    user.getUserId(),
                    e.getMessage(),
                    e);
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions",
                    null,
                    null,
                    e);
        }
    }

    /** Filter transactions based on account's lastSyncedAt date */
    private List<Object> filterTransactionsByLastSyncedAt(
            final List<Object> transactions,
            final java.time.Instant lastSyncedAt,
            final LocalDate endDate) {

        if (lastSyncedAt == null) {
            return transactions;
        }

        final LocalDate lastSyncedDate =
                lastSyncedAt.atZone(java.time.ZoneId.of("UTC")).toLocalDate();
        final List<Object> filtered = new ArrayList<>();

        for (final var transaction : transactions) {
            final String transactionDateStr = extractTransactionDate(transaction);
            if (transactionDateStr != null && !transactionDateStr.isEmpty()) {
                try {
                    final LocalDate transactionDate =
                            LocalDate.parse(transactionDateStr, DATE_FORMATTER);
                    if (!transactionDate.isBefore(lastSyncedDate)) {
                        filtered.add(transaction);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not parse transaction date: {}", transactionDateStr);
                    // Include transaction if date parsing fails (better to include than exclude)
                    filtered.add(transaction);
                }
            } else {
                // Include transaction if date is missing (better to include than exclude)
                filtered.add(transaction);
            }
        }

        return filtered;
    }

    /** Extract transaction date from Plaid transaction */
    private String extractTransactionDate(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                final com.plaid.client.model.Transaction transaction =
                        (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getDate() != null) {
                    return transaction.getDate().format(DATE_FORMATTER);
                }
            }

            // Fallback: try reflection
            try {
                final java.lang.reflect.Method getDate =
                        plaidTransaction.getClass().getMethod("getDate");
                final Object date = getDate.invoke(plaidTransaction);
                if (date != null && date instanceof LocalDate) {
                    return ((LocalDate) date).format(DATE_FORMATTER);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not extract date via reflection: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.debug("Error extracting transaction date: {}", e.getMessage());
        }
        return null;
    }

    /** Process transactions for a specific account */
    private SyncResult processTransactionsForAccount(
            final UserTable user,
            final AccountTable account,
            final List<Object> plaidTransactions,
            final boolean isFirstSync) {

        int syncedCount = 0;
        int errorCount = 0;
        int duplicateCount = 0;

        final String institutionName = account.getInstitutionName();

        for (final var plaidTransaction : plaidTransactions) {
            try {
                final String plaidTransactionId =
                        dataExtractor.extractTransactionId(plaidTransaction);
                if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                    LOGGER.warn("Transaction has no Plaid ID, skipping");
                    errorCount++;
                    continue;
                }

                // CRITICAL FIX: Check if transaction with this Plaid ID already exists
                final Optional<TransactionTable> existingByPlaidId =
                        transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                if (existingByPlaidId.isPresent()) {
                    // Transaction with this Plaid ID already exists - update it
                    final TransactionTable existing = existingByPlaidId.get();
                    LOGGER.debug(
                            "Updating existing transaction with Plaid ID: {}", plaidTransactionId);
                    dataExtractor.updateTransactionFromPlaid(existing, plaidTransaction);
                    transactionRepository.save(existing);
                    duplicateCount++;
                    continue;
                }

                // CORRECTNESS FIX: pending → posted reconciliation.
                // When Plaid posts a previously-pending transaction it issues a
                // brand-new transaction_id and sets pending_transaction_id to the
                // old one. Without this block we'd insert the posted row and
                // leave the pending row untouched, double-counting the spend
                // (and often with a different amount — tip-adjusted, FX-settled).
                final String pendingPlaidId =
                        dataExtractor.extractPendingTransactionId(plaidTransaction);
                if (pendingPlaidId != null) {
                    final Optional<TransactionTable> pendingRow =
                            transactionRepository.findByPlaidTransactionId(pendingPlaidId);
                    if (pendingRow.isPresent()) {
                        final TransactionTable existing = pendingRow.get();
                        // Capture the pre-posted amount BEFORE the extractor
                        // overwrites it. This gives the UI an honest audit
                        // trail: "this $99 charge was previously pending at
                        // $100" — surfaces tip adjustments, FX settlement
                        // drift, and auth-reversal cases that otherwise look
                        // like silent balance corrections.
                        final java.math.BigDecimal priorAmount = existing.getAmount();
                        LOGGER.info(
                                "Reconciling pending→posted: pending plaidId={} → posted plaidId={} (user={}, account={}, priorAmount={})",
                                pendingPlaidId,
                                plaidTransactionId,
                                user.getUserId(),
                                account.getAccountId(),
                                priorAmount);
                        existing.setPlaidTransactionId(plaidTransactionId);
                        dataExtractor.updateTransactionFromPlaid(existing, plaidTransaction);
                        existing.setPending(Boolean.FALSE);
                        // Only record the prior amount when it actually differs
                        // from the posted amount — if they match, the pending
                        // was accurate and the audit field would be noise.
                        if (priorAmount != null
                                && existing.getAmount() != null
                                && priorAmount.compareTo(existing.getAmount()) != 0) {
                            existing.setPendingAmount(priorAmount);
                        }
                        transactionRepository.save(existing);
                        duplicateCount++;
                        continue;
                    }
                    // Pending row not found — may have been deleted by the user
                    // or we never saw the pending sync. Fall through to normal
                    // insert path so we still capture the posted transaction.
                    LOGGER.debug(
                            "Posted transaction references unknown pending id={} — inserting as new",
                            pendingPlaidId);
                }

                // CRITICAL FIX: Check for matching imported transaction by composite key
                // This prevents creating duplicates when an imported transaction (no Plaid ID)
                // matches a Plaid transaction
                final java.math.BigDecimal amount = extractAmountFromPlaid(plaidTransaction);
                final String transactionDate = extractDateFromPlaid(plaidTransaction);
                final String description = extractDescriptionFromPlaid(plaidTransaction);

                if (amount != null && transactionDate != null && description != null) {
                    // Check for matching transaction by composite key (amount, date, description)
                    final Optional<TransactionTable> matchingTransaction =
                            transactionRepository.findByCompositeKey(
                                    account.getAccountId(),
                                    amount,
                                    transactionDate,
                                    description,
                                    user.getUserId());

                    if (matchingTransaction.isPresent()) {
                        final TransactionTable existing = matchingTransaction.get();

                        // Only update if existing transaction doesn't have a Plaid ID
                        // This prevents overwriting Plaid transactions with different Plaid IDs
                        if (existing.getPlaidTransactionId() == null
                                || existing.getPlaidTransactionId().isEmpty()) {
                            LOGGER.info(
                                    "🔄 Found matching imported transaction (no Plaid ID) - updating with Plaid ID: accountId={}, amount={}, date={}, description={}...",
                                    account.getAccountId(),
                                    amount,
                                    transactionDate,
                                    description.length() > 50
                                            ? description.substring(0, 50) + "..."
                                            : description);

                            // Update existing transaction with Plaid data and ID
                            existing.setPlaidTransactionId(plaidTransactionId);
                            dataExtractor.updateTransactionFromPlaid(existing, plaidTransaction);
                            transactionRepository.save(existing);
                            duplicateCount++; // Count as duplicate (matched existing)
                            continue;
                        } else {
                            LOGGER.debug(
                                    "Matching transaction found but already has Plaid ID: {} (different from new: {}), creating new transaction",
                                    existing.getPlaidTransactionId(),
                                    plaidTransactionId);
                        }
                    }
                }

                // No matching transaction found - create new one
                final TransactionTable transaction =
                        createTransactionFromPlaid(
                                user.getUserId(),
                                plaidTransaction,
                                institutionName,
                                account.getAccountId());

                // Use saveIfPlaidTransactionNotExists to prevent duplicates (handles race
                // conditions)
                final boolean saved =
                        transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                if (saved) {
                    syncedCount++;
                } else {
                    LOGGER.debug(
                            "Transaction with Plaid ID {} already exists (race condition), skipping",
                            plaidTransactionId);
                    duplicateCount++;
                }
            } catch (Exception e) {
                LOGGER.error("Error processing transaction: {}", e.getMessage(), e);
                errorCount++;
            }
        }

        return new SyncResult(syncedCount, errorCount, duplicateCount);
    }

    /** Create transaction from Plaid transaction data */
    private TransactionTable createTransactionFromPlaid(
            final String userId,
            final Object plaidTransaction,
            final String institutionName,
            final String accountId) {
        final TransactionTable transaction = new TransactionTable();

        final String plaidTransactionId = dataExtractor.extractTransactionId(plaidTransaction);

        // Generate transaction ID
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            if (institutionName != null
                    && !institutionName.isEmpty()
                    && accountId != null
                    && !accountId.isEmpty()) {
                try {
                    final String generatedTransactionId =
                            IdGenerator.generateTransactionId(
                                    institutionName, accountId, plaidTransactionId);
                    transaction.setTransactionId(generatedTransactionId);
                } catch (IllegalArgumentException e) {
                    final java.util.UUID namespaceUUID =
                            java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
                    transaction.setTransactionId(
                            IdGenerator.generateDeterministicUUID(
                                    namespaceUUID, plaidTransactionId));
                }
            } else {
                final java.util.UUID namespaceUUID =
                        java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
                transaction.setTransactionId(
                        IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
            }
        } else {
            transaction.setTransactionId(java.util.UUID.randomUUID().toString());
        }

        transaction.setUserId(userId);
        transaction.setPlaidTransactionId(plaidTransactionId);
        transaction.setAccountId(accountId);
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());

        dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction);

        return transaction;
    }

    /** Update lastSyncedAt for accounts after successful sync */
    private void updateLastSyncedAtForAccounts(final List<AccountTable> accounts) {
        final java.time.Instant now = java.time.Instant.now();
        for (final var account : accounts) {
            account.setLastSyncedAt(now);
            account.setUpdatedAt(now);
            accountRepository.save(account);
        }
        LOGGER.info("Updated lastSyncedAt for {} accounts", accounts.size());
    }

    /**
     * Extract amount from a Plaid transaction.
     *
     * <p>Correctness notes: - Plaid's SDK returns amount as a `Double`. {@code
     * BigDecimal.valueOf(double)} is NOT lossy in the way {@code new BigDecimal(double)} is — it
     * round-trips through {@code Double.toString} which produces the shortest decimal that exactly
     * identifies the double (e.g. {@code 100.01d} → "100.01", not the IEEE-754 tail). For
     * 2-decimal-place financial values this is safe. - We still normalise to {@code scale == 2,
     * HALF_UP} so downstream equality and display layers see a canonical representation; without
     * this, a Plaid value of {@code 10.0} and a user-entered {@code 10.00} would produce different
     * DynamoDB attribute writes and break {@code compareTo} chains. - {@code NaN}/{@code Infinity}
     * are rejected defensively even though the SDK should never emit them; treating them as {@code
     * null} lets the caller skip the transaction rather than poison the sum.
     */
    private java.math.BigDecimal extractAmountFromPlaid(final Object plaidTransaction) {
        Double raw = null;
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                raw = ((com.plaid.client.model.Transaction) plaidTransaction).getAmount();
            } else {
                final Object amount =
                        plaidTransaction.getClass().getMethod("getAmount").invoke(plaidTransaction);
                if (amount instanceof Number) {
                    raw = ((Number) amount).doubleValue();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not extract amount from Plaid transaction: {}", e.getMessage());
            return null;
        }
        if (raw == null || raw.isNaN() || raw.isInfinite()) {
            return null;
        }
        return java.math.BigDecimal.valueOf(raw).setScale(2, java.math.RoundingMode.HALF_UP);
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
            final java.lang.reflect.Method getDate =
                    plaidTransaction.getClass().getMethod("getDate");
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
                final java.lang.reflect.Method getName =
                        plaidTransaction.getClass().getMethod("getName");
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

    /** Result of sync operation */
    private static class SyncResult {
        final int syncedCount;
        final int errorCount;
        final int duplicateCount;

        SyncResult(final int syncedCount, final int errorCount, final int duplicateCount) {
            this.syncedCount = syncedCount;
            this.errorCount = errorCount;
            this.duplicateCount = duplicateCount;
        }
    }
}
