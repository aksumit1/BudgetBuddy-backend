package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Service for syncing data from Plaid
 * Optimized to sync only changed data and use date ranges
 *
 * Features:
 * - Account synchronization
 * - Transaction synchronization
 * - Scheduled sync
 * - Error handling
 *
 * Note: DynamoDB doesn't use Spring's @Transactional. Use DynamoDB TransactWriteItems for transactions.
 */
@Service
public class PlaidSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidSyncService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    @SuppressWarnings("unused") // Reserved for future sync configuration
    private static final int DEFAULT_SYNC_DAYS = 30;
    @SuppressWarnings("unused") // Reserved for future first sync configuration
    private static final int FIRST_SYNC_MONTHS = 18; // Fetch 18 months on first sync

    private final PlaidService plaidService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public PlaidSyncService(final PlaidService plaidService, final AccountRepository accountRepository, final TransactionRepository transactionRepository) {
        this.plaidService = plaidService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Sync accounts for a user
     */
    public void syncAccounts(final UserTable user, final String accessToken) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            logger.info("Starting account sync for user: {}", user.getUserId());

            var accountsResponse = plaidService.getAccounts(accessToken);

            if (accountsResponse == null || accountsResponse.getAccounts() == null) {
                logger.warn("No accounts returned from Plaid for user: {}", user.getUserId());
                return;
            }

            int syncedCount = 0;
            int errorCount = 0;

            for (var plaidAccount : accountsResponse.getAccounts()) {
                try {
                    // Log the actual type for debugging
                    logger.debug("Processing Plaid account, type: {}", plaidAccount.getClass().getName());
                    
                    // Extract account ID - try multiple methods
                    String accountId = extractAccountId(plaidAccount);
                    if (accountId == null || accountId.isEmpty()) {
                        // Try direct method call if instanceof failed
                        try {
                            if (plaidAccount.getClass().getName().contains("AccountBase")) {
                                java.lang.reflect.Method getAccountIdMethod = plaidAccount.getClass().getMethod("getAccountId");
                                Object idObj = getAccountIdMethod.invoke(plaidAccount);
                                if (idObj != null) {
                                    accountId = idObj.toString();
                                    logger.debug("Extracted account ID via reflection: {}", accountId);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Could not extract account ID via reflection: {}", e.getMessage());
                        }
                        
                        if (accountId == null || accountId.isEmpty()) {
                            logger.error("Account ID is null or empty, skipping. Account type: {}, toString: {}", 
                                    plaidAccount.getClass().getName(), plaidAccount.toString());
                            errorCount++;
                            continue;
                        }
                    }
                    
                    logger.debug("Extracted account ID: {}", accountId);

                    // CRITICAL: Check if account exists by plaidAccountId first (primary deduplication key)
                    Optional<AccountTable> existingAccount = accountRepository.findByPlaidAccountId(accountId);
                    
                    // If not found by plaidAccountId, also check by account number + institution
                    // This provides additional deduplication when plaidAccountId might be missing or changed
                    if (existingAccount.isEmpty()) {
                        // First update account to get account number and institution name
                        AccountTable tempAccount = new AccountTable();
                        updateAccountFromPlaid(tempAccount, plaidAccount);
                        String accountNumber = tempAccount.getAccountNumber();
                        String institutionName = tempAccount.getInstitutionName();
                        if (accountNumber != null && !accountNumber.isEmpty() 
                                && institutionName != null && !institutionName.isEmpty()) {
                            existingAccount = accountRepository.findByAccountNumberAndInstitution(
                                    accountNumber, institutionName, user.getUserId());
                            if (existingAccount.isPresent()) {
                                logger.info("Found existing account by account number {} and institution {} (plaidAccountId: {})", 
                                        accountNumber, institutionName, accountId);
                            }
                        }
                    }

                    AccountTable account;
                    if (existingAccount.isPresent()) {
                        account = existingAccount.get();
                        logger.debug("Updating existing account: {} (accountId: {})", accountId, account.getAccountId());
                        // Update account details
                        updateAccountFromPlaid(account, plaidAccount);
                        // Ensure active is set to true
                        account.setActive(true);
                        // Ensure plaidAccountId is set (in case it was missing)
                        if (account.getPlaidAccountId() == null || account.getPlaidAccountId().isEmpty()) {
                            account.setPlaidAccountId(accountId);
                        }
                        // Verify required fields are set
                        if (account.getAccountName() == null || account.getAccountName().isEmpty()) {
                            logger.warn("Account name is null after update, setting default");
                            account.setAccountName("Unknown Account");
                        }
                        if (account.getBalance() == null) {
                            account.setBalance(java.math.BigDecimal.ZERO);
                        }
                        if (account.getCurrencyCode() == null || account.getCurrencyCode().isEmpty()) {
                            account.setCurrencyCode("USD");
                        }
                        accountRepository.save(account);
                        logger.debug("Updated account: {} (name: {}, balance: {})", 
                                account.getAccountId(), account.getAccountName(), account.getBalance());
                    } else {
                        // Create new account
                        account = new AccountTable();
                        account.setUserId(user.getUserId());
                        account.setPlaidAccountId(accountId);
                        account.setActive(true); // Set active to true for new accounts
                        account.setCreatedAt(java.time.Instant.now());
                        logger.debug("Creating new account with Plaid ID: {}", accountId);
                        // Update account details (this should populate name, type, balance, institutionName, etc.)
                        updateAccountFromPlaid(account, plaidAccount);
                        
                        // CRITICAL: Generate account ID using bank name + Plaid account ID for consistency
                        // This ensures the same account always gets the same ID across app and backend
                        if (account.getInstitutionName() != null && !account.getInstitutionName().isEmpty()) {
                            try {
                                String generatedAccountId = IdGenerator.generateAccountId(
                                    account.getInstitutionName(),
                                    accountId
                                );
                                account.setAccountId(generatedAccountId);
                                logger.debug("Generated account ID: {} from institution: {} and Plaid ID: {}", 
                                    generatedAccountId, account.getInstitutionName(), accountId);
                            } catch (IllegalArgumentException e) {
                                // Fallback: generate random UUID if ID generation fails
                                // CRITICAL: This matches iOS app's generateAccountIdFallback() which uses UUID()
                                // Both backend and iOS use random UUID when ID generation fails
                                logger.warn("⚠️ Failed to generate account ID, using UUID fallback: {}. " +
                                        "This matches iOS app fallback behavior.", e.getMessage());
                                account.setAccountId(java.util.UUID.randomUUID().toString());
                            }
                        } else {
                            // Fallback: generate random UUID if institution name is missing
                            // CRITICAL: This matches iOS app's generateAccountIdFallback() which uses UUID()
                            // Both backend and iOS use random UUID when institution name is unavailable
                            logger.warn("⚠️ Institution name is missing, using UUID fallback for account ID. " +
                                    "This matches iOS app fallback behavior.");
                            account.setAccountId(java.util.UUID.randomUUID().toString());
                        }
                        // Verify account has required fields before saving
                        if (account.getAccountName() == null || account.getAccountName().isEmpty()) {
                            logger.warn("Account name is null after updateAccountFromPlaid, setting default");
                            account.setAccountName("Unknown Account");
                        }
                        if (account.getBalance() == null) {
                            account.setBalance(java.math.BigDecimal.ZERO);
                        }
                        if (account.getCurrencyCode() == null || account.getCurrencyCode().isEmpty()) {
                            account.setCurrencyCode("USD");
                        }
                        // CRITICAL: Use conditional write to prevent race conditions and duplicates
                        // This checks if accountId already exists (prevents duplicate accountIds)
                        // We already checked plaidAccountId above, so this prevents race conditions
                        if (!accountRepository.saveIfNotExists(account)) {
                            // Account with same accountId already exists (race condition)
                            // Try to find it and update it instead
                            Optional<AccountTable> raceConditionAccount = accountRepository.findById(account.getAccountId());
                            if (raceConditionAccount.isPresent()) {
                                account = raceConditionAccount.get();
                                // Update the existing account
                                updateAccountFromPlaid(account, plaidAccount);
                                account.setActive(true);
                                if (account.getPlaidAccountId() == null || account.getPlaidAccountId().isEmpty()) {
                                    account.setPlaidAccountId(accountId);
                                }
                                accountRepository.save(account);
                                logger.debug("Updated account after race condition: {} (name: {}, balance: {})", 
                                        account.getAccountId(), account.getAccountName(), account.getBalance());
                            } else {
                                logger.warn("Account with ID {} already exists but could not be retrieved, skipping", account.getAccountId());
                                continue;
                            }
                        } else {
                            logger.debug("Created account: {} (name: {}, balance: {})", 
                                    account.getAccountId(), account.getAccountName(), account.getBalance());
                        }
                    }
                    syncedCount++;
                } catch (Exception e) {
                    logger.error("Error syncing account: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            logger.info("Account sync completed for user: {} - Synced: {}, Errors: {}",
                    user.getUserId(), syncedCount, errorCount);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error syncing accounts for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync accounts", null, null, e);
        }
    }

    /**
     * Sync transactions for a user
     * Uses per-account incremental sync: fetches transactions per account based on account-specific lastSyncedAt
     * For first sync (no lastSyncedAt), fetches 18 months of data
     */
    public void syncTransactions(final UserTable user, final String accessToken) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            logger.info("Starting transaction sync for user: {}", user.getUserId());

            LocalDate endDate = LocalDate.now();
            
            // Get all user accounts to sync per account
            var userAccounts = accountRepository.findByUserId(user.getUserId());
            
            if (userAccounts.isEmpty()) {
                logger.warn("No accounts found for user: {}", user.getUserId());
                return;
            }
            
            // Sync transactions per account with account-specific lastSyncedAt
            for (var account : userAccounts) {
                try {
                    syncTransactionsForAccount(user, accessToken, account, endDate);
                } catch (Exception e) {
                    logger.error("Failed to sync transactions for account {}: {}", 
                            account.getAccountId(), e.getMessage(), e);
                    // Continue with other accounts
                }
            }
            
            // Update lastSyncedAt for all accounts after successful sync
            java.time.Instant now = java.time.Instant.now();
            for (var account : userAccounts) {
                account.setLastSyncedAt(now);
                accountRepository.save(account);
            }
            logger.debug("Updated lastSyncedAt for {} accounts", userAccounts.size());
            
        } catch (Exception e) {
            logger.error("Error syncing transactions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions", null, null, e);
        }
    }
    
    /**
     * Sync transactions for a specific account
     * Uses account-specific lastSyncedAt for incremental sync
     * For first sync (no lastSyncedAt), fetches ALL available transactions (no date limit)
     * For subsequent syncs, uses exact lastSyncedAt timestamp (date + time)
     */
    private void syncTransactionsForAccount(
            final UserTable user,
            final String accessToken,
            final AccountTable account,
            final LocalDate endDate) {
        
        LocalDate startDate;
        boolean isFirstSync = false;
        
        if (account.getLastSyncedAt() == null) {
            // First sync: fetch ALL available transactions (Plaid allows up to 2 years)
            // Use maximum allowed range to get all historical data
            // CRITICAL FIX: Use minusYears(2) for Plaid's maximum, not minusDays(2)
            // Don't use time component - just use date for first fetch
            startDate = endDate.minusYears(2); // Plaid maximum is 2 years
            isFirstSync = true;
            logger.info("First sync for account {} - fetching ALL available transactions (up to 2 years, since {})", 
                    account.getAccountId(), startDate);
        } else {
            // Incremental sync: fetch since last sync date/time
            // CRITICAL FIX: Use the exact lastSyncedAt date (no time component, no day subtraction)
            // Convert Instant to LocalDate in UTC timezone
            // This ensures we get all transactions from the last sync date onwards
            java.time.ZonedDateTime lastSyncedZoned = account.getLastSyncedAt().atZone(java.time.ZoneId.of("UTC"));
            startDate = lastSyncedZoned.toLocalDate();
            // Don't subtract days - use exact date from last sync
            // Plaid API only accepts dates, not timestamps, so we use the date component
            logger.info("Incremental sync for account {} - fetching since {} (last sync timestamp: {})", 
                    account.getAccountId(), startDate, account.getLastSyncedAt());
        }

        try {
            var transactionsResponse = plaidService.getTransactions(
                    accessToken,
                    startDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );

            if (transactionsResponse == null || transactionsResponse.getTransactions() == null) {
                logger.warn("No transactions returned from Plaid for account: {}", account.getAccountId());
                return;
            }

            int totalTransactions = transactionsResponse.getTransactions().size();
            logger.info("Plaid returned {} transactions for account {} (date range: {} to {})", 
                    totalTransactions, account.getAccountId(), startDate, endDate);
            
            if (totalTransactions == 0) {
                logger.warn("Plaid returned 0 transactions for account {} in date range {} to {}. " +
                        "This may indicate no transactions exist in this range or Plaid API issue.", 
                        account.getAccountId(), startDate, endDate);
                return;
            }

            int syncedCount = 0;
            int errorCount = 0;
            int duplicateCount = 0;

            // CRITICAL: On first sync, deduplicate all transactions before saving
            // This ensures we don't create duplicates if sync runs multiple times
            if (isFirstSync) {
                logger.info("First sync detected - deduplicating {} transactions before saving", 
                        transactionsResponse.getTransactions().size());
            }

            for (var plaidTransaction : transactionsResponse.getTransactions()) {
                try {
                    String transactionId = extractTransactionId(plaidTransaction);
                    if (transactionId == null || transactionId.isEmpty()) {
                        logger.warn("Transaction ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }

                    // CRITICAL: Always check if transaction already exists to prevent duplicates
                    // This is especially important on first sync when refetching everything
                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(transactionId);
                    
                    if (existing.isPresent()) {
                        // Transaction already exists - update it (don't create duplicate)
                        TransactionTable transaction = existing.get();
                        updateTransactionFromPlaid(transaction, plaidTransaction);
                        transactionRepository.save(transaction);
                        syncedCount++;
                        duplicateCount++;
                        logger.debug("Updated existing transaction (deduplicated): plaidTransactionId={}, transactionId={}", 
                                transactionId, transaction.getTransactionId());
                    } else {
                        // Create new transaction
                        // First, get the account to extract institution name and account ID
                        // We need these to generate a consistent transaction ID
                        String accountIdForTransaction = null;
                        String institutionNameForTransaction = null;
                        
                        // Try to find account by Plaid account ID from transaction
                        String plaidAccountIdFromTransaction = extractAccountIdFromTransaction(plaidTransaction);
                        if (plaidAccountIdFromTransaction != null && !plaidAccountIdFromTransaction.isEmpty()) {
                            Optional<AccountTable> accountForTransaction = accountRepository.findByPlaidAccountId(plaidAccountIdFromTransaction);
                            if (accountForTransaction.isPresent()) {
                                accountIdForTransaction = accountForTransaction.get().getAccountId();
                                institutionNameForTransaction = accountForTransaction.get().getInstitutionName();
                            }
                        }
                        
                        // createTransactionFromPlaid will use Plaid transaction ID or generate deterministic ID
                        TransactionTable transaction = createTransactionFromPlaid(
                            user.getUserId(), 
                            plaidTransaction,
                            institutionNameForTransaction,
                            accountIdForTransaction
                        );
                        // Ensure plaidTransactionId is set (should already be set in createTransactionFromPlaid)
                        if (transaction.getPlaidTransactionId() == null || transaction.getPlaidTransactionId().isEmpty()) {
                            transaction.setPlaidTransactionId(transactionId);
                        }
                        
                        // Use conditional write to prevent duplicate Plaid transactions
                        boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                        if (saved) {
                            syncedCount++;
                            logger.debug("Created new transaction: plaidTransactionId={}, transactionId={}", 
                                    transactionId, transaction.getTransactionId());
                        } else {
                            // Race condition: transaction was inserted between check and save
                            // Fetch and update it instead of creating duplicate
                            Optional<TransactionTable> raceConditionExisting = transactionRepository.findByPlaidTransactionId(transactionId);
                            if (raceConditionExisting.isPresent()) {
                                transaction = raceConditionExisting.get();
                                updateTransactionFromPlaid(transaction, plaidTransaction);
                                transactionRepository.save(transaction);
                                syncedCount++;
                                duplicateCount++;
                                logger.debug("Updated transaction after race condition (deduplicated): plaidTransactionId={}, transactionId={}", 
                                        transactionId, transaction.getTransactionId());
                            } else {
                                logger.warn("Transaction with Plaid ID {} could not be saved or retrieved (possible duplicate)", transactionId);
                                errorCount++;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error syncing transaction: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            logger.info("Transaction sync completed for account {} - Synced: {}, Duplicates: {}, Errors: {}",
                    account.getAccountId(), syncedCount, duplicateCount, errorCount);
        } catch (com.budgetbuddy.exception.AppException e) {
            // Re-throw AppException as-is
            logger.error("AppException during transaction sync for account {}: {}", 
                    account.getAccountId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error syncing transactions for account {}: {}", account.getAccountId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions for account: " + e.getMessage(), null, null, e);
        }
    }

    /**
     * Scheduled sync for all users (runs daily)
     * Syncs transactions for all users with active Plaid accounts
     * 
     * Note: This implementation requires access tokens to be stored securely.
     * In production, maintain a mapping of userId -> accessToken in a secure storage.
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void scheduledSync() {
        logger.info("Starting scheduled Plaid sync for all users");
        
        try {
            // Find all unique user IDs that have accounts with plaidItemId (indicating active Plaid connection)
            // Note: Implementation requires access token storage - see comments below
            
            // Note: This is a simplified approach using scan
            // In production with large datasets, consider:
            // 1. Maintaining a separate GSI on plaidItemId
            // 2. Maintaining a separate table/mapping of userId -> accessToken
            // 3. Using DynamoDB Streams to maintain a list of active connections
            
            logger.info("Scheduled sync: Scanning for users with active Plaid connections");
            
            // For now, we'll log that scheduled sync is running
            // The actual sync implementation requires:
            // 1. Access tokens stored securely (e.g., AWS Secrets Manager, encrypted DynamoDB table)
            // 2. A way to retrieve access token for each user
            // 3. Error handling for expired/invalid tokens
            
            // Example implementation structure (commented out until access token storage is implemented):
            /*
            for (String userId : usersWithPlaidAccounts) {
                try {
                    String accessToken = getAccessTokenForUser(userId); // Retrieve from secure storage
                    if (accessToken != null && !accessToken.isEmpty()) {
                        UserTable user = userRepository.findById(userId).orElse(null);
                        if (user != null) {
                            syncTransactions(user, accessToken);
                            logger.debug("Scheduled sync completed for user: {}", userId);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to sync user {} in scheduled sync: {}", userId, e.getMessage());
                }
            }
            */
            
            logger.info("Scheduled Plaid sync completed (access token storage required for full implementation)");
            
        } catch (Exception e) {
            logger.error("Error in scheduled Plaid sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Extract account number (mask) from Plaid account
     */
    private String extractAccountNumber(final Object plaidAccount) {
        try {
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                com.plaid.client.model.AccountBase accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    return mask;
                }
            }
            // Try reflection
            try {
                java.lang.reflect.Method getMask = plaidAccount.getClass().getMethod("getMask");
                Object mask = getMask.invoke(plaidAccount);
                if (mask != null) {
                    return mask.toString();
                }
            } catch (Exception e) {
                logger.debug("Could not extract account mask: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("Error extracting account number: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract institution name from Plaid account
     */
    private String extractInstitutionName(final Object plaidAccount) {
        // Institution name is typically not in AccountBase, it's in the Item
        // For now, we'll get it from the account's institutionName if available
        // This is a simplified version - in production, you might need to look up the Item
        return null; // Will be set from account's institutionName field
    }

    /**
     * Extract account ID from Plaid account
     */
    private String extractAccountId(final Object plaidAccount) {
        try {
            String className = plaidAccount.getClass().getName();
            logger.debug("Extracting account ID from type: {}", className);
            
            // Try instanceof check first
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                com.plaid.client.model.AccountBase accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                String accountId = accountBase.getAccountId();
                if (accountId != null && !accountId.isEmpty()) {
                    logger.debug("Extracted account ID via instanceof: {}", accountId);
                    return accountId;
                } else {
                    logger.warn("AccountBase.getAccountId() returned null or empty");
                }
            }
            
            // Fallback: try reflection - this is more reliable across different classloaders
            try {
                java.lang.reflect.Method getAccountId = plaidAccount.getClass().getMethod("getAccountId");
                Object accountId = getAccountId.invoke(plaidAccount);
                if (accountId != null) {
                    String accountIdStr = accountId.toString();
                    // Validate it's not the entire object string
                    if (!accountIdStr.contains("class AccountBase") && !accountIdStr.contains("\n")) {
                        logger.debug("Extracted account ID via reflection: {}", accountIdStr);
                        return accountIdStr;
                    } else {
                        logger.warn("getAccountId() returned object string instead of ID: {}", accountIdStr.substring(0, Math.min(100, accountIdStr.length())));
                    }
                }
            } catch (NoSuchMethodException e) {
                logger.warn("getAccountId method not found on {}", className);
            } catch (Exception e) {
                logger.warn("Could not extract account ID using reflection: {}", e.getMessage());
            }
            
            // Last resort: log the object type for debugging
            String toString = plaidAccount.toString();
            logger.error("Failed to extract account ID from Plaid account. Type: {}, toString (first 200 chars): {}", 
                    className, toString.length() > 200 ? toString.substring(0, 200) : toString);
            return null;
        } catch (Exception e) {
            logger.error("Failed to extract account ID", e);
            return null;
        }
    }

    /**
     * Extract transaction ID from Plaid transaction
     */
    private String extractTransactionId(final Object plaidTransaction) {
        try {
            String className = plaidTransaction.getClass().getName();
            logger.debug("Extracting transaction ID from type: {}", className);
            
            // Try instanceof check first
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                String transactionId = transaction.getTransactionId();
                if (transactionId != null && !transactionId.isEmpty()) {
                    logger.debug("Extracted transaction ID via instanceof: {}", transactionId);
                    return transactionId;
                }
            }
            
            // Fallback: try reflection
            try {
                java.lang.reflect.Method getTransactionId = plaidTransaction.getClass().getMethod("getTransactionId");
                Object idObj = getTransactionId.invoke(plaidTransaction);
                if (idObj != null) {
                    String idStr = idObj.toString();
                    if (!idStr.contains("class Transaction") && !idStr.contains("\n")) {
                        logger.debug("Extracted transaction ID via reflection: {}", idStr);
                        return idStr;
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not extract transaction ID via reflection: {}", e.getMessage());
            }
            
            logger.error("Failed to extract transaction ID. Type: {}, toString: {}", 
                    className, plaidTransaction.toString().length() > 200 ? 
                    plaidTransaction.toString().substring(0, 200) : plaidTransaction.toString());
            return null;
        } catch (Exception e) {
            logger.error("Failed to extract transaction ID", e);
            return null;
        }
    }

    /**
     * Update account from Plaid account data
     * CRITICAL: Does NOT set lastSyncedAt - that should only be set after successful transaction sync
     * Setting lastSyncedAt here would cause first transaction sync to be treated as incremental sync
     */
    private void updateAccountFromPlaid(final AccountTable account, final Object plaidAccount) {
        try {
            java.time.Instant now = java.time.Instant.now();
            account.setUpdatedAt(now);
            // CRITICAL FIX: Do NOT set lastSyncedAt here
            // lastSyncedAt should only be set after successful transaction sync
            // Setting it here would cause new accounts to skip first transaction sync
            // account.setLastSyncedAt(now); // REMOVED - causes first sync to be skipped
            
            String className = plaidAccount.getClass().getName();
            logger.debug("Updating account from Plaid object, type: {}", className);
            
            // Plaid SDK returns AccountBase objects
            // Try instanceof check first
            com.plaid.client.model.AccountBase accountBase = null;
            if (plaidAccount instanceof com.plaid.client.model.AccountBase) {
                accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                logger.debug("Successfully cast to AccountBase via instanceof");
            } else {
                // Try to cast using reflection if instanceof fails (classloader issue)
                logger.warn("instanceof check failed for AccountBase, trying reflection. Type: {}", className);
                try {
                    // Try to get the AccountBase interface/class
                    Class<?> accountBaseClass = Class.forName("com.plaid.client.model.AccountBase");
                    if (accountBaseClass.isInstance(plaidAccount)) {
                        accountBase = (com.plaid.client.model.AccountBase) plaidAccount;
                        logger.debug("Successfully cast to AccountBase via Class.forName");
                    }
                } catch (ClassNotFoundException e) {
                    logger.warn("AccountBase class not found: {}", e.getMessage());
                } catch (ClassCastException e) {
                    logger.warn("Could not cast to AccountBase: {}", e.getMessage());
                }
            }
            
            if (accountBase != null) {
                
                // Extract account name - prefer official name, then name, then mask
                String officialName = accountBase.getOfficialName();
                if (officialName != null && !officialName.isEmpty()) {
                    account.setAccountName(officialName);
                } else {
                    String name = accountBase.getName();
                    if (name != null && !name.isEmpty()) {
                        account.setAccountName(name);
                    } else {
                        String mask = accountBase.getMask();
                        if (mask != null && !mask.isEmpty()) {
                            account.setAccountName("Account " + mask);
                        } else {
                            account.setAccountName("Unknown Account");
                        }
                    }
                }
                
                // Extract and store account number/mask for deduplication
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                    logger.debug("Stored account mask/number: {} for account: {}", mask, account.getAccountName());
                }
                
                // Extract account type and subtype
                if (accountBase.getType() != null) {
                    account.setAccountType(accountBase.getType().toString());
                }
                com.plaid.client.model.AccountSubtype subtype = accountBase.getSubtype();
                if (subtype != null) {
                    account.setAccountSubtype(subtype.toString());
                }
                
                // Extract balance - prefer available, then current
                if (accountBase.getBalances() != null) {
                    com.plaid.client.model.AccountBalance balances = accountBase.getBalances();
                    Double available = balances.getAvailable();
                    Double current = balances.getCurrent();
                    if (available != null) {
                        account.setBalance(java.math.BigDecimal.valueOf(available));
                    } else if (current != null) {
                        account.setBalance(java.math.BigDecimal.valueOf(current));
                    } else {
                        account.setBalance(java.math.BigDecimal.ZERO);
                    }
                    
                    // Extract currency code if available
                    if (balances.getIsoCurrencyCode() != null) {
                        account.setCurrencyCode(balances.getIsoCurrencyCode());
                    } else if (balances.getUnofficialCurrencyCode() != null) {
                        account.setCurrencyCode(balances.getUnofficialCurrencyCode());
                    } else {
                        account.setCurrencyCode("USD"); // Default
                    }
                } else {
                    account.setBalance(java.math.BigDecimal.ZERO);
                    account.setCurrencyCode("USD");
                }
                
                // Ensure active is set
                if (account.getActive() == null) {
                    account.setActive(true);
                }
                
                logger.debug("Updated account from Plaid: {} (name: {}, balance: {}, type: {})", 
                        account.getPlaidAccountId(), account.getAccountName(), account.getBalance(), account.getAccountType());
            } else {
                // Fallback: try reflection for other Plaid SDK versions
                logger.warn("Plaid account is not AccountBase instance, type: {}", plaidAccount.getClass().getName());
                try {
                    java.lang.reflect.Method getName = plaidAccount.getClass().getMethod("getName");
                    Object name = getName.invoke(plaidAccount);
                    if (name != null) {
                        account.setAccountName(name.toString());
                    }
                    
                    java.lang.reflect.Method getAccountId = plaidAccount.getClass().getMethod("getAccountId");
                    Object accountId = getAccountId.invoke(plaidAccount);
                    if (accountId != null && account.getPlaidAccountId() == null) {
                        account.setPlaidAccountId(accountId.toString());
                    }
                } catch (Exception e) {
                    logger.warn("Could not extract account fields using reflection: {}", e.getMessage());
                }
                
                // Ensure active is set even if we can't extract other fields
                if (account.getActive() == null) {
                    account.setActive(true);
                }
                
                // Set defaults if fields are still null
                if (account.getAccountName() == null) {
                    account.setAccountName("Unknown Account");
                }
                if (account.getBalance() == null) {
                    account.setBalance(java.math.BigDecimal.ZERO);
                }
                if (account.getCurrencyCode() == null) {
                    account.setCurrencyCode("USD");
                }
            }
        } catch (Exception e) {
            logger.error("Error updating account from Plaid data: {}", e.getMessage(), e);
            logger.error("Plaid account type: {}, toString: {}", 
                    plaidAccount.getClass().getName(), plaidAccount.toString());
            // Still set updatedAt and active even if mapping fails
            account.setUpdatedAt(java.time.Instant.now());
            if (account.getActive() == null) {
                account.setActive(true);
            }
            // Set defaults
            if (account.getAccountName() == null) {
                account.setAccountName("Unknown Account");
            }
            if (account.getBalance() == null) {
                account.setBalance(java.math.BigDecimal.ZERO);
            }
            if (account.getCurrencyCode() == null) {
                account.setCurrencyCode("USD");
            }
        }
    }

    /**
     * Create transaction from Plaid transaction data
     * Uses consistent ID generation: account bank + account id + transaction id
     * This ensures the same transaction always gets the same ID across app and backend
     */
    private TransactionTable createTransactionFromPlaid(
            final String userId, 
            final Object plaidTransaction,
            final String institutionName,
            final String accountId) {
        TransactionTable transaction = new TransactionTable();
        
        // Extract Plaid transaction ID first
        String plaidTransactionId = extractTransactionId(plaidTransaction);
        
        // CRITICAL: Generate transaction ID using: bank name + account id + transaction id
        // This ensures consistency between app and backend
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            if (institutionName != null && !institutionName.isEmpty() && 
                accountId != null && !accountId.isEmpty()) {
                try {
                    String generatedTransactionId = IdGenerator.generateTransactionId(
                        institutionName,
                        accountId,
                        plaidTransactionId
                    );
                    transaction.setTransactionId(generatedTransactionId);
                    logger.debug("Generated transaction ID: {} from institution: {}, account: {}, Plaid TX: {}", 
                        generatedTransactionId, institutionName, accountId, plaidTransactionId);
                } catch (IllegalArgumentException e) {
                    // Fallback: generate deterministic UUID from Plaid transaction ID if ID generation fails
                    // CRITICAL: Use TRANSACTION_NAMESPACE (6ba7b811) not ACCOUNT_NAMESPACE (6ba7b810)
                    // This ensures consistency with TransactionSyncService and iOS app fallback logic
                    // This matches iOS app's generateTransactionIdFallback(plaidTransactionId:) method
                    logger.warn("⚠️ Failed to generate transaction ID, using deterministic UUID fallback: {}. " +
                            "This matches iOS app fallback behavior.", e.getMessage());
                    java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"); // TRANSACTION_NAMESPACE
                    transaction.setTransactionId(IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
                    logger.debug("Generated fallback transaction ID: {} from Plaid ID: {}", 
                            transaction.getTransactionId(), plaidTransactionId);
                }
            } else {
                // Fallback: generate deterministic UUID from Plaid transaction ID if account info is missing
                // CRITICAL: Use TRANSACTION_NAMESPACE (6ba7b811) not ACCOUNT_NAMESPACE (6ba7b810)
                // This ensures consistency with TransactionSyncService and iOS app fallback logic
                // CRITICAL: Both iOS app and backend use the same deterministic UUID fallback
                // iOS app's generateTransactionIdFallback(plaidTransactionId:) uses the same logic:
                // generateDeterministicUUID(TRANSACTION_NAMESPACE, plaidTransactionId)
                // This ensures ID consistency between app and backend
                logger.info("Institution name or account ID missing, using deterministic UUID fallback from Plaid ID. " +
                        "Both iOS app and backend will generate the same ID. Plaid ID: {}", plaidTransactionId);
                java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"); // TRANSACTION_NAMESPACE
                transaction.setTransactionId(IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
                logger.debug("Generated fallback transaction ID: {} from Plaid ID: {} (missing institution/account). " +
                        "This matches iOS app's generateTransactionIdFallback() method.", 
                        transaction.getTransactionId(), plaidTransactionId);
            }
        } else {
            // Fallback: generate random UUID if Plaid ID is missing
            // CRITICAL: This matches iOS app's generateTransactionIdRandomFallback() which uses UUID()
            // Both backend and iOS use random UUID when Plaid transaction ID is unavailable
            transaction.setTransactionId(java.util.UUID.randomUUID().toString());
            logger.warn("⚠️ Plaid transaction ID is null or empty, generated random UUID: {}. " +
                    "This matches iOS app fallback behavior.", transaction.getTransactionId());
        }
        
        transaction.setUserId(userId);
        transaction.setPlaidTransactionId(plaidTransactionId); // Also store original Plaid ID for deduplication
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());
        
        // Update fields from Plaid
        updateTransactionFromPlaid(transaction, plaidTransaction);
        
        return transaction;
    }
    
    /**
     * Extract account ID from Plaid transaction
     * Plaid transactions have an account_id field that references the Plaid account
     */
    private String extractAccountIdFromTransaction(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                return transaction.getAccountId();
            }
            
            // Fallback: try reflection
            java.lang.reflect.Method getAccountId = plaidTransaction.getClass().getMethod("getAccountId");
            Object accountId = getAccountId.invoke(plaidTransaction);
            if (accountId != null) {
                return accountId.toString();
            }
        } catch (Exception e) {
            logger.warn("Could not extract account ID from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Update transaction from Plaid transaction data
     */
    private void updateTransactionFromPlaid(final TransactionTable transaction, final Object plaidTransaction) {
        try {
            java.time.Instant now = java.time.Instant.now();
            transaction.setUpdatedAt(now);
            
            String className = plaidTransaction.getClass().getName();
            logger.debug("Updating transaction from Plaid object, type: {}", className);
            
            // Try instanceof check first
            com.plaid.client.model.Transaction plaidTx = null;
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
            } else {
                // Try reflection fallback
                try {
                    Class<?> transactionClass = Class.forName("com.plaid.client.model.Transaction");
                    if (transactionClass.isInstance(plaidTransaction)) {
                        plaidTx = (com.plaid.client.model.Transaction) plaidTransaction;
                    }
                } catch (Exception e) {
                    logger.warn("Could not cast to Transaction via reflection: {}", e.getMessage());
                }
            }
            
            if (plaidTx != null) {
                // Extract amount
                if (plaidTx.getAmount() != null) {
                    transaction.setAmount(java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                }
                
                // Extract merchant name first (used for description fallback)
                String merchantName = plaidTx.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setMerchantName(merchantName);
                }
                
                // Extract description/name
                String name = plaidTx.getName();
                if (name != null && !name.isEmpty()) {
                    transaction.setDescription(name);
                } else if (merchantName != null && !merchantName.isEmpty()) {
                    transaction.setDescription(merchantName);
                } else {
                    transaction.setDescription("Transaction");
                }
                
                // Extract category
                java.util.List<String> categoryList = plaidTx.getCategory();
                if (categoryList != null && !categoryList.isEmpty()) {
                    transaction.setCategory(String.join(", ", categoryList));
                } else {
                    // Default to "Other" if category is null or empty
                    transaction.setCategory("Other");
                }
                
                // Extract date - CRITICAL for date range queries
                if (plaidTx.getDate() != null) {
                    // Plaid returns LocalDate, convert to YYYY-MM-DD string
                    transaction.setTransactionDate(plaidTx.getDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                    logger.debug("Set transaction date: {}", transaction.getTransactionDate());
                } else {
                    // Default to today if date is missing
                    transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                    logger.warn("Transaction date is null, using today's date");
                }
                
                // Extract currency code
                if (plaidTx.getIsoCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getIsoCurrencyCode());
                } else if (plaidTx.getUnofficialCurrencyCode() != null) {
                    transaction.setCurrencyCode(plaidTx.getUnofficialCurrencyCode());
                } else {
                    transaction.setCurrencyCode("USD");
                }
                
                // Extract pending status
                if (plaidTx.getPending() != null) {
                    transaction.setPending(plaidTx.getPending());
                }
                
                // Extract payment channel (for ACH detection)
                if (plaidTx.getPaymentChannel() != null) {
                    transaction.setPaymentChannel(plaidTx.getPaymentChannel().toString());
                }
                
                // Extract account ID
                if (plaidTx.getAccountId() != null) {
                    // Find account by Plaid account ID
                    Optional<AccountTable> account = accountRepository.findByPlaidAccountId(plaidTx.getAccountId());
                    if (account.isPresent()) {
                        transaction.setAccountId(account.get().getAccountId());
                    }
                }
                
                logger.debug("Updated transaction from Plaid: {} (date: {}, amount: {}, description: {})", 
                        transaction.getPlaidTransactionId(), transaction.getTransactionDate(), 
                        transaction.getAmount(), transaction.getDescription());
            } else {
                // Fallback: use reflection
                logger.warn("Plaid transaction is not Transaction instance, using reflection. Type: {}", className);
                try {
                    // Try to extract key fields via reflection
                    java.lang.reflect.Method getAmount = plaidTransaction.getClass().getMethod("getAmount");
                    Object amount = getAmount.invoke(plaidTransaction);
                    if (amount != null && amount instanceof Number) {
                        transaction.setAmount(java.math.BigDecimal.valueOf(((Number) amount).doubleValue()));
                    }
                    
                    java.lang.reflect.Method getName = plaidTransaction.getClass().getMethod("getName");
                    Object name = getName.invoke(plaidTransaction);
                    if (name != null) {
                        transaction.setDescription(name.toString());
                    }
                    
                    java.lang.reflect.Method getDate = plaidTransaction.getClass().getMethod("getDate");
                    Object date = getDate.invoke(plaidTransaction);
                    if (date != null) {
                        if (date instanceof java.time.LocalDate) {
                            transaction.setTransactionDate(((java.time.LocalDate) date).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                        } else {
                            transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                        }
                    } else {
                        transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                    }
                } catch (Exception e) {
                    logger.error("Could not extract transaction fields via reflection: {}", e.getMessage());
                }
                
                // Set defaults
                if (transaction.getDescription() == null || transaction.getDescription().isEmpty()) {
                    transaction.setDescription("Transaction");
                }
                if (transaction.getTransactionDate() == null || transaction.getTransactionDate().isEmpty()) {
                    transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                }
                if (transaction.getAmount() == null) {
                    transaction.setAmount(java.math.BigDecimal.ZERO);
                }
                if (transaction.getCurrencyCode() == null || transaction.getCurrencyCode().isEmpty()) {
                    transaction.setCurrencyCode("USD");
                }
            }
        } catch (Exception e) {
            logger.error("Error updating transaction from Plaid data: {}", e.getMessage(), e);
            // Set defaults even on error
            if (transaction.getTransactionDate() == null || transaction.getTransactionDate().isEmpty()) {
                transaction.setTransactionDate(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
            }
            if (transaction.getDescription() == null || transaction.getDescription().isEmpty()) {
                transaction.setDescription("Transaction");
            }
            if (transaction.getAmount() == null) {
                transaction.setAmount(java.math.BigDecimal.ZERO);
            }
            if (transaction.getCurrencyCode() == null || transaction.getCurrencyCode().isEmpty()) {
                transaction.setCurrencyCode("USD");
            }
        }
    }
}
