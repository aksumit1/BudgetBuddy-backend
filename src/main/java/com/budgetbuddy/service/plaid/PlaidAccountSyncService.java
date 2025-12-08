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

                    // Check if account exists by plaidAccountId first
                    Optional<AccountTable> existingAccount = accountRepository.findByPlaidAccountId(accountId);
                    
                    // If not found, check by account number + institution
                    if (existingAccount.isEmpty()) {
                        AccountTable tempAccount = new AccountTable();
                        dataExtractor.updateAccountFromPlaid(tempAccount, plaidAccount);
                        String accountNumber = tempAccount.getAccountNumber();
                        
                        String institutionName = null;
                        if (accountsResponse.getItem() != null && accountsResponse.getItem().getInstitutionId() != null) {
                            institutionName = accountsResponse.getItem().getInstitutionId();
                        }
                        
                        if (accountNumber != null && !accountNumber.isEmpty()) {
                            if (institutionName != null && !institutionName.isEmpty()) {
                                existingAccount = accountRepository.findByAccountNumberAndInstitution(
                                        accountNumber, institutionName, user.getUserId());
                            } else {
                                existingAccount = accountRepository.findByAccountNumber(accountNumber, user.getUserId());
                            }
                            
                            if (existingAccount.isPresent()) {
                                AccountTable foundAccount = existingAccount.get();
                                if (foundAccount.getPlaidAccountId() == null || foundAccount.getPlaidAccountId().isEmpty()) {
                                    foundAccount.setPlaidAccountId(accountId);
                                    accountRepository.save(foundAccount);
                                }
                                if ((foundAccount.getInstitutionName() == null || foundAccount.getInstitutionName().isEmpty()) 
                                        && institutionName != null && !institutionName.isEmpty()) {
                                    foundAccount.setInstitutionName(institutionName);
                                    accountRepository.save(foundAccount);
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
                        accountRepository.save(account);
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
                                account.setAccountId(generatedAccountId);
                            } catch (IllegalArgumentException e) {
                                logger.warn("Failed to generate account ID, using UUID fallback: {}", e.getMessage());
                                account.setAccountId(java.util.UUID.randomUUID().toString());
                            }
                        } else {
                            account.setAccountId(java.util.UUID.randomUUID().toString());
                        }
                        
                        ensureAccountRequiredFields(account);
                        if (!accountRepository.saveIfNotExists(account)) {
                            Optional<AccountTable> raceConditionAccount = accountRepository.findById(account.getAccountId());
                            if (raceConditionAccount.isPresent()) {
                                account = raceConditionAccount.get();
                                dataExtractor.updateAccountFromPlaid(account, plaidAccount);
                                account.setActive(true);
                                if (account.getPlaidAccountId() == null || account.getPlaidAccountId().isEmpty()) {
                                    account.setPlaidAccountId(accountId);
                                }
                                accountRepository.save(account);
                            } else {
                                logger.warn("Account with ID {} already exists but could not be retrieved, skipping", 
                                        account.getAccountId());
                                continue;
                            }
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

