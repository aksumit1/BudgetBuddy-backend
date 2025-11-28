package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transaction service with cost optimization
 * Migrated to DynamoDB
 * Uses pagination and date filtering to minimize data transfer
 */
@Service
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionService(final TransactionRepository transactionRepository, final AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Get transactions with pagination to minimize data transfer
     * Note: DynamoDB doesn't support Pageable, so we use skip/limit pattern
     */
    public List<TransactionTable> getTransactions(UserTable user, int skip, int limit) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (skip < 0) skip = 0;
        if (limit <= 0 || limit > 100) limit = 50; // Default limit, max 100

        return transactionRepository.findByUserId(user.getUserId(), skip, limit);
    }

    /**
     * Get transactions in date range (optimized query)
     */
    public List<TransactionTable> getTransactionsInRange(UserTable user, LocalDate startDate, LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before or equal to end date");
        }

        String startDateStr = startDate.format(DATE_FORMATTER);
        String endDateStr = endDate.format(DATE_FORMATTER);
        return transactionRepository.findByUserIdAndDateRange(user.getUserId(), startDateStr, endDateStr);
    }

    /**
     * Get transactions by category (for budget tracking)
     * Note: DynamoDB doesn't support complex queries, so we filter in application
     */
    public List<TransactionTable> getTransactionsByCategory(UserTable user, String category, LocalDate since) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (category == null || category.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        if (since == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Since date is required");
        }

        String sinceStr = since.format(DATE_FORMATTER);
        String nowStr = LocalDate.now().format(DATE_FORMATTER);
        List<TransactionTable> transactions = transactionRepository.findByUserIdAndDateRange(
                user.getUserId(), sinceStr, nowStr);

        return transactions.stream()
                .filter(t -> t != null && category.equals(t.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * Calculate total spending in date range (aggregated query to minimize data transfer)
     * Note: DynamoDB doesn't support aggregation, so we calculate in application
     */
    public BigDecimal getTotalSpending(final UserTable user, final LocalDate startDate, final LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }

        List<TransactionTable> transactions = getTransactionsInRange(user, startDate, endDate);
        return transactions.stream()
                .filter(t -> t != null)
                .map(TransactionTable::getAmount)
                .filter((amount) -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Save transaction (from Plaid sync)
     * Uses conditional write to prevent duplicate transactions (deduplication)
     */
    public TransactionTable saveTransaction(final TransactionTable transaction) {
        if (transaction == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction cannot be null");
        }
        if (transaction.getUserId() == null || transaction.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction user ID is required");
        }
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            transaction.setTransactionId(UUID.randomUUID().toString());
        }

        // Use conditional write to prevent duplicate Plaid transactions
        boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
        if (!saved) {
            // Transaction already exists (duplicate detected)
            if (transaction.getPlaidTransactionId() != null && !transaction.getPlaidTransactionId().isEmpty()) {
                // Duplicate Plaid transaction - fetch by Plaid ID
                logger.debug("Transaction with Plaid ID {} already exists, fetching existing", transaction.getPlaidTransactionId());
                return transactionRepository.findByPlaidTransactionId(transaction.getPlaidTransactionId())
                        .orElseThrow(() -> new AppException(ErrorCode.RECORD_ALREADY_EXISTS, 
                                "Transaction with Plaid ID already exists but could not be retrieved"));
            } else {
                // Duplicate transactionId (no Plaid ID) - fetch by transaction ID
                logger.debug("Transaction with ID {} already exists, fetching existing", transaction.getTransactionId());
                return transactionRepository.findById(transaction.getTransactionId())
                        .orElseThrow(() -> new AppException(ErrorCode.RECORD_ALREADY_EXISTS, 
                                "Transaction with ID already exists but could not be retrieved"));
            }
        }

        return transaction;
    }

    /**
     * Find transaction by Plaid ID (for deduplication)
     */
    public Optional<TransactionTable> findByPlaidTransactionId(String plaidTransactionId) {
        return transactionRepository.findByPlaidTransactionId(plaidTransactionId);
    }

    /**
     * Create manual transaction (backward compatibility - generates new UUID)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String category) {
        return createTransaction(user, accountId, amount, transactionDate, description, category, null, null);
    }

    /**
     * Create manual transaction (backward compatibility - with transactionId)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String category, final String transactionId) {
        return createTransaction(user, accountId, amount, transactionDate, description, category, transactionId, null);
    }

    /**
     * Create manual transaction
     * @param transactionId Optional transaction ID from app. If provided and valid, use it to ensure app-backend ID consistency.
     *                      If not provided or invalid, generate a new UUID.
     * @param notes Optional user notes for the transaction
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String category, final String transactionId, final String notes) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (accountId == null || accountId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }
        if (amount == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Amount is required");
        }
        if (transactionDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction date is required");
        }
        if (category == null || category.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }

        // Try to find account by accountId first
        Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
        
        // If not found and plaidAccountId is provided, try lookup by Plaid account ID (fallback)
        // This handles cases where account IDs don't match between app and backend
        String plaidAccountId = null; // Will be extracted from request if needed
        if (accountOpt.isEmpty()) {
            // Note: plaidAccountId parameter would need to be added to createTransaction signature
            // For now, we'll handle this in the controller
            logger.debug("Account {} not found by ID, will need Plaid account ID for fallback lookup", accountId);
        }
        
        AccountTable account = accountOpt
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        TransactionTable transaction = new TransactionTable();
        
        // Use provided transactionId if valid, otherwise generate new UUID
        // This ensures app and backend use the same transaction ID for consistency
        if (transactionId != null && !transactionId.trim().isEmpty()) {
            // Validate UUID format
            try {
                UUID.fromString(transactionId); // Validates UUID format
                // Check if transaction with this ID already exists
                if (transactionRepository.findById(transactionId).isPresent()) {
                    // Transaction already exists - this could be a duplicate request
                    logger.warn("Transaction with ID {} already exists, generating new UUID", transactionId);
                    transaction.setTransactionId(UUID.randomUUID().toString());
                } else {
                    // Use the provided ID
                    transaction.setTransactionId(transactionId);
                    logger.info("Using provided transaction ID: {}", transactionId);
                }
            } catch (IllegalArgumentException e) {
                // Invalid UUID format, generate new one
                logger.warn("Invalid transaction ID format '{}', generating new UUID", transactionId);
                transaction.setTransactionId(UUID.randomUUID().toString());
            }
        } else {
            // No transactionId provided, generate new UUID
            transaction.setTransactionId(UUID.randomUUID().toString());
        }
        
        transaction.setUserId(user.getUserId());
        transaction.setAccountId(accountId);
        transaction.setAmount(amount);
        transaction.setTransactionDate(transactionDate.format(DATE_FORMATTER));
        transaction.setDescription(description != null ? description : "");
        transaction.setCategory(category);
        transaction.setCurrencyCode(user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()
                ? user.getPreferredCurrency() : "USD");
        
        // Set notes if provided
        if (notes != null) {
            transaction.setNotes(notes.trim().isEmpty() ? null : notes.trim());
        }

        transactionRepository.save(transaction);
        logger.info("Created transaction {} for user {} with notes: {}", transaction.getTransactionId(), user.getEmail(), notes != null && !notes.trim().isEmpty() ? "yes" : "no");
        return transaction;
    }

    /**
     * Update transaction (e.g., notes)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     */
    public TransactionTable updateTransaction(final UserTable user, final String transactionId, final String notes) {
        return updateTransaction(user, transactionId, null, notes);
    }
    
    /**
     * Update transaction (e.g., notes)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId not found
     */
    public TransactionTable updateTransaction(final UserTable user, final String transactionId, final String plaidTransactionId, final String notes) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Try to find by transactionId first
        Optional<TransactionTable> transactionOpt = transactionRepository.findById(transactionId);
        
        // If not found and plaidTransactionId is provided, try lookup by Plaid ID
        if (transactionOpt.isEmpty() && plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            logger.debug("Transaction {} not found by ID, trying Plaid ID: {}", transactionId, plaidTransactionId);
            transactionOpt = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (transactionOpt.isPresent()) {
                logger.info("Found transaction by Plaid ID {} (requested ID: {})", plaidTransactionId, transactionId);
            }
        }
        
        TransactionTable transaction = transactionOpt
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        // Update notes: if notes is null, clear it; if notes is provided, set it (trimming whitespace)
        if (notes != null) {
            transaction.setNotes(notes.trim().isEmpty() ? null : notes.trim());
        } else {
            // Explicitly clear notes when null is passed
            transaction.setNotes(null);
        }
        transaction.setUpdatedAt(java.time.Instant.now());

        transactionRepository.save(transaction);
        logger.info("Updated transaction {} notes for user {}", transactionId, user.getEmail());
        return transaction;
    }

    /**
     * Delete transaction
     */
    public void deleteTransaction(final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        TransactionTable transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        transactionRepository.delete(transactionId);
        logger.info("Deleted transaction {} for user {}", transactionId, user.getEmail());
    }
}
