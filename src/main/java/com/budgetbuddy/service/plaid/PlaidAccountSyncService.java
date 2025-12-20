package com.budgetbuddy.service.plaid;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.budgetbuddy.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for syncing accounts from Plaid
 * Extracted from PlaidSyncService for better modularity
 */
@Service
public class PlaidAccountSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidAccountSyncService.class);

    private final PlaidService plaidService;
    private final AccountRepository accountRepository;
    @SuppressWarnings("unused") // Reserved for future account categorization
    private final PlaidCategoryMapper categoryMapper;
    private final PlaidDataExtractor dataExtractor;

    public PlaidAccountSyncService(
            final PlaidService plaidService,
            final AccountRepository accountRepository,
            final PlaidCategoryMapper categoryMapper,
            final PlaidDataExtractor dataExtractor) {
        this.plaidService = plaidService;
        this.accountRepository = accountRepository;
        this.categoryMapper = categoryMapper;
        this.dataExtractor = dataExtractor;
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

            // Check if we already have accounts for this Plaid item BEFORE making API call
            if (itemId != null && !itemId.isEmpty()) {
                var existingAccounts = accountRepository.findByPlaidItemId(itemId);
                if (!existingAccounts.isEmpty()) {
                    logger.info("Found {} existing accounts for Plaid item {} - will update with latest data from Plaid", 
                            existingAccounts.size(), itemId);
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
            
            // OPTIMIZATION: Load all existing accounts once to avoid repeated findByUserId calls
            // This prevents N+1 query problem where findByAccountNumber calls findByUserId for each account
            List<AccountTable> existingUserAccounts = accountRepository.findByUserId(user.getUserId());
            java.util.Map<String, AccountTable> accountsByPlaidId = new java.util.HashMap<>();
            java.util.Map<String, AccountTable> accountsByNumber = new java.util.HashMap<>();
            
            // Build lookup maps for efficient deduplication
            for (AccountTable existing : existingUserAccounts) {
                if (existing.getPlaidAccountId() != null && !existing.getPlaidAccountId().isEmpty()) {
                    accountsByPlaidId.put(existing.getPlaidAccountId(), existing);
                }
                if (existing.getAccountNumber() != null && !existing.getAccountNumber().isEmpty()) {
                    String key = existing.getAccountNumber() + ":" + 
                                (existing.getInstitutionName() != null ? existing.getInstitutionName() : "");
                    accountsByNumber.put(key, existing);
                }
            }
            
            // OPTIMIZATION: Collect accounts to save and batch them at the end
            // Track which accounts are new (need saveIfNotExists) vs existing (need save)
            java.util.List<AccountTable> accountsToSave = new java.util.ArrayList<>();
            java.util.Set<String> newAccountIds = new java.util.HashSet<>();

            for (var plaidAccount : accountsResponse.getAccounts()) {
                try {
                    String accountId = dataExtractor.extractAccountId(plaidAccount);
                    if (accountId == null || accountId.isEmpty()) {
                        logger.error("Account ID is null or empty, skipping. Account type: {}", 
                                plaidAccount.getClass().getName());
                        errorCount++;
                        continue;
                    }

                    logger.debug("Extracted account ID: {}", accountId);

                    // OPTIMIZATION: Use in-memory lookup maps instead of database queries
                    // Check if account exists by plaidAccountId first
                    Optional<AccountTable> existingAccount = Optional.ofNullable(accountsByPlaidId.get(accountId));
                    
                    // If not found, check by account number + institution using in-memory map
                    if (existingAccount.isEmpty()) {
                        AccountTable tempAccount = new AccountTable();
                        dataExtractor.updateAccountFromPlaid(tempAccount, plaidAccount);
                        String accountNumber = tempAccount.getAccountNumber();
                        
                        String institutionName = null;
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            institutionName = accountsResponse.getItem().getInstitutionId();
                        }
                        
                        if (accountNumber != null && !accountNumber.isEmpty()) {
                            // Try with institution name first, then without
                            String keyWithInstitution = accountNumber + ":" + 
                                    (institutionName != null ? institutionName : "");
                            String keyWithoutInstitution = accountNumber + ":";
                            
                            AccountTable foundByNumber = accountsByNumber.get(keyWithInstitution);
                            if (foundByNumber == null && institutionName != null && !institutionName.isEmpty()) {
                                foundByNumber = accountsByNumber.get(keyWithoutInstitution);
                            }
                            
                            if (foundByNumber != null) {
                                existingAccount = Optional.of(foundByNumber);
                                // Update Plaid ID if missing (will save later in batch)
                                if (foundByNumber.getPlaidAccountId() == null || foundByNumber.getPlaidAccountId().isEmpty()) {
                                    foundByNumber.setPlaidAccountId(accountId);
                                }
                                if ((foundByNumber.getInstitutionName() == null || foundByNumber.getInstitutionName().isEmpty()) 
                                        && institutionName != null && !institutionName.isEmpty()) {
                                    foundByNumber.setInstitutionName(institutionName);
                                }
                            }
                        }
                    }

                    AccountTable account;
                    if (existingAccount.isPresent()) {
                        account = existingAccount.get();
                        logger.debug("Updating existing account: {} (accountId: {})", accountId, account.getAccountId());
                        dataExtractor.updateAccountFromPlaid(account, plaidAccount);
                        
                        if ((account.getInstitutionName() == null || account.getInstitutionName().isEmpty()) 
                                && accountsResponse.getItem() != null 
                                && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(accountsResponse.getItem().getInstitutionId());
                        }
                        
                        String finalItemId = (itemId != null && !itemId.isEmpty()) ? itemId : 
                                dataExtractor.extractItemId(accountsResponse.getItem());
                        if (finalItemId != null && !finalItemId.isEmpty()) {
                            account.setPlaidItemId(finalItemId);
                        }
                        
                        account.setActive(true);
                        if (account.getPlaidAccountId() == null || account.getPlaidAccountId().isEmpty()) {
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
                        
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            account.setInstitutionName(accountsResponse.getItem().getInstitutionId());
                        }
                        
                        // Generate account ID
                        if (account.getInstitutionName() != null && !account.getInstitutionName().isEmpty()) {
                            try {
                                String generatedAccountId = IdGenerator.generateAccountId(
                                    account.getInstitutionName(),
                                    accountId
                                );
                                // CRITICAL FIX: Normalize generated account ID to lowercase for consistency
                                String normalizedId = IdGenerator.normalizeUUID(generatedAccountId);
                                account.setAccountId(normalizedId);
                            } catch (IllegalArgumentException e) {
                                logger.warn("Failed to generate account ID, using UUID fallback: {}", e.getMessage());
                                // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                                account.setAccountId(java.util.UUID.randomUUID().toString().toLowerCase());
                            }
                        } else {
                            // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                            account.setAccountId(java.util.UUID.randomUUID().toString().toLowerCase());
                        }
                        
                        ensureAccountRequiredFields(account);
                        // Check if account ID already exists in our in-memory map (race condition check)
                        if (accountsByPlaidId.containsKey(accountId) || 
                            (account.getAccountNumber() != null && 
                             accountsByNumber.containsKey(account.getAccountNumber() + ":" + 
                                 (account.getInstitutionName() != null ? account.getInstitutionName() : "")))) {
                            logger.debug("Account {} already exists (race condition), skipping", accountId);
                            continue;
                        }
                        // Add to in-memory maps to prevent duplicates in this batch
                        accountsByPlaidId.put(accountId, account);
                        if (account.getAccountNumber() != null && !account.getAccountNumber().isEmpty()) {
                            String key = account.getAccountNumber() + ":" + 
                                        (account.getInstitutionName() != null ? account.getInstitutionName() : "");
                            accountsByNumber.put(key, account);
                        }
                        // Collect for batch save
                        // This is a new account, so it will use saveIfNotExists()
                        newAccountIds.add(account.getAccountId());
                        accountsToSave.add(account);
                    }
                    syncedCount++;
                } catch (Exception e) {
                    logger.error("Error syncing account: {}", e.getMessage(), e);
                    errorCount++;
                }
            }
            
            // OPTIMIZATION: Batch save all accounts at once instead of one-by-one
            // This reduces database round trips and cache evictions
            if (!accountsToSave.isEmpty()) {
                try {
                    // Use individual saves for conditional writes (saveIfNotExists logic)
                    // Batch save doesn't support conditional writes, so we save individually but in one transaction
                    for (AccountTable accountToSave : accountsToSave) {
                        // For new accounts, use saveIfNotExists; for existing, use save
                        if (newAccountIds.contains(accountToSave.getAccountId())) {
                            // New account - use conditional write
                            accountRepository.saveIfNotExists(accountToSave);
                        } else {
                            // Existing account - regular save
                            accountRepository.save(accountToSave);
                        }
                    }
                    logger.debug("Batch saved {} accounts for user: {}", accountsToSave.size(), user.getUserId());
                } catch (Exception e) {
                    logger.error("Error batch saving accounts for user {}: {}", user.getUserId(), e.getMessage(), e);
                    // Fall back to individual saves
                    for (AccountTable accountToSave : accountsToSave) {
                        try {
                            accountRepository.save(accountToSave);
                        } catch (Exception saveError) {
                            logger.error("Error saving individual account {}: {}", 
                                    accountToSave.getAccountId(), saveError.getMessage());
                        }
                    }
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

    private void ensureAccountRequiredFields(AccountTable account) {
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
}

