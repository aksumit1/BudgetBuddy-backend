package com.budgetbuddy.service.plaid;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.budgetbuddy.service.correctness.BalanceReconciliationService;
import com.budgetbuddy.util.IdGenerator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Service for syncing accounts from Plaid Extracted from PlaidSyncService for better modularity */
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class PlaidAccountSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidAccountSyncService.class);

    private final PlaidService plaidService;
    private final AccountRepository accountRepository;

    @SuppressWarnings("unused") // Reserved for future account categorization
    private final PlaidCategoryMapper categoryMapper;

    private final PlaidDataExtractor dataExtractor;
    private final BalanceReconciliationService balanceReconciliation;

    public PlaidAccountSyncService(
            final PlaidService plaidService,
            final AccountRepository accountRepository,
            final PlaidCategoryMapper categoryMapper,
            final PlaidDataExtractor dataExtractor,
            final BalanceReconciliationService balanceReconciliation) {
        this.plaidService = plaidService;
        this.accountRepository = accountRepository;
        this.categoryMapper = categoryMapper;
        this.dataExtractor = dataExtractor;
        this.balanceReconciliation = balanceReconciliation;
    }

    /**
     * Sync accounts for a user
     *
     * @param user The user to sync accounts for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID - if provided, checks for existing accounts before
     *     making API call
     */
    public void syncAccounts(final UserTable user, final String accessToken, final String itemId) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            LOGGER.info(
                    "Starting account sync for user: {} (itemId: {})", user.getUserId(), itemId);

            // Check if we already have accounts for this Plaid item BEFORE making API call
            if (itemId != null && !itemId.isEmpty()) {
                final var existingAccounts = accountRepository.findByPlaidItemId(itemId);
                if (!existingAccounts.isEmpty()) {
                    LOGGER.info(
                            "Found {} existing accounts for Plaid item {} - will update with latest data from Plaid",
                            existingAccounts.size(),
                            itemId);
                } else {
                    LOGGER.debug(
                            "No existing accounts found for Plaid item {} - this appears to be a new connection",
                            itemId);
                }
            }

            final var accountsResponse = plaidService.getAccounts(accessToken);

            if (accountsResponse == null || accountsResponse.getAccounts() == null) {
                LOGGER.warn("No accounts returned from Plaid for user: {}", user.getUserId());
                return;
            }

            int syncedCount = 0;
            int errorCount = 0;

            // OPTIMIZATION: Load all existing accounts once to avoid repeated findByUserId calls
            // This prevents N+1 query problem where findByAccountNumber calls findByUserId for each
            // account
            final List<AccountTable> existingUserAccounts =
                    accountRepository.findByUserId(user.getUserId());
            final java.util.Map<String, AccountTable> accountsByPlaidId = new java.util.HashMap<>();
            final java.util.Map<String, AccountTable> accountsByNumber = new java.util.HashMap<>();

            // Build lookup maps for efficient deduplication
            for (final AccountTable existing : existingUserAccounts) {
                if (existing.getPlaidAccountId() != null
                        && !existing.getPlaidAccountId().isEmpty()) {
                    accountsByPlaidId.put(existing.getPlaidAccountId(), existing);
                }
                if (existing.getAccountNumber() != null && !existing.getAccountNumber().isEmpty()) {
                    final String key =
                            existing.getAccountNumber()
                                    + ":"
                                    + (existing.getInstitutionName() != null
                                    ? existing.getInstitutionName()
                                    : "");
                    accountsByNumber.put(key, existing);
                }
            }

            // OPTIMIZATION: Collect accounts to save and batch them at the end
            // Track which accounts are new (need saveIfNotExists) vs existing (need save)
            final java.util.List<AccountTable> accountsToSave = new java.util.ArrayList<>();
            final java.util.Set<String> newAccountIds = new java.util.HashSet<>();

            for (final var plaidAccount : accountsResponse.getAccounts()) {
                try {
                    final String accountId = dataExtractor.extractAccountId(plaidAccount);
                    if (accountId == null || accountId.isEmpty()) {
                        LOGGER.error(
                                "Account ID is null or empty, skipping. Account type: {}",
                                plaidAccount.getClass().getName());
                        errorCount++;
                        continue;
                    }

                    LOGGER.debug("Extracted account ID: {}", accountId);

                    // OPTIMIZATION: Use in-memory lookup maps instead of database queries
                    // Check if account exists by plaidAccountId first
                    Optional<AccountTable> existingAccount =
                            Optional.ofNullable(accountsByPlaidId.get(accountId));

                    // If not found, check by account number + institution using in-memory map
                    if (existingAccount.isEmpty()) {
                        final AccountTable tempAccount = new AccountTable();
                        dataExtractor.updateAccountFromPlaid(tempAccount, plaidAccount);
                        final String accountNumber = tempAccount.getAccountNumber();

                        String institutionName = null;
                        if (accountsResponse.getItem() != null
                                && accountsResponse.getItem().getInstitutionId() != null) {
                            institutionName = accountsResponse.getItem().getInstitutionId();
                        }

                        if (accountNumber != null && !accountNumber.isEmpty()) {
                            // Try with institution name first, then without
                            final String keyWithInstitution =
                                    accountNumber
                                            + ":"
                                            + (institutionName != null ? institutionName : "");
                            final String keyWithoutInstitution = accountNumber + ":";

                            AccountTable foundByNumber = accountsByNumber.get(keyWithInstitution);
                            if (foundByNumber == null
                                    && institutionName != null
                                    && !institutionName.isEmpty()) {
                                foundByNumber = accountsByNumber.get(keyWithoutInstitution);
                            }

                            if (foundByNumber != null) {
                                existingAccount = Optional.of(foundByNumber);
                                // Update Plaid ID if missing (will save later in batch)
                                if (foundByNumber.getPlaidAccountId() == null
                                        || foundByNumber.getPlaidAccountId().isEmpty()) {
                                    foundByNumber.setPlaidAccountId(accountId);
                                }
                                if ((foundByNumber.getInstitutionName() == null
                                                || foundByNumber.getInstitutionName().isEmpty())
                                        && institutionName != null
                                        && !institutionName.isEmpty()) {
                                    foundByNumber.setInstitutionName(institutionName);
                                }
                            }
                        }
                    }

                    AccountTable account;
                    if (existingAccount.isPresent()) {
                        account = existingAccount.get();
                        LOGGER.debug(
                                "Updating existing account: {} (accountId: {})",
                                accountId,
                                account.getAccountId());
                        dataExtractor.updateAccountFromPlaid(account, plaidAccount);

                        if ((account.getInstitutionName() == null
                                        || account.getInstitutionName().isEmpty())
                                && accountsResponse.getItem() != null
                                && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(
                                    accountsResponse.getItem().getInstitutionId());
                        }

                        final String finalItemId =
                                itemId != null && !itemId.isEmpty()
                                        ? itemId
                                        : dataExtractor.extractItemId(accountsResponse.getItem());
                        if (finalItemId != null && !finalItemId.isEmpty()) {
                            account.setPlaidItemId(finalItemId);
                        }

                        account.setActive(true);
                        if (account.getPlaidAccountId() == null
                                || account.getPlaidAccountId().isEmpty()) {
                            account.setPlaidAccountId(accountId);
                        }
                        ensureAccountRequiredFields(account);
                        // Collect for batch save instead of saving immediately
                        // This is an existing account, so it will use save() not saveIfNotExists()
                        accountsToSave.add(account);
                    } else {
                        // Create new account
                        account = new AccountTable();
                        account.setUserId(user.getUserId());
                        account.setPlaidAccountId(accountId);
                        account.setActive(true);
                        account.setCreatedAt(java.time.Instant.now());
                        dataExtractor.updateAccountFromPlaid(account, plaidAccount);

                        if (accountsResponse.getItem() != null
                                && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(
                                    accountsResponse.getItem().getInstitutionId());
                        }

                        // Generate account ID
                        if (account.getInstitutionName() != null
                                && !account.getInstitutionName().isEmpty()) {
                            try {
                                final String generatedAccountId =
                                        IdGenerator.generateAccountId(
                                                account.getInstitutionName(), accountId);
                                // CRITICAL FIX: Normalize generated account ID to lowercase for
                                // consistency
                                final String normalizedId = IdGenerator.normalizeUUID(generatedAccountId);
                                account.setAccountId(normalizedId);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn(
                                        "Failed to generate account ID, using UUID fallback: {}",
                                        e.getMessage());
                                // CRITICAL FIX: Normalize generated UUID to lowercase for
                                // consistency
                                account.setAccountId(
                                        java.util.UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
                            }
                        } else {
                            // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                            account.setAccountId(
                                    java.util.UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
                        }

                        ensureAccountRequiredFields(account);
                        // Check if account ID already exists in our in-memory map (race condition
                        // check)
                        if (accountsByPlaidId.containsKey(accountId)
                                || (account.getAccountNumber() != null
                                        && accountsByNumber.containsKey(
                                                account.getAccountNumber()
                                                        + ":"
                                                        + (account.getInstitutionName() != null
                                                                ? account.getInstitutionName()
                                                                : "")))) {
                            LOGGER.debug(
                                    "Account {} already exists (race condition), skipping",
                                    accountId);
                            continue;
                        }
                        // Add to in-memory maps to prevent duplicates in this batch
                        accountsByPlaidId.put(accountId, account);
                        if (account.getAccountNumber() != null
                                && !account.getAccountNumber().isEmpty()) {
                            final String key =
                                    account.getAccountNumber()
                                            + ":"
                                            + (account.getInstitutionName() != null
                                            ? account.getInstitutionName()
                                            : "");
                            accountsByNumber.put(key, account);
                        }
                        // Collect for batch save
                        // This is a new account, so it will use saveIfNotExists()
                        newAccountIds.add(account.getAccountId());
                        accountsToSave.add(account);
                    }
                    syncedCount++;
                } catch (Exception e) {
                    LOGGER.error("Error syncing account: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            // Persist each account. For new accounts we use saveIfNotExists
            // (create-race protection). For existing accounts we use
            // saveWithLock so a concurrent user edit doesn't get silently
            // clobbered by this sync. On conflict we re-read the latest row,
            // re-merge only the Plaid-owned fields (balance, availableCredit,
            // lastSyncedAt) via the extractor — user-owned fields like
            // accountName (protected by accountNameOverridden) survive.
            if (!accountsToSave.isEmpty()) {
                for (final AccountTable accountToSave : accountsToSave) {
                    try {
                        // Reconcile BEFORE save so the reconciliation service
                        // can still read the previous balance from DynamoDB.
                        // After save, the prior value is gone. Skip for new
                        // accounts — there's no prior state to compare.
                        if (!newAccountIds.contains(accountToSave.getAccountId())) {
                            balanceReconciliation.reconcile(accountToSave);
                        }
                        if (newAccountIds.contains(accountToSave.getAccountId())) {
                            accountRepository.saveIfNotExists(accountToSave);
                        } else {
                            persistWithConflictRetry(accountToSave);
                        }
                    } catch (Exception saveError) {
                        LOGGER.error(
                                "Error saving account {}: {}",
                                accountToSave.getAccountId(),
                                saveError.getMessage());
                        errorCount++;
                    }
                }
                LOGGER.debug(
                        "Saved {} accounts for user: {}", accountsToSave.size(), user.getUserId());
            }

            LOGGER.info(
                    "Account sync completed for user: {} - Synced: {}, Errors: {}",
                    user.getUserId(),
                    syncedCount,
                    errorCount);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error(
                    "Error syncing accounts for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED, "Failed to sync accounts", null, null, e);
        }
    }

    private void ensureAccountRequiredFields(final AccountTable account) {
        if (account.getAccountName() == null || account.getAccountName().isEmpty()) {
            account.setAccountName("Unknown Account");
        }
        if (account.getBalance() == null) {
            account.setBalance(java.math.BigDecimal.ZERO);
        }
        if (account.getCurrencyCode() == null || account.getCurrencyCode().isEmpty()) {
            account.setCurrencyCode("USD");
        }
    }

    /**
     * Save {@code account} with optimistic concurrency. On conflict (a user renamed / edited the
     * row between our read and our write), re-read the latest row, copy only the Plaid-owned fields
     * from our in-memory copy onto it, and save again. User-owned fields (accountName behind {@code
     * accountNameOverridden}, aprPercent, rewardType, rewardMultipliers, foreignTxFeePercent) are
     * left alone — they were never ours to overwrite. Gives up after one retry to avoid looping
     * against a write-storm.
     */
    private void persistWithConflictRetry(final AccountTable account) {
        try {
            accountRepository.saveWithLock(account);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            LOGGER.info(
                    "Account {} had a concurrent user edit — re-reading and re-merging Plaid fields",
                    account.getAccountId());
            final Optional<AccountTable> fresh = accountRepository.findById(account.getAccountId());
            if (fresh.isEmpty()) {
                LOGGER.warn("Account {} disappeared during conflict retry", account.getAccountId());
                return;
            }
            final AccountTable merged = fresh.get();
            // Copy Plaid-owned fields from our sync result onto the fresh row.
            merged.setBalance(account.getBalance());
            merged.setAvailableCredit(account.getAvailableCredit());
            merged.setCreditLimit(account.getCreditLimit());
            merged.setLastSyncedAt(account.getLastSyncedAt());
            merged.setPaymentDueDate(account.getPaymentDueDate());
            merged.setMinimumPaymentDue(account.getMinimumPaymentDue());
            merged.setPlaidAccountId(account.getPlaidAccountId());
            merged.setPlaidItemId(account.getPlaidItemId());
            merged.setActive(Boolean.TRUE);
            merged.setUpdatedAt(java.time.Instant.now());
            try {
                accountRepository.saveWithLock(merged);
            } catch (
                    com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                            retry) {
                // Second conflict — the user is actively editing. The next
                // scheduled sync in a few minutes will catch up; don't thrash.
                LOGGER.warn(
                        "Account {} still conflicts after retry; skipping this sync cycle",
                        account.getAccountId());
            }
        }
    }
}
