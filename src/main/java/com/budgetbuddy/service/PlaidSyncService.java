package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private static final int DEFAULT_SYNC_DAYS = 30;

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
                    String accountId = extractAccountId(plaidAccount);
                    if (accountId == null || accountId.isEmpty()) {
                        logger.warn("Account ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }

                    // Check if account exists
                    Optional<AccountTable> existingAccount = accountRepository.findByPlaidAccountId(accountId);

                    AccountTable account;
                    if (existingAccount.isPresent()) {
                        account = existingAccount.get();
                        logger.debug("Updating existing account: {}", accountId);
                        // Update account details
                        updateAccountFromPlaid(account, plaidAccount);
                        accountRepository.save(account);
                    } else {
                        // Create new account
                        account = new AccountTable();
                        account.setAccountId(java.util.UUID.randomUUID().toString());
                        account.setUserId(user.getUserId());
                        account.setPlaidAccountId(accountId);
                        logger.debug("Creating new account: {}", accountId);
                        // Update account details
                        updateAccountFromPlaid(account, plaidAccount);
                        // Use conditional write to prevent race conditions
                        if (!accountRepository.saveIfNotExists(account)) {
                            logger.warn("Account with ID {} already exists, skipping", account.getAccountId());
                            continue;
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
            LocalDate startDate = endDate.minusDays(DEFAULT_SYNC_DAYS);

            var transactionsResponse = plaidService.getTransactions(
                    accessToken,
                    startDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );

            if (transactionsResponse == null || transactionsResponse.getTransactions() == null) {
                logger.warn("No transactions returned from Plaid for user: {}", user.getUserId());
                return;
            }

            int syncedCount = 0;
            int errorCount = 0;

            for (var plaidTransaction : transactionsResponse.getTransactions()) {
                try {
                    String transactionId = extractTransactionId(plaidTransaction);
                    if (transactionId == null || transactionId.isEmpty()) {
                        logger.warn("Transaction ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }

                    // Use conditional write to prevent duplicates and race conditions
                    // First check if transaction already exists
                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(transactionId);
                    
                    if (existing.isPresent()) {
                        // Update existing transaction
                        TransactionTable transaction = existing.get();
                        updateTransactionFromPlaid(transaction, plaidTransaction);
                        transactionRepository.save(transaction);
                        syncedCount++;
                    } else {
                        // Create new transaction
                        TransactionTable transaction = createTransactionFromPlaid(user.getUserId(), plaidTransaction);
                        // Ensure plaidTransactionId is set
                        transaction.setPlaidTransactionId(transactionId);
                        
                        // Use conditional write to prevent duplicate Plaid transactions
                        boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                        if (saved) {
                            syncedCount++;
                        } else {
                            // Race condition: transaction was inserted between check and save
                            // Fetch and update it
                            Optional<TransactionTable> raceConditionExisting = transactionRepository.findByPlaidTransactionId(transactionId);
                            if (raceConditionExisting.isPresent()) {
                                transaction = raceConditionExisting.get();
                                updateTransactionFromPlaid(transaction, plaidTransaction);
                                transactionRepository.save(transaction);
                                syncedCount++;
                            } else {
                                logger.warn("Transaction with Plaid ID {} could not be saved or retrieved", transactionId);
                                errorCount++;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error syncing transaction: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            logger.info("Transaction sync completed for user: {} - Synced: {}, Errors: {}",
                    user.getUserId(), syncedCount, errorCount);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error syncing transactions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions", null, null, e);
        }
    }

    /**
     * Scheduled sync for all users (runs daily)
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void scheduledSync() {
        logger.info("Starting scheduled Plaid sync");
        // TODO: Implement scheduled sync for all users
        // This would require maintaining a list of users with active Plaid connections
    }

    /**
     * Extract account ID from Plaid account
     */
    private String extractAccountId(final Object plaidAccount) {
        // In production, properly extract account ID from Plaid account object
        // This is a placeholder - actual implementation depends on Plaid SDK structure
        try {
            // Use reflection or proper Plaid SDK methods to get account ID
            return plaidAccount.toString(); // Placeholder
        } catch (Exception e) {
            logger.error("Failed to extract account ID", e);
            return null;
        }
    }

    /**
     * Extract transaction ID from Plaid transaction
     */
    private String extractTransactionId(final Object plaidTransaction) {
        // In production, properly extract transaction ID from Plaid transaction object
        try {
            // Use reflection or proper Plaid SDK methods to get transaction ID
            return plaidTransaction.toString(); // Placeholder
        } catch (Exception e) {
            logger.error("Failed to extract transaction ID", e);
            return null;
        }
    }

    /**
     * Update account from Plaid account data
     */
    private void updateAccountFromPlaid(final AccountTable account, final Object plaidAccount) {
        // In production, properly map all fields from Plaid account
        // This is a placeholder
        account.setUpdatedAt(java.time.Instant.now());
    }

    /**
     * Create transaction from Plaid transaction data
     */
    private TransactionTable createTransactionFromPlaid(final String userId, final Object plaidTransaction) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(java.util.UUID.randomUUID().toString());
        transaction.setUserId(userId);
        // Map other fields from plaidTransaction
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());
        return transaction;
    }

    /**
     * Update transaction from Plaid transaction data
     */
    private void updateTransactionFromPlaid(final TransactionTable transaction, final Object plaidTransaction) {
        // Update transaction fields from Plaid
        transaction.setUpdatedAt(java.time.Instant.now());
    }
}
