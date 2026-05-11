package com.budgetbuddy.service;

import com.budgetbuddy.audit.AuditService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

/**
 * Transaction service with cost optimization Uses pagination and date filtering to minimize data
 * transfer
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
public class TransactionService {

    private static final String NULL = "null";
    private static final String UNKNOWN = "unknown";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionTypeCategoryService transactionTypeCategoryService;
    private final AuditService auditService; // P2: Audit logging
    private final CategoryLearningService learningService; // User corrections and learning
    private final org.springframework.context.ApplicationContext
            applicationContext; // For lazy access to GoalProgressService

    public TransactionService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final TransactionTypeCategoryService transactionTypeCategoryService,
            final AuditService auditService,
            final CategoryLearningService learningService,
            final org.springframework.context.ApplicationContext applicationContext) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionTypeCategoryService = transactionTypeCategoryService;
        this.auditService = auditService;
        this.learningService = learningService;
        this.applicationContext = applicationContext;
    }

    /**
     * Get transactions with pagination to minimize data transfer Note: DynamoDB doesn't support
     * Pageable, so we use skip/limit pattern
     */
    public List<TransactionTable> getTransactions(final UserTable user, int skip, int limit) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (skip < 0) {
            skip = 0;
        }
        if (limit <= 0 || limit > 100) {
            limit = 50; // Default limit, max 100
        }
        return transactionRepository.findByUserId(user.getUserId(), skip, limit);
    }

    /** Get transactions in date range (optimized query) */
    public List<TransactionTable> getTransactionsInRange(
            final UserTable user, final LocalDate startDate, final LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(
                    ErrorCode.INVALID_DATE_RANGE, "Start date must be before or equal to end date");
        }

        final String startDateStr = startDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);
        return transactionRepository.findByUserIdAndDateRange(
                user.getUserId(), startDateStr, endDateStr);
    }

    /**
     * Get transactions by category (for budget tracking) Note: DynamoDB doesn't support complex
     * queries, so we filter in application
     */
    public List<TransactionTable> getTransactionsByCategory(
            final UserTable user, final String category, final LocalDate since) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (category == null || category.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        if (since == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Since date is required");
        }

        final String sinceStr = since.format(DATE_FORMATTER);
        final String nowStr = LocalDate.now().format(DATE_FORMATTER);
        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), sinceStr, nowStr);

        return transactions.stream()
                .filter(
                        t ->
                                t != null
                                        && (category.equals(t.getCategoryPrimary())
                                                || category.equals(t.getCategoryDetailed())))
                .collect(Collectors.toList());
    }

    /**
     * Calculate total spending in date range (aggregated query to minimize data transfer) Note:
     * DynamoDB doesn't support aggregation, so we calculate in application
     */
    public BigDecimal getTotalSpending(
            final UserTable user, final LocalDate startDate, final LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }

        final List<TransactionTable> transactions =
                getTransactionsInRange(user, startDate, endDate);
        return transactions.stream()
                .filter(t -> t != null)
                .map(TransactionTable::getAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get loan/credit card payments for a specific account For loan accounts: returns negative
     * amounts (payments made TO the loan) For credit card accounts: returns positive amounts
     * (payments made TO the credit card) and payment category transactions
     *
     * @param user The user
     * @param accountId The account ID
     * @param startDate Optional start date filter
     * @param endDate Optional end date filter
     * @return List of loan/credit card payment transactions
     */
    public List<TransactionTable> getLoanOrCreditCardPayments(
            final UserTable user,
            final String accountId,
            final LocalDate startDate,
            final LocalDate endDate) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (accountId == null || accountId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account ID is required");
        }

        // Get the account to determine its type
        final Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
        }

        final AccountTable account = accountOpt.get();

        // Verify account belongs to user
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Account does not belong to user");
        }

        // Get all transactions for the user
        final List<TransactionTable> allTransactions;
        if (startDate != null && endDate != null) {
            allTransactions = getTransactionsInRange(user, startDate, endDate);
        } else {
            // Get all transactions if no date range specified
            allTransactions =
                    transactionRepository.findByUserId(
                            user.getUserId(), 0, 10_000); // Large limit to get all
        }

        // Filter transactions for this account
        final List<TransactionTable> accountTransactions =
                allTransactions.stream()
                        .filter(t -> t != null && accountId.equals(t.getAccountId()))
                        .collect(Collectors.toList());

        // Determine account type
        String accountType = account.getAccountType();
        if (accountType == null) {
            accountType = "";
        }
        final String accountTypeUpper = accountType.toUpperCase(Locale.ROOT);

        // Check if it's a loan account
        final boolean isLoanAccount =
                "MORTGAGE".equals(accountTypeUpper)
                        || "AUTO_LOAN".equals(accountTypeUpper)
                        || "PERSONAL_LOAN".equals(accountTypeUpper)
                        || "STUDENT_LOAN".equals(accountTypeUpper)
                        || "CREDIT_LINE".equals(accountTypeUpper);

        // Check if it's a credit card account
        final boolean isCreditCardAccount =
                "CREDIT_CARD".equals(accountTypeUpper) || "CHARGE_CARD".equals(accountTypeUpper);

        // Filter transactions based on account type
        if (isLoanAccount) {
            // For loan accounts, return negative amounts (payments made TO the loan)
            return accountTransactions.stream()
                    .filter(
                            t ->
                                    t.getAmount() != null
                                            && t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());
        } else if (isCreditCardAccount) {
            // For credit card accounts, return positive amounts (payments made TO the credit card)
            // and transactions with payment category
            return accountTransactions.stream()
                    .filter(
                            t -> {
                                if (t.getAmount() == null) {
                                    return false;
                                }
                                // Positive amounts (payments that reduce credit card balance)
                                final boolean isPositiveAmount =
                                        t.getAmount().compareTo(BigDecimal.ZERO) > 0;
                                // Payment category transactions
                                final boolean isPaymentCategory =
                                        "payment".equalsIgnoreCase(t.getCategoryPrimary())
                                                || "payment"
                                                        .equalsIgnoreCase(t.getCategoryDetailed());
                                return isPositiveAmount || isPaymentCategory;
                            })
                    .collect(Collectors.toList());
        } else {
            // Not a loan or credit card account - return empty list
            return List.of();
        }
    }

    /**
     * Save transaction (from Plaid sync) Uses conditional write to prevent duplicate transactions
     * (deduplication)
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
            transaction.setTransactionId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        }

        // CRITICAL: Ensure transactionType is set (for old transactions that might have null
        // transactionType)
        // If transactionType is null/empty, calculate it even if override flag is set (null type
        // takes precedence)
        // If transactionType is set but override is false, recalculate if needed
        if (isNullOrEmpty(transaction.getTransactionType())) {
            // Type is null/empty - always calculate (null type takes precedence over override flag)
            ensureTransactionTypeSetWithAccountLookup(transaction);
        }
        // The else branch is intentionally absent: a non-null type already on the
        // transaction is left as-is, override flag or not — the recalc was a
        // safety-only path that no caller actually depended on.

        // CRITICAL FIX: Wells Fargo credit card special case
        // Wells Fargo credit card statements don't apply negative signs to payment transactions
        // If this is a Wells Fargo credit card account, transaction type is PAYMENT, category is
        // payment,
        // and amount is negative, make it positive (Wells Fargo convention)
        // This applies as a special case after sign reversal, type and category assignments
        if (transaction.getAccountId() != null
                && transaction.getTransactionType() != null
                && "PAYMENT".equalsIgnoreCase(transaction.getTransactionType())
                && transaction.getCategoryPrimary() != null
                && "payment".equalsIgnoreCase(transaction.getCategoryPrimary())
                && transaction.getAmount() != null
                && transaction.getAmount().compareTo(BigDecimal.ZERO) < 0) {

            try {
                final Optional<AccountTable> accountOpt =
                        accountRepository.findById(transaction.getAccountId());
                if (accountOpt.isPresent()) {
                    final AccountTable account = accountOpt.get();
                    final String accountType = account.getAccountType();
                    final String institutionName = account.getInstitutionName();
                    final String accountName = account.getAccountName();

                    // Check if it's a Wells Fargo credit card account
                    final boolean isCreditCard =
                            accountType != null
                                    && (accountType.toLowerCase(Locale.ROOT).contains("credit")
                                            || "creditcard".equalsIgnoreCase(accountType)
                                            || "credit_card".equalsIgnoreCase(accountType));

                    final boolean isWellsFargo =
                            (institutionName != null
                                            && (institutionName
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("wells fargo")
                                                    || institutionName
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("wellsfargo")
                                                    || "wf"
                                                            .equalsIgnoreCase(
                                                                    institutionName.toLowerCase(
                                                                            Locale.ROOT))))
                                    || (accountName != null
                                            && (accountName
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("wells fargo")
                                                    || accountName
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("wellsfargo")
                                                    || accountName
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("wf credit")));

                    final boolean isWellsFargoCreditCard = isCreditCard && isWellsFargo;

                    if (isWellsFargoCreditCard) {
                        // Wells Fargo credit card payment: make amount positive
                        final BigDecimal originalAmount = transaction.getAmount();
                        final BigDecimal positiveAmount = originalAmount.negate();
                        transaction.setAmount(positiveAmount);
                        LOGGER.info(
                                "🏷️ Wells Fargo credit card payment: Converted negative amount {} to positive {} for payment transaction (description: '{}', account: '{}')",
                                originalAmount,
                                positiveAmount,
                                transaction.getDescription(),
                                accountName);
                    }
                }
            } catch (Exception e) {
                // Non-fatal: if account lookup fails, continue without special case
                LOGGER.debug(
                        "Could not check account for Wells Fargo special case: {}", e.getMessage());
            }
        }

        // Use conditional write to prevent duplicate Plaid transactions
        final boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);

        // P1: Invalidate cache when transaction is saved (category/type may change)
        if (saved) {
            invalidateCategoryCache(transaction);
        }

        if (!saved) {
            // Transaction already exists (duplicate detected)
            final TransactionTable existing;
            if (transaction.getPlaidTransactionId() != null
                    && !transaction.getPlaidTransactionId().isEmpty()) {
                // Duplicate Plaid transaction - fetch by Plaid ID
                // Transaction with Plaid ID already exists
                existing =
                        transactionRepository
                                .findByPlaidTransactionId(transaction.getPlaidTransactionId())
                                .orElseThrow(
                                        () ->
                                                new AppException(
                                                        ErrorCode.RECORD_ALREADY_EXISTS,
                                                        "Transaction with Plaid ID already exists but could not be retrieved"));
            } else {
                // Duplicate transactionId (no Plaid ID) - fetch by transaction ID
                // Transaction with ID already exists
                existing =
                        transactionRepository
                                .findById(transaction.getTransactionId())
                                .orElseThrow(
                                        () ->
                                                new AppException(
                                                        ErrorCode.RECORD_ALREADY_EXISTS,
                                                        "Transaction with ID already exists but could not be retrieved"));
            }

            // CRITICAL: Ensure existing transaction has transactionType set (for old transactions)
            // If transactionType is null/empty, calculate it even if override flag is set (null
            // type takes precedence)
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
     *
     * @param user The user
     * @param requests List of CreateTransactionRequest objects
     * @return BatchImportResponse with success/failure counts
     */
    public com.budgetbuddy.api.TransactionController.BatchImportResponse createTransactionsBatch(
            final UserTable user,
            final List<com.budgetbuddy.api.TransactionController.CreateTransactionRequest>
                    requests) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (requests == null || requests.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transactions list is required");
        }

        final com.budgetbuddy.api.TransactionController.BatchImportResponse response =
                new com.budgetbuddy.api.TransactionController.BatchImportResponse();
        response.setTotal(requests.size());
        response.setCreated(0);
        response.setFailed(0);
        response.setErrors(new ArrayList<>());
        response.setCreatedTransactionIds(new ArrayList<>());

        for (final com.budgetbuddy.api.TransactionController.CreateTransactionRequest request :
                requests) {
            // CRITICAL: Skip null transactions (edge case handling)
            if (request == null) {
                response.setFailed(response.getFailed() + 1);
                response.getErrors().add("Null transaction request skipped");
                continue;
            }
            try {
                final TransactionTable transaction =
                        createTransaction(
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
                                request.getMerchantName(), // Pass merchantName (where purchase was
                                // made)
                                request.getLocation(), // Pass location (store/city/state)
                                request.getPaymentChannel(), // Pass paymentChannel
                                request.getUserName(), // Pass userName (card/account user - family
                                // member)
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
                final String transactionDesc =
                        request.getDescription() != null ? request.getDescription() : UNKNOWN;
                final String errorMsg =
                        String.format(
                                "Transaction %s: %s",
                                transactionDesc,
                                e.getMessage() != null ? e.getMessage() : "Unknown error");
                response.getErrors().add(errorMsg);
                LOGGER.error("Failed to create transaction in batch: {}", errorMsg, e);
            }
        }

        LOGGER.info(
                "Batch import completed: {} total, {} created, {} failed",
                response.getTotal(),
                response.getCreated(),
                response.getFailed());
        return response;
    }

    /** Find transaction by Plaid ID (for deduplication) */
    public Optional<TransactionTable> findByPlaidTransactionId(final String plaidTransactionId) {
        return transactionRepository.findByPlaidTransactionId(plaidTransactionId);
    }

    /**
     * Look up a row scoped to the owning user. Returns empty when the row doesn't exist OR is owned
     * by someone else — callers must not leak the difference between "not found" and "not yours" to
     * API clients. Added for Flow 4 / O2 optimistic-lock pre-check in {@code PUT
     * /api/transactions/{id}}.
     */
    public Optional<TransactionTable> findByTransactionIdAndUserId(
            final String transactionId, final String userId) {
        if (transactionId == null
                || transactionId.isEmpty()
                || userId == null
                || userId.isEmpty()) {
            return Optional.empty();
        }
        return transactionRepository
                .findById(transactionId)
                .filter(t -> userId.equals(t.getUserId()));
    }

    // ========== TRANSACTION TYPE HELPER METHODS ==========

    /** Standardized null/empty string check for transactionType */
    private boolean isNullOrEmpty(final String s) {
        return s == null || s.isBlank();
    }

    /**
     * Parse and validate user-provided transactionType
     *
     * @return Optional containing valid TransactionType, or empty if invalid/missing
     */
    private Optional<com.budgetbuddy.model.TransactionType> parseUserTransactionType(
            final String transactionType) {
        if (isNullOrEmpty(transactionType)) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    com.budgetbuddy.model.TransactionType.valueOf(
                            transactionType.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            LOGGER.warn(
                    "Invalid transaction type '{}' provided, will calculate automatically: {}",
                    transactionType,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Ensure transactionType is set on transaction (calculates if null/empty) This is a centralized
     * method to reduce code duplication
     */
    private void ensureTransactionTypeSet(
            final TransactionTable transaction, final AccountTable account) {
        if (isNullOrEmpty(transaction.getTransactionType())) {
            final com.budgetbuddy.model.TransactionType calculatedType =
                    resolveTransactionType(
                            account,
                            transaction.getCategoryPrimary(),
                            transaction.getCategoryDetailed(),
                            transaction.getAmount(),
                            null,
                            transaction.getDescription(),
                            transaction.getPaymentChannel());
            transaction.setTransactionType(calculatedType.name());
            final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            final String callerInfo =
                    stackTrace.length > 2
                            ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber()
                            : UNKNOWN;
            LOGGER.info(
                    "🔍 [TransactionType] Set transaction type | Line: {} | TransactionId: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Type: {} | Method: ensureTransactionTypeSet",
                    callerInfo,
                    transaction.getTransactionId(),
                    transaction.getAmount(),
                    transaction.getDescription() != null ? transaction.getDescription() : NULL,
                    transaction.getCategoryPrimary() != null
                            ? transaction.getCategoryPrimary()
                            : NULL,
                    transaction.getAccountId() != null ? transaction.getAccountId() : NULL,
                    calculatedType.name());
            // Calculated and set transactionType
        }
    }

    /** Ensure transactionType is set on transaction, fetching account if needed */
    private void ensureTransactionTypeSetWithAccountLookup(final TransactionTable transaction) {
        if (isNullOrEmpty(transaction.getTransactionType())) {
            AccountTable account = null;
            if (transaction.getAccountId() != null) {
                account = accountRepository.findById(transaction.getAccountId()).orElse(null);
            }
            ensureTransactionTypeSet(transaction, account);
        }
    }

    /** Set transaction type using unified service (hybrid logic) or fallback to old determiner */
    private void setTransactionTypeFromUnifiedServiceOrCalculate(
            final TransactionTable transaction,
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount) {

        try {
            // Try unified service first (hybrid logic)
            final TransactionTypeCategoryService.TypeResult typeResult =
                    transactionTypeCategoryService.determineTransactionType(
                            account,
                            categoryPrimary,
                            categoryDetailed,
                            amount,
                            null, // transactionTypeIndicator not available
                            transaction.getDescription(),
                            transaction.getPaymentChannel());

            if (typeResult != null) {
                transaction.setTransactionType(typeResult.getTransactionType().name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                final String callerInfo =
                        stackTrace.length > 2
                                ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber()
                                : UNKNOWN;
                LOGGER.info(
                        "🔍 [TransactionType] Set transaction type (UNIFIED SERVICE) | Line: {} | TransactionId: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Type: {} | Source: {} | Confidence: {} | Method: setTransactionTypeFromUnifiedServiceOrCalculate",
                        callerInfo,
                        transaction.getTransactionId(),
                        amount,
                        transaction.getDescription() != null ? transaction.getDescription() : NULL,
                        categoryPrimary != null ? categoryPrimary : NULL,
                        account != null ? account.getAccountId() : NULL,
                        typeResult.getTransactionType().name(),
                        typeResult.getSource(),
                        typeResult.getConfidence());
                // Calculated transaction type using unified service
            } else {
                final com.budgetbuddy.model.TransactionType calculatedType =
                        resolveTransactionType(
                                account,
                                categoryPrimary,
                                categoryDetailed,
                                amount,
                                null,
                                transaction.getDescription(),
                                transaction.getPaymentChannel());
                transaction.setTransactionType(calculatedType.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                final String callerInfo =
                        stackTrace.length > 2
                                ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber()
                                : UNKNOWN;
                LOGGER.info(
                        "🔍 [TransactionType] Set transaction type (FALLBACK) | Line: {} | TransactionId: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Type: {} | Method: setTransactionTypeFromUnifiedServiceOrCalculate (fallback)",
                        callerInfo,
                        transaction.getTransactionId(),
                        amount,
                        transaction.getDescription() != null ? transaction.getDescription() : NULL,
                        categoryPrimary != null ? categoryPrimary : NULL,
                        account != null ? account.getAccountId() : NULL,
                        calculatedType.name());
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to calculate transaction type using unified service, using fallback: {}",
                    e.getMessage());
            final com.budgetbuddy.model.TransactionType calculatedType =
                    resolveTransactionType(
                            account,
                            categoryPrimary,
                            categoryDetailed,
                            amount,
                            null,
                            transaction.getDescription(),
                            transaction.getPaymentChannel());
            transaction.setTransactionType(calculatedType.name());
            if (transaction.getTransactionTypeOverridden() == null) {
                transaction.setTransactionTypeOverridden(false);
            }
            final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            final String callerInfo =
                    stackTrace.length > 2
                            ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber()
                            : UNKNOWN;
            LOGGER.info(
                    "🔍 [TransactionType] Set transaction type (EXCEPTION FALLBACK) | Line: {} | TransactionId: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Type: {} | Error: {} | Method: setTransactionTypeFromUnifiedServiceOrCalculate (exception fallback)",
                    callerInfo,
                    transaction.getTransactionId(),
                    amount,
                    transaction.getDescription() != null ? transaction.getDescription() : NULL,
                    categoryPrimary != null ? categoryPrimary : NULL,
                    account != null ? account.getAccountId() : NULL,
                    calculatedType.name(),
                    e.getMessage());
        }
    }

    private com.budgetbuddy.model.TransactionType resolveTransactionType(
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount,
            final String transactionTypeIndicator,
            final String description,
            final String paymentChannel) {
        try {
            final TransactionTypeCategoryService.TypeResult typeResult =
                    transactionTypeCategoryService.determineTransactionType(
                            account,
                            categoryPrimary,
                            categoryDetailed != null && !categoryDetailed.isEmpty()
                                    ? categoryDetailed
                                    : categoryPrimary,
                            amount,
                            transactionTypeIndicator,
                            description,
                            paymentChannel);
            if (typeResult.getTransactionType() != null) {
                return typeResult.getTransactionType();
            }
        } catch (Exception e) {
            LOGGER.debug("resolveTransactionType failed: {}", e.getMessage());
        }
        return com.budgetbuddy.model.TransactionType.EXPENSE;
    }

    /** Create manual transaction (backward compatibility - generates new UUID) */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary) {
        // Call main method with 18 nulls after the 6 initial params (categoryDetailed,
        // importerCategoryPrimary, importerCategoryDetailed, transactionId, notes, plaidAccountId,
        // plaidTransactionId, transactionType, currencyCode, importSource, importBatchId,
        // importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId,
        // linkedTransactionId)
        return createTransaction(
                user,
                accountId,
                amount,
                transactionDate,
                description,
                categoryPrimary,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Create manual transaction (backward compatibility - with categoryDetailed) */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary,
            final String categoryDetailed) {
        // Call main method with 16 nulls after the 7 initial params (importerCategoryPrimary,
        // importerCategoryDetailed, transactionId, notes, plaidAccountId, plaidTransactionId,
        // transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus,
        // merchantName, paymentChannel, userName, goalId, linkedTransactionId)
        return createTransaction(
                user,
                accountId,
                amount,
                transactionDate,
                description,
                categoryPrimary,
                categoryDetailed,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Create manual transaction
     *
     * @param transactionId Optional transaction ID from app. If provided and valid, use it to
     *     ensure app-backend ID consistency. If not provided or invalid, generate a new UUID.
     * @param notes Optional user notes for the transaction
     */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary,
            final String categoryDetailed,
            final String transactionId,
            final String notes) {
        // Call main method: 7 initial params, then importerCategoryPrimary (null),
        // importerCategoryDetailed (null), transactionId, notes, then remaining 12 fields
        // (plaidAccountId, plaidTransactionId, transactionType, currencyCode, importSource,
        // importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName,
        // goalId, linkedTransactionId)
        return createTransaction(
                user,
                accountId,
                amount,
                transactionDate,
                description,
                categoryPrimary,
                categoryDetailed,
                null,
                null,
                transactionId,
                notes,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback
     * lookup
     */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary,
            final String categoryDetailed,
            final String transactionId,
            final String notes,
            final String plaidAccountId) {
        // Call main method: 7 initial params, then importerCategoryPrimary (null),
        // importerCategoryDetailed (null), transactionId, notes, plaidAccountId, then remaining 10
        // fields (plaidTransactionId, transactionType, currencyCode, importSource, importBatchId,
        // importFileName, reviewStatus, merchantName, paymentChannel, userName, goalId,
        // linkedTransactionId)
        return createTransaction(
                user,
                accountId,
                amount,
                transactionDate,
                description,
                categoryPrimary,
                categoryDetailed,
                null,
                null,
                transactionId,
                notes,
                plaidAccountId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback
     * lookup
     *
     * @param categoryPrimary Primary category (required)
     * @param categoryDetailed Detailed category (optional, defaults to categoryPrimary if not
     *     provided)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup and ID
     *     consistency
     * @param transactionType Optional user-selected transaction type. If not provided, backend will
     *     calculate it.
     * @param currencyCode Optional currency code (USD, INR, etc.). Defaults to user preference or
     *     USD.
     * @param importSource Optional import source ("CSV", "PLAID", "MANUAL", "API")
     * @param importBatchId Optional UUID for grouping imports
     * @param importFileName Optional original file name for imports
     */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary,
            final String categoryDetailed,
            final String transactionId,
            final String notes,
            final String plaidAccountId,
            final String plaidTransactionId,
            final String transactionType,
            final String currencyCode,
            final String importSource,
            final String importBatchId,
            final String importFileName) {
        // Overload with importerCategory fields (defaults to null for backward compatibility)
        return createTransaction(
                user,
                accountId,
                amount,
                transactionDate,
                description,
                categoryPrimary,
                categoryDetailed,
                null,
                null,
                transactionId,
                notes,
                plaidAccountId,
                plaidTransactionId,
                transactionType,
                currencyCode,
                importSource,
                importBatchId,
                importFileName,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Create transaction with importer category fields support
     *
     * @param merchantName Optional merchant name (where purchase was made, e.g., "Amazon",
     *     "Starbucks")
     * @param paymentChannel Optional payment channel (online, in_store, ach, etc.)
     * @param userName Optional card/account user name (family member who made the transaction)
     */
    public TransactionTable createTransaction(
            final UserTable user,
            final String accountId,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String description,
            final String categoryPrimary,
            final String categoryDetailed,
            final String importerCategoryPrimary,
            final String importerCategoryDetailed,
            final String transactionId,
            final String notes,
            final String plaidAccountId,
            final String plaidTransactionId,
            final String transactionType,
            final String currencyCode,
            final String importSource,
            final String importBatchId,
            final String importFileName,
            final String reviewStatus,
            final String merchantName,
            final String location,
            final String paymentChannel,
            final String userName,
            final String goalId,
            final String linkedTransactionId) {
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

        final AccountTable account = resolveAccountForCreate(user, accountId, plaidAccountId);

        // CRITICAL: Verify account belongs to user (already checked for Plaid accounts above, but
        // double-check for safety)
        if (account.getUserId() == null || !account.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS,
                    "Account does not belong to user. Account userId: "
                            + account.getUserId()
                            + ", User userId: "
                            + user.getUserId());
        }

        final TransactionTable transaction = new TransactionTable();

        // Resolve transaction ID (provided UUID, deterministic from Plaid/import, or random)
        // and check for idempotency hits. Returns the existing transaction if one matches.
        final TransactionTable idempotencyHit =
                resolveTransactionIdOrFindExisting(
                        transaction,
                        user,
                        account,
                        transactionId,
                        plaidTransactionId,
                        transactionType,
                        description,
                        userName,
                        amount,
                        transactionDate,
                        importSource,
                        importFileName);
        if (idempotencyHit != null) {
            return idempotencyHit;
        }

        transaction.setUserId(user.getUserId());
        // CRITICAL FIX: Use the account's ID (which may be the pseudo account ID if accountId was
        // null/empty)
        transaction.setAccountId(account.getAccountId());
        transaction.setAmount(amount);
        final String formattedTransactionDate = transactionDate.format(DATE_FORMATTER);
        transaction.setTransactionDate(formattedTransactionDate);
        String cleanedDescription = removeNamesFromText(description, userName);
        if (cleanedDescription == null || cleanedDescription.isEmpty()) {
            cleanedDescription = description != null ? description : "";
        }
        transaction.setDescription(cleanedDescription);

        // Set merchant name (where purchase was made, e.g., "Amazon", "Starbucks")
        final String cleanedMerchantName = removeNamesFromText(merchantName, userName);
        if (cleanedMerchantName != null && !cleanedMerchantName.isBlank()) {
            transaction.setMerchantName(cleanedMerchantName.trim());
        } else if (merchantName != null && !merchantName.isBlank()) {
            transaction.setMerchantName(merchantName.trim());
        }

        // Set location (store separately, do not merge into description)
        if (location != null && !location.isBlank()) {
            transaction.setLocation(location.trim());
        }

        // Set payment channel
        if (paymentChannel != null && !paymentChannel.isBlank()) {
            transaction.setPaymentChannel(paymentChannel.trim());
        }

        // Set user name (card/account user - family member who made the transaction)
        if (userName != null && !userName.isBlank()) {
            transaction.setUserName(userName.trim());
        }

        // Set goal ID (goal this transaction contributes to)
        if (goalId != null && !goalId.isBlank()) {
            transaction.setGoalId(goalId.trim());
        }

        // Set linked transaction ID (for cross-account duplicate detection, e.g., credit card
        // payment linked to checking payment)
        if (linkedTransactionId != null && !linkedTransactionId.isBlank()) {
            transaction.setLinkedTransactionId(linkedTransactionId.trim());
        }

        // CRITICAL: Set importer category fields (from import parser or Plaid)
        if (importerCategoryPrimary != null && !importerCategoryPrimary.isEmpty()) {
            transaction.setImporterCategoryPrimary(importerCategoryPrimary);
        }
        if (importerCategoryDetailed != null && !importerCategoryDetailed.isEmpty()) {
            transaction.setImporterCategoryDetailed(importerCategoryDetailed);
        }

        applyImportCategorization(
                transaction,
                account,
                categoryPrimary,
                categoryDetailed,
                importerCategoryPrimary,
                importerCategoryDetailed,
                cleanedDescription,
                cleanedMerchantName,
                merchantName,
                amount,
                paymentChannel,
                importSource);

        // Set currency code: use provided currencyCode if available, otherwise user preference,
        // otherwise USD
        if (currencyCode != null && !currencyCode.isBlank()) {
            transaction.setCurrencyCode(currencyCode.trim().toUpperCase(Locale.ROOT));
        } else if (user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()) {
            transaction.setCurrencyCode(user.getPreferredCurrency());
        } else {
            transaction.setCurrencyCode("USD"); // Default fallback
        }

        // Set import metadata if provided
        if (importSource != null && !importSource.isBlank()) {
            transaction.setImportSource(importSource.trim().toUpperCase(Locale.ROOT));
        }
        if (importBatchId != null && !importBatchId.isBlank()) {
            transaction.setImportBatchId(importBatchId.trim());
        }
        if (importFileName != null && !importFileName.isBlank()) {
            transaction.setImportFileName(importFileName.trim());
        }
        if (importSource != null && !importSource.isBlank()) {
            // Set importedAt timestamp when import metadata is provided
            transaction.setImportedAt(Instant.now());
        }

        // CRITICAL: Use user-provided transactionType if available, otherwise calculate using
        // unified service
        // This allows users to override the automatic determination
        if (transactionType != null && !transactionType.isBlank()) {
            // User provided transaction type - use it
            try {
                final com.budgetbuddy.model.TransactionType userType =
                        com.budgetbuddy.model.TransactionType.valueOf(
                                transactionType.trim().toUpperCase(Locale.ROOT));
                final String existingType = transaction.getTransactionType();
                transaction.setTransactionType(userType.name());

                // Determine if this is an import (CSV, PDF, etc.)
                final boolean isImport =
                        importSource != null
                                && !importSource.isBlank()
                                && ("CSV".equalsIgnoreCase(importSource)
                                        || "PDF".equalsIgnoreCase(importSource)
                                        || "EXCEL".equalsIgnoreCase(importSource));

                if (isImport) {
                    // For imports: Only mark as overridden if transaction was already overridden
                    // and new type differs
                    final boolean wasOverridden =
                            Boolean.TRUE.equals(transaction.getTransactionTypeOverridden());
                    if (wasOverridden
                            && existingType != null
                            && !userType.name().equals(existingType)) {
                        // Transaction was already overridden and new type differs - keep override
                        // flag
                        transaction.setTransactionTypeOverridden(true);
                    } else {
                        // First time setting value from import - don't mark as overridden
                        // Only set to false if currently null (preserve existing state)
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                    }
                } else {
                    // For non-imports (user API calls): Only mark as overridden if new type differs
                    // from existing
                    if (existingType == null || !userType.name().equals(existingType)) {
                        transaction.setTransactionTypeOverridden(true);
                    }
                }
                // Using user-provided transaction type
            } catch (IllegalArgumentException e) {
                LOGGER.warn(
                        "Invalid transaction type '{}' provided, will calculate automatically",
                        transactionType);
                // Fall through to calculate
                setTransactionTypeFromUnifiedServiceOrCalculate(
                        transaction,
                        account,
                        transaction.getCategoryPrimary(),
                        transaction.getCategoryDetailed(),
                        amount);
            }
        } else {
            // No user-provided type - calculate using unified service
            setTransactionTypeFromUnifiedServiceOrCalculate(
                    transaction,
                    account,
                    transaction.getCategoryPrimary(),
                    transaction.getCategoryDetailed(),
                    amount);
        }

        // Post-process type/category for imports only (determination happens here)
        final boolean isImport = importSource != null && !importSource.isBlank();
        if (isImport && !Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
            // Wells Fargo credit card payments should be positive
            if (account != null
                    && account.getAccountType() != null
                    && (account.getAccountType().toLowerCase(Locale.ROOT).contains("credit")
                            || "creditcard".equalsIgnoreCase(account.getAccountType())
                            || "credit_card".equalsIgnoreCase(account.getAccountType()))) {
                final String institutionName = account.getInstitutionName();
                final String accountName = account.getAccountName();
                final boolean isWellsFargo =
                        (institutionName != null
                                        && (institutionName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("wells fargo")
                                                || institutionName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("wellsfargo")
                                                || "wf"
                                                        .equalsIgnoreCase(
                                                                institutionName.toLowerCase(
                                                                        Locale.ROOT))))
                                || (accountName != null
                                        && (accountName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("wells fargo")
                                                || accountName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("wellsfargo")
                                                || accountName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("wf credit")));
                if (isWellsFargo
                        && transaction.getTransactionType() != null
                        && "PAYMENT".equalsIgnoreCase(transaction.getTransactionType())
                        && transaction.getCategoryPrimary() != null
                        && "payment".equalsIgnoreCase(transaction.getCategoryPrimary())
                        && amount != null
                        && amount.compareTo(BigDecimal.ZERO) < 0) {
                    final BigDecimal positiveAmount = amount.negate();
                    transaction.setAmount(positiveAmount);
                    LOGGER.info(
                            "🏷️ Wells Fargo credit card payment: Converted negative amount {} to positive {} for payment transaction (description: '{}')",
                            amount,
                            positiveAmount,
                            description);
                }
            }
        }

        // Set Plaid transaction ID if provided (for fallback lookup and deduplication)
        if (plaidTransactionId != null && !plaidTransactionId.isBlank()) {
            transaction.setPlaidTransactionId(plaidTransactionId.trim());
        }

        // Set notes if provided
        if (notes != null) {
            transaction.setNotes(notes.isBlank() ? null : notes.trim());
        }

        // Set review status if provided
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            transaction.setReviewStatus(reviewStatus.trim());
        }

        // Link checking/savings payments to matching credit/loan payments if possible
        if (transaction.getLinkedTransactionId() == null
                || transaction.getLinkedTransactionId().isBlank()) {
            try {
                attemptLinkPaymentTransaction(user, transaction, account, amount);
            } catch (Exception e) {
                LOGGER.debug("Failed to auto-link payment transaction: {}", e.getMessage());
            }
        }

        final TransactionTable duplicate =
                findDuplicateForCreate(
                        user,
                        account,
                        transaction,
                        amount,
                        description,
                        transactionId,
                        plaidTransactionId,
                        importSource,
                        importFileName);
        if (duplicate != null) {
            return duplicate;
        }

        // CRITICAL: Set timestamps for data freshness and incremental sync
        final Instant now = Instant.now();
        transaction.setCreatedAt(now);
        transaction.setUpdatedAt(now);

        transactionRepository.save(transaction);

        // Trigger goal progress recalculation if transaction is assigned to a goal
        if (transaction.getGoalId() != null && !transaction.getGoalId().isEmpty()) {
            try {
                final GoalProgressService goalProgressService =
                        applicationContext.getBean(GoalProgressService.class);
                // ApplicationContext.getBean(Class) is @NonNull — it throws
                // NoSuchBeanDefinitionException rather than returning null.
                goalProgressService.onTransactionGoalAssignmentChanged(
                        user.getUserId(), transaction.getGoalId());
            } catch (Exception e) {
                LOGGER.debug(
                        "Goal progress service not available or error triggering recalculation: {}",
                        e.getMessage());
            }
        }

        // P1: Invalidate cache when transaction is created (category/type may change)
        invalidateCategoryCache(transaction);

        // P2: Audit logging
        final String source =
                importSource != null
                        ? importSource
                        : (plaidTransactionId != null ? "PLAID" : "MANUAL");
        auditService.logTransactionCreation(transaction, source);

        LOGGER.info(
                "Created transaction {} for user {} with notes: {} (Plaid ID: {})",
                transaction.getTransactionId(),
                user.getEmail(),
                notes != null && !notes.isBlank() ? "yes" : "no",
                plaidTransactionId != null ? plaidTransactionId : "none");
        return transaction;
    }

    private void attemptLinkPaymentTransaction(
            final UserTable user,
            final TransactionTable transaction,
            final AccountTable account,
            final BigDecimal amount) {
        if (user == null || transaction == null || account == null || amount == null) {
            return;
        }
        if (transaction.getTransactionType() == null
                || !"PAYMENT".equalsIgnoreCase(transaction.getTransactionType())) {
            return;
        }
        if (!isCheckingOrSavingsAccount(account)) {
            return;
        }
        final String transactionDateStr = transaction.getTransactionDate();
        if (transactionDateStr == null || transactionDateStr.isEmpty()) {
            return;
        }

        final BigDecimal absAmount = amount.abs();
        if (absAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        final List<AccountTable> userAccounts = accountRepository.findByUserId(user.getUserId());
        final List<String> targetAccountIds =
                userAccounts.stream()
                        .filter(this::isCreditOrLoanAccount)
                        .map(AccountTable::getAccountId)
                        .filter(id -> id != null && !id.isEmpty())
                        .collect(Collectors.toList());
        if (targetAccountIds.isEmpty()) {
            return;
        }

        final LocalDate date;
        try {
            date = LocalDate.parse(transactionDateStr, DATE_FORMATTER);
        } catch (java.time.format.DateTimeParseException e) {
            // Best-effort linking pass; an unparsable date just skips this row.
            LOGGER.debug("attemptLinkPaymentTransaction: unparsable date '{}'", transactionDateStr);
            return;
        }
        final String startDate = date.minusDays(3).format(DATE_FORMATTER);
        final String endDate = date.plusDays(5).format(DATE_FORMATTER);
        final List<TransactionTable> candidateTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), startDate, endDate);

        TransactionTable bestMatch = null;
        for (final TransactionTable candidate : candidateTransactions) {
            if (candidate == null || candidate.getTransactionId() == null) {
                continue;
            }
            if (!targetAccountIds.contains(candidate.getAccountId())) {
                continue;
            }
            if (candidate.getAmount() == null || candidate.getAmount().compareTo(absAmount) != 0) {
                continue;
            }
            final boolean isPaymentCategory =
                    "payment".equalsIgnoreCase(candidate.getCategoryPrimary())
                            || "payment".equalsIgnoreCase(candidate.getCategoryDetailed());
            final boolean isPaymentType =
                    "PAYMENT".equalsIgnoreCase(candidate.getTransactionType());
            if (!isPaymentCategory && !isPaymentType) {
                continue;
            }
            final String linkedId = candidate.getLinkedTransactionId();
            if (linkedId != null
                    && !linkedId.isEmpty()
                    && !linkedId.equals(transaction.getTransactionId())) {
                continue;
            }

            if (transactionDateStr.equals(candidate.getTransactionDate())) {
                bestMatch = candidate;
                break;
            }
            if (bestMatch == null) {
                bestMatch = candidate;
            }
        }

        if (bestMatch != null) {
            transaction.setLinkedTransactionId(bestMatch.getTransactionId());
            if (bestMatch.getLinkedTransactionId() == null
                    || bestMatch.getLinkedTransactionId().isEmpty()) {
                bestMatch.setLinkedTransactionId(transaction.getTransactionId());
                transactionRepository.save(bestMatch);
            }
        }
    }

    private boolean isCheckingOrSavingsAccount(final AccountTable account) {
        if (account == null || account.getAccountType() == null) {
            return false;
        }
        final String typeLower = account.getAccountType().toLowerCase(Locale.ROOT);
        return typeLower.contains("checking")
                || typeLower.contains("depository")
                || typeLower.contains("savings");
    }

    private boolean isCreditOrLoanAccount(final AccountTable account) {
        if (account == null || account.getAccountType() == null) {
            return false;
        }
        final String typeUpper = account.getAccountType().toUpperCase(Locale.ROOT);
        if ("CREDIT_CARD".equals(typeUpper) || "CHARGE_CARD".equals(typeUpper)) {
            return true;
        }
        return "MORTGAGE".equals(typeUpper)
                || "AUTO_LOAN".equals(typeUpper)
                || "PERSONAL_LOAN".equals(typeUpper)
                || "STUDENT_LOAN".equals(typeUpper)
                || "CREDIT_LINE".equals(typeUpper);
    }

    /**
     * Update transaction (e.g., notes) Supports lookup by transactionId or plaidTransactionId
     * (fallback) CRITICAL: When notes is null in this method, it means clear notes (explicit
     * update)
     */
    public TransactionTable updateTransaction(
            final UserTable user, final String transactionId, final String notes) {
        // When this 3-parameter method is called, notes is explicitly provided (even if null)
        // So null means clear notes, not preserve
        return updateTransaction(
                user,
                transactionId,
                null,
                null,
                notes,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null);
    }

    /**
     * Update transaction (e.g., amount, notes, category override, audit state, hidden state)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     *
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId
     *     not found
     * @param amount Optional: transaction amount (for type changes)
     * @param categoryPrimary Optional: override primary category
     * @param categoryDetailed Optional: override detailed category
     * @param reviewStatus Optional: review status ("none", "flagged", "reviewed", "error")
     * @param isHidden Optional: whether transaction is hidden from view
     * @param transactionType Optional: user-selected transaction type. If not provided, backend
     *     will calculate it.
     * @param clearNotesIfNull If true, null notes means clear notes. If false, null notes means
     *     preserve existing.
     * @param goalId Optional: Goal ID this transaction contributes to
     */
    // Flow 7 / O13 — blow the analytics cache when anything on a transaction changes.
    // The cache is keyed by userId + date range, so we can't target a single row; the
    // cache is small (5k / 5 min) so a full wipe is cheap.
    @CacheEvict(value = "analytics", cacheManager = "analyticsCacheManager", allEntries = true)
    public TransactionTable updateTransaction(
            final UserTable user,
            final String transactionId,
            final String plaidTransactionId,
            final BigDecimal amount,
            final String notes,
            final String categoryPrimary,
            final String categoryDetailed,
            final String reviewStatus,
            final Boolean isHidden,
            final String transactionType,
            final boolean clearNotesIfNull,
            final String goalId,
            final String linkedTransactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Try to find by transactionId first
        Optional<TransactionTable> transactionOpt = transactionRepository.findById(transactionId);

        // If not found and plaidTransactionId is provided, try lookup by Plaid ID
        if (transactionOpt.isEmpty()
                && plaidTransactionId != null
                && !plaidTransactionId.isEmpty()) {
            // Transaction not found by ID, trying Plaid ID
            transactionOpt = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (transactionOpt.isPresent()) {
                LOGGER.info(
                        "Found transaction by Plaid ID {} (requested ID: {})",
                        plaidTransactionId,
                        transactionId);
            }
        }

        TransactionTable transaction =
                transactionOpt.orElseThrow(
                        () ->
                                new AppException(
                                        ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        // Update amount if provided (for type changes)
        if (amount != null) {
            transaction.setAmount(amount);
            LOGGER.info("Amount updated: {}", amount);
        }

        // Update Plaid transaction ID if provided
        if (plaidTransactionId != null && !plaidTransactionId.isBlank()) {
            transaction.setPlaidTransactionId(plaidTransactionId.trim());
            LOGGER.info("Plaid transaction ID updated: {}", plaidTransactionId.trim());
        }

        // CRITICAL FIX: Handle notes update based on clearNotesIfNull flag
        // - If clearNotesIfNull is true: null notes means clear notes (explicit update)
        // - If clearNotesIfNull is false: null notes means preserve existing (partial update)
        if (notes != null) {
            // Notes field was provided in the request - update it
            final String trimmedNotes = notes.trim();
            transaction.setNotes(trimmedNotes.isEmpty() ? null : trimmedNotes);
            LOGGER.info("Notes updated: {}", trimmedNotes.isEmpty() ? "cleared" : trimmedNotes);
        } else if (clearNotesIfNull) {
            // Notes is null and clearNotesIfNull is true - clear notes (explicit update)
            transaction.setNotes(null);
            LOGGER.info("Notes cleared (explicit null update)");
        }

        // Update category override if provided
        boolean categoryChanged = false;
        final String oldCategoryPrimary = transaction.getCategoryPrimary();
        if (categoryPrimary != null && !categoryPrimary.isBlank()) {
            final String trimmedPrimary = categoryPrimary.trim();
            final String trimmedDetailed =
                    categoryDetailed != null && !categoryDetailed.isBlank()
                            ? categoryDetailed.trim()
                            : trimmedPrimary;

            transaction.setCategoryPrimary(trimmedPrimary);
            transaction.setCategoryDetailed(trimmedDetailed);
            categoryChanged = true;

            // P2: Audit logging for category changes
            if (!trimmedPrimary.equals(oldCategoryPrimary)) {
                auditService.logCategoryChange(
                        transaction.getTransactionId(),
                        user.getUserId(),
                        oldCategoryPrimary,
                        trimmedPrimary,
                        "USER_OVERRIDE",
                        "User manually changed category");

                // Record correction for learning (async, best effort)
                try {
                    learningService.recordCorrection(
                            user.getUserId(),
                            transaction.getTransactionId(),
                            transaction.getMerchantName(),
                            oldCategoryPrimary,
                            transaction.getCategoryDetailed(), // Original detailed
                            trimmedPrimary,
                            trimmedDetailed,
                            transaction.getTransactionType(), // Original type
                            transaction.getTransactionType(), // Corrected type (same, unless user
                            // changes it)
                            transaction.getDescription());
                } catch (Exception e) {
                    LOGGER.debug("Failed to record correction (non-blocking): {}", e.getMessage());
                    // Don't fail transaction update if correction recording fails
                }
            }

            // CRITICAL: Always set categoryOverridden=true when category is changed
            // Additionally, ensure it's set for income, investment, and loan categories
            final boolean isIncomeInvestmentOrLoan =
                    isIncomeCategory(trimmedPrimary)
                            || isInvestmentCategory(trimmedPrimary)
                            || isLoanCategory(trimmedPrimary)
                            || isIncomeCategory(trimmedDetailed)
                            || isInvestmentCategory(trimmedDetailed)
                            || isLoanCategory(trimmedDetailed);

            transaction.setCategoryOverridden(true);

            if (isIncomeInvestmentOrLoan) {
                LOGGER.info(
                        "Category override applied for income/investment/loan: primary={}, detailed={}",
                        trimmedPrimary,
                        trimmedDetailed);
            } else {
                LOGGER.info(
                        "Category override applied: primary={}, detailed={}",
                        trimmedPrimary,
                        trimmedDetailed);
            }
        }

        // CRITICAL: Update transaction type if:
        // 1. User provided a transactionType (explicit override) - always respect user selection
        // 2. Category or amount changed and no user-provided type AND not already overridden -
        // recalculate automatically
        final Optional<com.budgetbuddy.model.TransactionType> userTypeOpt =
                parseUserTransactionType(transactionType);

        if (userTypeOpt.isPresent()) {
            // User provided transaction type - use it (override automatic calculation)
            final com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
            final String existingType = transaction.getTransactionType();
            transaction.setTransactionType(userType.name());
            // Only mark as overridden if new type differs from existing type
            if (existingType == null || !userType.name().equals(existingType)) {
                transaction.setTransactionTypeOverridden(true);
            }
            // Using user-provided transaction type
        } else if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())
                && (categoryChanged || amount != null)) {
            // User didn't provide type, transaction not overridden, and category/amount changed -
            // recalculate
            // Fetch account to determine transaction type
            final AccountTable account =
                    accountRepository.findById(transaction.getAccountId()).orElse(null);

            // Use unified service if account is available, otherwise fallback
            if (account != null) {
                try {
                    final TransactionTypeCategoryService.TypeResult typeResult =
                            transactionTypeCategoryService.determineTransactionType(
                                    account,
                                    transaction.getCategoryPrimary(),
                                    transaction.getCategoryDetailed(),
                                    transaction.getAmount(),
                                    null,
                                    transaction.getDescription(),
                                    transaction.getPaymentChannel());

                    if (typeResult != null) {
                        transaction.setTransactionType(typeResult.getTransactionType().name());
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                        // Recalculated transaction type using unified service
                    } else {
                        final com.budgetbuddy.model.TransactionType calculatedType =
                                resolveTransactionType(
                                        account,
                                        transaction.getCategoryPrimary(),
                                        transaction.getCategoryDetailed(),
                                        transaction.getAmount(),
                                        null,
                                        transaction.getDescription(),
                                        transaction.getPaymentChannel());
                        transaction.setTransactionType(calculatedType.name());
                        if (transaction.getTransactionTypeOverridden() == null) {
                            transaction.setTransactionTypeOverridden(false);
                        }
                        // Recalculated transaction type
                    }
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to recalculate transaction type using unified service, using fallback: {}",
                            e.getMessage());
                    final com.budgetbuddy.model.TransactionType calculatedType =
                            resolveTransactionType(
                                    account,
                                    transaction.getCategoryPrimary(),
                                    transaction.getCategoryDetailed(),
                                    transaction.getAmount(),
                                    null,
                                    transaction.getDescription(),
                                    transaction.getPaymentChannel());
                    transaction.setTransactionType(calculatedType.name());
                    if (transaction.getTransactionTypeOverridden() == null) {
                        transaction.setTransactionTypeOverridden(false);
                    }
                }
            } else {
                final com.budgetbuddy.model.TransactionType calculatedType =
                        resolveTransactionType(
                                null,
                                transaction.getCategoryPrimary(),
                                transaction.getCategoryDetailed(),
                                transaction.getAmount(),
                                null,
                                transaction.getDescription(),
                                transaction.getPaymentChannel());
                transaction.setTransactionType(calculatedType.name());
                if (transaction.getTransactionTypeOverridden() == null) {
                    transaction.setTransactionTypeOverridden(false);
                }
                // Recalculated transaction type (no account)
            }
        }

        // Update review status if provided
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            transaction.setReviewStatus(reviewStatus.trim());
            LOGGER.info("Review status updated: reviewStatus={}", reviewStatus.trim());
        }

        // Update hidden state if provided
        if (isHidden != null) {
            transaction.setIsHidden(isHidden);
            LOGGER.info("Hidden state updated: isHidden={}", isHidden);
        }

        // Handle goalId assignment/removal
        final String oldGoalId = transaction.getGoalId();
        if (goalId != null) {
            if (goalId.isBlank()) {
                transaction.setGoalId(null);
            } else {
                transaction.setGoalId(goalId.trim());
            }
        }

        // Handle linkedTransactionId assignment/removal
        if (linkedTransactionId != null) {
            if (linkedTransactionId.isBlank()) {
                transaction.setLinkedTransactionId(null);
            } else {
                transaction.setLinkedTransactionId(linkedTransactionId.trim());
            }
        }

        transaction.setUpdatedAt(Instant.now());

        // P2: Audit logging for updates
        final StringBuilder changes = new StringBuilder();
        if (categoryChanged) {
            changes.append("category:")
                    .append(oldCategoryPrimary)
                    .append("->")
                    .append(transaction.getCategoryPrimary())
                    .append(';');
        }
        if (amount != null) {
            changes.append("amount:").append(amount).append(';');
        }
        if (notes != null || clearNotesIfNull) {
            changes.append("notes:updated;");
        }
        if (transactionType != null) {
            changes.append("type:").append(transactionType).append(';');
        }
        if (goalId != null) {
            changes.append("goalId:").append(goalId).append(';');
        }
        auditService.logTransactionUpdate(
                transaction.getTransactionId(),
                user.getUserId(),
                changes.toString(),
                "USER_UPDATE");

        // P1: Invalidate cache when transaction is updated (category/type may change)
        if (categoryChanged || amount != null || transactionType != null) {
            invalidateCategoryCache(transaction);
        }

        // User-facing edit. Under lock so a concurrent Plaid re-sync of the
        // same transaction (common when the user edits a category right
        // after a sync was triggered) doesn't overwrite the user's
        // category/notes with Plaid's defaults. On conflict we re-read, re-
        // apply ONLY the fields the user edited, and try once more — the
        // Plaid-owned fields (amount, pending, description) survive.
        try {
            transactionRepository.saveWithLock(transaction);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            final TransactionTable fresh =
                    transactionRepository.findById(transaction.getTransactionId()).orElse(null);
            if (fresh == null) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "Transaction disappeared during update");
            }
            // Preserve user-owned fields — the edit came from them and is
            // authoritative regardless of what Plaid re-synced.
            fresh.setCategoryPrimary(transaction.getCategoryPrimary());
            fresh.setCategoryDetailed(transaction.getCategoryDetailed());
            fresh.setCategoryOverridden(transaction.getCategoryOverridden());
            fresh.setNotes(transaction.getNotes());
            fresh.setReviewStatus(transaction.getReviewStatus());
            fresh.setIsHidden(transaction.getIsHidden());
            fresh.setTransactionType(transaction.getTransactionType());
            fresh.setTransactionTypeOverridden(transaction.getTransactionTypeOverridden());
            fresh.setGoalId(transaction.getGoalId());
            fresh.setLinkedTransactionId(transaction.getLinkedTransactionId());
            // Amount: only override if user explicitly changed it in this
            // request (the `amount` param being non-null is the signal).
            if (amount != null) {
                fresh.setAmount(transaction.getAmount());
            }
            fresh.setUpdatedAt(transaction.getUpdatedAt());
            transactionRepository.saveWithLock(fresh);
            transaction = fresh;
        }

        // Trigger goal progress recalculation if goalId changed
        final String newGoalId = transaction.getGoalId();
        if ((oldGoalId == null && newGoalId != null)
                || (oldGoalId != null && !oldGoalId.equals(newGoalId))) {
            try {
                // ApplicationContext.getBean(Class) is @NonNull — throws if missing.
                final GoalProgressService goalProgressService =
                        applicationContext.getBean(GoalProgressService.class);
                if (newGoalId != null) {
                    goalProgressService.onTransactionGoalAssignmentChanged(
                            user.getUserId(), newGoalId);
                }
                if (oldGoalId != null && !oldGoalId.equals(newGoalId)) {
                    goalProgressService.onTransactionGoalAssignmentChanged(
                            user.getUserId(), oldGoalId);
                }
            } catch (Exception e) {
                LOGGER.debug(
                        "Goal progress service not available or error triggering recalculation: {}",
                        e.getMessage());
            }
        }

        LOGGER.info("Updated transaction {} for user {}", transactionId, user.getEmail());
        return transaction;
    }

    /**
     * Get a single transaction by ID Supports lookup by transactionId or plaidTransactionId
     * (fallback)
     */
    public TransactionTable getTransaction(final UserTable user, final String transactionId) {
        return getTransaction(user, transactionId, null);
    }

    /**
     * Get a single transaction by ID Supports lookup by transactionId or plaidTransactionId
     * (fallback)
     *
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId
     *     not found
     */
    public TransactionTable getTransaction(
            final UserTable user, final String transactionId, final String plaidTransactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Try to find by transactionId first
        Optional<TransactionTable> transactionOpt = transactionRepository.findById(transactionId);

        // If not found and plaidTransactionId is provided, try lookup by Plaid ID
        if (transactionOpt.isEmpty()
                && plaidTransactionId != null
                && !plaidTransactionId.isEmpty()) {
            // Transaction not found by ID, trying Plaid ID
            transactionOpt = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (transactionOpt.isPresent()) {
                LOGGER.info(
                        "Found transaction by Plaid ID {} (requested ID: {})",
                        plaidTransactionId,
                        transactionId);
            }
        }

        final TransactionTable transaction =
                transactionOpt.orElseThrow(
                        () ->
                                new AppException(
                                        ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found"));

        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        return transaction;
    }

    /*
     * Soft-delete a transaction (Flow 4 / O9). Sets {@code deletedAt} and bumps {@code updatedAt}
     * so incremental sync propagates the tombstone to every device. The row stays in storage for
     * the undo window; a scheduled purge job removes rows older than 30 days.
     *
     * <p>A companion {@link #restoreTransaction} reverses the soft delete within the undo window.
     */
    /**
     * Hard-delete a list of transactions by id. Used by cascade paths (e.g. account deletion) where
     * a soft-delete tombstone would just leak rows that no surviving account references. Skips
     * ownership checks — the caller is expected to have already verified the rows belong to the
     * right user.
     */
    public void batchDeleteTransactions(final List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return;
        }
        transactionRepository.batchDelete(transactionIds);
    }

    public void deleteTransaction(final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        final TransactionTable transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.TRANSACTION_NOT_FOUND,
                                                "Transaction not found"));

        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        if (transaction.getDeletedAt() != null) {
            // Idempotent: a duplicate DELETE on an already-soft-deleted row is a no-op.
            LOGGER.info(
                    "Transaction {} already soft-deleted; ignoring duplicate delete",
                    transactionId);
            return;
        }

        final Instant now = Instant.now();
        transaction.setDeletedAt(now);
        transaction.setUpdatedAt(now);
        transactionRepository.save(transaction);
        LOGGER.info("Soft-deleted transaction {} for user {}", transactionId, user.getEmail());
    }

    /**
     * Restore a soft-deleted transaction (Flow 4 / O9 — undo). Clears {@code deletedAt} and bumps
     * {@code updatedAt} so peers see the resurrection. No-op if the row is already live.
     */
    public TransactionTable restoreTransaction(final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        final TransactionTable transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.TRANSACTION_NOT_FOUND,
                                                "Transaction not found"));
        if (transaction.getUserId() == null || !transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }
        if (transaction.getDeletedAt() == null) {
            return transaction;
        }
        transaction.setDeletedAt(null);
        transaction.setUpdatedAt(Instant.now());
        transactionRepository.save(transaction);
        return transaction;
    }

    /** Check if a category is an income category */
    private boolean isIncomeCategory(final String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        final String lower = category.toLowerCase(Locale.ROOT);
        return "income".equals(lower)
                || "salary".equals(lower)
                || "interest".equals(lower)
                || "dividend".equals(lower)
                || "stipend".equals(lower)
                || "rentincome".equals(lower)
                || "rental_income".equals(lower)
                || "rental income".equals(lower)
                || "tips".equals(lower)
                || "otherincome".equals(lower)
                || "other_income".equals(lower)
                || "other income".equals(lower);
    }

    /** Check if a category is an investment category */
    private boolean isInvestmentCategory(final String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        final String lower = category.toLowerCase(Locale.ROOT);
        return "investment".equals(lower)
                || "cd".equals(lower)
                || "bonds".equals(lower)
                || "municipalbonds".equals(lower)
                || "municipal_bonds".equals(lower)
                || "tbills".equals(lower)
                || "t_bills".equals(lower)
                || "treasury bills".equals(lower)
                || "stocks".equals(lower)
                || "401k".equals(lower)
                || "fourzeroonek".equals(lower)
                || "529".equals(lower)
                || "fivetwonine".equals(lower)
                || "ira".equals(lower)
                || "mutualfunds".equals(lower)
                || "mutual_funds".equals(lower)
                || "etf".equals(lower)
                || "moneymarket".equals(lower)
                || "money_market".equals(lower)
                || "preciousmetals".equals(lower)
                || "precious_metals".equals(lower)
                || "crypto".equals(lower)
                || "otherinvestment".equals(lower)
                || "other_investment".equals(lower);
    }

    private String removeNamesFromText(final String text, final String... names) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = text;
        if (names != null) {
            for (final String name : names) {
                if (name == null) {
                    continue;
                }
                final String trimmed = name.trim();
                if (trimmed.isEmpty() || trimmed.length() < 2) {
                    continue;
                }
                final String[] parts = trimmed.split("\\s+");
                final String pattern;
                if (parts.length == 1) {
                    pattern = "(?i)\\b" + Pattern.quote(trimmed) + "\\b";
                } else {
                    final StringBuilder builder = new StringBuilder("(?i)\\b");
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) {
                            builder.append("\\s+");
                        }
                        builder.append(Pattern.quote(parts[i]));
                    }
                    builder.append("\\b");
                    pattern = builder.toString();
                }
                cleaned = cleaned.replaceAll(pattern, " ");
            }
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    /**
     * Check if a category is a loan category Note: Loan categories are typically identified by
     * account type, not transaction category But we check for loan-related categories that might be
     * used
     */
    private boolean isLoanCategory(final String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        final String lower = category.toLowerCase(Locale.ROOT);
        return lower.contains("loan")
                || "mortgage".equals(lower)
                || "autoloan".equals(lower)
                || "auto_loan".equals(lower)
                || "personalloan".equals(lower)
                || "personal_loan".equals(lower)
                || "studentloan".equals(lower)
                || "student_loan".equals(lower)
                || "creditline".equals(lower)
                || "credit_line".equals(lower);
    }

    /**
     * P1: Invalidates category determination cache when transaction is created/updated This ensures
     * cache freshness when categories or types change
     */
    @CacheEvict(value = "categoryDetermination", allEntries = true)
    private void invalidateCategoryCache(TransactionTable transaction) {
        // Cache eviction is handled by @CacheEvict annotation
        // This method exists to trigger cache invalidation
        // Invalidated category determination cache
    }

    /**
     * Resolve the AccountTable to attach to a new transaction.
     *
     * <ul>
     *   <li>Plaid transactions ({@code plaidAccountId} present) must find a real account by
     *       accountId, by PlaidAccountIdIndex, or by user-account fallback scan. They never fall
     *       back to the pseudo account — that would lose Plaid linkage.
     *   <li>Manual transactions without {@code accountId} get the user's pseudo account.
     *   <li>Manual transactions with {@code accountId} look it up directly.
     * </ul>
     */
    private AccountTable resolveAccountForCreate(
            final UserTable user, final String accountId, final String plaidAccountId) {
        if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
            return resolvePlaidAccount(user, accountId, plaidAccountId);
        }
        if (accountId == null || accountId.isEmpty()) {
            LOGGER.info(
                    "No account ID provided for manual transaction - using pseudo account for user {}",
                    user.getUserId());
            return accountRepository.getOrCreatePseudoAccount(user.getUserId());
        }
        return accountRepository
                .findById(accountId)
                .orElseThrow(
                        () -> new AppException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    }

    private AccountTable resolvePlaidAccount(
            final UserTable user, final String accountId, final String plaidAccountId) {
        Optional<AccountTable> accountOpt = Optional.empty();
        if (accountId != null && !accountId.isEmpty()) {
            accountOpt = accountRepository.findById(accountId);
        }
        if (accountOpt.isEmpty()) {
            accountOpt = lookupByPlaidIndex(user, plaidAccountId, accountId);
        }
        if (accountOpt.isEmpty()) {
            accountOpt = scanUserAccountsForPlaidId(user, plaidAccountId);
        }
        return accountOpt.orElseThrow(
                () ->
                        new AppException(
                                ErrorCode.ACCOUNT_NOT_FOUND,
                                "Plaid transaction requires a valid account. Account not found for Plaid account ID: "
                                        + plaidAccountId));
    }

    /** GSI lookup by plaidAccountId; rejects matches that belong to a different user. */
    private Optional<AccountTable> lookupByPlaidIndex(
            final UserTable user, final String plaidAccountId, final String accountId) {
        final Optional<AccountTable> accountOpt =
                accountRepository.findByPlaidAccountId(plaidAccountId);
        if (accountOpt.isEmpty()) {
            return Optional.empty();
        }
        final AccountTable foundAccount = accountOpt.get();
        if (foundAccount.getUserId() == null
                || !foundAccount.getUserId().equals(user.getUserId())) {
            LOGGER.warn(
                    "Account found by Plaid ID {} belongs to different user (found: {}, requested: {})",
                    plaidAccountId,
                    foundAccount.getUserId(),
                    user.getUserId());
            return Optional.empty();
        }
        LOGGER.info(
                "Found account by Plaid ID {} (requested accountId: {})",
                plaidAccountId,
                accountId);
        return accountOpt;
    }

    /**
     * Re-apply unified category determination for imports with a real account. For non-imports,
     * pseudo accounts, or when the user edited the category in preview, the provided category is
     * used verbatim. Mutates {@code transaction}.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private void applyImportCategorization(
            final TransactionTable transaction,
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final String importerCategoryPrimary,
            final String importerCategoryDetailed,
            final String cleanedDescription,
            final String cleanedMerchantName,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String importSource) {
        final boolean isUserEditedCategory =
                categoryPrimary != null
                        && !categoryPrimary.isEmpty()
                        && importerCategoryPrimary != null
                        && !importerCategoryPrimary.isEmpty()
                        && !categoryPrimary.equalsIgnoreCase(importerCategoryPrimary);

        if (isImportWithRealAccount(importSource, account) && !isUserEditedCategory) {
            // Real-account import: re-run unified service for better accuracy. Failure of the
            // unified service falls back to the importer-provided categories.
            try {
                final TransactionTypeCategoryService.TypeResult preTypeResult =
                        transactionTypeCategoryService.determineTransactionType(
                                account,
                                null,
                                null,
                                amount,
                                null,
                                cleanedDescription,
                                paymentChannel);
                final TransactionTypeCategoryService.CategoryResult categoryResult =
                        transactionTypeCategoryService.determineCategory(
                                importerCategoryPrimary != null
                                        ? importerCategoryPrimary
                                        : categoryPrimary,
                                importerCategoryDetailed != null
                                        ? importerCategoryDetailed
                                        : categoryDetailed,
                                account,
                                cleanedMerchantName != null ? cleanedMerchantName : merchantName,
                                cleanedDescription,
                                amount,
                                paymentChannel,
                                null,
                                importSource,
                                preTypeResult != null ? preTypeResult.getTransactionType() : null);
                if (categoryResult != null) {
                    applyDeterminedCategory(
                            transaction, categoryResult, importerCategoryPrimary, categoryPrimary);
                } else {
                    applyFallbackCategories(transaction, categoryPrimary, categoryDetailed, false);
                }
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed to re-apply unified service for import, using provided categories: {}",
                        e.getMessage());
                applyFallbackCategories(transaction, categoryPrimary, categoryDetailed, false);
            }
            return;
        }

        if (isUserEditedCategory) {
            applyFallbackCategories(transaction, categoryPrimary, categoryDetailed, true);
            LOGGER.info(
                    "✅ Preserving user-edited category from preview: categoryPrimary='{}' (importerCategoryPrimary='{}')",
                    categoryPrimary,
                    importerCategoryPrimary);
            return;
        }
        applyFallbackCategories(transaction, categoryPrimary, categoryDetailed, false);
    }

    private static boolean isImportWithRealAccount(
            final String importSource, final AccountTable account) {
        return importSource != null
                && !importSource.isBlank()
                && account != null
                && account.getAccountId() != null
                && !account.getAccountId().startsWith("pseudo-");
    }

    /**
     * Write the determined category into the transaction. If the determined category differs from
     * the importer's parsed category (or the source is internal), mark it as overridden so that
     * re-import won't clobber the override.
     */
    private static void applyDeterminedCategory(
            final TransactionTable transaction,
            final TransactionTypeCategoryService.CategoryResult categoryResult,
            final String importerCategoryPrimary,
            final String categoryPrimary) {
        final String determinedPrimary = categoryResult.getCategoryPrimary();
        final String determinedDetailed = categoryResult.getCategoryDetailed();
        final String source = categoryResult.getSource();

        final boolean isInternalOverride =
                (source != null && !"IMPORTER".equals(source) && !"IMPORT".equals(source))
                        || (importerCategoryPrimary != null
                                && !importerCategoryPrimary.isEmpty()
                                && !importerCategoryPrimary.equalsIgnoreCase(determinedPrimary));

        transaction.setCategoryPrimary(determinedPrimary);
        transaction.setCategoryDetailed(determinedDetailed);
        transaction.setCategoryOverridden(isInternalOverride);

        if (isInternalOverride) {
            LOGGER.info(
                    "✅ Internal category override applied for import: Importer='{}' → Determined='{}' (source: {}, confidence: {}). "
                            + "This override will be preserved during re-import.",
                    importerCategoryPrimary != null ? importerCategoryPrimary : categoryPrimary,
                    determinedPrimary,
                    source,
                    categoryResult.getConfidence());
        }
    }

    private static void applyFallbackCategories(
            final TransactionTable transaction,
            final String categoryPrimary,
            final String categoryDetailed,
            final boolean overridden) {
        transaction.setCategoryPrimary(categoryPrimary);
        transaction.setCategoryDetailed(
                categoryDetailed != null && !categoryDetailed.isEmpty()
                        ? categoryDetailed
                        : categoryPrimary);
        transaction.setCategoryOverridden(overridden);
    }

    /**
     * Look for an existing same-date transaction that matches the new one closely enough to be
     * treated as a duplicate. Returns the existing transaction (idempotency hit) or null.
     *
     * <ul>
     *   <li>Imports: match on account + amount + date + import-source + file-name + description.
     *   <li>Manual w/o explicit ID: match on account + amount + date only.
     *   <li>Anything with an explicit transactionId or plaidTransactionId: skip — that path is
     *       already idempotent via the ID lookup above.
     * </ul>
     */
    private TransactionTable findDuplicateForCreate(
            final UserTable user,
            final AccountTable account,
            final TransactionTable transaction,
            final BigDecimal amount,
            final String description,
            final String transactionId,
            final String plaidTransactionId,
            final String importSource,
            final String importFileName) {
        final String transactionDateStr = transaction.getTransactionDate();
        if (transactionDateStr == null || transactionDateStr.isEmpty()) {
            return null;
        }
        final List<TransactionTable> sameDate =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), transactionDateStr, transactionDateStr);
        if (importSource != null
                && !importSource.isBlank()
                && importFileName != null
                && !importFileName.isBlank()) {
            return findImportedDuplicate(
                    sameDate,
                    account,
                    amount,
                    transactionDateStr,
                    description,
                    importSource,
                    importFileName);
        }
        if ((plaidTransactionId == null || plaidTransactionId.isBlank())
                && (transactionId == null || transactionId.isBlank())) {
            return findManualDuplicate(sameDate, account, amount, transactionDateStr);
        }
        return null;
    }

    private static TransactionTable findImportedDuplicate(
            final List<TransactionTable> candidates,
            final AccountTable account,
            final BigDecimal amount,
            final String transactionDateStr,
            final String description,
            final String importSource,
            final String importFileName) {
        for (final TransactionTable existing : candidates) {
            if (matchesAccountAmountDate(existing, account, amount, transactionDateStr)
                    && safeEqualsIgnoreCase(importSource.trim(), existing.getImportSource())
                    && safeEqualsIgnoreCase(importFileName.trim(), existing.getImportFileName())
                    && safeEqualsIgnoreCase(
                            description != null ? description.trim() : "",
                            existing.getDescription())) {
                LOGGER.info(
                        "Duplicate imported transaction detected (source: {}, file: {}, account: {}, amount: {}, date: {}). "
                                + "Returning existing transaction {} instead of creating new one.",
                        importSource,
                        importFileName,
                        account != null ? account.getAccountId() : NULL,
                        amount,
                        transactionDateStr,
                        existing.getTransactionId());
                return existing;
            }
        }
        return null;
    }

    private static TransactionTable findManualDuplicate(
            final List<TransactionTable> candidates,
            final AccountTable account,
            final BigDecimal amount,
            final String transactionDateStr) {
        for (final TransactionTable existing : candidates) {
            if (matchesAccountAmountDate(existing, account, amount, transactionDateStr)) {
                LOGGER.info(
                        "Duplicate transaction detected (account: {}, amount: {}, date: {}). "
                                + "Returning existing transaction {} instead of creating new one.",
                        account != null ? account.getAccountId() : NULL,
                        amount,
                        transactionDateStr,
                        existing.getTransactionId());
                return existing;
            }
        }
        return null;
    }

    private static boolean matchesAccountAmountDate(
            final TransactionTable existing,
            final AccountTable account,
            final BigDecimal amount,
            final String transactionDateStr) {
        return account != null
                && account.getAccountId().equals(existing.getAccountId())
                && amount.compareTo(existing.getAmount()) == 0
                && transactionDateStr.equals(existing.getTransactionDate());
    }

    private static boolean safeEqualsIgnoreCase(final String a, final String b) {
        return a.equalsIgnoreCase(b != null ? b : "");
    }

    /**
     * Either set {@code transaction.transactionId} (deterministic or random) or return an existing
     * transaction when idempotency rules dictate. Three paths:
     *
     * <ol>
     *   <li>Caller provided a valid UUID → check repository; if a hit exists and belongs to the
     *       same user, possibly update Plaid linkage / transactionType, then return existing.
     *   <li>No (or invalid) UUID + Plaid txn ID → derive deterministic UUID from Plaid ID.
     *   <li>Otherwise + import metadata → derive deterministic UUID from (source, file, account,
     *       amount, date, description); if a transaction with that ID already exists for this user,
     *       return it.
     *   <li>Otherwise → random UUID.
     * </ol>
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private TransactionTable resolveTransactionIdOrFindExisting(
            final TransactionTable transaction,
            final UserTable user,
            final AccountTable account,
            final String transactionId,
            final String plaidTransactionId,
            final String transactionType,
            final String description,
            final String userName,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String importSource,
            final String importFileName) {
        if (transactionId != null && !transactionId.isBlank()) {
            final TransactionTable hit =
                    handleProvidedTransactionId(
                            transaction,
                            user,
                            transactionId,
                            plaidTransactionId,
                            transactionType,
                            importSource);
            if (hit != null) {
                return hit;
            }
        }
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            return generateAndAssignTransactionId(
                    transaction,
                    user,
                    account,
                    plaidTransactionId,
                    description,
                    userName,
                    amount,
                    transactionDate,
                    importSource,
                    importFileName);
        }
        return null;
    }

    /**
     * Caller provided a transactionId. Validate UUID, normalize, look up in repo. If existing,
     * apply the Plaid-vs-existing logic to decide whether to return it, update it, or fall through
     * to generate a fresh UUID.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private TransactionTable handleProvidedTransactionId(
            final TransactionTable transaction,
            final UserTable user,
            final String transactionId,
            final String plaidTransactionId,
            final String transactionType,
            final String importSource) {
        try {
            UUID.fromString(transactionId);
        } catch (IllegalArgumentException e) {
            LOGGER.warn(
                    "Invalid transaction ID format '{}', will use deterministic UUID from Plaid ID if available",
                    transactionId);
            return null;
        }
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(transactionId);
        final Optional<TransactionTable> existingOpt = transactionRepository.findById(normalizedId);
        if (existingOpt.isEmpty()) {
            transaction.setTransactionId(normalizedId);
            LOGGER.info(
                    "Using provided transaction ID (normalized): {} -> {}",
                    transactionId,
                    normalizedId);
            return null;
        }
        final TransactionTable existing = existingOpt.get();
        if (!existing.getUserId().equals(user.getUserId())) {
            LOGGER.warn(
                    "Transaction with ID {} already exists but belongs to different user. Generating new UUID for security.",
                    normalizedId);
            return null;
        }
        return reconcileExistingWithRequest(
                existing, normalizedId, plaidTransactionId, transactionType, importSource);
    }

    private TransactionTable reconcileExistingWithRequest(
            final TransactionTable existing,
            final String normalizedId,
            final String plaidTransactionId,
            final String transactionType,
            final String importSource) {
        final String existingPlaidId = existing.getPlaidTransactionId();
        final boolean existingHasPlaidId = existingPlaidId != null && !existingPlaidId.isEmpty();
        final boolean requestHasPlaidId =
                plaidTransactionId != null && !plaidTransactionId.isEmpty();
        if (requestHasPlaidId) {
            if (existingHasPlaidId && plaidTransactionId.equals(existingPlaidId)) {
                applyTransactionTypeOverrideIfChanged(existing, transactionType, normalizedId);
                LOGGER.info(
                        "Transaction with ID {} already exists and matches Plaid ID {}, returning existing",
                        normalizedId,
                        plaidTransactionId);
                return existing;
            }
            if (existingHasPlaidId) {
                LOGGER.warn(
                        "Transaction with ID {} already exists but Plaid ID doesn't match (existing: {}, provided: {}). "
                                + "Generating new UUID to prevent data conflict.",
                        normalizedId,
                        existingPlaidId,
                        plaidTransactionId);
                return null;
            }
            LOGGER.info(
                    "Transaction with ID {} exists without Plaid ID, but request provides Plaid ID {}. "
                            + "Updating existing transaction with Plaid ID and transactionType.",
                    normalizedId,
                    plaidTransactionId);
            existing.setPlaidTransactionId(plaidTransactionId);
            applyTransactionTypeOverrideIfChanged(existing, transactionType, normalizedId);
            transactionRepository.save(existing);
            return existing;
        }
        applyTransactionTypeOverrideForImportAware(
                existing, transactionType, importSource, normalizedId);
        LOGGER.info(
                "Transaction with ID {} already exists (no Plaid ID in request). Returning existing for idempotency.",
                normalizedId);
        return existing;
    }

    /** Apply a user-provided transactionType to {@code existing}, marking overridden if changed. */
    private void applyTransactionTypeOverrideIfChanged(
            final TransactionTable existing,
            final String transactionType,
            final String normalizedId) {
        final Optional<com.budgetbuddy.model.TransactionType> userTypeOpt =
                parseUserTransactionType(transactionType);
        if (userTypeOpt.isEmpty()) {
            return;
        }
        final com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
        final String existingType = existing.getTransactionType();
        if (userType.name().equals(existingType)) {
            return;
        }
        existing.setTransactionType(userType.name());
        existing.setTransactionTypeOverridden(true);
        transactionRepository.save(existing);
        LOGGER.info(
                "Updated transactionType to {} for existing transaction {} (user override)",
                userType,
                normalizedId);
    }

    /**
     * Variant of above that's aware of import sources — preserves override state across re-imports.
     */
    private void applyTransactionTypeOverrideForImportAware(
            final TransactionTable existing,
            final String transactionType,
            final String importSource,
            final String normalizedId) {
        final Optional<com.budgetbuddy.model.TransactionType> userTypeOpt =
                parseUserTransactionType(transactionType);
        if (userTypeOpt.isEmpty()) {
            return;
        }
        final com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
        final String existingType = existing.getTransactionType();
        existing.setTransactionType(userType.name());
        final boolean isImport =
                importSource != null
                        && !importSource.isBlank()
                        && ("CSV".equalsIgnoreCase(importSource)
                                || "PDF".equalsIgnoreCase(importSource)
                                || "EXCEL".equalsIgnoreCase(importSource));
        if (isImport) {
            final boolean wasOverridden =
                    Boolean.TRUE.equals(existing.getTransactionTypeOverridden());
            if (wasOverridden && existingType != null && !userType.name().equals(existingType)) {
                existing.setTransactionTypeOverridden(true);
            } else if (existing.getTransactionTypeOverridden() == null) {
                existing.setTransactionTypeOverridden(false);
            }
        } else if (existingType == null || !userType.name().equals(existingType)) {
            existing.setTransactionTypeOverridden(true);
        }
        transactionRepository.save(existing);
        LOGGER.info(
                "Updated transactionType to {} for existing transaction {} (user override)",
                userType,
                normalizedId);
    }

    /**
     * Generate and set the transaction ID when no valid one was provided. May return an existing
     * transaction if the deterministic-import-key path detects a duplicate.
     */
    private TransactionTable generateAndAssignTransactionId(
            final TransactionTable transaction,
            final UserTable user,
            final AccountTable account,
            final String plaidTransactionId,
            final String description,
            final String userName,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String importSource,
            final String importFileName) {
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            final UUID namespaceUUID =
                    UUID.fromString(
                            "6ba7b811-9dad-11d1-80b4-00c04fd430c8"); // TRANSACTION_NAMESPACE
            final String generatedId =
                    com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(
                            namespaceUUID, plaidTransactionId);
            final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
            transaction.setTransactionId(normalizedId);
            LOGGER.info(
                    "Generated deterministic transaction ID (normalized): {} from Plaid ID: {} (matches iOS app fallback)",
                    normalizedId,
                    plaidTransactionId);
            return null;
        }
        if (importSource != null
                && !importSource.isBlank()
                && importFileName != null
                && !importFileName.isBlank()
                && account != null) {
            return generateImportDeterministicIdOrFindExisting(
                    transaction,
                    user,
                    account,
                    description,
                    userName,
                    amount,
                    transactionDate,
                    importSource,
                    importFileName);
        }
        transaction.setTransactionId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        return null;
    }

    private TransactionTable generateImportDeterministicIdOrFindExisting(
            final TransactionTable transaction,
            final UserTable user,
            final AccountTable account,
            final String description,
            final String userName,
            final BigDecimal amount,
            final LocalDate transactionDate,
            final String importSource,
            final String importFileName) {
        final String normalizedFileName =
                importFileName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        final String transactionDateStr = transactionDate.format(DATE_FORMATTER);
        String cleanedDescriptionForKey = removeNamesFromText(description, userName);
        if (cleanedDescriptionForKey == null || cleanedDescriptionForKey.isEmpty()) {
            cleanedDescriptionForKey = description != null ? description : "";
        }
        final String transactionKey =
                String.format(
                        "%s|%s|%s|%s|%s|%s",
                        importSource.trim().toUpperCase(Locale.ROOT),
                        normalizedFileName,
                        account.getAccountId(),
                        amount.toString(),
                        transactionDateStr,
                        cleanedDescriptionForKey.trim().toLowerCase(Locale.ROOT));
        final UUID importNamespaceUUID =
                UUID.fromString("7ba7b811-9dad-11d1-80b4-00c04fd430c8"); // IMPORT_NAMESPACE
        final String generatedId =
                com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(
                        importNamespaceUUID, transactionKey);
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
        final Optional<TransactionTable> existingByIdOpt =
                transactionRepository.findById(normalizedId);
        if (existingByIdOpt.isPresent()
                && existingByIdOpt.get().getUserId().equals(user.getUserId())) {
            LOGGER.info(
                    "Duplicate imported transaction detected by deterministic ID (source: {}, file: {}). "
                            + "Returning existing transaction {} instead of creating new one.",
                    importSource,
                    importFileName,
                    normalizedId);
            return existingByIdOpt.get();
        }
        transaction.setTransactionId(normalizedId);
        LOGGER.info(
                "Generated deterministic transaction ID for import (source: {}, file: {}): {}",
                importSource,
                importFileName,
                normalizedId);
        return null;
    }

    /** Fallback for when the PlaidAccountIdIndex GSI isn't consistent yet. */
    private Optional<AccountTable> scanUserAccountsForPlaidId(
            final UserTable user, final String plaidAccountId) {
        final Optional<AccountTable> result =
                accountRepository.findByUserId(user.getUserId()).stream()
                        .filter(acc -> plaidAccountId.equals(acc.getPlaidAccountId()))
                        .findFirst();
        if (result.isPresent()) {
            LOGGER.info(
                    "Found account by Plaid ID {} using fallback method (searching user's accounts)",
                    plaidAccountId);
        }
        return result;
    }
}
