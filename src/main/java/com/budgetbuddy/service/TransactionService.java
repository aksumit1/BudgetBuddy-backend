package com.budgetbuddy.service;

import com.budgetbuddy.audit.AuditService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transaction service with cost optimization
 * Uses pagination and date filtering to minimize data transfer
 */
@Service
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionTypeDeterminer transactionTypeDeterminer;
    private final TransactionTypeCategoryService transactionTypeCategoryService;
    private final AuditService auditService; // P2: Audit logging
    private final org.springframework.context.ApplicationContext applicationContext; // For lazy access to GoalProgressService

    public TransactionService(final TransactionRepository transactionRepository, 
                             final AccountRepository accountRepository,
                             final TransactionTypeDeterminer transactionTypeDeterminer,
                             final TransactionTypeCategoryService transactionTypeCategoryService,
                             final AuditService auditService,
                             final org.springframework.context.ApplicationContext applicationContext) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionTypeDeterminer = transactionTypeDeterminer;
        this.transactionTypeCategoryService = transactionTypeCategoryService;
        this.auditService = auditService;
        this.applicationContext = applicationContext;
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
                .filter(t -> t != null && (category.equals(t.getCategoryPrimary()) || category.equals(t.getCategoryDetailed())))
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
     * Get loan/credit card payments for a specific account
     * For loan accounts: returns negative amounts (payments made TO the loan)
     * For credit card accounts: returns positive amounts (payments made TO the credit card) and payment category transactions
     * 
     * @param user The user
     * @param accountId The account ID
     * @param startDate Optional start date filter
     * @param endDate Optional end date filter
     * @return List of loan/credit card payment transactions
     */
    public List<TransactionTable> getLoanOrCreditCardPayments(final UserTable user, final String accountId, 
                                                              final LocalDate startDate, final LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (accountId == null || accountId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        // Get the account to determine its type
        Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
        }
        
        AccountTable account = accountOpt.get();
        
        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // Get all transactions for the user
        List<TransactionTable> allTransactions;
        if (startDate != null && endDate != null) {
            allTransactions = getTransactionsInRange(user, startDate, endDate);
        } else {
            // Get all transactions if no date range specified
            allTransactions = transactionRepository.findByUserId(user.getUserId(), 0, 10000); // Large limit to get all
        }

        // Filter transactions for this account
        List<TransactionTable> accountTransactions = allTransactions.stream()
                .filter(t -> t != null && accountId.equals(t.getAccountId()))
                .collect(Collectors.toList());

        // Determine account type
        String accountType = account.getAccountType();
        if (accountType == null) {
            accountType = "";
        }
        String accountTypeUpper = accountType.toUpperCase();

        // Check if it's a loan account
        boolean isLoanAccount = "MORTGAGE".equals(accountTypeUpper) ||
                                "AUTO_LOAN".equals(accountTypeUpper) ||
                                "PERSONAL_LOAN".equals(accountTypeUpper) ||
                                "STUDENT_LOAN".equals(accountTypeUpper) ||
                                "CREDIT_LINE".equals(accountTypeUpper);

        // Check if it's a credit card account
        boolean isCreditCardAccount = "CREDIT_CARD".equals(accountTypeUpper) ||
                                      "CHARGE_CARD".equals(accountTypeUpper);

        // Filter transactions based on account type
        if (isLoanAccount) {
            // For loan accounts, return negative amounts (payments made TO the loan)
            return accountTransactions.stream()
                    .filter(t -> t.getAmount() != null && t.getAmount().compareTo(BigDecimal.ZERO) < 0)
                    .collect(Collectors.toList());
        } else if (isCreditCardAccount) {
            // For credit card accounts, return positive amounts (payments made TO the credit card)
            // and transactions with payment category
            return accountTransactions.stream()
                    .filter(t -> {
                        if (t.getAmount() == null) {
                            return false;
                        }
                        // Positive amounts (payments that reduce credit card balance)
                        boolean isPositiveAmount = t.getAmount().compareTo(BigDecimal.ZERO) > 0;
                        // Payment category transactions
                        boolean isPaymentCategory = "payment".equalsIgnoreCase(t.getCategoryPrimary()) ||
                                                   "payment".equalsIgnoreCase(t.getCategoryDetailed());
                        return isPositiveAmount || isPaymentCategory;
                    })
                    .collect(Collectors.toList());
        } else {
            // Not a loan or credit card account - return empty list
            return List.of();
        }
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
            // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
            transaction.setTransactionId(UUID.randomUUID().toString().toLowerCase());
        }

        // CRITICAL: Ensure transactionType is set (for old transactions that might have null transactionType)
        // If transactionType is null/empty, calculate it even if override flag is set (null type takes precedence)
        // If transactionType is set but override is false, recalculate if needed
        if (isNullOrEmpty(transaction.getTransactionType())) {
            // Type is null/empty - always calculate (null type takes precedence over override flag)
            ensureTransactionTypeSetWithAccountLookup(transaction);
        } else if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
            // Type is set but not overridden - ensure it's valid (shouldn't need recalculation, but check anyway)
            // This is a safety check for edge cases
        }
        
        // CRITICAL FIX: Wells Fargo credit card special case
        // Wells Fargo credit card statements don't apply negative signs to payment transactions
        // If this is a Wells Fargo credit card account, transaction type is PAYMENT, category is payment,
        // and amount is negative, make it positive (Wells Fargo convention)
        // This applies as a special case after sign reversal, type and category assignments
        if (transaction.getAccountId() != null && 
            transaction.getTransactionType() != null &&
            transaction.getTransactionType().equalsIgnoreCase("PAYMENT") &&
            transaction.getCategoryPrimary() != null &&
            transaction.getCategoryPrimary().equalsIgnoreCase("payment") &&
            transaction.getAmount() != null &&
            transaction.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            
            try {
                Optional<AccountTable> accountOpt = accountRepository.findById(transaction.getAccountId());
                if (accountOpt.isPresent()) {
                    AccountTable account = accountOpt.get();
                    String accountType = account.getAccountType();
                    String institutionName = account.getInstitutionName();
                    String accountName = account.getAccountName();
                    
                    // Check if it's a Wells Fargo credit card account
                    boolean isCreditCard = accountType != null &&
                        (accountType.toLowerCase().contains("credit") ||
                         accountType.equalsIgnoreCase("creditcard") ||
                         accountType.equalsIgnoreCase("credit_card"));
                    
                    boolean isWellsFargo = (institutionName != null &&
                         (institutionName.toLowerCase().contains("wells fargo") ||
                          institutionName.toLowerCase().contains("wellsfargo") ||
                          institutionName.toLowerCase().equalsIgnoreCase("wf"))) ||
                        (accountName != null &&
                         (accountName.toLowerCase().contains("wells fargo") ||
                          accountName.toLowerCase().contains("wellsfargo") ||
                          accountName.toLowerCase().contains("wf credit")));
                    
                    boolean isWellsFargoCreditCard = isCreditCard && isWellsFargo;
                    
                    if (isWellsFargoCreditCard) {
                        // Wells Fargo credit card payment: make amount positive
                        BigDecimal originalAmount = transaction.getAmount();
                        BigDecimal positiveAmount = originalAmount.negate();
                        transaction.setAmount(positiveAmount);
                        logger.info("🏷️ Wells Fargo credit card payment: Converted negative amount {} to positive {} for payment transaction (description: '{}', account: '{}')",
                            originalAmount, positiveAmount, transaction.getDescription(), accountName);
                    }
                }
            } catch (Exception e) {
                // Non-fatal: if account lookup fails, continue without special case
                logger.debug("Could not check account for Wells Fargo special case: {}", e.getMessage());
            }
        }

        // Use conditional write to prevent duplicate Plaid transactions
        boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
        
        // P1: Invalidate cache when transaction is saved (category/type may change)
        if (saved) {
            invalidateCategoryCache(transaction);
        }
        
        if (!saved) {
            // Transaction already exists (duplicate detected)
            TransactionTable existing;
            if (transaction.getPlaidTransactionId() != null && !transaction.getPlaidTransactionId().isEmpty()) {
                // Duplicate Plaid transaction - fetch by Plaid ID
                // Transaction with Plaid ID already exists
                existing = transactionRepository.findByPlaidTransactionId(transaction.getPlaidTransactionId())
                        .orElseThrow(() -> new AppException(ErrorCode.RECORD_ALREADY_EXISTS, 
                                "Transaction with Plaid ID already exists but could not be retrieved"));
            } else {
                // Duplicate transactionId (no Plaid ID) - fetch by transaction ID
                // Transaction with ID already exists
                existing = transactionRepository.findById(transaction.getTransactionId())
                        .orElseThrow(() -> new AppException(ErrorCode.RECORD_ALREADY_EXISTS, 
                                "Transaction with ID already exists but could not be retrieved"));
            }
            
            // CRITICAL: Ensure existing transaction has transactionType set (for old transactions)
            // If transactionType is null/empty, calculate it even if override flag is set (null type takes precedence)
            if (isNullOrEmpty(existing.getTransactionType())) {
                ensureTransactionTypeSetWithAccountLookup(existing);
                transactionRepository.save(existing);
                // P1: Invalidate cache when transaction type is set
                invalidateCategoryCache(existing);
            }
            
            return existing;
        }

        return transaction;
    }

    /**
     * Create multiple transactions in a batch
     * @param user The user
     * @param requests List of CreateTransactionRequest objects
     * @return BatchImportResponse with success/failure counts
     */
    public com.budgetbuddy.api.TransactionController.BatchImportResponse createTransactionsBatch(
            final UserTable user,
            final List<com.budgetbuddy.api.TransactionController.CreateTransactionRequest> requests) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (requests == null || requests.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transactions list is required");
        }

        com.budgetbuddy.api.TransactionController.BatchImportResponse response = 
                new com.budgetbuddy.api.TransactionController.BatchImportResponse();
        response.setTotal(requests.size());
        response.setCreated(0);
        response.setFailed(0);
        response.setErrors(new ArrayList<>());
        response.setCreatedTransactionIds(new ArrayList<>());

        for (com.budgetbuddy.api.TransactionController.CreateTransactionRequest request : requests) {
            // CRITICAL: Skip null transactions (edge case handling)
            if (request == null) {
                response.setFailed(response.getFailed() + 1);
                response.getErrors().add("Null transaction request skipped");
                continue;
            }
            try {
                TransactionTable transaction = createTransaction(
                        user,
                        request.getAccountId(),
                        request.getAmount(),
                        request.getTransactionDate(),
                        request.getDescription(),
                        request.getCategoryPrimary(),
                        request.getCategoryDetailed(),
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        request.getTransactionId(),
                        request.getNotes(),
                        request.getPlaidAccountId(),
                        request.getPlaidTransactionId(),
                        request.getTransactionType(),
                        request.getCurrencyCode(),
                        request.getImportSource(),
                        request.getImportBatchId(),
                        request.getImportFileName(),
                        request.getReviewStatus(), // Pass review status
                        request.getMerchantName(), // Pass merchantName (where purchase was made)
                        request.getPaymentChannel(), // Pass paymentChannel
                        request.getUserName(), // Pass userName (card/account user - family member)
                        null, // goalId (not available in batch import request)
                        null // linkedTransactionId (not available in batch import request)
                );
                response.setCreated(response.getCreated() + 1);
                if (transaction.getTransactionId() != null) {
                    response.getCreatedTransactionIds().add(transaction.getTransactionId());
                }
            } catch (Exception e) {
                response.setFailed(response.getFailed() + 1);
                // CRITICAL: Handle case where request might be null (defensive programming)
                String transactionDesc = (request != null && request.getDescription() != null) ? request.getDescription() : "unknown";
                String errorMsg = String.format("Transaction %s: %s", 
                        transactionDesc,
                        e.getMessage() != null ? e.getMessage() : "Unknown error");
                response.getErrors().add(errorMsg);
                logger.error("Failed to create transaction in batch: {}", errorMsg, e);
            }
        }

        logger.info("Batch import completed: {} total, {} created, {} failed", 
                response.getTotal(), response.getCreated(), response.getFailed());
        return response;
    }

    /**
     * Find transaction by Plaid ID (for deduplication)
     */
    public Optional<TransactionTable> findByPlaidTransactionId(String plaidTransactionId) {
        return transactionRepository.findByPlaidTransactionId(plaidTransactionId);
    }

    // ========== TRANSACTION TYPE HELPER METHODS ==========

    /**
     * Standardized null/empty string check for transactionType
     */
    private boolean isNullOrEmpty(final String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Parse and validate user-provided transactionType
     * @return Optional containing valid TransactionType, or empty if invalid/missing
     */
    private Optional<com.budgetbuddy.model.TransactionType> parseUserTransactionType(final String transactionType) {
        if (isNullOrEmpty(transactionType)) {
            return Optional.empty();
        }
        try {
            return Optional.of(com.budgetbuddy.model.TransactionType.valueOf(transactionType.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid transaction type '{}' provided, will calculate automatically: {}", transactionType, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Ensure transactionType is set on transaction (calculates if null/empty)
     * This is a centralized method to reduce code duplication
     */
    private void ensureTransactionTypeSet(final TransactionTable transaction, final AccountTable account) {
        if (isNullOrEmpty(transaction.getTransactionType())) {
            com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                    account,
                    transaction.getCategoryPrimary(),
                    transaction.getCategoryDetailed() != null && !transaction.getCategoryDetailed().isEmpty()
                            ? transaction.getCategoryDetailed()
                            : transaction.getCategoryPrimary(),
                    transaction.getAmount()
            );
            transaction.setTransactionType(calculatedType.name());
            // Calculated and set transactionType
        }
    }

    /**
     * Ensure transactionType is set on transaction, fetching account if needed
     */
    private void ensureTransactionTypeSetWithAccountLookup(final TransactionTable transaction) {
        if (isNullOrEmpty(transaction.getTransactionType())) {
            AccountTable account = null;
            if (transaction.getAccountId() != null) {
                account = accountRepository.findById(transaction.getAccountId()).orElse(null);
            }
            ensureTransactionTypeSet(transaction, account);
        }
    }

    /**
     * Set transactionType from user-provided value or calculate automatically
     * @param transaction The transaction to update
     * @param userProvidedType Optional user-provided transactionType string
     * @param account The account associated with the transaction (can be null)
     * @param categoryPrimary Primary category
     * @param categoryDetailed Detailed category (can be null)
     * @param amount Transaction amount
     * @return true if user-provided type was used, false if calculated
     */
    private boolean setTransactionTypeFromUserOrCalculate(
            final TransactionTable transaction,
            final String userProvidedType,
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount) {
        
        Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(userProvidedType);
        
        if (userTypeOpt.isPresent()) {
            // User provided valid transactionType - use it and mark as overridden
            com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
            transaction.setTransactionType(userType.name());
            transaction.setTransactionTypeOverridden(true);
            // Using user-provided transaction type
            return true;
        } else {
            // No user-provided type or invalid - calculate automatically
            com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                    account,
                    categoryPrimary,
                    categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary,
                    amount
            );
            transaction.setTransactionType(calculatedType.name());
            // Only set overridden=false if it's currently null (preserve existing override state)
            if (transaction.getTransactionTypeOverridden() == null) {
                transaction.setTransactionTypeOverridden(false);
            }
            // Calculated transaction type
            return false;
        }
    }
    
    /**
     * Set transaction type using unified service (hybrid logic) or fallback to old determiner
     */
    private void setTransactionTypeFromUnifiedServiceOrCalculate(
            final TransactionTable transaction,
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount) {
        
        try {
            // Try unified service first (hybrid logic)
            TransactionTypeCategoryService.TypeResult typeResult = 
                transactionTypeCategoryService.determineTransactionType(
                    account,
                    categoryPrimary,
                    categoryDetailed,
                    amount,
                    null, // transactionTypeIndicator not available
                    transaction.getDescription(),
                    transaction.getPaymentChannel()
                );
            
            if (typeResult != null) {
                transaction.setTransactionType(typeResult.getTransactionType().name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                // Calculated transaction type using unified service
            } else {
                // Fallback to old determiner
                com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                    account,
                    categoryPrimary,
                    categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary,
                    amount
                );
                transaction.setTransactionType(calculatedType.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                // Calculated transaction type using fallback determiner
            }
        } catch (Exception e) {
            logger.warn("Failed to calculate transaction type using unified service, using fallback: {}", e.getMessage());
            // Fallback to old determiner
            com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                account,
                categoryPrimary,
                categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary,
                amount
            );
            transaction.setTransactionType(calculatedType.name());
            if (transaction.getTransactionTypeOverridden() == null) {
                transaction.setTransactionTypeOverridden(false);
            }
        }
    }

    /**
     * Create manual transaction (backward compatibility - generates new UUID)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary) {
        // Call main method with 18 nulls after the 6 initial params (categoryDetailed, importerCategoryPrimary, importerCategoryDetailed, transactionId, notes, plaidAccountId, plaidTransactionId, transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId, linkedTransactionId)
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create manual transaction (backward compatibility - with categoryDetailed)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed) {
        // Call main method with 16 nulls after the 7 initial params (importerCategoryPrimary, importerCategoryDetailed, transactionId, notes, plaidAccountId, plaidTransactionId, transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId, linkedTransactionId)
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create manual transaction
     * @param transactionId Optional transaction ID from app. If provided and valid, use it to ensure app-backend ID consistency.
     *                      If not provided or invalid, generate a new UUID.
     * @param notes Optional user notes for the transaction
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes) {
        // Call main method: 7 initial params, then importerCategoryPrimary (null), importerCategoryDetailed (null), transactionId, notes, then remaining 12 fields (plaidAccountId, plaidTransactionId, transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId, linkedTransactionId)
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, null, null, transactionId, notes, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    
    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback lookup
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes, final String plaidAccountId) {
        // Call main method: 7 initial params, then importerCategoryPrimary (null), importerCategoryDetailed (null), transactionId, notes, plaidAccountId, then remaining 10 fields (plaidTransactionId, transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId, linkedTransactionId)
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, null, null, transactionId, notes, plaidAccountId, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    
    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback lookup
     * @param categoryPrimary Primary category (required)
     * @param categoryDetailed Detailed category (optional, defaults to categoryPrimary if not provided)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup and ID consistency
     * @param transactionType Optional user-selected transaction type. If not provided, backend will calculate it.
     * @param currencyCode Optional currency code (USD, INR, etc.). Defaults to user preference or USD.
     * @param importSource Optional import source ("CSV", "PLAID", "MANUAL", "API")
     * @param importBatchId Optional UUID for grouping imports
     * @param importFileName Optional original file name for imports
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes, final String plaidAccountId, final String plaidTransactionId, final String transactionType, final String currencyCode, final String importSource, final String importBatchId, final String importFileName) {
        // Overload with importerCategory fields (defaults to null for backward compatibility)
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed,                                     
            null, null, transactionId, notes, plaidAccountId, plaidTransactionId, transactionType, currencyCode,                                               
            importSource, importBatchId, importFileName, null, null, null, null, null, null);
    }
    
    /**
     * Create transaction with importer category fields support
     * @param merchantName Optional merchant name (where purchase was made, e.g., "Amazon", "Starbucks")
     * @param paymentChannel Optional payment channel (online, in_store, ach, etc.)
     * @param userName Optional card/account user name (family member who made the transaction)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String importerCategoryPrimary, final String importerCategoryDetailed, final String transactionId, final String notes, final String plaidAccountId, final String plaidTransactionId, final String transactionType, final String currencyCode, final String importSource, final String importBatchId, final String importFileName, final String reviewStatus, final String merchantName, final String paymentChannel, final String userName, final String goalId, final String linkedTransactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (amount == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Amount is required");
        }
        if (transactionDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction date is required");
        }
        if (categoryPrimary == null || categoryPrimary.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category primary is required");
        }

        AccountTable account;
        
        // CRITICAL FIX: Plaid transactions should NEVER use pseudo account
        // If plaidAccountId is provided, this is a Plaid transaction and must use a real account
        if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
            // This is a Plaid transaction - must find account by Plaid ID or accountId
            Optional<AccountTable> accountOpt = null;
            
            // Try to find account by accountId first (if provided)
            if (accountId != null && !accountId.isEmpty()) {
                accountOpt = accountRepository.findById(accountId);
            }
            
            // If not found by accountId, try lookup by Plaid account ID (required for Plaid transactions)
            if (accountOpt == null || accountOpt.isEmpty()) {
                // Account not found by ID, trying Plaid account ID
                accountOpt = accountRepository.findByPlaidAccountId(plaidAccountId);
                if (accountOpt.isPresent()) {
                    AccountTable foundAccount = accountOpt.get();
                    // CRITICAL: Verify the account belongs to the user
                    if (foundAccount.getUserId() == null || !foundAccount.getUserId().equals(user.getUserId())) {
                        logger.warn("Account found by Plaid ID {} belongs to different user (found: {}, requested: {})", 
                                plaidAccountId, foundAccount.getUserId(), user.getUserId());
                        accountOpt = Optional.empty(); // Clear the result - account doesn't belong to user
                    } else {
                        logger.info("Found account by Plaid ID {} (requested accountId: {})", plaidAccountId, accountId);
                    }
                }
                
                // FALLBACK: If PlaidAccountIdIndex lookup failed, search user's accounts
                // This handles cases where the GSI isn't immediately consistent or isn't available
                if (accountOpt.isEmpty()) {
                    // Account not found by Plaid ID index, trying fallback
                    List<AccountTable> userAccounts = accountRepository.findByUserId(user.getUserId());
                    accountOpt = userAccounts.stream()
                            .filter(acc -> plaidAccountId != null && plaidAccountId.equals(acc.getPlaidAccountId()))
                            .findFirst();
                    if (accountOpt.isPresent()) {
                        logger.info("Found account by Plaid ID {} using fallback method (searching user's accounts)", plaidAccountId);
                    }
                }
            }
            
            // CRITICAL: Plaid transactions must have a real account - never use pseudo account
            account = accountOpt
                    .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, 
                        "Plaid transaction requires a valid account. Account not found for Plaid account ID: " + plaidAccountId));
        } else if (accountId == null || accountId.isEmpty()) {
            // Manual transaction without account - use pseudo account
            logger.info("No account ID provided for manual transaction - using pseudo account for user {}", user.getUserId());
            account = accountRepository.getOrCreatePseudoAccount(user.getUserId());
        } else {
            // Manual transaction with accountId - find account by ID
            Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
            account = accountOpt
                    .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
        }

        // CRITICAL: Verify account belongs to user (already checked for Plaid accounts above, but double-check for safety)
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, 
                    "Account does not belong to user. Account userId: " + account.getUserId() + ", User userId: " + user.getUserId());
        }

        TransactionTable transaction = new TransactionTable();
        
        // CRITICAL: Use provided transactionId if valid, otherwise use deterministic UUID from Plaid ID if available
        // This ensures app and backend use the same transaction ID for consistency
        if (transactionId != null && !transactionId.trim().isEmpty()) {
            // Validate UUID format
            try {
                UUID.fromString(transactionId); // Validates UUID format
                // CRITICAL FIX: Normalize ID to lowercase before checking for existing
                // This ensures we check with the normalized ID that will be saved
                String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(transactionId);
                // Check if transaction with this ID already exists (using normalized ID)
                Optional<TransactionTable> existingOpt = transactionRepository.findById(normalizedId);
                if (existingOpt.isPresent()) {
                    TransactionTable existing = existingOpt.get();
                    
                    // CRITICAL FIX: Verify the existing transaction belongs to the same user
                    // This prevents unauthorized access and ensures idempotent behavior
                    if (!existing.getUserId().equals(user.getUserId())) {
                        // Transaction exists but belongs to a different user - security issue
                        logger.warn("Transaction with ID {} already exists but belongs to different user. Generating new UUID for security.", normalizedId);
                        // Fall through to generate new UUID
                    } else {
                        // Transaction exists and belongs to the same user - check Plaid ID matching
                        String existingPlaidId = existing.getPlaidTransactionId();
                        boolean existingHasPlaidId = existingPlaidId != null && !existingPlaidId.isEmpty();
                        boolean requestHasPlaidId = plaidTransactionId != null && !plaidTransactionId.isEmpty();
                        
                        if (requestHasPlaidId && plaidTransactionId != null) {
                            // Request provides Plaid ID - must match for idempotency
                            final String providedPlaidId = plaidTransactionId; // Non-null due to check above
                            if (existingHasPlaidId && providedPlaidId.equals(existingPlaidId)) {
                                // Same transaction (matched by Plaid ID) - update transactionType if user provided one
                                if (transactionType != null && !transactionType.trim().isEmpty()) {
                                    try {
                                        com.budgetbuddy.model.TransactionType userTransactionType = com.budgetbuddy.model.TransactionType.valueOf(transactionType.trim().toUpperCase());
                                        if (!userTransactionType.name().equals(existing.getTransactionType())) {
                                            // User provided different transactionType - update it
                                            existing.setTransactionType(userTransactionType.name());
                                            transactionRepository.save(existing);
                                            logger.info("Updated transactionType to {} for existing transaction {} (user override)", 
                                                    userTransactionType, normalizedId);
                                        }
                                    } catch (IllegalArgumentException e) {
                                        logger.warn("Invalid transaction type '{}' provided for existing transaction, keeping existing type: {}", 
                                                transactionType, existing.getTransactionType());
                                    }
                                }
                                logger.info("Transaction with ID {} already exists and matches Plaid ID {}, returning existing", 
                                        normalizedId, providedPlaidId);
                                return existing;
                            } else if (existingHasPlaidId && !providedPlaidId.equals(existingPlaidId)) {
                                // CRITICAL: Plaid ID provided but doesn't match existing - this is a conflict
                                // Generate new UUID to prevent data corruption
                                logger.warn("Transaction with ID {} already exists but Plaid ID doesn't match (existing: {}, provided: {}). " +
                                        "Generating new UUID to prevent data conflict.", 
                                        normalizedId, existingPlaidId, providedPlaidId);
                                // Fall through to generate new UUID
                            } else {
                                // Request has Plaid ID but existing doesn't - update existing with Plaid ID and transactionType
                                // This handles the case where a manual transaction is later linked to Plaid
                                logger.info("Transaction with ID {} exists without Plaid ID, but request provides Plaid ID {}. " +
                                        "Updating existing transaction with Plaid ID and transactionType.", 
                                        normalizedId, providedPlaidId);
                                // Update Plaid ID
                                existing.setPlaidTransactionId(providedPlaidId);
                                // Update transactionType if user provided one
                                Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(transactionType);
                                if (userTypeOpt.isPresent()) {
                                    existing.setTransactionType(userTypeOpt.get().name());
                                    existing.setTransactionTypeOverridden(true);
                                    // Updated transactionType (user override)
                                }
                                transactionRepository.save(existing);
                                return existing;
                            }
                        } else {
                            // No Plaid ID provided in request - update transactionType if user provided one
                            // This handles both manual transactions and Plaid transactions without ID in request
                            Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(transactionType);
                            if (userTypeOpt.isPresent()) {
                                com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
                                if (!userType.name().equals(existing.getTransactionType())) {
                                    // User provided different transactionType - update it and mark as overridden
                                    existing.setTransactionType(userType.name());
                                    existing.setTransactionTypeOverridden(true);
                                    transactionRepository.save(existing);
                                    logger.info("Updated transactionType to {} for existing transaction {} (user override)", 
                                            userType, normalizedId);
                                }
                            }
                            logger.info("Transaction with ID {} already exists (no Plaid ID in request). Returning existing for idempotency.", 
                                    normalizedId);
                            return existing;
                        }
                    }
                } else {
                    // Transaction doesn't exist - set normalized ID
                    transaction.setTransactionId(normalizedId);
                    logger.info("Using provided transaction ID (normalized): {} -> {}", transactionId, normalizedId);
                }
            } catch (IllegalArgumentException e) {
                // Invalid UUID format, fall through to generate deterministic UUID from Plaid ID if available
                logger.warn("Invalid transaction ID format '{}', will use deterministic UUID from Plaid ID if available", transactionId);
            }
        }
        
        // If transactionId is not set yet, try to generate deterministic UUID
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                // Generate deterministic UUID from Plaid transaction ID (matches iOS app fallback)
                // This ensures both app and backend use the same ID when institution/account info is missing
                java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"); // TRANSACTION_NAMESPACE
                String generatedId = com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(namespaceUUID, plaidTransactionId);
                // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
                transaction.setTransactionId(normalizedId);
                logger.info("Generated deterministic transaction ID (normalized): {} from Plaid ID: {} (matches iOS app fallback)", 
                        normalizedId, plaidTransactionId);
            } else if (importSource != null && !importSource.trim().isEmpty() && 
                      importFileName != null && !importFileName.trim().isEmpty() &&
                      account != null) {
                // CRITICAL FIX: Generate deterministic UUID for imported transactions to prevent duplicates
                // This ensures reimporting the same file creates the same transaction IDs
                // Use: importSource + normalized filename + accountId + amount + date + description
                String normalizedFileName = importFileName.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "");
                // CRITICAL FIX: Use the transactionDate parameter (LocalDate) and format it consistently
                // Don't use transaction.getTransactionDate() as it hasn't been set yet
                String transactionDateStr = transactionDate != null ? transactionDate.format(DATE_FORMATTER) : "";
                String descriptionStr = description != null ? description : "";
                String transactionKey = String.format("%s|%s|%s|%s|%s|%s",
                        importSource.trim().toUpperCase(),
                        normalizedFileName,
                        account.getAccountId(),
                        amount.toString(),
                        transactionDateStr,
                        descriptionStr.trim().toLowerCase());
                
                java.util.UUID importNamespaceUUID = java.util.UUID.fromString("7ba7b811-9dad-11d1-80b4-00c04fd430c8"); // IMPORT_NAMESPACE
                String generatedId = com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(importNamespaceUUID, transactionKey);
                String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
                
                // CRITICAL: Check if transaction with this deterministic ID already exists
                Optional<TransactionTable> existingByIdOpt = transactionRepository.findById(normalizedId);
                if (existingByIdOpt.isPresent()) {
                    TransactionTable existing = existingByIdOpt.get();
                    // Verify it belongs to the same user
                    if (existing.getUserId().equals(user.getUserId())) {
                        logger.info("Duplicate imported transaction detected by deterministic ID (source: {}, file: {}). " +
                                "Returning existing transaction {} instead of creating new one.", 
                                importSource, importFileName, normalizedId);
                        return existing;
                    }
                }
                
                transaction.setTransactionId(normalizedId);
                logger.info("Generated deterministic transaction ID for import (source: {}, file: {}): {}", 
                        importSource, importFileName, normalizedId);
            } else {
                // No Plaid ID or import metadata available, generate random UUID
                // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                String generatedId = UUID.randomUUID().toString().toLowerCase();
                transaction.setTransactionId(generatedId);
                // Generated random UUID for transaction ID
            }
        }
        
        transaction.setUserId(user.getUserId());
        // CRITICAL FIX: Use the account's ID (which may be the pseudo account ID if accountId was null/empty)
        transaction.setAccountId(account.getAccountId());
        transaction.setAmount(amount);
        String formattedTransactionDate = transactionDate.format(DATE_FORMATTER);
        transaction.setTransactionDate(formattedTransactionDate);
        transaction.setDescription(description != null ? description : "");
        
        // Set merchant name (where purchase was made, e.g., "Amazon", "Starbucks")
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            transaction.setMerchantName(merchantName.trim());
        }
        
        // Set payment channel
        if (paymentChannel != null && !paymentChannel.trim().isEmpty()) {
            transaction.setPaymentChannel(paymentChannel.trim());
        }
        
        // Set user name (card/account user - family member who made the transaction)                     
        if (userName != null && !userName.trim().isEmpty()) {                                             
            transaction.setUserName(userName.trim());
        }
        
        // Set goal ID (goal this transaction contributes to)
        if (goalId != null && !goalId.trim().isEmpty()) {
            transaction.setGoalId(goalId.trim());
        }
        
        // Set linked transaction ID (for cross-account duplicate detection, e.g., credit card payment linked to checking payment)
        if (linkedTransactionId != null && !linkedTransactionId.trim().isEmpty()) {
            transaction.setLinkedTransactionId(linkedTransactionId.trim());
        }
        
        // CRITICAL: Set importer category fields (from import parser or Plaid)
        if (importerCategoryPrimary != null && !importerCategoryPrimary.isEmpty()) {
            transaction.setImporterCategoryPrimary(importerCategoryPrimary);
        }
        if (importerCategoryDetailed != null && !importerCategoryDetailed.isEmpty()) {
            transaction.setImporterCategoryDetailed(importerCategoryDetailed);
        }
        
        // CRITICAL: For imports, re-apply unified service with account information for better accuracy
        // Account is now available, so we can get more accurate categories and types
        // EXCEPTION: If categoryPrimary differs from importerCategoryPrimary, it means the user edited it
        // in the preview, so we should respect the edited category and NOT re-run determineCategory()
        boolean isUserEditedCategory = (categoryPrimary != null && !categoryPrimary.isEmpty() && 
                                        importerCategoryPrimary != null && !importerCategoryPrimary.isEmpty() &&
                                        !categoryPrimary.equalsIgnoreCase(importerCategoryPrimary));
        
        if (importSource != null && !importSource.trim().isEmpty() && 
            account != null && account.getAccountId() != null && !account.getAccountId().startsWith("pseudo-") &&
            !isUserEditedCategory) {
            // This is an import with a real account - re-apply unified service for better accuracy
            // But skip if user edited the category in preview (categoryPrimary != importerCategoryPrimary)
            try {
                TransactionTypeCategoryService.CategoryResult categoryResult = 
                    transactionTypeCategoryService.determineCategory(
                        importerCategoryPrimary != null ? importerCategoryPrimary : categoryPrimary,
                        importerCategoryDetailed != null ? importerCategoryDetailed : categoryDetailed,
                        account,
                        merchantName, // Use merchantName from parameter (merchant where purchase was made)
                        description,
                        amount,
                        paymentChannel, // Use paymentChannel from parameter
                        null, // transactionTypeIndicator not available
                        importSource
                    );
                
                if (categoryResult != null) {
                    String determinedPrimary = categoryResult.getCategoryPrimary();
                    String determinedDetailed = categoryResult.getCategoryDetailed();
                    
                    // CRITICAL: Check if determined category differs from importer's parsed category
                    // If it does, this is an internal override (parser/rules/ML overrode importer)
                    // Set categoryOverridden=true to prevent re-import from overriding it
                    boolean isInternalOverride = false;
                    
                    // Check if source indicates override (not "IMPORTER")
                    String source = categoryResult.getSource();
                    if (source != null && !"IMPORTER".equals(source) && !"IMPORT".equals(source)) {
                        // Source indicates internal logic overrode importer category
                        // This means parser/rules/ML determined a different category than the importer
                        isInternalOverride = true;
                        // Internal override detected for import
                    } else if (importerCategoryPrimary != null && !importerCategoryPrimary.isEmpty()) {
                        // Compare determined category with importer category
                        // If they differ, it's an override
                        if (!importerCategoryPrimary.equalsIgnoreCase(determinedPrimary)) {
                            isInternalOverride = true;
                            // Internal override detected
                        }
                    }
                    
                    transaction.setCategoryPrimary(determinedPrimary);
                    transaction.setCategoryDetailed(determinedDetailed);
                    
                    // CRITICAL: Set override flag if internal logic overrode importer category
                    // This prevents re-import from overriding the internally determined category
                    transaction.setCategoryOverridden(isInternalOverride);
                    
                    if (isInternalOverride) {
                        logger.info("✅ Internal category override applied for import: Importer='{}' → Determined='{}' (source: {}, confidence: {:.2f}). " +
                            "This override will be preserved during re-import.",
                            importerCategoryPrimary != null ? importerCategoryPrimary : categoryPrimary,
                            determinedPrimary, source, categoryResult.getConfidence());
                    } else {
                        // Re-applied unified service for import with account
                        // Re-applied unified service for import with account
                    }
                } else {
                    // Fallback to provided categories
                    transaction.setCategoryPrimary(categoryPrimary);
                    transaction.setCategoryDetailed(categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary);
                    transaction.setCategoryOverridden(false);
                }
            } catch (Exception e) {
                logger.warn("Failed to re-apply unified service for import, using provided categories: {}", e.getMessage());
                // Fallback to provided categories
                transaction.setCategoryPrimary(categoryPrimary);
                transaction.setCategoryDetailed(categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary);
                transaction.setCategoryOverridden(false);
            }
        } else if (isUserEditedCategory) {
            // User edited category in preview - respect the edited category
            transaction.setCategoryPrimary(categoryPrimary);
            transaction.setCategoryDetailed(categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary);
            transaction.setCategoryOverridden(true); // Mark as overridden since user edited it
            logger.info("✅ Preserving user-edited category from preview: categoryPrimary='{}' (importerCategoryPrimary='{}')",
                    categoryPrimary, importerCategoryPrimary);
        } else {
            // Not an import, or no account, or pseudo account - use provided categories
            transaction.setCategoryPrimary(categoryPrimary);
            transaction.setCategoryDetailed(categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary);
            transaction.setCategoryOverridden(false);
        }
        
        // Set currency code: use provided currencyCode if available, otherwise user preference, otherwise USD
        if (currencyCode != null && !currencyCode.trim().isEmpty()) {
            transaction.setCurrencyCode(currencyCode.trim().toUpperCase());
        } else if (user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()) {
            transaction.setCurrencyCode(user.getPreferredCurrency());
        } else {
            transaction.setCurrencyCode("USD"); // Default fallback
        }
        
        // Set import metadata if provided
        if (importSource != null && !importSource.trim().isEmpty()) {
            transaction.setImportSource(importSource.trim().toUpperCase());
        }
        if (importBatchId != null && !importBatchId.trim().isEmpty()) {
            transaction.setImportBatchId(importBatchId.trim());
        }
        if (importFileName != null && !importFileName.trim().isEmpty()) {
            transaction.setImportFileName(importFileName.trim());
        }
        if (importSource != null && !importSource.trim().isEmpty()) {
            // Set importedAt timestamp when import metadata is provided
            transaction.setImportedAt(Instant.now());
        }
        
        // CRITICAL: Use user-provided transactionType if available, otherwise calculate using unified service
        // This allows users to override the automatic determination
        if (transactionType != null && !transactionType.trim().isEmpty()) {
            // User provided transaction type - use it and mark as overridden
            try {
                com.budgetbuddy.model.TransactionType userType = com.budgetbuddy.model.TransactionType.valueOf(transactionType.trim().toUpperCase());
                transaction.setTransactionType(userType.name());
                transaction.setTransactionTypeOverridden(true);
                // Using user-provided transaction type
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid transaction type '{}' provided, will calculate automatically", transactionType);
                // Fall through to calculate
                setTransactionTypeFromUnifiedServiceOrCalculate(transaction, account, transaction.getCategoryPrimary(), transaction.getCategoryDetailed(), amount);
            }
        } else {
            // No user-provided type - calculate using unified service
            setTransactionTypeFromUnifiedServiceOrCalculate(transaction, account, transaction.getCategoryPrimary(), transaction.getCategoryDetailed(), amount);
        }
        
        // Set Plaid transaction ID if provided (for fallback lookup and deduplication)
        if (plaidTransactionId != null && !plaidTransactionId.trim().isEmpty()) {
            transaction.setPlaidTransactionId(plaidTransactionId.trim());
        }
        
        // Set notes if provided
        if (notes != null) {
            transaction.setNotes(notes.trim().isEmpty() ? null : notes.trim());
        }
        
        // Set review status if provided
        if (reviewStatus != null && !reviewStatus.trim().isEmpty()) {
            transaction.setReviewStatus(reviewStatus.trim());
        }

        // CRITICAL: Check for duplicate transactions before saving
        // Enhanced duplicate detection for imported transactions (CSV/Excel/PDF)
        String transactionDateStr = transaction.getTransactionDate();
        if (transactionDateStr != null && !transactionDateStr.isEmpty()) {
            // Query transactions for this user on the same date
            List<TransactionTable> sameDateTransactions = transactionRepository.findByUserIdAndDateRange(
                    user.getUserId(), 
                    transactionDateStr, 
                    transactionDateStr);
            
            // For imported transactions, check for duplicates using import metadata
            if (importSource != null && !importSource.trim().isEmpty() && 
                importFileName != null && !importFileName.trim().isEmpty()) {
                // This is an imported transaction - check for duplicates by import source + filename + transaction details
                for (TransactionTable existing : sameDateTransactions) {
                    boolean accountMatch = account != null && account.getAccountId().equals(existing.getAccountId());
                    boolean amountMatch = amount.compareTo(existing.getAmount()) == 0;
                    boolean dateMatch = transactionDateStr.equals(existing.getTransactionDate());
                    boolean importSourceMatch = importSource.trim().equalsIgnoreCase(
                            existing.getImportSource() != null ? existing.getImportSource() : "");
                    boolean fileNameMatch = importFileName.trim().equalsIgnoreCase(
                            existing.getImportFileName() != null ? existing.getImportFileName() : "");
                    // Also check description for better matching (handles cases where same amount/date but different transactions)
                    boolean descriptionMatch = (description != null ? description.trim() : "")
                            .equalsIgnoreCase(existing.getDescription() != null ? existing.getDescription() : "");
                    
                    if (accountMatch && amountMatch && dateMatch && importSourceMatch && 
                        fileNameMatch && descriptionMatch) {
                        // Duplicate imported transaction found - return existing for idempotency
                        logger.info("Duplicate imported transaction detected (source: {}, file: {}, account: {}, amount: {}, date: {}). " +
                                "Returning existing transaction {} instead of creating new one.", 
                                importSource, importFileName, account != null ? account.getAccountId() : "null", amount, transactionDateStr, existing.getTransactionId());
                        return existing;
                    }
                }
            } else if ((plaidTransactionId == null || plaidTransactionId.trim().isEmpty()) && 
                      (transactionId == null || transactionId.trim().isEmpty())) {
                // This is a manual transaction without explicit ID - check for duplicates by account, amount, and date
                for (TransactionTable existing : sameDateTransactions) {
                    boolean accountMatch = account != null && account.getAccountId().equals(existing.getAccountId());
                    boolean amountMatch = amount.compareTo(existing.getAmount()) == 0;
                    boolean dateMatch = transactionDateStr.equals(existing.getTransactionDate());
                    
                    if (accountMatch && amountMatch && dateMatch) {
                        // Duplicate found - return existing transaction for idempotency
                        logger.info("Duplicate transaction detected (account: {}, amount: {}, date: {}). " +
                                "Returning existing transaction {} instead of creating new one.", 
                                account != null ? account.getAccountId() : "null", amount, transactionDateStr, existing.getTransactionId());
                        return existing;
                    }
                }
            }
        }

        // CRITICAL: Set timestamps for data freshness and incremental sync
        Instant now = Instant.now();
        transaction.setCreatedAt(now);
        transaction.setUpdatedAt(now);

        transactionRepository.save(transaction);
        
        // Trigger goal progress recalculation if transaction is assigned to a goal
        if (transaction.getGoalId() != null && !transaction.getGoalId().isEmpty()) {
            try {
                com.budgetbuddy.service.GoalProgressService goalProgressService = 
                    applicationContext.getBean(com.budgetbuddy.service.GoalProgressService.class);
                if (goalProgressService != null) {
                    goalProgressService.onTransactionGoalAssignmentChanged(user.getUserId(), transaction.getGoalId());
                }
            } catch (Exception e) {
                logger.debug("Goal progress service not available or error triggering recalculation: {}", e.getMessage());
            }
        }
        
        // P1: Invalidate cache when transaction is created (category/type may change)
        invalidateCategoryCache(transaction);
        
        // P2: Audit logging
        String source = importSource != null ? importSource : (plaidTransactionId != null ? "PLAID" : "MANUAL");
        auditService.logTransactionCreation(transaction, source);
        
        logger.info("Created transaction {} for user {} with notes: {} (Plaid ID: {})",
                transaction.getTransactionId(), user.getEmail(),
                notes != null && !notes.trim().isEmpty() ? "yes" : "no",
                plaidTransactionId != null ? plaidTransactionId : "none");
        return transaction;
    }

    /**
     * Update transaction (e.g., notes)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     * CRITICAL: When notes is null in this method, it means clear notes (explicit update)
     */
    public TransactionTable updateTransaction(final UserTable user, final String transactionId, final String notes) {
        // When this 3-parameter method is called, notes is explicitly provided (even if null)
        // So null means clear notes, not preserve
        return updateTransaction(user, transactionId, null, null, notes, null, null, null, null, null, true, null, null);
    }
    
    /**
     * Update transaction (e.g., amount, notes, category override, audit state, hidden state)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId not found
     * @param amount Optional: transaction amount (for type changes)
     * @param categoryPrimary Optional: override primary category
     * @param categoryDetailed Optional: override detailed category
     * @param reviewStatus Optional: review status ("none", "flagged", "reviewed", "error")
     * @param isHidden Optional: whether transaction is hidden from view
     * @param transactionType Optional: user-selected transaction type. If not provided, backend will calculate it.
     * @param clearNotesIfNull If true, null notes means clear notes. If false, null notes means preserve existing.
     * @param goalId Optional: Goal ID this transaction contributes to
     */
    public TransactionTable updateTransaction(final UserTable user, final String transactionId, final String plaidTransactionId, final BigDecimal amount, final String notes, final String categoryPrimary, final String categoryDetailed, final String reviewStatus, final Boolean isHidden, final String transactionType, final boolean clearNotesIfNull, final String goalId, final String linkedTransactionId) {
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
            // Transaction not found by ID, trying Plaid ID
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

        // Update amount if provided (for type changes)
        if (amount != null) {
            transaction.setAmount(amount);
            logger.info("Amount updated: {}", amount);
        }

        // Update Plaid transaction ID if provided
        if (plaidTransactionId != null && !plaidTransactionId.trim().isEmpty()) {
            transaction.setPlaidTransactionId(plaidTransactionId.trim());
            logger.info("Plaid transaction ID updated: {}", plaidTransactionId.trim());
        }

        // CRITICAL FIX: Handle notes update based on clearNotesIfNull flag
        // - If clearNotesIfNull is true: null notes means clear notes (explicit update)
        // - If clearNotesIfNull is false: null notes means preserve existing (partial update)
        if (notes != null) {
            // Notes field was provided in the request - update it
            String trimmedNotes = notes.trim();
            transaction.setNotes(trimmedNotes.isEmpty() ? null : trimmedNotes);
            logger.info("Notes updated: {}", trimmedNotes.isEmpty() ? "cleared" : trimmedNotes);
        } else if (clearNotesIfNull) {
            // Notes is null and clearNotesIfNull is true - clear notes (explicit update)
            transaction.setNotes(null);
            logger.info("Notes cleared (explicit null update)");
        } else {
            // Notes is null and clearNotesIfNull is false - preserve existing notes
            // This prevents clearing notes when only other fields (like plaidTransactionId) are being updated
            // Notes field not provided - preserving existing notes
        }
        
        // Update category override if provided
        boolean categoryChanged = false;
        String oldCategoryPrimary = transaction.getCategoryPrimary();
        if (categoryPrimary != null && !categoryPrimary.trim().isEmpty()) {
            String trimmedPrimary = categoryPrimary.trim();
            String trimmedDetailed = categoryDetailed != null && !categoryDetailed.trim().isEmpty() 
                    ? categoryDetailed.trim() 
                    : trimmedPrimary;
            
            transaction.setCategoryPrimary(trimmedPrimary);
            transaction.setCategoryDetailed(trimmedDetailed);
            categoryChanged = true;
            
            // P2: Audit logging for category changes
            if (!trimmedPrimary.equals(oldCategoryPrimary)) {
                auditService.logCategoryChange(
                    transaction.getTransactionId(), user.getUserId(),
                    oldCategoryPrimary, trimmedPrimary,
                    "USER_OVERRIDE", "User manually changed category"
                );
            }
            
            // CRITICAL: Always set categoryOverridden=true when category is changed
            // Additionally, ensure it's set for income, investment, and loan categories
            boolean isIncomeInvestmentOrLoan = isIncomeCategory(trimmedPrimary) || 
                                               isInvestmentCategory(trimmedPrimary) || 
                                               isLoanCategory(trimmedPrimary) ||
                                               isIncomeCategory(trimmedDetailed) || 
                                               isInvestmentCategory(trimmedDetailed) || 
                                               isLoanCategory(trimmedDetailed);
            
            transaction.setCategoryOverridden(true);
            
            if (isIncomeInvestmentOrLoan) {
                logger.info("Category override applied for income/investment/loan: primary={}, detailed={}", 
                        trimmedPrimary, trimmedDetailed);
            } else {
                logger.info("Category override applied: primary={}, detailed={}", 
                        trimmedPrimary, trimmedDetailed);
            }
        }
        
        // CRITICAL: Update transaction type if:
        // 1. User provided a transactionType (explicit override) - always respect user selection
        // 2. Category or amount changed and no user-provided type AND not already overridden - recalculate automatically
        Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(transactionType);
        
        if (userTypeOpt.isPresent()) {
            // User provided transaction type - use it (override automatic calculation)
            com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
            transaction.setTransactionType(userType.name());
            transaction.setTransactionTypeOverridden(true);
            // Using user-provided transaction type
        } else if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden()) && (categoryChanged || amount != null)) {
            // User didn't provide type, transaction not overridden, and category/amount changed - recalculate
            // Fetch account to determine transaction type
            AccountTable account = accountRepository.findById(transaction.getAccountId())
                    .orElse(null);
            
            // Use unified service if account is available, otherwise fallback
            if (account != null) {
                try {
                    TransactionTypeCategoryService.TypeResult typeResult = 
                        transactionTypeCategoryService.determineTransactionType(
                            account,
                            transaction.getCategoryPrimary(),
                            transaction.getCategoryDetailed(),
                            transaction.getAmount(),
                            null,
                            transaction.getDescription(),
                            transaction.getPaymentChannel()
                        );
                    
                    if (typeResult != null) {
                        transaction.setTransactionType(typeResult.getTransactionType().name());
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                        // Recalculated transaction type using unified service
                    } else {
                        // Fallback
                        com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                                account,
                                transaction.getCategoryPrimary(),
                                transaction.getCategoryDetailed(),
                                transaction.getAmount()
                        );
                        transaction.setTransactionType(calculatedType.name());
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                        // Recalculated transaction type
                    }
                } catch (Exception e) {
                    logger.warn("Failed to recalculate transaction type using unified service, using fallback: {}", e.getMessage());
                    // Fallback
                    com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                            account,
                            transaction.getCategoryPrimary(),
                            transaction.getCategoryDetailed(),
                            transaction.getAmount()
                    );
                    transaction.setTransactionType(calculatedType.name());
                    if (transaction.getTransactionTypeOverridden() == null) {
                        transaction.setTransactionTypeOverridden(false);
                    }
                }
            } else {
                // No account - use fallback
                com.budgetbuddy.model.TransactionType calculatedType = transactionTypeDeterminer.determineTransactionType(
                        null,
                        transaction.getCategoryPrimary(),
                        transaction.getCategoryDetailed(),
                        transaction.getAmount()
                );
                transaction.setTransactionType(calculatedType.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                // Recalculated transaction type (no account)
            }
        }
        
        // Update review status if provided
        if (reviewStatus != null && !reviewStatus.trim().isEmpty()) {
            transaction.setReviewStatus(reviewStatus.trim());
            logger.info("Review status updated: reviewStatus={}", reviewStatus.trim());
        }
        
        // Update hidden state if provided
        if (isHidden != null) {
            transaction.setIsHidden(isHidden);
            logger.info("Hidden state updated: isHidden={}", isHidden);
        }
        
        // Handle goalId assignment/removal
        String oldGoalId = transaction.getGoalId();
        if (goalId != null) {
            if (goalId.trim().isEmpty()) {
                transaction.setGoalId(null);
            } else {
                transaction.setGoalId(goalId.trim());
            }
        }
        
        // Handle linkedTransactionId assignment/removal
        if (linkedTransactionId != null) {
            if (linkedTransactionId.trim().isEmpty()) {
                transaction.setLinkedTransactionId(null);
            } else {
                transaction.setLinkedTransactionId(linkedTransactionId.trim());
            }
        }
        
        transaction.setUpdatedAt(java.time.Instant.now());                                                

        // P2: Audit logging for updates
        StringBuilder changes = new StringBuilder();
        if (categoryChanged) {
            changes.append("category:").append(oldCategoryPrimary).append("->").append(transaction.getCategoryPrimary()).append(";");                          
        }
        if (amount != null) {
            changes.append("amount:").append(amount).append(";");                                         
        }
        if (notes != null || clearNotesIfNull) {
            changes.append("notes:updated;");
        }
        if (transactionType != null) {
            changes.append("type:").append(transactionType).append(";");                                  
        }
        if (goalId != null) {
            changes.append("goalId:").append(goalId).append(";");
        }
        auditService.logTransactionUpdate(transaction.getTransactionId(), user.getUserId(),               
            changes.toString(), "USER_UPDATE");

        // P1: Invalidate cache when transaction is updated (category/type may change)                    
        if (categoryChanged || amount != null || transactionType != null) {                               
            invalidateCategoryCache(transaction);
        }

        transactionRepository.save(transaction);
        
        // Trigger goal progress recalculation if goalId changed
        String newGoalId = transaction.getGoalId();
        if ((oldGoalId == null && newGoalId != null) || 
            (oldGoalId != null && !oldGoalId.equals(newGoalId))) {
            try {
                com.budgetbuddy.service.GoalProgressService goalProgressService = 
                    applicationContext.getBean(com.budgetbuddy.service.GoalProgressService.class);
                if (goalProgressService != null) {
                    if (newGoalId != null) {
                        goalProgressService.onTransactionGoalAssignmentChanged(user.getUserId(), newGoalId);
                    }
                    if (oldGoalId != null && !oldGoalId.equals(newGoalId)) {
                        goalProgressService.onTransactionGoalAssignmentChanged(user.getUserId(), oldGoalId);
                    }
                }
            } catch (Exception e) {
                logger.debug("Goal progress service not available or error triggering recalculation: {}", e.getMessage());
            }
        }
        
        logger.info("Updated transaction {} for user {}", transactionId, user.getEmail());          
        return transaction;
    }

    /**
     * Get a single transaction by ID
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     */
    public TransactionTable getTransaction(final UserTable user, final String transactionId) {
        return getTransaction(user, transactionId, null);
    }
    
    /**
     * Get a single transaction by ID
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId not found
     */
    public TransactionTable getTransaction(final UserTable user, final String transactionId, final String plaidTransactionId) {
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
            // Transaction not found by ID, trying Plaid ID
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
    
    /**
     * Check if a category is an income category
     */
    private boolean isIncomeCategory(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        String lower = category.toLowerCase();
        return lower.equals("income") || 
               lower.equals("salary") || 
               lower.equals("interest") || 
               lower.equals("dividend") || 
               lower.equals("stipend") || 
               lower.equals("rentincome") || 
               lower.equals("rental_income") || 
               lower.equals("rental income") ||
               lower.equals("tips") || 
               lower.equals("otherincome") || 
               lower.equals("other_income") || 
               lower.equals("other income");
    }
    
    /**
     * Check if a category is an investment category
     */
    private boolean isInvestmentCategory(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        String lower = category.toLowerCase();
        return lower.equals("investment") || 
               lower.equals("cd") || 
               lower.equals("bonds") || 
               lower.equals("municipalbonds") || 
               lower.equals("municipal_bonds") ||
               lower.equals("tbills") || 
               lower.equals("t_bills") || 
               lower.equals("treasury bills") ||
               lower.equals("stocks") || 
               lower.equals("401k") || 
               lower.equals("fourzeroonek") ||
               lower.equals("529") || 
               lower.equals("fivetwonine") ||
               lower.equals("ira") || 
               lower.equals("mutualfunds") || 
               lower.equals("mutual_funds") ||
               lower.equals("etf") || 
               lower.equals("moneymarket") || 
               lower.equals("money_market") ||
               lower.equals("preciousmetals") || 
               lower.equals("precious_metals") ||
               lower.equals("crypto") || 
               lower.equals("otherinvestment") || 
               lower.equals("other_investment");
    }
    
    /**
     * Check if a category is a loan category
     * Note: Loan categories are typically identified by account type, not transaction category
     * But we check for loan-related categories that might be used
     */
    private boolean isLoanCategory(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        String lower = category.toLowerCase();
        return lower.contains("loan") || 
               lower.equals("mortgage") || 
               lower.equals("autoloan") || 
               lower.equals("auto_loan") ||
               lower.equals("personalloan") || 
               lower.equals("personal_loan") ||
               lower.equals("studentloan") || 
               lower.equals("student_loan") ||
               lower.equals("creditline") || 
               lower.equals("credit_line");
    }
    
    /**
     * P1: Invalidates category determination cache when transaction is created/updated
     * This ensures cache freshness when categories or types change
     */
    @CacheEvict(value = "categoryDetermination", allEntries = true)
    private void invalidateCategoryCache(TransactionTable transaction) {
        // Cache eviction is handled by @CacheEvict annotation
        // This method exists to trigger cache invalidation
        // Invalidated category determination cache
    }
}
