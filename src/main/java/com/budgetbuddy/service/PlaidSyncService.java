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
    private final PlaidCategoryMapper categoryMapper;

    public PlaidSyncService(final PlaidService plaidService, final AccountRepository accountRepository, 
                           final TransactionRepository transactionRepository, final PlaidCategoryMapper categoryMapper) {
        this.plaidService = plaidService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryMapper = categoryMapper;
    }

    /**
     * Sync accounts for a user
     * @param user The user to sync accounts for
     * @param accessToken The Plaid access token
     * @param itemId Optional Plaid item ID - if provided, checks for existing accounts before making API call
     */
    public void syncAccounts(final UserTable user, final String accessToken, final String itemId) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            logger.info("Starting account sync for user: {} (itemId: {})", user.getUserId(), itemId);

            // CRITICAL: Check if we already have accounts for this Plaid item BEFORE making API call
            // This prevents unnecessary API calls when reconnecting to the same bank
            if (itemId != null && !itemId.isEmpty()) {
                var existingAccounts = accountRepository.findByPlaidItemId(itemId);
                if (!existingAccounts.isEmpty()) {
                    logger.info("Found {} existing accounts for Plaid item {} - will update with latest data from Plaid", 
                            existingAccounts.size(), itemId);
                    // We still need to call Plaid to get updated balances, but we know accounts exist
                    // This helps with deduplication logic below
                } else {
                    logger.debug("No existing accounts found for Plaid item {} - this appears to be a new connection", itemId);
                }
            }

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
                    // This deduplication happens BEFORE processing to prevent unnecessary work
                    Optional<AccountTable> existingAccount = accountRepository.findByPlaidAccountId(accountId);
                    
                    // If not found by plaidAccountId, also check by account number + institution
                    // This provides additional deduplication when plaidAccountId might be missing or changed
                    if (existingAccount.isEmpty()) {
                        // First update account to get account number
                        AccountTable tempAccount = new AccountTable();
                        updateAccountFromPlaid(tempAccount, plaidAccount);
                        String accountNumber = tempAccount.getAccountNumber();
                        
                        // Get institution name from Item if available
                        String institutionName = null;
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            institutionName = accountsResponse.getItem().getInstitutionId();
                        }
                        
                        // CRITICAL FIX: Check by accountNumber even if institutionName is null
                        // This handles cases where institutionName is missing but accountNumber is available
                        if (accountNumber != null && !accountNumber.isEmpty()) {
                            if (institutionName != null && !institutionName.isEmpty()) {
                                // Both available - use both for matching
                                existingAccount = accountRepository.findByAccountNumberAndInstitution(
                                        accountNumber, institutionName, user.getUserId());
                                if (existingAccount.isPresent()) {
                                    logger.info("Found existing account by account number {} and institution {} (plaidAccountId: {})", 
                                            accountNumber, institutionName, accountId);
                                }
                            } else {
                                // Institution name missing - match by accountNumber only
                                existingAccount = accountRepository.findByAccountNumber(accountNumber, user.getUserId());
                                if (existingAccount.isPresent()) {
                                    logger.warn("⚠️ Found existing account by account number {} (institution name was null) (plaidAccountId: {})", 
                                            accountNumber, accountId);
                                }
                            }
                            
                            // CRITICAL: If account was found by accountNumber but doesn't have plaidAccountId,
                            // we need to update it with the plaidAccountId to prevent future duplicates
                            if (existingAccount.isPresent()) {
                                AccountTable foundAccount = existingAccount.get();
                                if (foundAccount.getPlaidAccountId() == null || foundAccount.getPlaidAccountId().isEmpty()) {
                                    logger.info("Updating existing account with missing plaidAccountId: {} -> {}", 
                                            foundAccount.getAccountId(), accountId);
                                    foundAccount.setPlaidAccountId(accountId);
                                    accountRepository.save(foundAccount);
                                }
                                // Also update institutionName if it was missing
                                if ((foundAccount.getInstitutionName() == null || foundAccount.getInstitutionName().isEmpty()) 
                                        && institutionName != null && !institutionName.isEmpty()) {
                                    logger.info("Updating existing account with missing institutionName: {} -> {}", 
                                            foundAccount.getAccountId(), institutionName);
                                    foundAccount.setInstitutionName(institutionName);
                                    accountRepository.save(foundAccount);
                                }
                            }
                        }
                    }
                    
                    // CRITICAL FIX: Also check all user accounts for duplicates by accountNumber+institution
                    // This catches cases where plaidAccountId might be different but it's the same account
                    // This is a fallback check to catch any edge cases
                    if (existingAccount.isEmpty()) {
                        // Get all user accounts and check manually
                        var allUserAccounts = accountRepository.findByUserId(user.getUserId());
                        for (var userAccount : allUserAccounts) {
                            // Check if this account matches by accountNumber (with or without institution)
                            // CRITICAL FIX: Don't require institutionName to be non-null
                            if (userAccount.getAccountNumber() != null && !userAccount.getAccountNumber().isEmpty()) {
                                
                                // We need to get accountNumber from the Plaid account
                                AccountTable tempAccount = new AccountTable();
                                updateAccountFromPlaid(tempAccount, plaidAccount);
                                String plaidAccountNumber = tempAccount.getAccountNumber();
                                
                                String plaidInstitutionName = null;
                                if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                                    plaidInstitutionName = accountsResponse.getItem().getInstitutionId();
                                }
                                
                                // CRITICAL FIX: Match by accountNumber even if institutionName is null
                                if (plaidAccountNumber != null && !plaidAccountNumber.isEmpty() &&
                                    plaidAccountNumber.equals(userAccount.getAccountNumber())) {
                                    
                                // If both have institution names, they must match
                                // If either is missing, match by accountNumber only
                                boolean institutionMatches = true;
                                if (plaidInstitutionName != null && !plaidInstitutionName.isEmpty() &&
                                    userAccount.getInstitutionName() != null && !userAccount.getInstitutionName().isEmpty()) {
                                    // Both have institution names - they must match
                                    institutionMatches = plaidInstitutionName.equals(userAccount.getInstitutionName());
                                }
                                // If either institution name is null, we match by accountNumber only (institutionMatches stays true)
                                    
                                    if (institutionMatches) {
                                        logger.info("Found duplicate account by accountNumber{}: {} (existing: {}, new plaidAccountId: {})", 
                                                (plaidInstitutionName != null && !plaidInstitutionName.isEmpty() ? 
                                                    " + institution: " + plaidInstitutionName : " only"), 
                                                plaidAccountNumber, userAccount.getAccountId(), accountId);
                                        existingAccount = Optional.of(userAccount);
                                        
                                        // Update the existing account with plaidAccountId if missing
                                        if (userAccount.getPlaidAccountId() == null || userAccount.getPlaidAccountId().isEmpty()) {
                                            userAccount.setPlaidAccountId(accountId);
                                            accountRepository.save(userAccount);
                                        }
                                        // Update institutionName if it was missing
                                        if ((userAccount.getInstitutionName() == null || userAccount.getInstitutionName().isEmpty()) 
                                                && plaidInstitutionName != null && !plaidInstitutionName.isEmpty()) {
                                            userAccount.setInstitutionName(plaidInstitutionName);
                                            accountRepository.save(userAccount);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    AccountTable account;
                    if (existingAccount.isPresent()) {
                        account = existingAccount.get();
                        logger.debug("Updating existing account: {} (accountId: {})", accountId, account.getAccountId());
                        // Update account details
                        updateAccountFromPlaid(account, plaidAccount);
                        
                        // Update institution name from Item if available and missing
                        if ((account.getInstitutionName() == null || account.getInstitutionName().isEmpty()) 
                                && accountsResponse.getItem() != null 
                                && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(accountsResponse.getItem().getInstitutionId());
                            logger.debug("Updated institution name from Item: {}", account.getInstitutionName());
                        }
                        
                        // CRITICAL: Update plaidItemId if missing or different
                        String itemIdFromResponse = null;
                        if (accountsResponse.getItem() != null) {
                            try {
                                java.lang.reflect.Method getItemIdMethod = accountsResponse.getItem().getClass().getMethod("getItemId");
                                Object itemIdObj = getItemIdMethod.invoke(accountsResponse.getItem());
                                if (itemIdObj != null) {
                                    itemIdFromResponse = itemIdObj.toString();
                                }
                            } catch (Exception e) {
                                logger.debug("Could not extract itemId from Item object: {}", e.getMessage());
                            }
                        }
                        String finalItemId = (itemId != null && !itemId.isEmpty()) ? itemId : itemIdFromResponse;
                        if (finalItemId != null && !finalItemId.isEmpty()) {
                            account.setPlaidItemId(finalItemId);
                            logger.debug("Updated plaidItemId: {}", finalItemId);
                        }
                        
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
                        // CRITICAL FINAL CHECK: Before creating a new account, do one more comprehensive check
                        // This catches edge cases where plaidAccountId might have been missing on previous syncs
                        // or where accountNumber/institutionName matching failed due to data inconsistencies
                        var allUserAccounts = accountRepository.findByUserId(user.getUserId());
                        Optional<AccountTable> finalCheckAccount = Optional.empty();
                        
                        // Get accountNumber and institutionName from Plaid account for comparison
                        AccountTable tempAccountForCheck = new AccountTable();
                        updateAccountFromPlaid(tempAccountForCheck, plaidAccount);
                        String plaidAccountNumber = tempAccountForCheck.getAccountNumber();
                        
                        String plaidInstitutionName = null;
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            plaidInstitutionName = accountsResponse.getItem().getInstitutionId();
                        }
                        
                        for (var userAccount : allUserAccounts) {
                            // Check by plaidAccountId (in case it was added later or we missed it)
                            if (userAccount.getPlaidAccountId() != null && userAccount.getPlaidAccountId().equals(accountId)) {
                                logger.warn("⚠️ Found existing account by plaidAccountId in final check: {} (accountId: {})", 
                                        accountId, userAccount.getAccountId());
                                finalCheckAccount = Optional.of(userAccount);
                                break;
                            }
                            
                            // Check by accountNumber + institutionName (comprehensive check)
                            // CRITICAL FIX: Also check by accountNumber only if institutionName is missing
                            if (plaidAccountNumber != null && !plaidAccountNumber.isEmpty() &&
                                userAccount.getAccountNumber() != null && !userAccount.getAccountNumber().isEmpty() &&
                                plaidAccountNumber.equals(userAccount.getAccountNumber())) {
                                
                                // If both have institution names, they must match
                                // If either is missing, match by accountNumber only
                                boolean institutionMatches = true;
                                if (plaidInstitutionName != null && !plaidInstitutionName.isEmpty() &&
                                    userAccount.getInstitutionName() != null && !userAccount.getInstitutionName().isEmpty()) {
                                    // Both have institution names - they must match
                                    institutionMatches = plaidInstitutionName.equals(userAccount.getInstitutionName());
                                }
                                // If either institution name is null, we match by accountNumber only
                                
                                if (institutionMatches) {
                                    logger.warn("⚠️ Found existing account by accountNumber{} in final check: {} (accountId: {})", 
                                            (plaidInstitutionName != null && !plaidInstitutionName.isEmpty() ? 
                                                " + institution: " + plaidInstitutionName : " only"), 
                                            plaidAccountNumber, userAccount.getAccountId());
                                    finalCheckAccount = Optional.of(userAccount);
                                    
                                    // Update with plaidAccountId if missing
                                    if (userAccount.getPlaidAccountId() == null || userAccount.getPlaidAccountId().isEmpty()) {
                                        userAccount.setPlaidAccountId(accountId);
                                        accountRepository.save(userAccount);
                                    }
                                    // Update institutionName if it was missing
                                    if ((userAccount.getInstitutionName() == null || userAccount.getInstitutionName().isEmpty()) 
                                            && plaidInstitutionName != null && !plaidInstitutionName.isEmpty()) {
                                        userAccount.setInstitutionName(plaidInstitutionName);
                                        accountRepository.save(userAccount);
                                    }
                                    break;
                                }
                            }
                        }
                        
                        if (finalCheckAccount.isPresent()) {
                            // Use the existing account instead of creating a new one
                            account = finalCheckAccount.get();
                            logger.info("Updating existing account found in final check: {} (accountId: {}, plaidAccountId: {})", 
                                    accountId, account.getAccountId(), account.getPlaidAccountId());
                            updateAccountFromPlaid(account, plaidAccount);
                            account.setActive(true);
                            if (account.getPlaidAccountId() == null || account.getPlaidAccountId().isEmpty()) {
                                account.setPlaidAccountId(accountId);
                            }
                            accountRepository.save(account);
                            syncedCount++;
                            continue; // Skip to next account
                        }
                        
                        // Create new account (no duplicate found after all checks)
                        account = new AccountTable();
                        account.setUserId(user.getUserId());
                        account.setPlaidAccountId(accountId);
                        account.setActive(true); // Set active to true for new accounts
                        account.setCreatedAt(java.time.Instant.now());
                        logger.debug("Creating new account with Plaid ID: {} (no duplicates found after comprehensive checks)", accountId);
                        // Update account details (this should populate name, type, balance, etc.)
                        updateAccountFromPlaid(account, plaidAccount);
                        
                        // Set institution name from Item if available
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(accountsResponse.getItem().getInstitutionId());
                            logger.debug("Set institution name from Item: {}", account.getInstitutionName());
                        }
                        
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
     * OPTIMIZED: Batches all accounts into a single Plaid API call to reduce API requests
     * Uses per-account incremental sync: filters transactions per account based on account-specific lastSyncedAt
     * For first sync (no lastSyncedAt), fetches ALL available transactions (up to 2 years)
     * CRITICAL: Checks lastSyncedAt BEFORE making API calls to prevent unnecessary Plaid API requests
     */
    public void syncTransactions(final UserTable user, final String accessToken) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User cannot be null");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            logger.info("Starting batched transaction sync for user: {}", user.getUserId());

            LocalDate endDate = LocalDate.now();
            
            // Get all user accounts to sync
            var userAccounts = accountRepository.findByUserId(user.getUserId());
            
            if (userAccounts.isEmpty()) {
                logger.warn("No accounts found for user: {}", user.getUserId());
                return;
            }
            
            // CRITICAL: Filter out accounts that were recently synced (within 5 minutes)
            // This prevents unnecessary API calls when reconnecting
            var accountsToSync = new java.util.ArrayList<AccountTable>();
            for (var account : userAccounts) {
                if (account.getLastSyncedAt() != null) {
                    java.time.Instant now = java.time.Instant.now();
                    java.time.Duration timeSinceLastSync = java.time.Duration.between(account.getLastSyncedAt(), now);
                    if (timeSinceLastSync.toMinutes() < 5) {
                        logger.info("Skipping transaction sync for account {} - last synced {} minutes ago (less than 5 minutes threshold)", 
                                account.getAccountId(), timeSinceLastSync.toMinutes());
                        continue;
                    }
                }
                accountsToSync.add(account);
            }
            
            if (accountsToSync.isEmpty()) {
                logger.info("All accounts were recently synced - skipping transaction sync");
                return;
            }
            
            logger.info("Syncing transactions for {} accounts ({} skipped due to recent sync)", 
                    accountsToSync.size(), userAccounts.size() - accountsToSync.size());
            
            // OPTIMIZATION: Find the earliest lastSyncedAt across all accounts to minimize date range
            // For first sync (no lastSyncedAt), use 2 years back (Plaid maximum)
            LocalDate earliestStartDate = null;
            
            for (var account : accountsToSync) {
                if (account.getLastSyncedAt() == null) {
                    // First sync - use maximum range
                    earliestStartDate = endDate.minusYears(2);
                    logger.info("First sync detected for account {} - using maximum date range (2 years)", 
                            account.getAccountId());
                    break; // If any account is first sync, use max range
                } else {
                    // Incremental sync - use the account's lastSyncedAt date
                    java.time.ZonedDateTime lastSyncedZoned = account.getLastSyncedAt().atZone(java.time.ZoneId.of("UTC"));
                    LocalDate accountStartDate = lastSyncedZoned.toLocalDate();
                    
                    if (earliestStartDate == null || accountStartDate.isBefore(earliestStartDate)) {
                        earliestStartDate = accountStartDate;
                    }
                }
            }
            
            if (earliestStartDate == null) {
                // Fallback: should not happen, but use 2 years if somehow all accounts have null lastSyncedAt
                earliestStartDate = endDate.minusYears(2);
                logger.warn("No start date determined - using 2 years back as fallback");
            }
            
            logger.info("Batched transaction sync: fetching transactions from {} to {} for {} accounts", 
                    earliestStartDate, endDate, accountsToSync.size());
            
            // OPTIMIZATION: Make ONE API call for all accounts instead of N calls
            // Plaid's /transactions/get endpoint returns transactions for ALL accounts if account_ids is not specified
            var allTransactionsResponse = plaidService.getTransactions(
                    accessToken,
                    earliestStartDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );
            
            if (allTransactionsResponse == null || allTransactionsResponse.getTransactions() == null || 
                    allTransactionsResponse.getTransactions().isEmpty()) {
                logger.warn("No transactions returned from Plaid for user: {}", user.getUserId());
                // Still update lastSyncedAt to prevent repeated API calls
                updateLastSyncedAtForAccounts(accountsToSync);
                return;
            }
            
            int totalTransactions = allTransactionsResponse.getTransactions().size();
            logger.info("Plaid returned {} total transactions (batched for {} accounts)", 
                    totalTransactions, accountsToSync.size());
            
            // OPTIMIZATION: Group transactions by account ID to process per account
            // This maintains per-account lastSyncedAt filtering while using batched API call
            java.util.Map<String, java.util.List<Object>> transactionsByAccount = new java.util.HashMap<>();
            java.util.List<Object> unassignedTransactions = new java.util.ArrayList<>();
            
            for (var plaidTransaction : allTransactionsResponse.getTransactions()) {
                String plaidAccountId = extractAccountIdFromTransaction(plaidTransaction);
                if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                    transactionsByAccount.computeIfAbsent(plaidAccountId, k -> new java.util.ArrayList<>()).add(plaidTransaction);
                } else {
                    unassignedTransactions.add(plaidTransaction);
                    logger.warn("Transaction has no account ID - will try to assign later");
                }
            }
            
            logger.info("Grouped transactions: {} accounts have transactions, {} unassigned", 
                    transactionsByAccount.size(), unassignedTransactions.size());
            
            // Process transactions per account, respecting individual lastSyncedAt dates
            int totalSyncedCount = 0;
            int totalErrorCount = 0;
            int totalDuplicateCount = 0;
            
            for (var account : accountsToSync) {
                try {
                    // Get transactions for this account
                    java.util.List<Object> accountTransactions = new java.util.ArrayList<>();
                    
                    if (account.getPlaidAccountId() != null && !account.getPlaidAccountId().isEmpty()) {
                        accountTransactions = transactionsByAccount.getOrDefault(account.getPlaidAccountId(), new java.util.ArrayList<>());
                    }
                    
                    // Also check unassigned transactions (might belong to this account)
                    // This is a fallback for transactions without account_id
                    if (!unassignedTransactions.isEmpty() && accountTransactions.isEmpty()) {
                        logger.debug("Account {} has no assigned transactions, checking {} unassigned transactions", 
                                account.getAccountId(), unassignedTransactions.size());
                    }
                    
                    if (accountTransactions.isEmpty()) {
                        logger.debug("No transactions found for account {} (Plaid account ID: {})", 
                                account.getAccountId(), account.getPlaidAccountId());
                        continue;
                    }
                    
                    // Filter transactions based on account's specific lastSyncedAt
                    // This ensures we only process transactions that are new for this account
                    java.util.List<Object> filteredTransactions = filterTransactionsByLastSyncedAt(
                            accountTransactions, account.getLastSyncedAt(), endDate);
                    
                    if (filteredTransactions.isEmpty()) {
                        logger.debug("No new transactions for account {} after filtering by lastSyncedAt", 
                                account.getAccountId());
                        continue;
                    }
                    
                    logger.info("Processing {} transactions for account {} (filtered from {} total)", 
                            filteredTransactions.size(), account.getAccountId(), accountTransactions.size());
                    
                    // Process transactions for this account
                    var result = processTransactionsForAccount(user, account, filteredTransactions, account.getLastSyncedAt() == null);
                    totalSyncedCount += result.syncedCount;
                    totalErrorCount += result.errorCount;
                    totalDuplicateCount += result.duplicateCount;
                    
                } catch (Exception e) {
                    logger.error("Failed to process transactions for account {}: {}", 
                            account.getAccountId(), e.getMessage(), e);
                    totalErrorCount++;
                }
            }
            
            // Update lastSyncedAt for all successfully synced accounts
            updateLastSyncedAtForAccounts(accountsToSync);
            
            logger.info("Batched transaction sync completed: {} synced, {} duplicates, {} errors across {} accounts", 
                    totalSyncedCount, totalDuplicateCount, totalErrorCount, accountsToSync.size());
            
        } catch (AppException e) {
            // Re-throw AppException (e.g., rate limit errors)
            throw e;
        } catch (Exception e) {
            logger.error("Error syncing transactions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions", null, null, e);
        }
    }
    
    /**
     * Filter transactions based on account's lastSyncedAt date
     * For first sync (lastSyncedAt == null), returns all transactions
     * For incremental sync, returns only transactions on or after lastSyncedAt date
     */
    private java.util.List<Object> filterTransactionsByLastSyncedAt(
            final java.util.List<Object> transactions,
            final java.time.Instant lastSyncedAt,
            final LocalDate endDate) {
        
        if (lastSyncedAt == null) {
            // First sync - return all transactions
            return transactions;
        }
        
        // Incremental sync - filter by date
        LocalDate lastSyncedDate = lastSyncedAt.atZone(java.time.ZoneId.of("UTC")).toLocalDate();
        java.util.List<Object> filtered = new java.util.ArrayList<>();
        
        for (var transaction : transactions) {
            try {
                // Extract transaction date
                String transactionDateStr = extractTransactionDate(transaction);
                if (transactionDateStr != null && !transactionDateStr.isEmpty()) {
                    LocalDate transactionDate = LocalDate.parse(transactionDateStr);
                    // Include transactions on or after lastSyncedAt date
                    if (!transactionDate.isBefore(lastSyncedDate)) {
                        filtered.add(transaction);
                    }
                } else {
                    // If we can't determine date, include it (better safe than sorry)
                    filtered.add(transaction);
                }
            } catch (Exception e) {
                logger.debug("Could not filter transaction by date: {}", e.getMessage());
                // Include transaction if we can't determine date
                filtered.add(transaction);
            }
        }
        
        return filtered;
    }
    
    /**
     * Extract transaction date from Plaid transaction
     */
    private String extractTransactionDate(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getDate() != null) {
                    return transaction.getDate().toString();
                }
            }
            
            // Fallback: try reflection
            java.lang.reflect.Method getDateMethod = plaidTransaction.getClass().getMethod("getDate");
            Object dateObj = getDateMethod.invoke(plaidTransaction);
            if (dateObj != null) {
                return dateObj.toString();
            }
        } catch (Exception e) {
            logger.debug("Could not extract transaction date: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Process transactions for a specific account
     * Returns sync statistics
     */
    private static class SyncResult {
        int syncedCount = 0;
        int errorCount = 0;
        int duplicateCount = 0;
    }
    
    private SyncResult processTransactionsForAccount(
            final UserTable user,
            final AccountTable account,
            final java.util.List<Object> plaidTransactions,
            final boolean isFirstSync) {
        
        SyncResult result = new SyncResult();
        
        if (isFirstSync) {
            logger.info("First sync detected for account {} - processing {} transactions", 
                    account.getAccountId(), plaidTransactions.size());
        }
        
        for (var plaidTransaction : plaidTransactions) {
            try {
                String transactionId = extractTransactionId(plaidTransaction);
                if (transactionId == null || transactionId.isEmpty()) {
                    logger.warn("Transaction ID is null or empty, skipping");
                    result.errorCount++;
                    continue;
                }

                // CRITICAL: Always check if transaction already exists to prevent duplicates
                // First check by plaidTransactionId (primary method)
                Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(transactionId);
                
                // CRITICAL FIX: If not found by plaidTransactionId, check by composite key
                // This handles cases where plaidTransactionId changes due to reconnection/relinking
                if (existing.isEmpty()) {
                    // Extract transaction details for composite key lookup
                    java.math.BigDecimal amount = extractAmount(plaidTransaction);
                    String transactionDate = extractTransactionDate(plaidTransaction);
                    String description = extractDescription(plaidTransaction);
                    String merchantName = extractMerchantName(plaidTransaction);
                    
                    // Use description or merchantName for matching
                    String matchKey = (description != null && !description.isEmpty()) ? description : merchantName;
                    
                    if (amount != null && transactionDate != null && matchKey != null && !matchKey.isEmpty()) {
                        existing = transactionRepository.findByCompositeKey(
                                account.getAccountId(),
                                amount,
                                transactionDate,
                                matchKey,
                                user.getUserId());
                        
                        if (existing.isPresent()) {
                            logger.info("Found existing transaction by composite key (plaidTransactionId changed): " +
                                    "accountId={}, amount={}, date={}, description={}, oldPlaidId={}, newPlaidId={}",
                                    account.getAccountId(), amount, transactionDate, matchKey,
                                    existing.get().getPlaidTransactionId(), transactionId);
                        }
                    }
                }
                
                if (existing.isPresent()) {
                    // Transaction already exists - update it (don't create duplicate)
                    TransactionTable transaction = existing.get();
                    
                    // CRITICAL: Update plaidTransactionId if it changed (due to reconnection/relinking)
                    if (!transactionId.equals(transaction.getPlaidTransactionId())) {
                        logger.info("Updating transaction with new plaidTransactionId: old={}, new={}, transactionId={}",
                                transaction.getPlaidTransactionId(), transactionId, transaction.getTransactionId());
                        transaction.setPlaidTransactionId(transactionId);
                    }
                    
                    updateTransactionFromPlaid(transaction, plaidTransaction);
                    transactionRepository.save(transaction);
                    result.syncedCount++;
                    result.duplicateCount++;
                    logger.debug("Updated existing transaction (deduplicated): plaidTransactionId={}, transactionId={}", 
                            transactionId, transaction.getTransactionId());
                } else {
                    // Create new transaction
                    String accountIdForTransaction = account.getAccountId();
                    String institutionNameForTransaction = account.getInstitutionName();
                    
                    TransactionTable transaction = createTransactionFromPlaid(
                        user.getUserId(), 
                        plaidTransaction,
                        institutionNameForTransaction,
                        accountIdForTransaction
                    );
                    
                    // Ensure plaidTransactionId is set
                    if (transaction.getPlaidTransactionId() == null || transaction.getPlaidTransactionId().isEmpty()) {
                        transaction.setPlaidTransactionId(transactionId);
                    }
                    
                    // Use conditional write to prevent duplicate Plaid transactions
                    boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                    if (saved) {
                        result.syncedCount++;
                        logger.debug("Created new transaction: plaidTransactionId={}, transactionId={}", 
                                transactionId, transaction.getTransactionId());
                    } else {
                        // Race condition: transaction was inserted between check and save
                        Optional<TransactionTable> raceConditionExisting = transactionRepository.findByPlaidTransactionId(transactionId);
                        if (raceConditionExisting.isPresent()) {
                            transaction = raceConditionExisting.get();
                            updateTransactionFromPlaid(transaction, plaidTransaction);
                            transactionRepository.save(transaction);
                            result.syncedCount++;
                            result.duplicateCount++;
                            logger.debug("Updated transaction after race condition (deduplicated): plaidTransactionId={}, transactionId={}", 
                                    transactionId, transaction.getTransactionId());
                        } else {
                            logger.warn("Transaction with Plaid ID {} could not be saved or retrieved (possible duplicate)", transactionId);
                            result.errorCount++;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing transaction: {}", e.getMessage(), e);
                result.errorCount++;
            }
        }
        
        return result;
    }
    
    /**
     * Update lastSyncedAt for accounts after successful sync
     */
    private void updateLastSyncedAtForAccounts(final java.util.List<AccountTable> accounts) {
        java.time.Instant now = java.time.Instant.now();
        for (var account : accounts) {
            account.setLastSyncedAt(now);
            accountRepository.save(account);
        }
        logger.debug("Updated lastSyncedAt for {} accounts", accounts.size());
    }
    
    /**
     * Sync transactions for a specific account
     * Uses account-specific lastSyncedAt for incremental sync
     * For first sync (no lastSyncedAt), fetches ALL available transactions (no date limit)
     * For subsequent syncs, uses exact lastSyncedAt timestamp (date + time)
     * 
     * @deprecated This method is no longer used. Transaction syncing now uses batched fetching
     * in syncTransactions() to reduce API calls. REMOVED - use syncTransactions() instead.
     */
    @Deprecated
    @SuppressWarnings("unused") // Kept for reference only - will be removed in next version
    private void syncTransactionsForAccount(
            final UserTable user,
            final String accessToken,
            final AccountTable account,
            final LocalDate endDate) {
        
        // CRITICAL: Check if we recently synced this account BEFORE making API call
        // This prevents unnecessary Plaid API calls when reconnecting
        if (account.getLastSyncedAt() != null) {
            java.time.Instant now = java.time.Instant.now();
            java.time.Duration timeSinceLastSync = java.time.Duration.between(account.getLastSyncedAt(), now);
            // If synced within last 5 minutes, skip to prevent rate limiting
            if (timeSinceLastSync.toMinutes() < 5) {
                logger.info("Skipping transaction sync for account {} - last synced {} minutes ago (less than 5 minutes threshold)", 
                        account.getAccountId(), timeSinceLastSync.toMinutes());
                return;
            }
        }
        
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
                    // First check by plaidTransactionId (primary method)
                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(transactionId);
                    
                    // CRITICAL FIX: If not found by plaidTransactionId, check by composite key
                    // This handles cases where plaidTransactionId changes due to reconnection/relinking
                    if (existing.isEmpty()) {
                        // Extract transaction details for composite key lookup
                        java.math.BigDecimal amount = extractAmount(plaidTransaction);
                        String transactionDate = extractTransactionDate(plaidTransaction);
                        String description = extractDescription(plaidTransaction);
                        String merchantName = extractMerchantName(plaidTransaction);
                        
                        // Use description or merchantName for matching
                        String matchKey = (description != null && !description.isEmpty()) ? description : merchantName;
                        
                        // Get account ID for composite key lookup
                        String accountIdForLookup = account.getAccountId();
                        String plaidAccountIdFromTransaction = extractAccountIdFromTransaction(plaidTransaction);
                        if (plaidAccountIdFromTransaction != null && !plaidAccountIdFromTransaction.isEmpty()) {
                            Optional<AccountTable> accountForTransaction = accountRepository.findByPlaidAccountId(plaidAccountIdFromTransaction);
                            if (accountForTransaction.isPresent()) {
                                accountIdForLookup = accountForTransaction.get().getAccountId();
                            }
                        }
                        
                        if (amount != null && transactionDate != null && matchKey != null && !matchKey.isEmpty() && accountIdForLookup != null) {
                            existing = transactionRepository.findByCompositeKey(
                                    accountIdForLookup,
                                    amount,
                                    transactionDate,
                                    matchKey,
                                    user.getUserId());
                            
                            if (existing.isPresent()) {
                                logger.info("Found existing transaction by composite key (plaidTransactionId changed): " +
                                        "accountId={}, amount={}, date={}, description={}, oldPlaidId={}, newPlaidId={}",
                                        accountIdForLookup, amount, transactionDate, matchKey,
                                        existing.get().getPlaidTransactionId(), transactionId);
                            }
                        }
                    }
                    
                    if (existing.isPresent()) {
                        // Transaction already exists - update it (don't create duplicate)
                        TransactionTable transaction = existing.get();
                        
                        // CRITICAL: Update plaidTransactionId if it changed (due to reconnection/relinking)
                        if (!transactionId.equals(transaction.getPlaidTransactionId())) {
                            logger.info("Updating transaction with new plaidTransactionId: old={}, new={}, transactionId={}",
                                    transaction.getPlaidTransactionId(), transactionId, transaction.getTransactionId());
                            transaction.setPlaidTransactionId(transactionId);
                        }
                        
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
     * Reserved for future use - may be needed for account deduplication
     */
    @SuppressWarnings("unused")
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
     * Reserved for future use - may be needed for institution name extraction
     */
    @SuppressWarnings("unused")
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
     * Extract amount from Plaid transaction
     */
    private java.math.BigDecimal extractAmount(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getAmount() != null) {
                    return java.math.BigDecimal.valueOf(transaction.getAmount());
                }
            } else {
                // Fallback: try reflection
                java.lang.reflect.Method getAmount = plaidTransaction.getClass().getMethod("getAmount");
                Object amount = getAmount.invoke(plaidTransaction);
                if (amount != null && amount instanceof Number) {
                    return java.math.BigDecimal.valueOf(((Number) amount).doubleValue());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract amount from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract description from Plaid transaction
     */
    private String extractDescription(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                String name = transaction.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            } else {
                // Fallback: try reflection
                java.lang.reflect.Method getName = plaidTransaction.getClass().getMethod("getName");
                Object name = getName.invoke(plaidTransaction);
                if (name != null) {
                    return name.toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract description from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract merchant name from Plaid transaction
     */
    private String extractMerchantName(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                String merchantName = transaction.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    return merchantName;
                }
            } else {
                // Fallback: try reflection
                java.lang.reflect.Method getMerchantName = plaidTransaction.getClass().getMethod("getMerchantName");
                Object merchantName = getMerchantName.invoke(plaidTransaction);
                if (merchantName != null) {
                    return merchantName.toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract merchant name from Plaid transaction: {}", e.getMessage());
        }
        return null;
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
                
                // Extract Plaid personal finance category
                String plaidCategoryPrimary = null;
                String plaidCategoryDetailed = null;
                
                try {
                    // Get personal_finance_category from Plaid
                    var pfc = plaidTx.getPersonalFinanceCategory();
                    if (pfc != null) {
                        if (pfc.getPrimary() != null) {
                            plaidCategoryPrimary = pfc.getPrimary();
                        }
                        if (pfc.getDetailed() != null) {
                            plaidCategoryDetailed = pfc.getDetailed();
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not extract personal_finance_category (may not be available): {}", e.getMessage());
                }
                
                // Map Plaid categories to our category structure
                PlaidCategoryMapper.CategoryMapping categoryMapping;
                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                    categoryMapping = categoryMapper.mapPlaidCategory(
                        plaidCategoryPrimary,
                        plaidCategoryDetailed,
                        transaction.getMerchantName(),
                        transaction.getDescription()
                    );
                    logger.debug("Mapped Plaid category (primary: {}, detailed: {}) to (primary: {}, detailed: {})", 
                                plaidCategoryPrimary, plaidCategoryDetailed,
                                categoryMapping.getPrimary(), categoryMapping.getDetailed());
                } else {
                    // No Plaid category available - use defaults
                    categoryMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
                    logger.debug("No Plaid category available, using default: other/other");
                }
                
                // Store Plaid's original categories
                transaction.setPlaidCategoryPrimary(plaidCategoryPrimary);
                transaction.setPlaidCategoryDetailed(plaidCategoryDetailed);
                
                // Store mapped categories (can be overridden by user later)
                transaction.setCategoryPrimary(categoryMapping.getPrimary());
                transaction.setCategoryDetailed(categoryMapping.getDetailed());
                transaction.setCategoryOverridden(categoryMapping.isOverridden());
                
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
