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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary) {
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, null, null, null, null, null);
    }

    /**
     * Create manual transaction (backward compatibility - with categoryDetailed)
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed) {
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, null, null, null, null);
    }

    /**
     * Create manual transaction
     * @param transactionId Optional transaction ID from app. If provided and valid, use it to ensure app-backend ID consistency.
     *                      If not provided or invalid, generate a new UUID.
     * @param notes Optional user notes for the transaction
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes) {
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, transactionId, notes, null, null);
    }
    
    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback lookup
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes, final String plaidAccountId) {
        return createTransaction(user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, transactionId, notes, plaidAccountId, null);
    }
    
    /**
     * Create transaction with optional Plaid account ID and Plaid transaction ID for fallback lookup
     * @param categoryPrimary Primary category (required)
     * @param categoryDetailed Detailed category (optional, defaults to categoryPrimary if not provided)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup and ID consistency
     */
    public TransactionTable createTransaction(final UserTable user, final String accountId, final BigDecimal amount, final LocalDate transactionDate, final String description, final String categoryPrimary, final String categoryDetailed, final String transactionId, final String notes, final String plaidAccountId, final String plaidTransactionId) {
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
                logger.debug("Account {} not found by ID, trying Plaid account ID: {}", accountId, plaidAccountId);
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
                    logger.debug("Account not found by Plaid ID index, trying fallback: searching user's accounts");
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
                                // Same transaction (matched by Plaid ID) - return existing (idempotent)
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
                                // Request has Plaid ID but existing doesn't - update existing with Plaid ID
                                // This handles the case where a manual transaction is later linked to Plaid
                                logger.info("Transaction with ID {} exists without Plaid ID, but request provides Plaid ID {}. " +
                                        "Will update existing transaction with Plaid ID.", 
                                        normalizedId, providedPlaidId);
                                // Continue to update the existing transaction with Plaid ID
                                // Note: The transaction will be updated later in the method
                                return existing;
                            }
                        } else {
                            // No Plaid ID provided in request - return existing for idempotency
                            // This handles both manual transactions and Plaid transactions without ID in request
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
        
        // If transactionId is not set yet, try to generate deterministic UUID from Plaid ID
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
            } else {
                // No Plaid ID available, generate random UUID
                // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
                String generatedId = UUID.randomUUID().toString().toLowerCase();
                transaction.setTransactionId(generatedId);
                logger.debug("No Plaid transaction ID available, generated random UUID (normalized): {}", generatedId);
            }
        }
        
        transaction.setUserId(user.getUserId());
        // CRITICAL FIX: Use the account's ID (which may be the pseudo account ID if accountId was null/empty)
        transaction.setAccountId(account.getAccountId());
        transaction.setAmount(amount);
        transaction.setTransactionDate(transactionDate.format(DATE_FORMATTER));
        transaction.setDescription(description != null ? description : "");
        transaction.setCategoryPrimary(categoryPrimary);
        transaction.setCategoryDetailed(categoryDetailed != null && !categoryDetailed.isEmpty() ? categoryDetailed : categoryPrimary);
        transaction.setCategoryOverridden(false); // User-created transactions are not overrides
        transaction.setCurrencyCode(user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()
                ? user.getPreferredCurrency() : "USD");
        
        // Set Plaid transaction ID if provided (for fallback lookup and deduplication)
        if (plaidTransactionId != null && !plaidTransactionId.trim().isEmpty()) {
            transaction.setPlaidTransactionId(plaidTransactionId.trim());
        }
        
        // Set notes if provided
        if (notes != null) {
            transaction.setNotes(notes.trim().isEmpty() ? null : notes.trim());
        }

        // CRITICAL: Set timestamps for data freshness and incremental sync
        Instant now = Instant.now();
        transaction.setCreatedAt(now);
        transaction.setUpdatedAt(now);

        transactionRepository.save(transaction);
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
        return updateTransaction(user, transactionId, null, null, notes, null, null, null, null, true);
    }
    
    /**
     * Update transaction (e.g., amount, notes, category override, audit state, hidden state)
     * Supports lookup by transactionId or plaidTransactionId (fallback)
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId not found
     * @param amount Optional: transaction amount (for type changes)
     * @param categoryPrimary Optional: override primary category
     * @param categoryDetailed Optional: override detailed category
     * @param isAudited Optional: audit checkmark state
     * @param isHidden Optional: whether transaction is hidden from view
     * @param clearNotesIfNull If true, null notes means clear notes. If false, null notes means preserve existing.
     */
    public TransactionTable updateTransaction(final UserTable user, final String transactionId, final String plaidTransactionId, final BigDecimal amount, final String notes, final String categoryPrimary, final String categoryDetailed, final Boolean isAudited, final Boolean isHidden, final boolean clearNotesIfNull) {
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
            logger.debug("Notes field not provided (null) - preserving existing notes: {}", 
                    transaction.getNotes() != null ? transaction.getNotes() : "none");
        }
        
        // Update category override if provided
        if (categoryPrimary != null && !categoryPrimary.trim().isEmpty()) {
            String trimmedPrimary = categoryPrimary.trim();
            String trimmedDetailed = categoryDetailed != null && !categoryDetailed.trim().isEmpty() 
                    ? categoryDetailed.trim() 
                    : trimmedPrimary;
            
            transaction.setCategoryPrimary(trimmedPrimary);
            transaction.setCategoryDetailed(trimmedDetailed);
            
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
        
        // Update audit state if provided
        if (isAudited != null) {
            transaction.setIsAudited(isAudited);
            logger.info("Audit state updated: isAudited={}", isAudited);
        }
        
        // Update hidden state if provided
        if (isHidden != null) {
            transaction.setIsHidden(isHidden);
            logger.info("Hidden state updated: isHidden={}", isHidden);
        }
        
        transaction.setUpdatedAt(java.time.Instant.now());

        transactionRepository.save(transaction);
        logger.info("Updated transaction {} notes for user {}", transactionId, user.getEmail());
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
}
