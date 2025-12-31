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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Service for syncing transactions from Plaid
 * Extracted from PlaidSyncService for better modularity
 */
@Service
public class PlaidTransactionSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidTransactionSyncService.class);
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

    /**
     * Sync transactions for a user
     * OPTIMIZED: Batches all accounts into a single Plaid API call
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
            
            // Filter out accounts that were recently synced (within 5 minutes)
            var accountsToSync = new ArrayList<AccountTable>();
            for (var account : userAccounts) {
                if (account.getLastSyncedAt() != null) {
                    java.time.Instant now = java.time.Instant.now();
                    java.time.Duration timeSinceLastSync = java.time.Duration.between(account.getLastSyncedAt(), now);
                    if (timeSinceLastSync.toMinutes() < 5) {
                        logger.info("Skipping transaction sync for account {} - last synced {} minutes ago", 
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
            
            logger.info("Syncing transactions for {} accounts", accountsToSync.size());
            
            // Find the earliest lastSyncedAt across all accounts
            LocalDate earliestStartDate = null;
            for (var account : accountsToSync) {
                if (account.getLastSyncedAt() == null) {
                    earliestStartDate = endDate.minusYears(2);
                    break;
                } else {
                    java.time.ZonedDateTime lastSyncedZoned = account.getLastSyncedAt().atZone(java.time.ZoneId.of("UTC"));
                    LocalDate accountStartDate = lastSyncedZoned.toLocalDate();
                    if (earliestStartDate == null || accountStartDate.isBefore(earliestStartDate)) {
                        earliestStartDate = accountStartDate;
                    }
                }
            }
            
            if (earliestStartDate == null) {
                earliestStartDate = endDate.minusYears(2);
            }
            
            logger.info("Batched transaction sync: fetching transactions from {} to {} for {} accounts", 
                    earliestStartDate, endDate, accountsToSync.size());
            
            // Make ONE API call for all accounts
            var allTransactionsResponse = plaidService.getTransactions(
                    accessToken,
                    earliestStartDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );
            
            if (allTransactionsResponse == null || allTransactionsResponse.getTransactions() == null || 
                    allTransactionsResponse.getTransactions().isEmpty()) {
                logger.warn("No transactions returned from Plaid for user: {}", user.getUserId());
                updateLastSyncedAtForAccounts(accountsToSync);
                return;
            }
            
            int totalTransactions = allTransactionsResponse.getTransactions().size();
            logger.info("Plaid returned {} total transactions (batched for {} accounts)", 
                    totalTransactions, accountsToSync.size());
            
            // Group transactions by account ID
            Map<String, List<Object>> transactionsByAccount = new HashMap<>();
            List<Object> unassignedTransactions = new ArrayList<>();
            
            for (var plaidTransaction : allTransactionsResponse.getTransactions()) {
                String plaidAccountId = dataExtractor.extractAccountIdFromTransaction(plaidTransaction);
                if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                    transactionsByAccount.computeIfAbsent(plaidAccountId, k -> new ArrayList<>()).add(plaidTransaction);
                } else {
                    unassignedTransactions.add(plaidTransaction);
                }
            }
            
            logger.info("Grouped transactions: {} accounts have transactions, {} unassigned", 
                    transactionsByAccount.size(), unassignedTransactions.size());
            
            // Process transactions per account
            int totalSyncedCount = 0;
            int totalErrorCount = 0;
            int totalDuplicateCount = 0;
            
            for (var account : accountsToSync) {
                try {
                    List<Object> accountTransactions = new ArrayList<>();
                    
                    if (account.getPlaidAccountId() != null && !account.getPlaidAccountId().isEmpty()) {
                        accountTransactions = transactionsByAccount.getOrDefault(account.getPlaidAccountId(), new ArrayList<>());
                    }
                    
                    if (accountTransactions.isEmpty()) {
                        logger.debug("No transactions found for account {} (Plaid account ID: {})", 
                                account.getAccountId(), account.getPlaidAccountId());
                        continue;
                    }
                    
                    // Filter transactions based on account's specific lastSyncedAt
                    List<Object> filteredTransactions = filterTransactionsByLastSyncedAt(
                            accountTransactions, account.getLastSyncedAt(), endDate);
                    
                    if (filteredTransactions.isEmpty()) {
                        logger.debug("No new transactions for account {} after filtering", account.getAccountId());
                        continue;
                    }
                    
                    logger.info("Processing {} transactions for account {} (filtered from {} total)", 
                            filteredTransactions.size(), account.getAccountId(), accountTransactions.size());
                    
                    // Process transactions for this account
                    var result = processTransactionsForAccount(user, account, filteredTransactions, 
                            account.getLastSyncedAt() == null);
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
            throw e;
        } catch (Exception e) {
            logger.error("Error syncing transactions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync transactions", null, null, e);
        }
    }

    /**
     * Filter transactions based on account's lastSyncedAt date
     */
    private List<Object> filterTransactionsByLastSyncedAt(
            final List<Object> transactions,
            final java.time.Instant lastSyncedAt,
            final LocalDate endDate) {
        
        if (lastSyncedAt == null) {
            return transactions;
        }
        
        LocalDate lastSyncedDate = lastSyncedAt.atZone(java.time.ZoneId.of("UTC")).toLocalDate();
        List<Object> filtered = new ArrayList<>();
        
        for (var transaction : transactions) {
            String transactionDateStr = extractTransactionDate(transaction);
            if (transactionDateStr != null && !transactionDateStr.isEmpty()) {
                try {
                    LocalDate transactionDate = LocalDate.parse(transactionDateStr, DATE_FORMATTER);
                    if (!transactionDate.isBefore(lastSyncedDate)) {
                        filtered.add(transaction);
                    }
                } catch (Exception e) {
                    logger.debug("Could not parse transaction date: {}", transactionDateStr);
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

    /**
     * Extract transaction date from Plaid transaction
     */
    private String extractTransactionDate(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getDate() != null) {
                    return transaction.getDate().format(DATE_FORMATTER);
                }
            }
            
            // Fallback: try reflection
            try {
                java.lang.reflect.Method getDate = plaidTransaction.getClass().getMethod("getDate");
                Object date = getDate.invoke(plaidTransaction);
                if (date != null && date instanceof java.time.LocalDate) {
                    return ((java.time.LocalDate) date).format(DATE_FORMATTER);
                }
            } catch (Exception e) {
                logger.debug("Could not extract date via reflection: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("Error extracting transaction date: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Process transactions for a specific account
     */
    private SyncResult processTransactionsForAccount(
            final UserTable user,
            final AccountTable account,
            final List<Object> plaidTransactions,
            final boolean isFirstSync) {
        
        int syncedCount = 0;
        int errorCount = 0;
        int duplicateCount = 0;
        
        String institutionName = account.getInstitutionName();
        
        for (var plaidTransaction : plaidTransactions) {
            try {
                String plaidTransactionId = dataExtractor.extractTransactionId(plaidTransaction);
                if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                    logger.warn("Transaction has no Plaid ID, skipping");
                    errorCount++;
                    continue;
                }
                
                // CRITICAL FIX: Check if transaction with this Plaid ID already exists
                Optional<TransactionTable> existingByPlaidId = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                if (existingByPlaidId.isPresent()) {
                    // Transaction with this Plaid ID already exists - update it
                    TransactionTable existing = existingByPlaidId.get();
                    logger.debug("Updating existing transaction with Plaid ID: {}", plaidTransactionId);
                    dataExtractor.updateTransactionFromPlaid(existing, plaidTransaction);
                    transactionRepository.save(existing);
                    duplicateCount++;
                    continue;
                }
                
                // CRITICAL FIX: Check for matching imported transaction by composite key
                // This prevents creating duplicates when an imported transaction (no Plaid ID) matches a Plaid transaction
                java.math.BigDecimal amount = extractAmountFromPlaid(plaidTransaction);
                String transactionDate = extractDateFromPlaid(plaidTransaction);
                String description = extractDescriptionFromPlaid(plaidTransaction);
                
                if (amount != null && transactionDate != null && description != null) {
                    // Check for matching transaction by composite key (amount, date, description)
                    Optional<TransactionTable> matchingTransaction = transactionRepository.findByCompositeKey(
                            account.getAccountId(), amount, transactionDate, description, user.getUserId());
                    
                    if (matchingTransaction.isPresent()) {
                        TransactionTable existing = matchingTransaction.get();
                        
                        // Only update if existing transaction doesn't have a Plaid ID
                        // This prevents overwriting Plaid transactions with different Plaid IDs
                        if (existing.getPlaidTransactionId() == null || existing.getPlaidTransactionId().isEmpty()) {
                            logger.info("🔄 Found matching imported transaction (no Plaid ID) - updating with Plaid ID: accountId={}, amount={}, date={}, description={}...", 
                                    account.getAccountId(), amount, transactionDate, 
                                    description.length() > 50 ? description.substring(0, 50) + "..." : description);
                            
                            // Update existing transaction with Plaid data and ID
                            existing.setPlaidTransactionId(plaidTransactionId);
                            dataExtractor.updateTransactionFromPlaid(existing, plaidTransaction);
                            transactionRepository.save(existing);
                            duplicateCount++; // Count as duplicate (matched existing)
                            continue;
                        } else {
                            logger.debug("Matching transaction found but already has Plaid ID: {} (different from new: {}), creating new transaction", 
                                    existing.getPlaidTransactionId(), plaidTransactionId);
                        }
                    }
                }
                
                // No matching transaction found - create new one
                TransactionTable transaction = createTransactionFromPlaid(user.getUserId(), plaidTransaction, institutionName, account.getAccountId());
                
                // Use saveIfPlaidTransactionNotExists to prevent duplicates (handles race conditions)
                boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                if (saved) {
                    syncedCount++;
                } else {
                    logger.debug("Transaction with Plaid ID {} already exists (race condition), skipping", 
                            plaidTransactionId);
                    duplicateCount++;
                }
            } catch (Exception e) {
                logger.error("Error processing transaction: {}", e.getMessage(), e);
                errorCount++;
            }
        }
        
        return new SyncResult(syncedCount, errorCount, duplicateCount);
    }

    /**
     * Create transaction from Plaid transaction data
     */
    private TransactionTable createTransactionFromPlaid(
            final String userId, 
            final Object plaidTransaction,
            final String institutionName,
            final String accountId) {
        TransactionTable transaction = new TransactionTable();
        
        String plaidTransactionId = dataExtractor.extractTransactionId(plaidTransaction);
        
        // Generate transaction ID
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            if (institutionName != null && !institutionName.isEmpty() && accountId != null && !accountId.isEmpty()) {
                try {
                    String generatedTransactionId = IdGenerator.generateTransactionId(
                        institutionName,
                        accountId,
                        plaidTransactionId
                    );
                    transaction.setTransactionId(generatedTransactionId);
                } catch (IllegalArgumentException e) {
                    java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
                    transaction.setTransactionId(IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
                }
            } else {
                java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
                transaction.setTransactionId(IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId));
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

    /**
     * Update lastSyncedAt for accounts after successful sync
     */
    private void updateLastSyncedAtForAccounts(final List<AccountTable> accounts) {
        java.time.Instant now = java.time.Instant.now();
        for (var account : accounts) {
            account.setLastSyncedAt(now);
            account.setUpdatedAt(now);
            accountRepository.save(account);
        }
        logger.info("Updated lastSyncedAt for {} accounts", accounts.size());
    }

    /**
     * Extract amount from Plaid transaction
     */
    private java.math.BigDecimal extractAmountFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getAmount() != null) {
                    return java.math.BigDecimal.valueOf(transaction.getAmount());
                }
            }
            
            // Fallback: try reflection
            java.lang.reflect.Method getAmount = plaidTransaction.getClass().getMethod("getAmount");
            Object amount = getAmount.invoke(plaidTransaction);
            if (amount != null) {
                if (amount instanceof Double) {
                    return java.math.BigDecimal.valueOf((Double) amount);
                } else if (amount instanceof Number) {
                    return java.math.BigDecimal.valueOf(((Number) amount).doubleValue());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract amount from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract date from Plaid transaction (returns YYYY-MM-DD format)
     */
    private String extractDateFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                if (transaction.getDate() != null) {
                    // Plaid date is a LocalDate - convert to YYYY-MM-DD format
                    return transaction.getDate().format(DATE_FORMATTER);
                }
            }
            
            // Fallback: try reflection
            java.lang.reflect.Method getDate = plaidTransaction.getClass().getMethod("getDate");
            Object date = getDate.invoke(plaidTransaction);
            if (date != null) {
                if (date instanceof LocalDate) {
                    return ((LocalDate) date).format(DATE_FORMATTER);
                } else {
                    return date.toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract date from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract description from Plaid transaction (name or merchantName)
     */
    private String extractDescriptionFromPlaid(final Object plaidTransaction) {
        try {
            if (plaidTransaction instanceof com.plaid.client.model.Transaction) {
                com.plaid.client.model.Transaction transaction = (com.plaid.client.model.Transaction) plaidTransaction;
                String name = transaction.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
                String merchantName = transaction.getMerchantName();
                if (merchantName != null && !merchantName.isEmpty()) {
                    return merchantName;
                }
            }
            
            // Fallback: try reflection
            try {
                java.lang.reflect.Method getName = plaidTransaction.getClass().getMethod("getName");
                Object name = getName.invoke(plaidTransaction);
                if (name != null && !name.toString().isEmpty()) {
                    return name.toString();
                }
            } catch (Exception e) {
                // Try merchantName instead
            }
            
            try {
                java.lang.reflect.Method getMerchantName = plaidTransaction.getClass().getMethod("getMerchantName");
                Object merchantName = getMerchantName.invoke(plaidTransaction);
                if (merchantName != null && !merchantName.toString().isEmpty()) {
                    return merchantName.toString();
                }
            } catch (Exception e) {
                // Ignore
            }
        } catch (Exception e) {
            logger.warn("Could not extract description from Plaid transaction: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Result of sync operation
     */
    private static class SyncResult {
        final int syncedCount;
        final int errorCount;
        final int duplicateCount;

        SyncResult(int syncedCount, int errorCount, int duplicateCount) {
            this.syncedCount = syncedCount;
            this.errorCount = errorCount;
            this.duplicateCount = duplicateCount;
        }
    }
}

