package com.budgetbuddy.service;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Unified service for determining transaction types and categories across all sources (Plaid,
 * CSV/Excel/PDF Import, Manual)
 *
 * <p>This service implements hybrid logic combining: - Account type information - Importer-provided
 * categories (Plaid categories, parsed categories from files) - Transaction parsing logic (merchant
 * name, description, amount, payment channel) - ML-based category detection - Rule-based reasoning
 *
 * <p>Architecture: 1. For Plaid: Write to importerCategoryPrimary/importerCategoryDetailed, then
 * apply hybrid logic to internal fields 2. For Imports: Parse category, write to
 * importerCategoryPrimary/importerCategoryDetailed, then apply hybrid logic 3. Always use internal
 * categoryPrimary/categoryDetailed for display 4. User overrides always win (stored in internal
 * fields with override flags)
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
// PMD.UnusedFormalParameter: the determineTypePriority* helper family takes a consistent 8-param
// signature so the orchestrator can chain them uniformly. Each helper uses only the subset of
// inputs it needs.
@SuppressWarnings({
    "PMD.AvoidCatchingGenericException",
    "PMD.OnlyOneReturn",
    "PMD.UnusedFormalParameter"
})
@Service
public class TransactionTypeCategoryService {

    private static final String OTHER = "other";
    private static final String NULL = "null";
    private static final String INTEREST = "interest";
    private static final String UTILITIES = "utilities";
    private static final String MORTGAGE = "mortgage";
    private static final String LOAN = "loan";
    private static final String SALARY = "salary";
    private static final String N_401K = "401k";
    private static final String ACCOUNT_TYPE = "ACCOUNT_TYPE";
    private static final String CATEGORY = "CATEGORY";
    private static final String CATEGORY_OVERRIDE = "CATEGORY_OVERRIDE";
    private static final String HYBRID = "HYBRID";
    private static final String TYPE_ALIGN = "TYPE_ALIGN";
    private static final String CHECKING = "checking";
    private static final String CREDIT = "credit";
    private static final String CREDIT_CARD = "credit card";
    private static final String CREDITCARD = "creditcard";
    private static final String DEPOSIT = "deposit";
    private static final String DEPOSITORY = "depository";
    private static final String DIVIDEND = "dividend";
    private static final String INCOME = "income";
    private static final String INVESTMENT = "investment";
    private static final String IRA = "ira";
    private static final String PAYMENT = "payment";
    private static final String SAVINGS = "savings";
    private static final String TAX = "tax";
    private static final String TRANSFER = "transfer";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionTypeCategoryService.class);

    private static final List<String> INCOME_CATEGORIES =
            List.of(
                    SALARY, DEPOSIT, DIVIDEND, INTEREST, CREDIT, INCOME, "stipend", "rent", "tips",
                    OTHER);
    private static final List<String> PAYMENT_CATEGORIES = List.of(PAYMENT);
    private static final List<String> INVESTMENT_CATEGORIES = List.of(INVESTMENT, TRANSFER);
    private static final List<String> EXPENSE_CATEGORIES =
            List.of(
                    "dining",
                    "groceries",
                    "travel",
                    "transportation",
                    "education",
                    "entertainment",
                    "shopping",
                    TRANSFER,
                    PAYMENT,
                    "fees",
                    TAX,
                    "tech",
                    "insurance",
                    "pet",
                    "cash",
                    UTILITIES,
                    "health",
                    "healthcare",
                    "charity",
                    OTHER);

    private final PlaidCategoryMapper plaidCategoryMapper;
    private final ImportCategoryParser importCategoryParser;
    private final EnhancedCategoryDetectionService enhancedCategoryDetection;
    private final com.budgetbuddy.service.ml.MerchantCategoryDataService
            merchantCategoryDataService;
    private final ImportCategoryConfig importCategoryConfig; // P2: Configuration for keywords
    private final GlobalFinancialConfig
            globalFinancialConfig; // Global scale: Region-specific config
    private final CircuitBreakerService circuitBreakerService; // P2: Circuit breaker for ML service
    private final CategoryLearningService learningService; // User corrections and custom mappings

    // Note: fuzzyMatchingService is used via enhancedCategoryDetection, not directly

    public TransactionTypeCategoryService(
            final PlaidCategoryMapper plaidCategoryMapper,
            final ImportCategoryParser importCategoryParser,
            final EnhancedCategoryDetectionService enhancedCategoryDetection,
            final ImportCategoryConfig importCategoryConfig,
            final GlobalFinancialConfig globalFinancialConfig,
            final CircuitBreakerService circuitBreakerService,
            final com.budgetbuddy.service.ml.MerchantCategoryDataService
                    merchantCategoryDataService,
            final CategoryLearningService learningService) {
        this.plaidCategoryMapper = plaidCategoryMapper;
        this.importCategoryParser = importCategoryParser;
        this.enhancedCategoryDetection = enhancedCategoryDetection;
        this.importCategoryConfig = importCategoryConfig;
        this.globalFinancialConfig = globalFinancialConfig;
        this.circuitBreakerService = circuitBreakerService;
        this.merchantCategoryDataService = merchantCategoryDataService;
        this.learningService = learningService;
    }

    /** Result of category determination */
    public static class CategoryResult {
        private final String categoryPrimary;
        private final String categoryDetailed;
        private final String source; // "PLAID", "IMPORT", HYBRID, "ML", "RULE"
        private final double confidence; // 0.0 to 1.0

        public CategoryResult(
                final String categoryPrimary,
                final String categoryDetailed,
                final String source,
                final double confidence) {
            this.categoryPrimary = categoryPrimary;
            this.categoryDetailed = categoryDetailed;
            this.source = source;
            this.confidence = confidence;
        }

        public String getCategoryPrimary() {
            return categoryPrimary;
        }

        public String getCategoryDetailed() {
            return categoryDetailed;
        }

        public String getSource() {
            return source;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /** Result of transaction type determination */
    public static class TypeResult {
        private final TransactionType transactionType;
        private final String source; // "ACCOUNT", CATEGORY, "AMOUNT", HYBRID
        private final double confidence; // 0.0 to 1.0

        public TypeResult(
                final TransactionType transactionType,
                final String source,
                final double confidence) {
            this.transactionType = transactionType;
            this.source = source;
            this.confidence = confidence;
        }

        public TransactionType getTransactionType() {
            return transactionType;
        }

        public String getSource() {
            return source;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /**
     * Determines transaction type from account type string (for PDF/CSV imports) Used when account
     * is not yet available but account type is known
     *
     * @param accountType Account type string (e.g., CREDIT, DEPOSITORY, LOAN, INVESTMENT)
     * @param accountSubtype Account subtype string (e.g., CHECKING, CREDIT_CARD)
     * @param amount Transaction amount
     * @param description Transaction description (optional, for logging)
     * @param paymentChannel Payment channel (optional, for logging)
     * @return TypeResult with transaction type, or null if cannot determine
     */
    public TypeResult determineTransactionTypeFromAccountType(
            final String accountType,
            final String accountSubtype,
            final BigDecimal amount,
            final String description,
            final String paymentChannel) {

        // Edge case: Null account type or amount
        if (accountType == null || amount == null) {
            return null;
        }

        // Edge case: Zero amount - cannot determine type from account type alone
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            LOGGER.debug(
                    "Transaction type determination: Zero amount transaction, cannot determine from account type alone");
            return null;
        }

        // Normalize account type string (trim, lowercase for matching)
        final String normalizedAccountType = accountType.trim().toLowerCase(Locale.ROOT);
        final String normalizedAccountSubtype =
                accountSubtype != null ? accountSubtype.trim().toLowerCase(Locale.ROOT) : null;
        final AccountTypeInfo accountInfo =
                getAccountTypeInfoFromString(normalizedAccountType, normalizedAccountSubtype);
        final String descLower =
                description != null ? description.trim().toLowerCase(Locale.ROOT) : "";

        // Credit card accounts:
        // +amount = EXPENSE (charge/purchase)
        // -amount = PAYMENT (payment to credit card - paying off debt)
        if (accountInfo.isCreditCard) {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                if (isPaymentReceived(descLower)) {
                    LOGGER.debug(
                            "Transaction type determined from account type string: Loan account payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
                } else {
                    LOGGER.debug(
                            "Transaction type determined from account type string: Credit card charge → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                }
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                LOGGER.debug(
                        "Transaction type determined from account type string: Credit card payment (negative) → PAYMENT");
                return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
            }
        }

        // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.):
        // +amount = INCOME (dividends, interest, distributions)
        // -amount = EXPENSE (fees) or INVESTMENT (purchases)
        // Use category/description to distinguish fees from purchases
        if (accountInfo.isInvestment) {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                LOGGER.debug(
                        "Transaction type determined from account type string: Investment account positive amount → INCOME");
                return new TypeResult(TransactionType.INCOME, ACCOUNT_TYPE, 0.95);
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                // Check if it's a fee or purchase (simplified - full logic in
                // determineTransactionType)

                final boolean isFee =
                        descLower.contains("fee")
                                || descLower.contains("charge")
                                || descLower.contains("commission")
                                || descLower.contains(TAX)
                                || descLower.contains("expense")
                                || descLower.contains("custodial")
                                || descLower.contains("maintenance")
                                || descLower.contains("service charge")
                                || descLower.contains(OTHER)
                                || descLower.contains("cost")
                                || descLower.contains("sales load");

                if (isFee) {
                    LOGGER.debug(
                            "Transaction type determined from account type string: Investment account fee → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                } else {
                    // Default to INVESTMENT for negative amounts (likely a purchase)
                    LOGGER.debug(
                            "Transaction type determined from account type string: Investment account purchase → INVESTMENT");
                    return new TypeResult(TransactionType.INVESTMENT, ACCOUNT_TYPE, 0.95);
                }
            }
        }

        // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
        // +amount = PAYMENT (if payment keywords) or CREDIT (disbursement)
        // -amount = EXPENSE (fees)
        if (accountInfo.isLoan) {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                // Check if it's a payment using existing helper

                // Create a temporary account object for isLoanPayment check
                final AccountTable tempAccount = new AccountTable();
                tempAccount.setAccountType(accountType);
                tempAccount.setAccountSubtype(accountSubtype);

                if (isPaymentReceived(descLower) || isLoanPayment(descLower, tempAccount)) {
                    LOGGER.debug(
                            "Transaction type determined from account type string: Loan account payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
                } else {
                    // Positive without payment keywords = CREDIT (disbursement)
                    // Note: We use PAYMENT type but category will distinguish
                    LOGGER.debug(
                            "Transaction type determined from account type string: Loan account credit/disbursement → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.90);
                }
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                LOGGER.debug(
                        "Transaction type determined from account type string: Loan account fee/charge → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
            }
        }

        // Checking/Savings/Money Market accounts:
        // +amount (credit) = INCOME
        // -amount (debit) = EXPENSE
        if (accountInfo.isCheckingOrSavings) {
            // Positive amount (credit) on checking/savings = INCOME (always)
            // This includes: payroll deposits, rental income, transfers in, interest, etc.
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                LOGGER.debug(
                        "Transaction type determined from account type string: Credit on checking/savings account → INCOME");
                return new TypeResult(TransactionType.INCOME, ACCOUNT_TYPE, 0.95);
            }
            // Negative amount (debit) on checking/savings = EXPENSE
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                if (isCreditCardPayment(description, null, null, null)) {
                    LOGGER.debug(
                            "Transaction type determined from account type: Credit card payment on checking account → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
                }
                LOGGER.debug(
                        "Transaction type determined from account type string: Debit on checking/savings account → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
            }
        }

        return null;
    }

    /**
     * Determines transaction type using hybrid logic (account type + parser logic)
     *
     * <p>Priority: 1. Account type (investment/loan accounts) 2. Category-based (income/investment
     * categories) 3. Transaction parsing logic (debit/credit indicators, amount sign) 4. Default to
     * EXPENSE
     *
     * <p>P1: Cached for performance - type determination involves multiple lookups
     */
    @Cacheable(
            value = "categoryDetermination",
            key =
                    "'TYPE_' + (#account?.accountType ?: 'null') + '_' + (#categoryPrimary ?: 'null') + '_' + (#amount ?: 'null') + '_' + (#transactionTypeIndicator ?: 'null') + '_' + (#description ?: 'null')")
    public TypeResult determineTransactionType(
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount,
            final String transactionTypeIndicator,
            final String description,
            final String paymentChannel) {

        final String descLower = description != null ? description.toLowerCase(Locale.ROOT) : "";
        TypeResult result0 =
                determineTypePriority0AccountType(
                        account,
                        categoryPrimary,
                        categoryDetailed,
                        amount,
                        transactionTypeIndicator,
                        description,
                        paymentChannel,
                        descLower);
        if (result0 != null) {
            return result0;
        }
        TypeResult result1 =
                determineTypePriority1Category(
                        account,
                        categoryPrimary,
                        categoryDetailed,
                        amount,
                        transactionTypeIndicator,
                        description,
                        paymentChannel,
                        descLower);
        if (result1 != null) {
            return result1;
        }
        return determineTypePriority2PaymentsAndIndicators(
                account,
                categoryPrimary,
                categoryDetailed,
                amount,
                transactionTypeIndicator,
                description,
                paymentChannel,
                descLower);
    }

    private TypeResult determineTypePriority0AccountType(
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount,
            final String transactionTypeIndicator,
            final String description,
            final String paymentChannel,
            final String descLower) {
        // Priority 0: Account type-based intelligent inference (highest priority for
        // account-specific logic)
        if (account != null && account.getAccountType() != null) {
            final AccountTypeInfo accountInfo = getAccountTypeInfo(account);

            // Credit card accounts:
            // +amount = EXPENSE (charge/purchase)
            // -amount = PAYMENT (payment to credit card - paying off debt)
            // EXCEPTION: Dining transactions on credit cards should be EXPENSE, not PAYMENT (even
            // if negative)
            // EXCEPTION: Wells Fargo credit card payments have negative amounts but should be
            // PAYMENT type
            if (accountInfo.isCreditCard) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    final String callerInfo =
                            stackTrace.length > 2
                                    ? stackTrace[2].getFileName()
                                            + ":"
                                            + stackTrace[2].getLineNumber()
                                    : "unknown";
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Positive amount on credit card → EXPENSE",
                                callerInfo,
                                amount,
                                description != null ? description : NULL,
                                categoryPrimary != null ? categoryPrimary : NULL,
                                account.getAccountName());
                    }
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // CRITICAL FIX: Check if category is an expense category (dining, shopping,
                    // groceries, etc.)
                    // Expense categories on credit cards should be EXPENSE, not PAYMENT, even if
                    // negative
                    // This fixes: Lululemon purchases showing as PAYMENT instead of EXPENSE
                    final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    final String callerInfo =
                            stackTrace.length > 2
                                    ? stackTrace[2].getFileName()
                                            + ":"
                                            + stackTrace[2].getLineNumber()
                                    : "unknown";

                    if (categoryPrimary != null) {
                        final String categoryLower = categoryPrimary.toLowerCase(Locale.ROOT);
                        // Expense categories that should always be EXPENSE, not PAYMENT
                        final boolean isExpenseCategory =
                                "dining".equals(categoryLower)
                                        || "shopping".equals(categoryLower)
                                        || "groceries".equals(categoryLower)
                                        || "transportation".equals(categoryLower)
                                        || "travel".equals(categoryLower)
                                        || "entertainment".equals(categoryLower)
                                        || "healthcare".equals(categoryLower)
                                        || "education".equals(categoryLower)
                                        || UTILITIES.equals(categoryLower)
                                        || "subscriptions".equals(categoryLower)
                                        || "pet".equals(categoryLower)
                                        || "health".equals(categoryLower)
                                        || "rent".equals(categoryLower)
                                        || "charity".equals(categoryLower)
                                        || "fees".equals(categoryLower)
                                        || TAX.equals(categoryLower)
                                        || "insurance".equals(categoryLower)
                                        || "cash".equals(categoryLower)
                                        || OTHER.equals(categoryLower);

                        final boolean isPaymentKeyword = isPaymentReceived(descLower);
                        final boolean isPaymentCategory = PAYMENT.equalsIgnoreCase(categoryPrimary);

                        if (isPaymentKeyword || isPaymentCategory) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Payment keyword/category detected → PAYMENT | isPaymentKeyword: {} | isPaymentCategory: {}",
                                        callerInfo,
                                        amount,
                                        description != null ? description : NULL,
                                        categoryPrimary,
                                        account.getAccountName(),
                                        isPaymentKeyword,
                                        isPaymentCategory);
                            }
                            return new TypeResult(TransactionType.PAYMENT, CATEGORY_OVERRIDE, 0.95);
                        }

                        if (isExpenseCategory) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Expense category on credit card (negative amount) → EXPENSE (overriding default PAYMENT)",
                                        callerInfo,
                                        amount,
                                        description != null ? description : NULL,
                                        categoryPrimary,
                                        account.getAccountName());
                            }
                            return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
                        }

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Negative amount, non-expense category → PAYMENT",
                                    callerInfo,
                                    amount,
                                    description != null ? description : NULL,
                                    categoryPrimary,
                                    account.getAccountName());
                        }
                        return new TypeResult(TransactionType.PAYMENT, CATEGORY_OVERRIDE, 0.95);
                    }

                    final boolean isPaymentKeyword = isPaymentReceived(descLower);
                    if (isPaymentKeyword) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Payment keyword in description → PAYMENT",
                                    callerInfo,
                                    amount,
                                    description != null ? description : NULL,
                                    categoryPrimary != null ? categoryPrimary : NULL,
                                    account.getAccountName());
                        }
                        return new TypeResult(TransactionType.PAYMENT, CATEGORY_OVERRIDE, 0.95);
                    }

                    // Default: negative amount on credit card without payment keywords = EXPENSE
                    // (likely a charge)
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Negative amount, no payment keywords, no category → EXPENSE (default for credit card charges)",
                                callerInfo,
                                amount,
                                description != null ? description : NULL,
                                categoryPrimary != null ? categoryPrimary : NULL,
                                account.getAccountName());
                    }
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.90);
                }
            }

            // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.):
            // +amount = INCOME (dividends, interest, distributions) OR INVESTMENT (transfers,
            // deposits)
            // -amount = EXPENSE (fees, charges) or INVESTMENT (purchases of instruments)
            // Use category/description to distinguish fees from purchases, and transfers from
            // income
            if (accountInfo.isInvestment) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    // Check if it's a transfer or deposit (should be INVESTMENT, not INCOME)
                    final String categoryLower =
                            categoryPrimary != null ? categoryPrimary.toLowerCase(Locale.ROOT) : "";

                    // Transfers, deposits, and investment-related transactions should be INVESTMENT
                    final boolean isTransfer =
                            descLower.contains(TRANSFER)
                                    || descLower.contains(DEPOSIT)
                                    || descLower.contains("contribution")
                                    || descLower.contains("from")
                                    || categoryLower.contains(INVESTMENT)
                                    || categoryLower.contains(TRANSFER);

                    if (isTransfer) {
                        LOGGER.debug(
                                "Transaction type determined from account type: Investment account transfer/deposit → INVESTMENT");
                        return new TypeResult(TransactionType.INVESTMENT, ACCOUNT_TYPE, 0.95);
                    }

                    // Positive amounts on investment accounts = INCOME (dividends, interest,
                    // distributions)
                    LOGGER.debug(
                            "Transaction type determined from account type: Investment account positive amount → INCOME");
                    return new TypeResult(TransactionType.INCOME, ACCOUNT_TYPE, 0.95);
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // Negative amounts: Check if it's a fee or a purchase

                    // Check for fee-related keywords
                    final boolean isFee =
                            descLower.contains("fee")
                                    || descLower.contains("charge")
                                    || descLower.contains("commission")
                                    || descLower.contains(TAX)
                                    || descLower.contains("expense")
                                    || descLower.contains("custodial")
                                    || descLower.contains("maintenance")
                                    || descLower.contains("service charge")
                                    || descLower.contains(OTHER)
                                    || descLower.contains("cost")
                                    || descLower.contains("sales load");

                    if (isFee) {
                        LOGGER.debug(
                                "Transaction type determined from account type: Investment account fee (negative) → EXPENSE");
                        return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                    } else {
                        // Default to INVESTMENT for negative amounts (likely a purchase)
                        LOGGER.debug(
                                "Transaction type determined from account type: Investment account negative amount → INVESTMENT (default)");
                        return new TypeResult(TransactionType.INVESTMENT, ACCOUNT_TYPE, 0.90);
                    }
                }
            }

            // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
            // +amount = PAYMENT (if payment keywords present) or CREDIT (loan
            // disbursement/increase)
            // -amount = EXPENSE (fees, charges)
            if (accountInfo.isLoan) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    // Positive amounts: Check if it's a payment or credit (disbursement)
                    final String combinedText = descLower;

                    // Use existing payment detection logic
                    if (isPaymentReceived(descLower) || isLoanPayment(combinedText, account)) {
                        LOGGER.info(
                                "Transaction type determined from account type: Loan account payment (positive) → PAYMENT");
                        return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
                    } else {
                        // Positive amount without payment keywords = CREDIT (loan
                        // disbursement/increase)
                        // Note: CREDIT is not a separate type, but we'll use a category-based
                        // approach
                        // For now, treat as PAYMENT but category will distinguish
                        LOGGER.info(
                                "Transaction type determined from account type: Loan account credit/disbursement (positive) → PAYMENT (category will distinguish)");
                        return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.85);
                    }
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // Negative amounts on loan accounts = EXPENSE (fees, charges)
                    LOGGER.info(
                            "Transaction type determined from account type: Loan account fee/charge (negative) → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                }
                return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
            }

            // Checking/Savings/Money Market accounts:
            //   +amount (credit) = INCOME  (money flowing IN — payroll,
            //                               rental, interest, transfer IN)
            //   -amount (debit)  = EXPENSE (money flowing OUT — cheques,
            //                               ATM, debit card, transfer OUT)
            //
            // A prior version of this block had the comparisons inverted:
            // `< 0 → INCOME` and `> 0 → EXPENSE`, which made every outgoing
            // transfer (e.g. Remitly -$20,000) look like income and every
            // deposit look like an expense. Fixed: inequalities now match
            // the block-leading convention.
            if (accountInfo.isCheckingOrSavings) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    LOGGER.debug(
                            "Transaction type determined from account type: Credit on checking/savings account → INCOME");
                    return new TypeResult(TransactionType.INCOME, ACCOUNT_TYPE, 0.95);
                }
                if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // CRITICAL: Check for transfer category/keywords BEFORE credit card payment
                    // check

                    // Check if this is a credit card payment before defaulting to EXPENSE
                    if (isCreditCardPayment(description, null, categoryPrimary, account)) {
                        LOGGER.info(
                                "Transaction type determined from account type: Credit card payment on checking account → PAYMENT");
                        return new TypeResult(TransactionType.PAYMENT, ACCOUNT_TYPE, 0.95);
                    } else if (TRANSFER.equalsIgnoreCase(categoryPrimary)
                            || descLower.contains(TRANSFER)
                            || descLower.contains("paypal")
                            || descLower.contains("inst xfer")
                            || descLower.contains("xfer")
                            || descLower.contains("online transfer")) {
                        LOGGER.info(
                                "Transaction type determined from account type: Transfer on checking account → EXPENSE");
                        return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                    }

                    LOGGER.info(
                            "Transaction type determined from account type: Debit on checking/savings account → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, ACCOUNT_TYPE, 0.95);
                }
            }
        }
        return null;
    }

    private TypeResult determineTypePriority1Category(
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount,
            final String transactionTypeIndicator,
            final String description,
            final String paymentChannel,
            final String descLower) {
        // Priority 1: Category-based type determination
        if (categoryPrimary != null) {
            final String categoryLower = categoryPrimary.toLowerCase(Locale.ROOT);
            final String categoryDetailedLower =
                    categoryDetailed != null ? categoryDetailed.toLowerCase(Locale.ROOT) : null;

            // CRITICAL FIX: Dining category should always be EXPENSE, not PAYMENT
            // This fixes cases like TST* DEEP DIVE which were incorrectly categorized as
            // PAYMENT/utilities
            // Dining transactions are expenses, regardless of account type or amount sign
            if ("dining".equals(categoryLower)) {
                LOGGER.debug(
                        "Transaction type determined: dining category → EXPENSE (overriding account-based logic)");
                return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
            }

            // CRITICAL FIX: Payment category should only be PAYMENT if it's actually a payment
            // On checking accounts, PAYMENT with positive amount (credit) should be INCOME
            // PAYMENT with UTILITIES detailed category should be EXPENSE
            // TRANSFER category = EXPENSE (money out) or INCOME (money in based on amount)
            if (TRANSFER.equals(categoryLower)) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    LOGGER.debug(
                            "Transaction type determined: transfer category with positive amount → INCOME");
                    return new TypeResult(TransactionType.INCOME, "TRANSFER_IN", 0.95);
                } else {
                    LOGGER.debug(
                            "Transaction type determined: transfer category with negative amount → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "TRANSFER_OUT", 0.95);
                }
            }

            // PAYMENT for checks/transfers should be EXPENSE
            // Only actual payments (credit card, mortgage, student loan, etc.) should be PAYMENT
            if (PAYMENT.equals(categoryLower)) {
                final String combinedText =
                        descLower; // merchantName not available in determineTransactionType

                // Check if it's a transfer (check, wire, PayPal, etc.) = EXPENSE (MUST check BEFORE
                // account type)
                if (descLower != null
                        && (descLower.contains("check")
                                || descLower.contains("wire")
                                || descLower.contains(TRANSFER)
                                || descLower.contains("ach transfer")
                                || descLower.contains("paypal")
                                || descLower.contains("inst xfer")
                                || descLower.contains("xfer"))) {
                    LOGGER.debug(
                            "Transaction type determined: payment/transfer → EXPENSE (overriding account type)");
                    return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
                }

                // Check if it's a checking/depository account with positive amount (credit)
                final boolean isCheckingAccount =
                        account != null
                                && account.getAccountType() != null
                                && (account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(CHECKING)
                                        || account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(DEPOSITORY)
                                        || account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(SAVINGS));

                final boolean isCreditCardAccount =
                        account != null
                                && account.getAccountType() != null
                                && (account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(CREDIT_CARD)
                                        || account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(CREDITCARD)
                                        || account.getAccountType()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(CREDIT));

                // Positive amount on checking account = credit = INCOME
                if (isCheckingAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    LOGGER.debug(
                            "Transaction type determined: payment category on checking account with positive amount → INCOME");
                    return new TypeResult(TransactionType.INCOME, CATEGORY_OVERRIDE, 0.95);
                }

                if (isCreditCardAccount
                        && amount != null
                        && amount.compareTo(BigDecimal.ZERO) > 0) {
                    LOGGER.debug(
                            "Transaction type determined: payment category on credit card account with positive amount → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
                }

                // Utilities detailed category = EXPENSE
                if (UTILITIES.equals(categoryDetailedLower)) {
                    LOGGER.debug("Transaction type determined: payment/utilities → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
                }

                // CRITICAL: Only map to PAYMENT if it's actually a payment
                // Check for payment keywords: credit card, mortgage, student loan, car loan,
                // personal loan, home loan, etc.
                if (isLoanPayment(combinedText, account)) {
                    LOGGER.debug(
                            "Transaction type determined: payment category is actual payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, CATEGORY, 0.95);
                }

                // If it's not a payment and not on a loan account, default to EXPENSE
                // This handles generic PAYMENT categories that aren't payment-related
                final boolean isLoanAccount =
                        account != null
                                && account.getAccountType() != null
                                && isLoanAccountType(account.getAccountType());

                if (!isLoanAccount) {
                    LOGGER.debug(
                            "Transaction type determined: payment category is not a payment → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, CATEGORY_OVERRIDE, 0.95);
                }

                // If it's on a loan account, it's likely a payment
                LOGGER.debug("Transaction type: payment category on loan account → PAYMENT");
                return new TypeResult(TransactionType.PAYMENT, CATEGORY, 0.95);
            }

            if (isIncomeCategory(categoryLower, categoryDetailedLower)) {
                LOGGER.debug("Transaction type determined: income category → INCOME");
                return new TypeResult(TransactionType.INCOME, CATEGORY, 0.95);
            }

            if (isInvestmentCategory(categoryLower, categoryDetailedLower)) {
                LOGGER.debug("Transaction type determined: investment category → INVESTMENT");
                return new TypeResult(TransactionType.INVESTMENT, CATEGORY, 0.95);
            }

            if (isExpenseCategory(categoryLower, categoryDetailedLower)) {
                LOGGER.debug("Transaction type determined: expense category → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, CATEGORY, 0.90);
            }

            // Deposit category with positive amount → INCOME type
            if (DEPOSIT.equals(categoryLower)
                    && amount != null
                    && amount.compareTo(BigDecimal.ZERO) > 0) {
                LOGGER.debug("Transaction type determined from category: deposit → INCOME");
                return new TypeResult(TransactionType.INCOME, CATEGORY, 0.95);
            }

            // Investment category → INVESTMENT type
            if (INVESTMENT.equals(categoryLower)) {
                LOGGER.info("Transaction type determined from category: investment → INVESTMENT");
                return new TypeResult(TransactionType.INVESTMENT, CATEGORY, 0.95);
            }

            // Income category → INCOME type
            if (INCOME.equals(categoryLower) || SALARY.equals(categoryLower)) {
                LOGGER.info(
                        "Transaction type determined from category: {} → INCOME", categoryLower);
                return new TypeResult(TransactionType.INCOME, CATEGORY, 0.95);
            }

            // Utilities category → EXPENSE type (even if primary is PAYMENT)
            if (UTILITIES.equals(categoryLower) || UTILITIES.equals(categoryDetailedLower)) {
                LOGGER.info("Transaction type determined from category: utilities → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, CATEGORY, 0.95);
            }
        }
        return null;
    }

    private TypeResult determineTypePriority2PaymentsAndIndicators(
            final AccountTable account,
            final String categoryPrimary,
            final String categoryDetailed,
            final BigDecimal amount,
            final String transactionTypeIndicator,
            final String description,
            final String paymentChannel,
            final String descLower) {
        // Priority 2: Check for actual payments (AUTOPAY/PAYMENT RECEIVED patterns)
        // CRITICAL: For credit card accounts, only actual payment patterns should be PAYMENT
        // All other positive amounts on credit cards are EXPENSE (charges)
        // This should be checked before debit/credit indicators to ensure payments are correctly
        // identified

        final String combinedText =
                descLower; // merchantName not available in determineTransactionType

        // Check if it's an actual payment (AUTOPAY or PAYMENT RECEIVED - THANK YOU)
        // This works for both credit card and loan accounts
        if (isPaymentReceived(descLower)) {
            LOGGER.debug(
                    "Transaction type determined: Payment pattern detected (AUTOPAY/PAYMENT RECEIVED) → PAYMENT");
            return new TypeResult(TransactionType.PAYMENT, HYBRID, 0.95);
        }

        // For loan accounts, check if it's a loan payment (but not credit card accounts)
        // Credit card accounts are already handled in Priority 0
        if (account != null && account.getAccountType() != null) {
            final AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            if (accountInfo.isLoan
                    && !accountInfo.isCreditCard
                    && isLoanPayment(combinedText, account)) {
                LOGGER.debug("Transaction type determined: Loan payment detected → PAYMENT");
                return new TypeResult(TransactionType.PAYMENT, HYBRID, 0.95);
            }
        }

        // Default fallback based on amount sign when other signals did not match
        TransactionType baseType = TransactionType.EXPENSE;
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            baseType = TransactionType.INCOME;
        }

        // CRITICAL FIX: Override PAYMENT type for checking accounts with positive amounts (credits)
        // Credits on checking accounts should always be INCOME, not PAYMENT
        // This handles cases like payroll deposits, rental income, transfers in, etc.
        if (baseType == TransactionType.PAYMENT
                && account != null
                && account.getAccountType() != null) {
            final String accountTypeLower = account.getAccountType().toLowerCase(Locale.ROOT);
            final boolean isCheckingAccount =
                    accountTypeLower.contains(CHECKING)
                            || accountTypeLower.contains(DEPOSITORY)
                            || accountTypeLower.contains(SAVINGS);

            if (isCheckingAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                // Positive amount (credit) on checking account should always be INCOME, not PAYMENT
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "🏷️ Overriding PAYMENT type to INCOME for credit on checking account: amount={}, accountType={}",
                            amount,
                            account.getAccountType());
                }
                baseType = TransactionType.INCOME;
            }
        }

        // CRITICAL FIX: Override PAYMENT type for credit card accounts with positive amounts that
        // are NOT payments
        // Credit card charges (positive amounts) should be EXPENSE, not PAYMENT
        // Only actual payments (AUTOPAY/PAYMENT RECEIVED) should be PAYMENT
        if (baseType == TransactionType.PAYMENT
                && account != null
                && account.getAccountType() != null
                && amount != null
                && amount.compareTo(BigDecimal.ZERO) > 0) {
            final AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            if (accountInfo.isCreditCard) {
                // Check if it's actually a payment pattern (reuse descLower from earlier)
                if (!isPaymentReceived(descLower)) {
                    // Not a payment pattern, so it's a charge → EXPENSE
                    LOGGER.info(
                            "🏷️ Overriding PAYMENT type to EXPENSE for credit card charge (not a payment): amount={}, description={}",
                            amount,
                            description);
                    baseType = TransactionType.EXPENSE;
                }
            }
        }

        // Priority 3: Enhance with transaction parsing logic (debit/credit indicators)
        if (transactionTypeIndicator != null && !transactionTypeIndicator.isBlank()) {
            final String indicator = transactionTypeIndicator.trim().toLowerCase(Locale.ROOT);

            // Debit indicator → EXPENSE (unless already determined as investment)
            if ((indicator.contains("debit")
                            || indicator.contains("dr")
                            || indicator.startsWith("db ")
                            || indicator.startsWith("dr ")
                            || "db".equals(indicator)
                            || "dr".equals(indicator))
                    && baseType != TransactionType.INVESTMENT) {
                LOGGER.debug(
                        "Transaction type enhanced: Debit indicator → EXPENSE (was: {})", baseType);
                return new TypeResult(TransactionType.EXPENSE, HYBRID, 0.95);
            }

            // Credit indicator → INCOME (unless already determined as investment)
            if ((indicator.contains(CREDIT)
                            || indicator.contains("cr")
                            || indicator.startsWith("cr ")
                            || "cr".equals(indicator))
                    && baseType != TransactionType.INVESTMENT) {
                LOGGER.debug(
                        "Transaction type enhanced: Credit indicator → INCOME (was: {})", baseType);
                return new TypeResult(TransactionType.INCOME, HYBRID, 0.95);
            }
        }

        // Return base type with appropriate source
        final String source =
                account != null
                        ? "ACCOUNT"
                        : (categoryPrimary != null
                                ? CATEGORY
                                : (amount != null && amount.compareTo(BigDecimal.ZERO) > 0
                                        ? "AMOUNT"
                                        : "DEFAULT"));
        final double confidence =
                account != null
                        ? 0.9
                        : (categoryPrimary != null ? 0.8 : (amount != null ? 0.7 : 0.5));

        return new TypeResult(baseType, source, confidence);
    }

    /**
     * Determines category using hybrid logic (importer category + parser + ML)
     *
     * <p>For Plaid: importerCategoryPrimary/importerCategoryDetailed are Plaid categories (raw) -
     * First map Plaid categories using PlaidCategoryMapper - Then apply hybrid logic with parser +
     * ML For Imports: importerCategoryPrimary/importerCategoryDetailed are parsed categories
     *
     * <p>Logic: 1. If Plaid: Map Plaid categories using PlaidCategoryMapper 2. If importer
     * categories exist, use them as base 3. Apply import parser logic for additional signals 4.
     * Apply ML detection 5. Reason with rules to determine best category 6. Return best result with
     * confidence
     *
     * <p>P1: Cached for performance - category determination is expensive (ML calls, parsing)
     * Global Scale: Supports region-specific category determination
     */
    @Cacheable(
            value = "categoryDetermination",
            key =
                    "#importerCategoryPrimary + '_' + #importerCategoryDetailed + '_' + (#account?.accountType ?: 'null') + '_' + (#merchantName ?: 'null') + '_' + (#description ?: 'null') + '_' + (#amount ?: 'null') + '_' + (#paymentChannel ?: 'null') + '_' + (#transactionTypeIndicator ?: 'null') + '_' + (#importSource ?: 'null') + '_' + (#account?.currencyCode ?: 'null') + '_' + (#account?.userId ?: 'null') + '_' + (#transactionTypeHint ?: 'null')")
    public CategoryResult determineCategory(
            final String importerCategoryPrimary,
            final String importerCategoryDetailed,
            final AccountTable account,
            final String merchantName,
            final String description,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator,
            final String importSource,
            final TransactionType transactionTypeHint) {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "determineCategory: merchant='{}' description='{}' amount={} channel='{}' importSource='{}' accountType='{}'",
                    merchantName,
                    description,
                    amount,
                    paymentChannel,
                    importSource,
                    account != null ? account.getAccountType() : null);
        }
        // Step 0: Check custom user mappings first (highest priority, user-defined)
        if (account != null && account.getUserId() != null && merchantName != null) {
            try {
                final com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable customMapping =
                        learningService.getCustomMapping(account.getUserId(), merchantName);
                if (customMapping != null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Using custom mapping for merchant '{}': {} / {}",
                                merchantName,
                                customMapping.getCategoryPrimary(),
                                customMapping.getCategoryDetailed());
                    }
                    // Update usage count (async, best effort)
                    try {
                        customMapping.setUsageCount(customMapping.getUsageCount() + 1);
                        customMapping.setLastUsedAt(java.time.Instant.now());
                        // Note: This would require async update to avoid blocking
                    } catch (Exception e) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Failed to update usage count: {}", e.getMessage());
                        }
                    }
                    return new CategoryResult(
                            customMapping.getCategoryPrimary(),
                            customMapping.getCategoryDetailed(),
                            "CUSTOM_MAPPING",
                            1.0 // 100% confidence for user-defined mappings
                            );
                }
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Error checking custom mapping: {}", e.getMessage());
                }
                // Continue with other detection methods
            }
        }

        // Step 1: Rule-based detection (centralized, honors importer hints).
        //
        // We only suppress rule-based merchant detection when the importer
        // gave an AUTHORITATIVE transaction-type category (deposit / transfer
        // / income / refund / interest) AND the description itself contains a
        // supporting structural pattern ("TRANSFER FROM", "ACH CREDIT",
        // "DIRECT DEPOSIT", etc.). That combination means a parser recognised
        // an ACH / wire / statement pattern the merchant corpus can't see —
        // "ONLINE TRANSFER FROM MORGANSTANLEY" → deposit must not be
        // rewritten to INVESTMENT just because the corpus has a
        // "morgan stanley" row.
        //
        // When the importer category is authoritative but the description
        // contains no corroborating pattern, the importer likely guessed
        // wrong from a weak hint (e.g. a "CHECK" prefix that happens to be a
        // passport-services payment, not an actual transfer). In that case
        // we run rule-based detection and let it override.
        final boolean importerIsAuthoritative =
                isAuthoritativeImporterCategory(importerCategoryPrimary)
                        || isAuthoritativeImporterCategory(importerCategoryDetailed);
        // Check corroboration against BOTH primary and detailed (when both are
        // authoritative) — e.g. importer=INCOME/INTEREST + description="Interest
        // payment" must corroborate via the INTEREST token, not silently fall
        // through because INCOME's tokens (payroll/salary/etc.) didn't match.
        final boolean importerCorroborated =
                importerIsAuthoritative
                        && (descriptionCorroboratesAuthoritativeCategory(
                                        isAuthoritativeImporterCategory(importerCategoryPrimary)
                                                ? importerCategoryPrimary
                                                        .trim()
                                                        .toLowerCase(Locale.ROOT)
                                                : null,
                                        merchantName,
                                        description)
                                || descriptionCorroboratesAuthoritativeCategory(
                                        isAuthoritativeImporterCategory(importerCategoryDetailed)
                                                ? importerCategoryDetailed
                                                        .trim()
                                                        .toLowerCase(Locale.ROOT)
                                                : null,
                                        merchantName,
                                        description));
        if (!importerCorroborated && merchantCategoryDataService != null) {
            final String ruleCategory =
                    merchantCategoryDataService.detectRuleBasedCategory(
                            merchantName,
                            description,
                            importerCategoryPrimary,
                            importerCategoryDetailed);
            if (ruleCategory != null) {
                return new CategoryResult(ruleCategory, ruleCategory, "RULE", 0.8);
            }
        }

        // When the importer's authoritative category is corroborated by the
        // description itself, short-circuit here so downstream ML cannot
        // pull the category off of a correctly-structured row. Without
        // this, "Online Transfer FROM Morganstanley" (importer = deposit,
        // corroborated by "transfer … from") would be rewritten to
        // INVESTMENT by an ML match on "Morganstanley".
        if (importerCorroborated) {
            final String primary =
                    pickAuthoritativeCategory(importerCategoryPrimary, importerCategoryDetailed);
            final String detailed =
                    hasSpecificCategory(importerCategoryDetailed)
                            ? importerCategoryDetailed.trim().toLowerCase(Locale.ROOT)
                            : primary;
            return new CategoryResult(primary, detailed, "IMPORTER_CORROBORATED", 0.90);
        }

        // Step 1: For Plaid, map the raw Plaid categories using PlaidCategoryMapper
        String mappedImporterPrimary = importerCategoryPrimary;
        String mappedImporterDetailed = importerCategoryDetailed;

        if ("PLAID".equals(importSource)
                && (importerCategoryPrimary != null || importerCategoryDetailed != null)) {
            try {
                final PlaidCategoryMapper.CategoryMapping plaidMapping =
                        plaidCategoryMapper.mapPlaidCategory(
                                importerCategoryPrimary,
                                importerCategoryDetailed,
                                merchantName,
                                description,
                                paymentChannel,
                                amount);
                if (plaidMapping != null) {
                    mappedImporterPrimary = plaidMapping.getPrimary();
                    mappedImporterDetailed = plaidMapping.getDetailed();
                    LOGGER.debug(
                            "Mapped Plaid categories: {} / {} → {} / {}",
                            importerCategoryPrimary,
                            importerCategoryDetailed,
                            mappedImporterPrimary,
                            mappedImporterDetailed);
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Failed to map Plaid categories: {}", e.getMessage());
                }
                // Continue with raw categories
            }
        }

        // Step 2: Get parser category (from import parsing logic)
        // CRITICAL FIX: For CSV/Excel/PDF imports, we already have the parsed category in
        // importerCategoryPrimary
        // Re-parsing is wasteful and causes duplicate processing. Use the already-parsed category
        // instead.
        String parserCategory = null;
        if ("CSV".equals(importSource)
                || "EXCEL".equals(importSource)
                || "PDF".equals(importSource)) {
            // For imports, use the already-parsed category from importerCategoryPrimary
            // This avoids duplicate parsing and improves performance
            parserCategory = importerCategoryPrimary;
            LOGGER.debug(
                    "Using already-parsed category for {} import: '{}'",
                    importSource,
                    parserCategory);
        } else {
            // For Plaid or other users, also get parser category as additional signal
            parserCategory =
                    importCategoryParser.parseCategory(
                            null,
                            description,
                            merchantName,
                            amount,
                            paymentChannel,
                            transactionTypeIndicator,
                            transactionTypeHint != null ? transactionTypeHint.name() : null,
                            account != null ? account.getAccountType() : null,
                            account != null ? account.getAccountSubtype() : null);
        }

        // Step 3: Get ML category with context-aware matching (P2: with circuit breaker protection)

        String mlCategory = null;
        double mlConfidence = 0.0;
        String mlSource = "NONE";
        String mlReason = null;
        try {
            // CRITICAL: Use context-aware detection (pass account type/subtype if available)
            final String accountType = account != null ? account.getAccountType() : null;
            final String accountSubtype = account != null ? account.getAccountSubtype() : null;

            final EnhancedCategoryDetectionService.DetectionResult mlResult =
                    circuitBreakerService.execute(
                            "ML_CategoryDetection",
                            () ->
                                    enhancedCategoryDetection.detectCategoryWithContext(
                                            merchantName,
                                            description,
                                            amount,
                                            paymentChannel,
                                            null,
                                            accountType,
                                            accountSubtype), // Pass account context for improved
                            // accuracy
                            null // Fallback: null if circuit is open
                            );
            if (mlResult != null && mlResult.category != null) {
                mlCategory = mlResult.category;
                mlConfidence = mlResult.confidence;
                mlSource = mlResult.method != null ? mlResult.method : "ML";
                mlReason = mlResult.reason;
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("ML category detection failed: {}", e.getMessage());
            }
        }
        /*
        // Step 4: Get account-based hints
        String accountHint = getAccountCategoryHint(account);

        // Step 5: Apply iOS fallback logic (consolidated from iOS BackendModels.swift)
        // ✅ COMPLETED: iOS fallback logic is now fully consolidated in backend
        // This ensures consistent behavior across iOS and backend
        String fallbackCategory = applyIOSFallbackLogic(
            mappedImporterPrimary, mappedImporterDetailed,
            parserCategory,
            merchantName, description, amount, paymentChannel);

        // Step 5.5: Store medium-confidence merchant result for fallback chain
        TransactionTypeCategoryService.CategoryResult mediumConfidenceMerchantResult = null;
        if (merchantName != null || description != null) {
            try {
                String mccCode = extractMCCFromDescription(description);
                TransactionTypeCategoryService.CategoryResult merchantResult = merchantService.detectCategory(
                    merchantName, description, mccCode
                );
                if (merchantResult != null && merchantResult.getConfidence() >= 0.90 &&
                    merchantResult.getConfidence() < 0.95) {
                    mediumConfidenceMerchantResult = merchantResult;
                }
            } catch (Exception e) {
                LOGGER.debug("Error checking medium-confidence merchant result: {}", e.getMessage());
            }
        }

        // Step 6: Reason with rules to determine best category
        CategoryResult resolved = reasonCategory(
            mappedImporterPrimary, mappedImporterDetailed,
            parserCategory,
            mlCategory, mlConfidence,
            accountHint,
            merchantName, description, amount, paymentChannel, importSource, fallbackCategory, account,
            mediumConfidenceMerchantResult);

        return alignCategoryToType(resolved, transactionTypeHint, account, amount, description);
        */

        // Tiered fallback chain. Each tier represents a signal source we trust; the first
        // one that yields a useful (non-null, non-OTHER) category wins. This replaces
        // the prior "if ml is null → other" drop that hid all upstream signals.
        CategoryResult resolved =
                buildTieredCategoryResult(
                        mlCategory,
                        mlConfidence,
                        mlSource,
                        mlReason,
                        mappedImporterPrimary,
                        mappedImporterDetailed,
                        parserCategory,
                        account);

        // Payment-category guard. Both the merchant rule-based detector and
        // the ML knownMerchants map contain short entries like "amex",
        // "chase", "boa" that fire PAYMENT on any description containing
        // the word. For a charge AT ESTEE LAUDER on an Amex card that's
        // wrong — it's a shopping charge, not a payment to Amex. Only keep
        // the PAYMENT verdict when the description actually looks like a
        // payment (contains PAYMENT, "autopay", "pmt", etc.).
        if (PAYMENT.equalsIgnoreCase(resolved.getCategoryPrimary())
                && !looksLikePayment(merchantName, description)) {
            LOGGER.info(
                    "Demoting spurious 'payment' classification for merchant='{}' (no payment-action phrase)",
                    merchantName);
            // If the importer gave us a specific (non-other) category, prefer it over a blank
            // OTHER — otherwise downstream ML noise erases what the parser already knew
            // (e.g. an investment/CD row demoted to OTHER because "Bank" matched PAYMENT).
            final String fallbackPrimary =
                    hasSpecificCategory(mappedImporterPrimary)
                            ? mappedImporterPrimary
                            : (hasSpecificCategory(mappedImporterDetailed)
                                    ? mappedImporterDetailed
                                    : OTHER);
            final String fallbackDetailed =
                    hasSpecificCategory(mappedImporterDetailed)
                            ? mappedImporterDetailed
                            : fallbackPrimary;
            resolved = new CategoryResult(fallbackPrimary, fallbackDetailed, "PAYMENT_GUARD", 0.40);
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Category resolved: merchant='{}' → category='{}' source='{}' confidence={}",
                    merchantName,
                    resolved.getCategoryPrimary(),
                    resolved.getSource(),
                    String.format("%.3f", resolved.getConfidence()));
        }

        return alignCategoryToType(resolved, transactionTypeHint, account, amount, description);
    }

    /**
     * Treat a transaction as a PAYMENT only when the merchant or description contains an explicit
     * payment-action phrase. Prevents "amex"/"chase"/"boa" in a purchase description from being
     * mistaken for a payment to those issuers. Single source of truth for the phrase list is {@link
     * com.budgetbuddy.service.category.PaymentPhrases#PAYMENT_ACTION}.
     */
    private static boolean looksLikePayment(final String merchantName, final String description) {
        return com.budgetbuddy.service.category.PaymentPhrases.isPaymentish(merchantName)
                || com.budgetbuddy.service.category.PaymentPhrases.isPaymentish(description);
    }

    /**
     * Picks the best available category signal. The priority chain splits "specific importer
     * signal" from "importer said 'other'" because they mean different things:
     *
     * <ul>
     *   <li>Importer says a <em>specific</em> category (groceries, dining, travel, etc.) — that's
     *       an upstream decision we trust above all except very-high-confidence ML. An Amex PDF
     *       that labels a row "Restaurants" stays "dining", full stop.
     *   <li>Importer says <em>OTHER</em> — ambiguous. It might mean "I genuinely inspected this and
     *       it's postage/misc" (USPS), or "my parser had no match so I defaulted." We only keep
     *       OTHER when no better signal exists; ML/merchant recognition gets priority over it.
     * </ul>
     *
     * Priority: 1. ML ≥ 0.70 — strong direct match wins. 2. Importer specific category (non-OTHER)
     * — authoritative upstream. 3. ML at any confidence — merchant-grounded even if weaker. 4.
     * Importer OTHER — preserved only when nothing better was found. 5. Parser category (keyword
     * heuristic). 6. Account hint — loans → payment, investment → investment. Credit cards
     * deliberately excluded (any category is possible). 7. OTHER at low confidence so downstream
     * sees it was a fallback.
     */
    private CategoryResult buildTieredCategoryResult(
            final String mlCategory,
            final double mlConfidence,
            final String mlSource,
            final String mlReason,
            final String importerPrimary,
            final String importerDetailed,
            final String parserCategory,
            final AccountTable account) {

        if (mlCategory != null && mlConfidence >= 0.70) {
            if (mlReason != null && !mlReason.isEmpty()) {
                LOGGER.debug(
                        "ML high-confidence pick: {} ({}): {}", mlCategory, mlSource, mlReason);
            }
            return new CategoryResult(mlCategory, mlCategory, mlSource, mlConfidence);
        }

        final String importerSpecificPrimary =
                hasSpecificCategory(importerPrimary) ? importerPrimary : null;
        final String importerSpecificDetailed =
                hasSpecificCategory(importerDetailed) ? importerDetailed : null;
        if (importerSpecificPrimary != null || importerSpecificDetailed != null) {
            final String primary =
                    importerSpecificPrimary != null
                            ? importerSpecificPrimary
                            : importerSpecificDetailed;
            final String detailed =
                    importerSpecificDetailed != null ? importerSpecificDetailed : primary;
            return new CategoryResult(primary, detailed, "IMPORTER", 0.75);
        }

        if (mlCategory != null) {
            return new CategoryResult(
                    mlCategory, mlCategory, mlSource, Math.max(mlConfidence, 0.50));
        }

        // Importer said OTHER — only keep as the answer if no stronger
        // signal emerged above.
        if (isOtherCategory(importerPrimary) || isOtherCategory(importerDetailed)) {
            return new CategoryResult(OTHER, OTHER, "IMPORTER_OTHER", 0.50);
        }

        if (hasSpecificCategory(parserCategory)) {
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.60);
        }

        final String accountHint = deriveCategoryFromAccountType(account);
        if (accountHint != null) {
            return new CategoryResult(accountHint, accountHint, "ACCOUNT_HINT", 0.45);
        }

        return new CategoryResult(OTHER, OTHER, "DEFAULT", 0.30);
    }

    /** A non-empty, non-placeholder, non-OTHER category. */
    private static boolean hasSpecificCategory(final String category) {
        if (category == null) {
            return false;
        }
        final String trimmed = category.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        return !OTHER.equals(lower)
                && !"uncategorized".equals(lower)
                && !"unknown".equals(lower)
                && !NULL.equals(lower);
    }

    /**
     * Importer categories that came from an ACH/wire/statement pattern match and should NOT be
     * overridden by merchant-keyword detection — provided the description also contains a
     * corroborating pattern ({@link #descriptionCorroboratesAuthoritativeCategory}). Without that
     * second check the mere label TRANSFER (which a parser can emit from a weak "CHECK #…" hint)
     * would block all downstream merchant detection.
     */
    private static final java.util.Set<String> AUTHORITATIVE_IMPORTER_CATEGORIES =
            java.util.Set.of(DEPOSIT, TRANSFER, INCOME, "refund", INTEREST);

    private static boolean isAuthoritativeImporterCategory(final String category) {
        if (category == null) {
            return false;
        }
        return AUTHORITATIVE_IMPORTER_CATEGORIES.contains(category.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Pick the authoritative category between primary and detailed, if either is authoritative.
     * Primary wins.
     */
    private static String pickAuthoritativeCategory(final String primary, final String detailed) {
        if (isAuthoritativeImporterCategory(primary)) {
            return primary.trim().toLowerCase(Locale.ROOT);
        }
        if (isAuthoritativeImporterCategory(detailed)) {
            return detailed.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * A category/description pair is corroborated when the description itself contains an explicit
     * token that matches the category. This prevents an incorrect authoritative-category label
     * (e.g. importer said TRANSFER after seeing only "CHECK #") from suppressing the
     * merchant-corpus lookup that would classify the row correctly.
     */
    private static boolean descriptionCorroboratesAuthoritativeCategory(
            final String authoritativeCategory,
            final String merchantName,
            final String description) {
        if (authoritativeCategory == null) {
            return false;
        }
        final String haystack =
                ((merchantName == null ? "" : merchantName)
                                + " "
                                + (description == null ? "" : description))
                        .toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return false;
        }
        final List<String> tokens =
                AUTHORITATIVE_CORROBORATING_TOKENS.getOrDefault(authoritativeCategory, List.of());
        for (final String token : tokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        final java.util.regex.Pattern pattern =
                AUTHORITATIVE_CORROBORATING_PATTERNS.get(authoritativeCategory);
        return pattern != null && pattern.matcher(haystack).find();
    }

    /**
     * The textual fingerprints we accept as proof that the importer's authoritative category
     * reflects a real structural pattern rather than a weak guess. Kept narrow on purpose — any new
     * token here widens the set of rows that suppress merchant-corpus overrides.
     */
    private static final java.util.Map<String, List<String>> AUTHORITATIVE_CORROBORATING_TOKENS =
            java.util.Map.of(
                    TRANSFER,
                    List.of(
                            TRANSFER,
                            "xfer",
                            "ach transfer",
                            "wire transfer",
                            "online transfer",
                            "internal transfer",
                            "zelle"),
                    DEPOSIT,
                    List.of(
                            DEPOSIT,
                            "ach credit",
                            "direct deposit",
                            "mobile deposit",
                            "check deposit"),
                    INCOME,
                    List.of("payroll", SALARY, "direct deposit", "wages", "commission"),
                    "refund",
                    List.of("refund", "reversal", "credit adjustment", "return"),
                    INTEREST,
                    List.of(INTEREST, "apy", DIVIDEND));

    /**
     * Regex patterns that corroborate an authoritative category when the relevant tokens appear in
     * the description but are separated by other text. Example: "Online Transfer 27265796721 from
     * Morganstanley" → the "transfer … from" pattern indicates an incoming deposit even though
     * "transfer from" is not contiguous.
     */
    private static final java.util.Map<String, java.util.regex.Pattern>
            AUTHORITATIVE_CORROBORATING_PATTERNS =
                    java.util.Map.of(
                            DEPOSIT,
                                    java.util.regex.Pattern.compile(
                                            "\\btransfer\\b.{0,60}\\bfrom\\b",
                                            java.util.regex.Pattern.CASE_INSENSITIVE),
                            TRANSFER,
                                    java.util.regex.Pattern.compile(
                                            "\\btransfer\\b.{0,60}\\bto\\b",
                                            java.util.regex.Pattern.CASE_INSENSITIVE));

    /** Explicit OTHER (not empty, not placeholder). */
    private static boolean isOtherCategory(final String category) {
        if (category == null) {
            return false;
        }
        return OTHER.equalsIgnoreCase(category.trim());
    }

    /**
     * Derive a category strictly from the account type — only for accounts whose type is
     * effectively the category (loan payments → payment, investment accounts → investment). Credit
     * cards return null: a credit-card charge can be groceries, dining, travel, anything —
     * defaulting to PAYMENT mis-categorises every such transaction.
     */
    private static String deriveCategoryFromAccountType(final AccountTable account) {
        if (account == null) {
            return null;
        }
        final String type = account.getAccountType();
        final String subtype = account.getAccountSubtype();
        final String typeLower = type != null ? type.toLowerCase(Locale.ROOT) : "";
        final String subtypeLower = subtype != null ? subtype.toLowerCase(Locale.ROOT) : "";

        if (typeLower.contains(LOAN)
                || subtypeLower.contains(LOAN)
                || subtypeLower.contains(MORTGAGE)) {
            return PAYMENT;
        }
        if (typeLower.contains(INVESTMENT)
                || typeLower.contains("brokerage")
                || subtypeLower.contains(IRA)
                || subtypeLower.contains(N_401K)) {
            return INVESTMENT;
        }
        // Intentionally no credit → payment rule — see javadoc.
        return null;
    }

    @Deprecated
    public CategoryResult determineCategory(
            final String importerCategoryPrimary,
            final String importerCategoryDetailed,
            final AccountTable account,
            final String merchantName,
            final String description,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator,
            final String importSource) {
        return determineCategory(
                importerCategoryPrimary,
                importerCategoryDetailed,
                account,
                merchantName,
                description,
                amount,
                paymentChannel,
                transactionTypeIndicator,
                importSource,
                null);
    }

    /*
     * Applies iOS fallback logic for ACH credits, interest payments, and income category
     * determination. ✅ COMPLETED: iOS fallback logic is now fully consolidated in backend (no
     * longer in iOS)
     *
     * <p>Logic: 1. ACH credits (positive amounts with paymentChannel == "ach") should be
     * income/deposit 2. Interest payment detection (handles misspellings like "INTRST", "INTR",
     * etc.) 3. Income category determination from description
     *
     * @return Fallback category if detected, null otherwise
     */

    /* Checks if the category is a specific income category (not generic INCOME) */

    /*
     * Determines income category from description and merchant name. ✅ COMPLETED: Logic
     * consolidated from iOS BackendModels.determineIncomeCategoryFromDescription()
     */

    /* Reasons about which category is best using rules */

    /**
     * Account type information structure for intelligent transaction type and category
     * determination
     */
    private static class AccountTypeInfo {
        boolean isCheckingOrSavings;
        boolean isCreditCard;
        boolean isInvestment;
        boolean isLoan;

        AccountTypeInfo(final String accountType, final String accountSubtype) {
            final String typeLower =
                    accountType != null ? accountType.toLowerCase(Locale.ROOT) : "";
            final String subtypeLower =
                    accountSubtype != null ? accountSubtype.toLowerCase(Locale.ROOT) : "";

            // Checking/Savings accounts
            this.isCheckingOrSavings =
                    typeLower.contains(CHECKING)
                            || typeLower.contains(SAVINGS)
                            || typeLower.contains(DEPOSITORY)
                            || typeLower.contains("money market")
                            || typeLower.contains("moneymarket");

            // Credit card accounts
            // CRITICAL: Check for CREDIT_CARD and CREDITCARD BEFORE CREDIT to avoid matching
            // credit lines
            // Only match CREDIT if it's NOT part of "credit line" or "line of credit"
            final boolean isCreditLine =
                    typeLower.contains("credit line")
                            || typeLower.contains("creditline")
                            || typeLower.contains("line of credit")
                            || typeLower.contains("lineofcredit")
                            || subtypeLower.contains("credit line")
                            || subtypeLower.contains("creditline");

            this.isCreditCard =
                    (typeLower.contains(CREDIT_CARD)
                                    || typeLower.contains(CREDITCARD)
                                    || typeLower.contains("charge card"))
                            || (!isCreditLine && typeLower.contains(CREDIT));

            // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, stocks, bonds, etc.)
            this.isInvestment =
                    typeLower.contains(INVESTMENT)
                            || typeLower.contains(N_401K)
                            || typeLower.contains(IRA)
                            || typeLower.contains("hsa")
                            || typeLower.contains("529")
                            || typeLower.contains("brokerage")
                            || typeLower.contains("stocks")
                            || typeLower.contains("bonds")
                            || typeLower.contains("mutual fund")
                            || typeLower.contains("mutualfund")
                            || typeLower.contains("etf")
                            || typeLower.contains("retirement")
                            || typeLower.contains("certificate")
                            || typeLower.contains("cd")
                            || subtypeLower.contains(N_401K)
                            || subtypeLower.contains(IRA)
                            || subtypeLower.contains("hsa")
                            || subtypeLower.contains("529")
                            || subtypeLower.contains("brokerage")
                            || subtypeLower.contains("cd")
                            || subtypeLower.contains("certificate");

            // Loan accounts (mortgage, student loan, car loan, personal loan, etc.)
            this.isLoan =
                    typeLower.contains(LOAN)
                            || typeLower.contains(MORTGAGE)
                            || typeLower.contains("student loan")
                            || typeLower.contains("studentloan")
                            || typeLower.contains("car loan")
                            || typeLower.contains("carloan")
                            || typeLower.contains("auto loan")
                            || typeLower.contains("autoloan")
                            || typeLower.contains("personal loan")
                            || typeLower.contains("personalloan")
                            || typeLower.contains("home loan")
                            || typeLower.contains("homeloan")
                            || subtypeLower.contains(LOAN)
                            || subtypeLower.contains(MORTGAGE);
        }
    }

    /** Gets account type information for intelligent transaction type and category determination */
    private AccountTypeInfo getAccountTypeInfo(final AccountTable account) {
        if (account == null) {
            return new AccountTypeInfo(null, null);
        }
        return new AccountTypeInfo(account.getAccountType(), account.getAccountSubtype());
    }

    /** Gets account type information from account type string (for PDF/CSV imports) */
    private AccountTypeInfo getAccountTypeInfoFromString(
            final String accountType, final String accountSubtype) {
        return new AccountTypeInfo(accountType, accountSubtype);
    }

    /* Gets category hint from account type (enhanced with comprehensive account type detection) */

    /**
     * Checks if a payment is actually a payment (credit card, mortgage, student loan, car loan,
     * personal loan, home loan, etc.) This is used to determine if PAYMENT category should map to
     * PAYMENT type
     *
     * @param combinedText Combined merchant name and description text (lowercase)
     * @param account Account associated with the transaction
     * @return true if this is a payment, false otherwise
     */
    private boolean isLoanPayment(final String combinedText, final AccountTable account) {
        if (combinedText == null || combinedText.isEmpty()) {
            // If no text, check account type
            return account != null
                    && account.getAccountType() != null
                    && isLoanAccountType(account.getAccountType());
        }

        final String textLower = combinedText.toLowerCase(Locale.ROOT);

        // Credit card payment keywords
        final String[] creditCardKeywords = {
            CREDIT_CARD,
            CREDITCARD,
            "cc payment",
            "card payment",
            "visa payment",
            "mastercard payment",
            "amex payment",
            "american express",
            "discover payment",
            "chase payment",
            "capital one",
            "citi payment"
        };
        for (final String keyword : creditCardKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Mortgage payment keywords
        final String[] mortgageKeywords = {
            MORTGAGE,
            "mortgage payment",
            "home loan payment",
            "homeloan payment",
            "house payment",
            "property loan",
            "real estate loan"
        };
        for (final String keyword : mortgageKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Student loan payment keywords
        final String[] studentLoanKeywords = {
            "student loan", "studentloan", "education loan", "educationloan",
            "federal student loan", "private student loan", "navient", "sallie mae"
        };
        for (final String keyword : studentLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Car loan / Auto loan payment keywords
        final String[] carLoanKeywords = {
            "car loan",
            "carloan",
            "auto loan",
            "autoloan",
            "vehicle loan",
            "auto payment",
            "car payment",
            "vehicle payment",
            "car financing"
        };
        for (final String keyword : carLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Personal loan payment keywords
        final String[] personalLoanKeywords = {
            "personal loan",
            "personalloan",
            "unsecured loan",
            "signature loan",
            "personal line of credit",
            "ploc"
        };
        for (final String keyword : personalLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Home loan payment keywords (separate from mortgage for clarity)
        final String[] homeLoanKeywords = {
            "home loan",
            "homeloan",
            "home equity",
            "homeequity",
            "heloc",
            "home equity line of credit",
            "second mortgage"
        };
        for (final String keyword : homeLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Other loan types
        final String[] otherLoanKeywords = {
            "loan payment", "loanpay", "loan pay", "installment loan",
            "payday loan", "paydayloan", "title loan", "titleloan",
            "business loan", "businessloan", "commercial loan"
        };
        for (final String keyword : otherLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }

        // Check account type if text doesn't match
        if (account != null && account.getAccountType() != null) {
            return isLoanAccountType(account.getAccountType());
        }

        return false;
    }

    /**
     * Checks if an account type is a loan account
     *
     * @param accountType Account type string
     * @return true if account type is loan-related, false otherwise
     */
    private boolean isLoanAccountType(final String accountType) {
        if (accountType == null) {
            return false;
        }

        final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);

        // Check for loan account types
        return accountTypeLower.contains(LOAN)
                || accountTypeLower.contains(MORTGAGE)
                || accountTypeLower.contains(CREDIT_CARD)
                || accountTypeLower.contains(CREDITCARD)
                || accountTypeLower.contains("student loan")
                || accountTypeLower.contains("studentloan")
                || accountTypeLower.contains("home loan")
                || accountTypeLower.contains("homeloan")
                || accountTypeLower.contains("car loan")
                || accountTypeLower.contains("carloan")
                || accountTypeLower.contains("auto loan")
                || accountTypeLower.contains("autoloan")
                || accountTypeLower.contains("personal loan")
                || accountTypeLower.contains("personalloan");
    }

    /** Checks if transaction is a credit card payment */
    private boolean isCreditCardPayment(
            final String description,
            final String merchantName,
            final String categoryString,
            final AccountTable account) {
        if (description == null && merchantName == null && categoryString == null) {
            return false;
        }

        // P3: Optimize string operations - use StringBuilder
        final StringBuilder combinedBuilder = new StringBuilder();
        if (merchantName != null) {
            combinedBuilder.append(merchantName).append(' ');
        }
        if (description != null) {
            combinedBuilder.append(description).append(' ');
        }
        if (categoryString != null) {
            combinedBuilder.append(categoryString);
        }
        final String combined = combinedBuilder.toString().toLowerCase(Locale.ROOT);

        // Deterministic baseline check: obvious credit-card-payment phrases
        // always match regardless of region config. This stops the check
        // from depending on Spring-injected config when the phrase is
        // unambiguous (e.g. "CHASE CREDIT CARD PAYMENT" should resolve to
        // a credit-card payment on any device, any locale, any test env).
        if (combined.contains("credit card payment")
                || combined.contains("credit card pmt")
                || combined.contains("cc payment")
                || combined.contains("card payment")) {
            return true;
        }

        // Global Scale: Use region-specific credit card keywords
        // Try to detect region from account currency or use default
        final String region =
                detectRegion(account); // TODO: Implement region detection from account/user
        final List<String> creditCardKeywords =
                globalFinancialConfig.getCreditCardKeywordsForRegion(region);

        // P2: Use configuration for keywords instead of hard-coding
        for (final String keyword : creditCardKeywords) {
            if (combined.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        // Fallback to default region keywords if region-specific didn't match.
        // globalFinancialConfig
        // is Spring-injected so non-null at runtime; the earlier call (line above) would have NPE'd
        // if it were null, so the previous defensive ternary here was a SpotBugs RCN false
        // positive.
        final String defaultRegion = globalFinancialConfig.getDefaultRegion();
        if (region != null && !region.equals(defaultRegion)) {
            for (final String keyword : importCategoryConfig.getCreditCardKeywords()) {
                if (combined.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if transaction description or merchant name indicates a payment received (AUTOPAY or
     * PAYMENT RECEIVED - THANK YOU) This is used to distinguish payments from charges on credit
     * card and loan accounts
     *
     * @param descriptionLower Lowercase transaction description
     * @param merchantNameLower Optional lowercase merchant name
     * @return true if description or merchant name contains payment received keywords
     */
    private boolean isPaymentReceived(
            final String descriptionLower, final String merchantNameLower) {
        String combinedText = "";
        if (descriptionLower != null) {
            combinedText += descriptionLower + " ";
        }
        if (merchantNameLower != null) {
            combinedText += merchantNameLower;
        }
        combinedText = combinedText.trim();

        if (combinedText.isEmpty()) {
            return false;
        }

        // Check for AUTOPAY patterns (case-insensitive, already lowercased)
        if (combinedText.contains("autopay")
                || combinedText.contains("auto pay")
                || combinedText.contains("auto-pay")
                || combinedText.contains("automatic payment")) {
            return true;
        }

        // Check for DIRECTPAY patterns (direct payment to credit card/loan)
        if (combinedText.contains("directpay")
                || combinedText.contains("direct pay")
                || combinedText.contains("direct-pay")
                || combinedText.contains("direct payment")) {
            return true;
        }

        if (combinedText.contains("fullpay")
                || combinedText.contains("full pay")
                || combinedText.contains("full-pay")
                || combinedText.contains("full payment")) {
            return true;
        }

        if (combinedText.contains("checkpay")
                || combinedText.contains("check pay")
                || combinedText.contains("check payment")) {
            return true;
        }

        if (combinedText.contains("paritalpay")
                || combinedText.contains("partial pay")
                || combinedText.contains("partial payment")) {
            return true;
        }

        // Check for PAYMENT RECEIVED - THANK YOU patterns
        if (combinedText.contains("payment received")
                && (combinedText.contains("thank you") || combinedText.contains("thankyou"))) {
            return true;
        }

        return false;
    }

    /** Overloaded method for backward compatibility (description only) */
    private boolean isPaymentReceived(final String descriptionLower) {
        return isPaymentReceived(descriptionLower, null);
    }

    /**
     * Detects region from account information (currency, account type, etc.) Global Scale: Supports
     * region-specific category determination
     *
     * @param account Account information
     * @return Region code (US, UK, CA, AU, IN, EU, etc.)
     */
    private String detectRegion(final AccountTable account) {
        if (account == null) {
            return globalFinancialConfig.getDefaultRegion();
        }

        // Detect region from currency code
        final String currencyCode = account.getCurrencyCode();
        if (currencyCode != null) {
            final String currencyUpper = currencyCode.toUpperCase(Locale.ROOT);
            // Currency-based region detection
            if ("USD".equals(currencyUpper)) {
                return "US";
            }
            if ("GBP".equals(currencyUpper)) {
                return "UK";
            }
            if ("CAD".equals(currencyUpper)) {
                return "CA";
            }
            if ("AUD".equals(currencyUpper)) {
                return "AU";
            }
            if ("INR".equals(currencyUpper)) {
                return "IN";
            }
            if ("EUR".equals(currencyUpper)) {
                return "EU";
            }
            // Add more currency mappings as needed
        }

        // Detect region from account type (US-specific types)
        final String accountType = account.getAccountType();
        if (accountType != null) {
            final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);
            if (accountTypeLower.contains(N_401K)
                    || accountTypeLower.contains("403b")
                    || accountTypeLower.contains(IRA)
                    || accountTypeLower.contains("hsa")
                    || accountTypeLower.contains("529")) {
                return "US";
            }
            // Add more region-specific account type detection
        }

        // Default to US for backward compatibility
        return globalFinancialConfig.getDefaultRegion();
    }

    /*
     * Helper method to determine if importer category is clearly wrong compared to merchant
     * detection This prevents incorrect overrides when importer is actually correct
     */

    /*
     * Helper method to determine if importer category is high-confidence High-confidence categories
     * are from reliable sources (Plaid) or specific categories
     */

    /*
     * Detects category from merchant name and description patterns This runs BEFORE trusting
     * importer categories to fix incorrect categorizations
     *
     * @param merchantName Original merchant name
     * @param description Original description
     * @param merchantLower Lowercase merchant name
     * @param descLower Lowercase description
     * @param combinedText Combined merchant + description (lowercase)
     * @return Detected category or null if no match
     */

    /**
     * Extract MCC code from transaction description if available Some banks include MCC codes in
     * transaction descriptions
     */
    private CategoryResult alignCategoryToType(
            final CategoryResult result,
            final TransactionType transactionTypeHint,
            final AccountTable account,
            final BigDecimal amount,
            final String description) {
        if (result == null || transactionTypeHint == null || account == null) {
            return result;
        }

        final String primary = result.getCategoryPrimary();
        final String detailed = result.getCategoryDetailed();
        final String primaryLower = primary != null ? primary.toLowerCase(Locale.ROOT) : null;
        final String detailedLower = detailed != null ? detailed.toLowerCase(Locale.ROOT) : null;

        if (transactionTypeHint == TransactionType.INCOME) {
            if (isCheckingOrSavingsAccount(account)
                    && amount != null
                    && amount.compareTo(BigDecimal.ZERO) > 0
                    && TRANSFER.equals(primaryLower)) {
                return new CategoryResult(DEPOSIT, DEPOSIT, TYPE_ALIGN, result.getConfidence());
            }
            if (!isIncomeCategory(primaryLower, detailedLower)) {
                final String inferred = inferIncomeCategory(description, primaryLower);
                return new CategoryResult(inferred, inferred, TYPE_ALIGN, result.getConfidence());
            }
            return result;
        }

        if (transactionTypeHint == TransactionType.PAYMENT) {
            if (!isPaymentCategory(primaryLower, detailedLower)) {
                return new CategoryResult(PAYMENT, PAYMENT, TYPE_ALIGN, result.getConfidence());
            }
            return result;
        }

        if (transactionTypeHint == TransactionType.INVESTMENT) {
            if (!isInvestmentCategory(primaryLower, detailedLower)) {
                return new CategoryResult(
                        INVESTMENT, INVESTMENT, TYPE_ALIGN, result.getConfidence());
            }
            return result;
        }

        return result;
    }

    private boolean isIncomeCategory(final String primaryLower, final String detailedLower) {
        return isCategoryInList(primaryLower, INCOME_CATEGORIES)
                || isCategoryInList(detailedLower, INCOME_CATEGORIES);
    }

    private boolean isPaymentCategory(final String primaryLower, final String detailedLower) {
        return isCategoryInList(primaryLower, PAYMENT_CATEGORIES)
                || isCategoryInList(detailedLower, PAYMENT_CATEGORIES);
    }

    private boolean isInvestmentCategory(final String primaryLower, final String detailedLower) {
        return isCategoryInList(primaryLower, INVESTMENT_CATEGORIES)
                || isCategoryInList(detailedLower, INVESTMENT_CATEGORIES);
    }

    private boolean isExpenseCategory(final String primaryLower, final String detailedLower) {
        return isCategoryInList(primaryLower, EXPENSE_CATEGORIES)
                || isCategoryInList(detailedLower, EXPENSE_CATEGORIES);
    }

    private boolean isCategoryInList(final String category, final List<String> categories) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        return categories.contains(category);
    }

    private boolean isCheckingOrSavingsAccount(final AccountTable account) {
        if (account == null || account.getAccountType() == null) {
            return false;
        }
        final String accountTypeLower = account.getAccountType().toLowerCase(Locale.ROOT);
        return accountTypeLower.contains(CHECKING)
                || accountTypeLower.contains(DEPOSITORY)
                || accountTypeLower.contains(SAVINGS);
    }

    private String inferIncomeCategory(
            final String description, final String categoryPrimaryLower) {
        final String descLower = description != null ? description.toLowerCase(Locale.ROOT) : "";
        if (descLower.contains(SALARY)
                || descLower.contains("payroll")
                || descLower.contains("paycheck")
                || descLower.contains("wage")) {
            return SALARY;
        }
        if (descLower.contains(DIVIDEND)) {
            return DIVIDEND;
        }
        if (descLower.contains(INTEREST)) {
            return INTEREST;
        }
        if (descLower.contains(DEPOSIT)) {
            return DEPOSIT;
        }
        if (descLower.contains(CREDIT)) {
            return CREDIT;
        }
        if (categoryPrimaryLower != null && !categoryPrimaryLower.isEmpty()) {
            return categoryPrimaryLower;
        }
        return INCOME;
    }
}
