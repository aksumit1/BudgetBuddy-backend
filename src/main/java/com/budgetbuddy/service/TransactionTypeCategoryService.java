package com.budgetbuddy.service;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unified service for determining transaction types and categories across all sources
 * (Plaid, CSV/Excel/PDF Import, Manual)
 * 
 * This service implements hybrid logic combining:
 * - Account type information
 * - Importer-provided categories (Plaid categories, parsed categories from files)
 * - Transaction parsing logic (merchant name, description, amount, payment channel)
 * - ML-based category detection
 * - Rule-based reasoning
 * 
 * Architecture:
 * 1. For Plaid: Write to importerCategoryPrimary/importerCategoryDetailed, then apply hybrid logic to internal fields
 * 2. For Imports: Parse category, write to importerCategoryPrimary/importerCategoryDetailed, then apply hybrid logic
 * 3. Always use internal categoryPrimary/categoryDetailed for display
 * 4. User overrides always win (stored in internal fields with override flags)
 */
@Service
public class TransactionTypeCategoryService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionTypeCategoryService.class);

    private final TransactionTypeDeterminer transactionTypeDeterminer;
    private final PlaidCategoryMapper plaidCategoryMapper;
    private final ImportCategoryParser importCategoryParser;
    private final EnhancedCategoryDetectionService enhancedCategoryDetection;
    private final ImportCategoryConfig importCategoryConfig; // P2: Configuration for keywords
    private final GlobalFinancialConfig globalFinancialConfig; // Global scale: Region-specific config
    private final CircuitBreakerService circuitBreakerService; // P2: Circuit breaker for ML service
    private final com.budgetbuddy.service.category.InMemoryMerchantService merchantService; // Zero-cost merchant lookup
    private final CategoryLearningService learningService; // User corrections and custom mappings
    // Note: fuzzyMatchingService is used via enhancedCategoryDetection, not directly

    public TransactionTypeCategoryService(
            TransactionTypeDeterminer transactionTypeDeterminer,
            PlaidCategoryMapper plaidCategoryMapper,
            ImportCategoryParser importCategoryParser,
            EnhancedCategoryDetectionService enhancedCategoryDetection,
            ImportCategoryConfig importCategoryConfig,
            GlobalFinancialConfig globalFinancialConfig,
            CircuitBreakerService circuitBreakerService,
            com.budgetbuddy.service.category.InMemoryMerchantService merchantService,
            CategoryLearningService learningService) {
        this.transactionTypeDeterminer = transactionTypeDeterminer;
        this.plaidCategoryMapper = plaidCategoryMapper;
        this.importCategoryParser = importCategoryParser;
        this.enhancedCategoryDetection = enhancedCategoryDetection;
        this.importCategoryConfig = importCategoryConfig;
        this.globalFinancialConfig = globalFinancialConfig;
        this.circuitBreakerService = circuitBreakerService;
        this.merchantService = merchantService;
        this.learningService = learningService;
    }

    /**
     * Result of category determination
     */
    public static class CategoryResult {
        private final String categoryPrimary;
        private final String categoryDetailed;
        private final String source; // "PLAID", "IMPORT", "HYBRID", "ML", "RULE"
        private final double confidence; // 0.0 to 1.0

        public CategoryResult(String categoryPrimary, String categoryDetailed, String source, double confidence) {
            this.categoryPrimary = categoryPrimary;
            this.categoryDetailed = categoryDetailed;
            this.source = source;
            this.confidence = confidence;
        }

        public String getCategoryPrimary() { return categoryPrimary; }
        public String getCategoryDetailed() { return categoryDetailed; }
        public String getSource() { return source; }
        public double getConfidence() { return confidence; }
    }

    /**
     * Result of transaction type determination
     */
    public static class TypeResult {
        private final TransactionType transactionType;
        private final String source; // "ACCOUNT", "CATEGORY", "AMOUNT", "HYBRID"
        private final double confidence; // 0.0 to 1.0

        public TypeResult(TransactionType transactionType, String source, double confidence) {
            this.transactionType = transactionType;
            this.source = source;
            this.confidence = confidence;
        }

        public TransactionType getTransactionType() { return transactionType; }
        public String getSource() { return source; }
        public double getConfidence() { return confidence; }
    }

    /**
     * Determines transaction type from account type string (for PDF/CSV imports)
     * Used when account is not yet available but account type is known
     * 
     * @param accountType Account type string (e.g., "credit", "depository", "loan", "investment")
     * @param accountSubtype Account subtype string (e.g., "checking", "credit card")
     * @param amount Transaction amount
     * @param description Transaction description (optional, for logging)
     * @param paymentChannel Payment channel (optional, for logging)
     * @return TypeResult with transaction type, or null if cannot determine
     */
    public TypeResult determineTransactionTypeFromAccountType(
            String accountType,
            String accountSubtype,
            BigDecimal amount,
            String description,
            String paymentChannel) {
        
        
        // Edge case: Null account type or amount
        if (accountType == null || amount == null) {
            return null;
        }
        
        // Edge case: Zero amount - cannot determine type from account type alone
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            logger.debug("Transaction type determination: Zero amount transaction, cannot determine from account type alone");
            return null;
        }
        
        // Normalize account type string (trim, lowercase for matching)
        String normalizedAccountType = accountType.trim().toLowerCase();
        String normalizedAccountSubtype = accountSubtype != null ? accountSubtype.trim().toLowerCase() : null;
        AccountTypeInfo accountInfo = getAccountTypeInfoFromString(normalizedAccountType, normalizedAccountSubtype);
        String descLower = description != null ? description.trim().toLowerCase() : "";
        
        // Credit card accounts: 
        // +amount = EXPENSE (charge/purchase)
        // -amount = PAYMENT (payment to credit card - paying off debt)
        if (accountInfo.isCreditCard) {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                if (isPaymentReceived(descLower)) {
                    logger.debug("Transaction type determined from account type string: Loan account payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
                } else {
                    logger.debug("Transaction type determined from account type string: Credit card charge → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                }
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                logger.debug("Transaction type determined from account type string: Credit card payment (negative) → PAYMENT");
                return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
            }
        }
        
        // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.): 
        // +amount = INCOME (dividends, interest, distributions)
        // -amount = EXPENSE (fees) or INVESTMENT (purchases)
        // Use category/description to distinguish fees from purchases
        if (accountInfo.isInvestment) {
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined from account type string: Investment account positive amount → INCOME");
                return new TypeResult(TransactionType.INCOME, "ACCOUNT_TYPE", 0.95);
            } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                // Check if it's a fee or purchase (simplified - full logic in determineTransactionType)
                
                boolean isFee = descLower.contains("fee") || descLower.contains("charge") || 
                               descLower.contains("commission") || descLower.contains("tax") ||
                               descLower.contains("expense") || descLower.contains("custodial") ||
                               descLower.contains("maintenance") || descLower.contains("service charge") ||
                               descLower.contains("other") || descLower.contains("cost") || 
                               descLower.contains("sales load");

                if (isFee) {
                    logger.debug("Transaction type determined from account type string: Investment account fee → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                } else {
                    // Default to INVESTMENT for negative amounts (likely a purchase)
                    logger.debug("Transaction type determined from account type string: Investment account purchase → INVESTMENT");
                    return new TypeResult(TransactionType.INVESTMENT, "ACCOUNT_TYPE", 0.95);
                }
            }
        }
        
        // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
        // +amount = PAYMENT (if payment keywords) or CREDIT (disbursement)
        // -amount = EXPENSE (fees)
        if (accountInfo.isLoan) {
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                // Check if it's a payment using existing helper
                
                // Create a temporary account object for isLoanPayment check
                AccountTable tempAccount = new AccountTable();
                tempAccount.setAccountType(accountType);
                tempAccount.setAccountSubtype(accountSubtype);
                
                if (isPaymentReceived(descLower) || isLoanPayment(descLower, tempAccount)) {
                    logger.debug("Transaction type determined from account type string: Loan account payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
                } else {
                    // Positive without payment keywords = CREDIT (disbursement)
                    // Note: We use PAYMENT type but category will distinguish
                    logger.debug("Transaction type determined from account type string: Loan account credit/disbursement → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.90);
                }
            } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                logger.debug("Transaction type determined from account type string: Loan account fee/charge → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
            }
        }
        
        // Checking/Savings/Money Market accounts:
        // +amount (credit) = INCOME
        // -amount (debit) = EXPENSE
        if (accountInfo.isCheckingOrSavings) {
            // Positive amount (credit) on checking/savings = INCOME (always)
            // This includes: payroll deposits, rental income, transfers in, interest, etc.
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined from account type string: Credit on checking/savings account → INCOME");
                return new TypeResult(TransactionType.INCOME, "ACCOUNT_TYPE", 0.95);
            }
            // Negative amount (debit) on checking/savings = EXPENSE
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                if (isCreditCardPayment(description, null, null, null)) {
                    logger.debug("Transaction type determined from account type: Credit card payment on checking account → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
                }
                logger.debug("Transaction type determined from account type string: Debit on checking/savings account → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
            }
        }
        
        return null;
    }
    
    /**
     * Determines transaction type using hybrid logic (account type + parser logic)
     * 
     * Priority:
     * 1. Account type (investment/loan accounts)
     * 2. Category-based (income/investment categories)
     * 3. Transaction parsing logic (debit/credit indicators, amount sign)
     * 4. Default to EXPENSE
     * 
     * P1: Cached for performance - type determination involves multiple lookups
     */
    @Cacheable(value = "categoryDetermination", key = "'TYPE_' + (#account?.accountType ?: 'null') + '_' + (#categoryPrimary ?: 'null') + '_' + (#amount ?: 'null') + '_' + (#transactionTypeIndicator ?: 'null') + '_' + (#description ?: 'null')")
    public TypeResult determineTransactionType(
            AccountTable account,
            String categoryPrimary,
            String categoryDetailed,
            BigDecimal amount,
            String transactionTypeIndicator,
            String description,
            String paymentChannel) {
        
        String descLower = description != null ? description.toLowerCase() : "";
        // Priority 0: Account type-based intelligent inference (highest priority for account-specific logic)
        if (account != null && account.getAccountType() != null) {
            AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            
            // Credit card accounts: 
            // +amount = EXPENSE (charge/purchase)
            // -amount = PAYMENT (payment to credit card - paying off debt)
            // EXCEPTION: Dining transactions on credit cards should be EXPENSE, not PAYMENT (even if negative)
            // EXCEPTION: Wells Fargo credit card payments have negative amounts but should be PAYMENT type
            if (accountInfo.isCreditCard) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    String callerInfo = stackTrace.length > 2 ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber() : "unknown";
                    logger.debug("🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Positive amount on credit card → EXPENSE", 
                        callerInfo, amount, description != null ? description : "null", categoryPrimary != null ? categoryPrimary : "null", 
                        account != null ? account.getAccountName() : "null");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // CRITICAL FIX: Check if category is an expense category (dining, shopping, groceries, etc.)
                    // Expense categories on credit cards should be EXPENSE, not PAYMENT, even if negative
                    // This fixes: Lululemon purchases showing as PAYMENT instead of EXPENSE
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    String callerInfo = stackTrace.length > 2 ? stackTrace[2].getFileName() + ":" + stackTrace[2].getLineNumber() : "unknown";
                    
                    if (categoryPrimary != null) {
                        String categoryLower = categoryPrimary.toLowerCase();
                        // Expense categories that should always be EXPENSE, not PAYMENT
                        boolean isExpenseCategory = categoryLower.equals("dining") || 
                                                   categoryLower.equals("shopping") ||
                                                   categoryLower.equals("groceries") ||
                                                   categoryLower.equals("transportation") ||
                                                   categoryLower.equals("travel") ||
                                                   categoryLower.equals("entertainment") ||
                                                   categoryLower.equals("healthcare") ||
                                                   categoryLower.equals("education") ||
                                                   categoryLower.equals("utilities") ||
                                                   categoryLower.equals("subscriptions") ||
                                                   categoryLower.equals("pet") ||
                                                   categoryLower.equals("health") ||
                                                   categoryLower.equals("rent") ||
                                                   categoryLower.equals("charity") ||
                                                   categoryLower.equals("other");
                        
                        
                        boolean isPaymentKeyword = isPaymentReceived(descLower);
                        boolean isPaymentCategory = categoryPrimary.equalsIgnoreCase("payment");
                        
                        if (isPaymentKeyword || isPaymentCategory) {
                            logger.debug("🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Payment keyword/category detected → PAYMENT | isPaymentKeyword: {} | isPaymentCategory: {}", 
                                callerInfo, amount, description != null ? description : "null", categoryPrimary, 
                                account != null ? account.getAccountName() : "null", isPaymentKeyword, isPaymentCategory);
                            return new TypeResult(TransactionType.PAYMENT, "CATEGORY_OVERRIDE", 0.95);
                        }

                        if (isExpenseCategory) {
                            logger.debug("🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Expense category on credit card (negative amount) → EXPENSE (overriding default PAYMENT)", 
                                callerInfo, amount, description != null ? description : "null", categoryPrimary, 
                                account != null ? account.getAccountName() : "null");
                            return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
                        }
                        
                        logger.debug("🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Negative amount, non-expense category → PAYMENT", 
                            callerInfo, amount, description != null ? description : "null", categoryPrimary, 
                            account != null ? account.getAccountName() : "null");
                        return new TypeResult(TransactionType.PAYMENT, "CATEGORY_OVERRIDE", 0.95);

                    }

                    boolean isPaymentKeyword = isPaymentReceived(descLower);
                    if (isPaymentKeyword) {
                        logger.debug("🔍 [TransactionType] Credit card PAYMENT decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Payment keyword in description → PAYMENT", 
                            callerInfo, amount, description != null ? description : "null", categoryPrimary != null ? categoryPrimary : "null", 
                            account != null ? account.getAccountName() : "null");
                        return new TypeResult(TransactionType.PAYMENT, "CATEGORY_OVERRIDE", 0.95);
                    }
                    
                    // Default: negative amount on credit card without payment keywords = EXPENSE (likely a charge)
                    logger.debug("🔍 [TransactionType] Credit card EXPENSE decision | Line: {} | Amount: {} | Description: '{}' | Category: {} | Account: {} | Decision: Negative amount, no payment keywords, no category → EXPENSE (default for credit card charges)", 
                        callerInfo, amount, description != null ? description : "null", categoryPrimary != null ? categoryPrimary : "null", 
                        account != null ? account.getAccountName() : "null");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.90);
                }
            }
            
            // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.): 
            // +amount = INCOME (dividends, interest, distributions) OR INVESTMENT (transfers, deposits)
            // -amount = EXPENSE (fees, charges) or INVESTMENT (purchases of instruments)
            // Use category/description to distinguish fees from purchases, and transfers from income
            if (accountInfo.isInvestment) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    // Check if it's a transfer or deposit (should be INVESTMENT, not INCOME)
                    String categoryLower = categoryPrimary != null ? categoryPrimary.toLowerCase() : "";
                    
                    // Transfers, deposits, and investment-related transactions should be INVESTMENT
                    boolean isTransfer = descLower.contains("transfer") || descLower.contains("deposit") ||
                                        descLower.contains("contribution") || descLower.contains("from") ||
                                        categoryLower.contains("investment") || categoryLower.contains("transfer");
                    
                    if (isTransfer) {
                        logger.debug("Transaction type determined from account type: Investment account transfer/deposit → INVESTMENT");
                        return new TypeResult(TransactionType.INVESTMENT, "ACCOUNT_TYPE", 0.95);
                    }
                    
                    // Positive amounts on investment accounts = INCOME (dividends, interest, distributions)
                    logger.debug("Transaction type determined from account type: Investment account positive amount → INCOME");
                    return new TypeResult(TransactionType.INCOME, "ACCOUNT_TYPE", 0.95);
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // Negative amounts: Check if it's a fee or a purchase
                    
                    String categoryLower = categoryPrimary != null ? categoryPrimary.toLowerCase() : "";
                    
                    // Check for fee-related keywords
                    boolean isFee = descLower.contains("fee") || descLower.contains("charge") || 
                                    descLower.contains("commission") || descLower.contains("tax") ||
                                    descLower.contains("expense") || descLower.contains("custodial") ||
                                    descLower.contains("maintenance") || descLower.contains("service charge") ||
                                    descLower.contains("other") || descLower.contains("cost") || 
                                    descLower.contains("sales load");

                    
                    // Check for investment purchase keywords (stocks, bonds, mutual funds, etc.)
                    boolean isPurchase = descLower.contains("purchase") || descLower.contains("buy") ||
                                        descLower.contains("stock") || descLower.contains("bond") ||
                                        descLower.contains("mutual fund") || descLower.contains("etf") ||
                                        descLower.contains("401k") || descLower.contains("ira") ||
                                        descLower.contains("contribution") || descLower.contains("investment") ||
                                        categoryLower.contains("investment") || categoryLower.contains("stocks") ||
                                        categoryLower.contains("bonds") || categoryLower.contains("mutual");
                    
                    if (isFee) {
                        logger.debug("Transaction type determined from account type: Investment account fee (negative) → EXPENSE");
                        return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);                    
                    } else {
                        // Default to INVESTMENT for negative amounts (likely a purchase)
                        logger.debug("Transaction type determined from account type: Investment account negative amount → INVESTMENT (default)");
                        return new TypeResult(TransactionType.INVESTMENT, "ACCOUNT_TYPE", 0.90);
                    }
                }
            }
            
            // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
            // +amount = PAYMENT (if payment keywords present) or CREDIT (loan disbursement/increase)
            // -amount = EXPENSE (fees, charges)
            if (accountInfo.isLoan) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    // Positive amounts: Check if it's a payment or credit (disbursement)
                    String combinedText = descLower;
                    
                    // Use existing payment detection logic
                    if (isPaymentReceived(descLower) || isLoanPayment(combinedText, account)) {
                        logger.info("Transaction type determined from account type: Loan account payment (positive) → PAYMENT");
                        return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
                    } else {
                        // Positive amount without payment keywords = CREDIT (loan disbursement/increase)
                        // Note: CREDIT is not a separate type, but we'll use a category-based approach
                        // For now, treat as PAYMENT but category will distinguish
                        logger.info("Transaction type determined from account type: Loan account credit/disbursement (positive) → PAYMENT (category will distinguish)");
                        return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.85);
                    }
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // Negative amounts on loan accounts = EXPENSE (fees, charges)
                    logger.info("Transaction type determined from account type: Loan account fee/charge (negative) → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                }
                return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
            }
            
            // Checking/Savings/Money Market accounts:
            // +amount (credit) = INCOME
            // -amount (debit) = EXPENSE (unless it's a credit card payment, then LOAN)
            if (accountInfo.isCheckingOrSavings) {
                // Positive amount (credit) on checking/savings = INCOME (always)
                // This includes: payroll deposits, rental income, transfers in, interest, etc.
                if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    logger.debug("Transaction type determined from account type: Credit on checking/savings account → INCOME");
                    return new TypeResult(TransactionType.INCOME, "ACCOUNT_TYPE", 0.95);
                }
                // Negative amount (debit) on checking/savings = EXPENSE or PAYMENT
                // EXCEPTION: Credit card payments are PAYMENT (paying off debt), not EXPENSE
                // BUT: Transfers (PayPal, online transfers, etc.) are EXPENSE, not PAYMENT
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    // CRITICAL: Check for transfer category/keywords BEFORE credit card payment check
                    
                    // Check if this is a credit card payment before defaulting to EXPENSE
                    if (isCreditCardPayment(description, null, categoryPrimary, account)) {
                        logger.info("Transaction type determined from account type: Credit card payment on checking account → PAYMENT");
                        return new TypeResult(TransactionType.PAYMENT, "ACCOUNT_TYPE", 0.95);
                    } else if ("transfer".equalsIgnoreCase(categoryPrimary) ||
                        descLower.contains("transfer") || descLower.contains("paypal") ||
                        descLower.contains("inst xfer") || descLower.contains("xfer") ||
                        descLower.contains("online transfer")) {
                            logger.info("Transaction type determined from account type: Transfer on checking account → EXPENSE");
                        return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                    }
                    
                    logger.info("Transaction type determined from account type: Debit on checking/savings account → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                }
            }
        }

        // Priority 1: Category-based type determination
        if (categoryPrimary != null) {
            String categoryLower = categoryPrimary.toLowerCase();
            String categoryDetailedLower = categoryDetailed != null ? categoryDetailed.toLowerCase() : null;
            
            // CRITICAL FIX: Dining category should always be EXPENSE, not LOAN
            // This fixes cases like TST* DEEP DIVE which were incorrectly categorized as LOAN/utilities
            // Dining transactions are expenses, regardless of account type or amount sign
            if ("dining".equals(categoryLower)) {
                logger.debug("Transaction type determined: dining category → EXPENSE (overriding account-based logic)");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
            }
            
            // CRITICAL FIX: Payment category should only be PAYMENT if it's actually a payment
            // On checking accounts, "payment" with positive amount (credit) should be INCOME
            // "payment" with "utilities" detailed category should be EXPENSE
            // "transfer" category = EXPENSE (money out) or INCOME (money in based on amount)
            if ("transfer".equals(categoryLower)) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    logger.debug("Transaction type determined: transfer category with positive amount → INCOME");
                    return new TypeResult(TransactionType.INCOME, "TRANSFER_IN", 0.95);
                } else {
                    logger.debug("Transaction type determined: transfer category with negative amount → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "TRANSFER_OUT", 0.95);
                }
            }
            
            // "payment" for checks/transfers should be EXPENSE
            // Only actual payments (credit card, mortgage, student loan, etc.) should be PAYMENT
            if ("payment".equals(categoryLower)) {
                String combinedText = descLower; // merchantName not available in determineTransactionType
                
            // Check if it's a transfer (check, wire, PayPal, etc.) = EXPENSE (MUST check BEFORE account type)
            if (descLower != null && 
                (descLower.contains("check") || descLower.contains("wire") || 
                 descLower.contains("transfer") || descLower.contains("ach transfer") ||
                 descLower.contains("paypal") || descLower.contains("inst xfer") ||
                 descLower.contains("xfer"))) {
                logger.debug("Transaction type determined: payment/transfer → EXPENSE (overriding account type)");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
            }
            
            // Check if it's a checking/depository account with positive amount (credit)
            boolean isCheckingAccount = account != null && account.getAccountType() != null &&
                (account.getAccountType().toLowerCase().contains("checking") ||
                 account.getAccountType().toLowerCase().contains("depository") ||
                 account.getAccountType().toLowerCase().contains("savings"));

            boolean isCreditCardAccount = account != null && account.getAccountType() != null &&
                 (account.getAccountType().toLowerCase().contains("credit card") ||
                  account.getAccountType().toLowerCase().contains("creditcard") ||
                  account.getAccountType().toLowerCase().contains("credit"));
            
            // Positive amount on checking account = credit = INCOME
            if (isCheckingAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined: payment category on checking account with positive amount → INCOME");
                return new TypeResult(TransactionType.INCOME, "CATEGORY_OVERRIDE", 0.95);
            }

            if (isCreditCardAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined: payment category on credit card account with positive amount → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
            }
            
            // Utilities detailed category = EXPENSE
            if ("utilities".equals(categoryDetailedLower)) {
                logger.debug("Transaction type determined: payment/utilities → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
            }
                
                // CRITICAL: Only map to PAYMENT if it's actually a payment
                // Check for payment keywords: credit card, mortgage, student loan, car loan, personal loan, home loan, etc.
                if (isLoanPayment(combinedText, account)) {
                    logger.debug("Transaction type determined: payment category is actual payment → PAYMENT");
                    return new TypeResult(TransactionType.PAYMENT, "CATEGORY", 0.95);
                }
                
                // If it's not a payment and not on a loan account, default to EXPENSE
                // This handles generic "payment" categories that aren't payment-related
                boolean isLoanAccount = account != null && account.getAccountType() != null &&
                    isLoanAccountType(account.getAccountType());
                
                if (!isLoanAccount) {
                    logger.debug("Transaction type determined: payment category is not a payment → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
                }
                
                // If it's on a loan account, it's likely a payment
                logger.debug("Transaction type: payment category on loan account → PAYMENT");
                return new TypeResult(TransactionType.PAYMENT, "CATEGORY", 0.95);
            }
            
            // Deposit category with positive amount → INCOME type
            if ("deposit".equals(categoryLower) && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined from category: deposit → INCOME");
                return new TypeResult(TransactionType.INCOME, "CATEGORY", 0.95);
            }
            
            // Investment category → INVESTMENT type
            if ("investment".equals(categoryLower)) {
                logger.info("Transaction type determined from category: investment → INVESTMENT");
                return new TypeResult(TransactionType.INVESTMENT, "CATEGORY", 0.95);
            }
            
            // Income category → INCOME type
            if ("income".equals(categoryLower) || "salary".equals(categoryLower)) {
                logger.info("Transaction type determined from category: {} → INCOME", categoryLower);
                return new TypeResult(TransactionType.INCOME, "CATEGORY", 0.95);
            }
            
            // Utilities category → EXPENSE type (even if primary is "payment")
            if ("utilities".equals(categoryLower) || "utilities".equals(categoryDetailedLower)) {
                logger.info("Transaction type determined from category: utilities → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY", 0.95);
            }
        }

        // Priority 2: Check for actual payments (AUTOPAY/PAYMENT RECEIVED patterns)
        // CRITICAL: For credit card accounts, only actual payment patterns should be PAYMENT
        // All other positive amounts on credit cards are EXPENSE (charges)
        // This should be checked before debit/credit indicators to ensure payments are correctly identified
        
        String combinedText = descLower; // merchantName not available in determineTransactionType
        
        // Check if it's an actual payment (AUTOPAY or PAYMENT RECEIVED - THANK YOU)
        // This works for both credit card and loan accounts
        if (isPaymentReceived(descLower)) {
            logger.debug("Transaction type determined: Payment pattern detected (AUTOPAY/PAYMENT RECEIVED) → PAYMENT");
            return new TypeResult(TransactionType.PAYMENT, "HYBRID", 0.95);
        }
        
        // For loan accounts, check if it's a loan payment (but not credit card accounts)
        // Credit card accounts are already handled in Priority 0
        if (account != null && account.getAccountType() != null) {
            AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            if (accountInfo.isLoan && !accountInfo.isCreditCard && isLoanPayment(combinedText, account)) {
                logger.debug("Transaction type determined: Loan payment detected → PAYMENT");
                return new TypeResult(TransactionType.PAYMENT, "HYBRID", 0.95);
            }
        }
        
        // Use existing TransactionTypeDeterminer as base (account + category + amount)
        TransactionType baseType = transactionTypeDeterminer.determineTransactionType(
            account, categoryPrimary, categoryDetailed, amount);
        
        // CRITICAL FIX: Override PAYMENT type for checking accounts with positive amounts (credits)
        // Credits on checking accounts should always be INCOME, not PAYMENT
        // This handles cases like payroll deposits, rental income, transfers in, etc.
        if (baseType == TransactionType.PAYMENT && 
            account != null && account.getAccountType() != null) {
            String accountTypeLower = account.getAccountType().toLowerCase();
            boolean isCheckingAccount = accountTypeLower.contains("checking") ||
                                       accountTypeLower.contains("depository") ||
                                       accountTypeLower.contains("savings");
            
            if (isCheckingAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                // Positive amount (credit) on checking account should always be INCOME, not PAYMENT
                logger.info("🏷️ Overriding PAYMENT type to INCOME for credit on checking account: amount={}, accountType={}", 
                        amount, account.getAccountType());
                baseType = TransactionType.INCOME;
            }
        }
        
        // CRITICAL FIX: Override PAYMENT type for credit card accounts with positive amounts that are NOT payments
        // Credit card charges (positive amounts) should be EXPENSE, not PAYMENT
        // Only actual payments (AUTOPAY/PAYMENT RECEIVED) should be PAYMENT
        if (baseType == TransactionType.PAYMENT && 
            account != null && account.getAccountType() != null && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            if (accountInfo.isCreditCard) {
                // Check if it's actually a payment pattern (reuse descLower from earlier)
                if (!isPaymentReceived(descLower)) {
                    // Not a payment pattern, so it's a charge → EXPENSE
                    logger.info("🏷️ Overriding PAYMENT type to EXPENSE for credit card charge (not a payment): amount={}, description={}", 
                            amount, description);
                    baseType = TransactionType.EXPENSE;
                }
            }
        }
        
        // Priority 3: Enhance with transaction parsing logic (debit/credit indicators)
        if (transactionTypeIndicator != null && !transactionTypeIndicator.trim().isEmpty()) {
            String indicator = transactionTypeIndicator.trim().toLowerCase();
            
            // Debit indicator → EXPENSE (unless already determined as investment)
            if ((indicator.contains("debit") || indicator.contains("dr") || 
                 indicator.startsWith("db ") || indicator.startsWith("dr ") ||
                 indicator.equals("db") || indicator.equals("dr")) &&
                baseType != TransactionType.INVESTMENT) {
                logger.debug("Transaction type enhanced: Debit indicator → EXPENSE (was: {})", baseType);
                return new TypeResult(TransactionType.EXPENSE, "HYBRID", 0.95);
            }
            
            // Credit indicator → INCOME (unless already determined as investment)
            if ((indicator.contains("credit") || indicator.contains("cr") ||
                 indicator.startsWith("cr ") || indicator.equals("cr")) &&
                baseType != TransactionType.INVESTMENT) {
                logger.debug("Transaction type enhanced: Credit indicator → INCOME (was: {})", baseType);
                return new TypeResult(TransactionType.INCOME, "HYBRID", 0.95);
            }
        }
        
        // Return base type with appropriate source
        String source = account != null ? "ACCOUNT" : 
                       (categoryPrimary != null ? "CATEGORY" : 
                       (amount != null && amount.compareTo(BigDecimal.ZERO) > 0 ? "AMOUNT" : "DEFAULT"));
        double confidence = account != null ? 0.9 : 
                           (categoryPrimary != null ? 0.8 : 
                           (amount != null ? 0.7 : 0.5));
        
        return new TypeResult(baseType, source, confidence);
    }

    /**
     * Determines category using hybrid logic (importer category + parser + ML)
     * 
     * For Plaid: importerCategoryPrimary/importerCategoryDetailed are Plaid categories (raw)
     *            - First map Plaid categories using PlaidCategoryMapper
     *            - Then apply hybrid logic with parser + ML
     * For Imports: importerCategoryPrimary/importerCategoryDetailed are parsed categories
     * 
     * Logic:
     * 1. If Plaid: Map Plaid categories using PlaidCategoryMapper
     * 2. If importer categories exist, use them as base
     * 3. Apply import parser logic for additional signals
     * 4. Apply ML detection
     * 5. Reason with rules to determine best category
     * 6. Return best result with confidence
     * 
     * P1: Cached for performance - category determination is expensive (ML calls, parsing)
     * Global Scale: Supports region-specific category determination
     */
    @Cacheable(value = "categoryDetermination", key = "#importerCategoryPrimary + '_' + #importerCategoryDetailed + '_' + (#account?.accountType ?: 'null') + '_' + (#merchantName ?: 'null') + '_' + (#description ?: 'null') + '_' + (#amount ?: 'null') + '_' + (#paymentChannel ?: 'null') + '_' + (#transactionTypeIndicator ?: 'null') + '_' + (#importSource ?: 'null') + '_' + (#account?.currencyCode ?: 'null') + '_' + (#account?.userId ?: 'null')")
    public CategoryResult determineCategory(
            String importerCategoryPrimary,
            String importerCategoryDetailed,
            AccountTable account,
            String merchantName,
            String description,
            BigDecimal amount,
            String paymentChannel,
            String transactionTypeIndicator,
            String importSource) {
        
        logger.info("-----SUMIT PRIORITY  ENTERING DETERMINE CATEGORY--------");
        // Step 0: Check custom user mappings first (highest priority, user-defined)
        if (account != null && account.getUserId() != null && merchantName != null) {
            try {
                com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable customMapping = 
                    learningService.getCustomMapping(account.getUserId(), merchantName);
                if (customMapping != null) {
                    logger.debug("Using custom mapping for merchant '{}': {} / {}", 
                        merchantName, customMapping.getCategoryPrimary(), customMapping.getCategoryDetailed());
                    // Update usage count (async, best effort)
                    try {
                        customMapping.setUsageCount(customMapping.getUsageCount() + 1);
                        customMapping.setLastUsedAt(java.time.Instant.now());
                        // Note: This would require async update to avoid blocking
                    } catch (Exception e) {
                        logger.debug("Failed to update usage count: {}", e.getMessage());
                    }
                    return new CategoryResult(
                        customMapping.getCategoryPrimary(),
                        customMapping.getCategoryDetailed(),
                        "CUSTOM_MAPPING",
                        1.0 // 100% confidence for user-defined mappings
                    );
                }
            } catch (Exception e) {
                logger.debug("Error checking custom mapping: {}", e.getMessage());
                // Continue with other detection methods
            }
        }
        
        // Step 0.5: Check merchant database and MCC codes (zero-cost, high confidence)
        if (merchantName != null || description != null) {
            try {
                // Extract MCC code if available (some banks include it in description)
                String mccCode = extractMCCFromDescription(description);
                
                TransactionTypeCategoryService.CategoryResult merchantResult = merchantService.detectCategory(
                    merchantName, description, mccCode
                );
                
                // Confidence-based fallback chain:
                // - 95%+ confidence: Use immediately (exact matches, MCC codes)
                // - 90-95% confidence: Use if no better option (fuzzy matches)
                // - <90% confidence: Continue to next detection method
                if (merchantResult != null) {
                    double confidence = merchantResult.getConfidence();
                    
                    if (confidence >= 0.95) {
                        // High confidence - use immediately
                        logger.debug("Category detected from merchant database: {} (confidence: {:.2f}, source: {})", 
                            merchantResult.getCategoryPrimary(), confidence, merchantResult.getSource());
                        return merchantResult;
                    } else if (confidence >= 0.90) {
                        // Medium confidence - store for fallback, continue to check other methods
                        logger.debug("Category candidate from merchant database: {} (confidence: {:.2f}, source: {}), continuing to check other methods", 
                            merchantResult.getCategoryPrimary(), confidence, merchantResult.getSource());
                        // Store for later use in fallback chain (will be checked in reasonCategory)
                        // For now, continue to other detection methods
                    }
                    // <90% confidence: ignore and continue
                }
            } catch (Exception e) {
                logger.debug("Error in merchant service detection: {}", e.getMessage());
                // Continue with other detection methods
            }
        }
        
        // Step 1: For Plaid, map the raw Plaid categories using PlaidCategoryMapper
        String mappedImporterPrimary = importerCategoryPrimary;
        String mappedImporterDetailed = importerCategoryDetailed;
        
        if ("PLAID".equals(importSource) && (importerCategoryPrimary != null || importerCategoryDetailed != null)) {
            try {
                PlaidCategoryMapper.CategoryMapping plaidMapping = plaidCategoryMapper.mapPlaidCategory(
                    importerCategoryPrimary,
                    importerCategoryDetailed,
                    merchantName,
                    description,
                    paymentChannel,
                    amount
                );
                if (plaidMapping != null) {
                    mappedImporterPrimary = plaidMapping.getPrimary();
                    mappedImporterDetailed = plaidMapping.getDetailed();
                    logger.debug("Mapped Plaid categories: {} / {} → {} / {}", 
                        importerCategoryPrimary, importerCategoryDetailed,
                        mappedImporterPrimary, mappedImporterDetailed);
                }
            } catch (Exception e) {
                logger.warn("Failed to map Plaid categories: {}", e.getMessage());
                // Continue with raw categories
            }
        }
        
        // Step 2: Get parser category (from import parsing logic)
        // CRITICAL FIX: For CSV/Excel/PDF imports, we already have the parsed category in importerCategoryPrimary
        // Re-parsing is wasteful and causes duplicate processing. Use the already-parsed category instead.
        String parserCategory = null;
        if ("CSV".equals(importSource) || "EXCEL".equals(importSource) || "PDF".equals(importSource)) {
            // For imports, use the already-parsed category from importerCategoryPrimary
            // This avoids duplicate parsing and improves performance
            parserCategory = importerCategoryPrimary;
            logger.debug("Using already-parsed category for {} import: '{}'", importSource, parserCategory);
        } else {
            // For Plaid or other users, also get parser category as additional signal
            parserCategory = importCategoryParser.parseCategory(
                null, description, merchantName, amount, paymentChannel, transactionTypeIndicator);
        }
        
        // Step 3: Get ML category with context-aware matching (P2: with circuit breaker protection)
        
        String mlCategory = null;
        double mlConfidence = 0.0;
        try {
            // CRITICAL: Use context-aware detection (pass account type/subtype if available)
            String accountType = account != null ? account.getAccountType() : null;
            String accountSubtype = account != null ? account.getAccountSubtype() : null;
            
            EnhancedCategoryDetectionService.DetectionResult mlResult = circuitBreakerService.execute(
                "ML_CategoryDetection",
                () -> enhancedCategoryDetection.detectCategoryWithContext(
                    merchantName, description, amount, paymentChannel, null,
                    accountType, accountSubtype),  // Pass account context for improved accuracy
                null // Fallback: null if circuit is open
            );
            if (mlResult != null && mlResult.category != null) {
                mlCategory = mlResult.category;
                mlConfidence = mlResult.confidence;
            }
        } catch (Exception e) {
            logger.debug("ML category detection failed: {}", e.getMessage());
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
                logger.debug("Error checking medium-confidence merchant result: {}", e.getMessage());
            }
        }
        
        // Step 6: Reason with rules to determine best category
        return reasonCategory(
            mappedImporterPrimary, mappedImporterDetailed,
            parserCategory,
            mlCategory, mlConfidence,
            accountHint,
            merchantName, description, amount, paymentChannel, importSource, fallbackCategory, account,
            mediumConfidenceMerchantResult);
        */
        return new CategoryResult(mlCategory, mlCategory, "SUMIT_DRIVE", 0.95);
    }
    
    /**
     * Applies iOS fallback logic for ACH credits, interest payments, and income category determination.
     * ✅ COMPLETED: iOS fallback logic is now fully consolidated in backend (no longer in iOS)
     * 
     * Logic:
     * 1. ACH credits (positive amounts with paymentChannel == "ach") should be income/deposit
     * 2. Interest payment detection (handles misspellings like "INTRST", "INTR", etc.)
     * 3. Income category determination from description
     * 
     * @return Fallback category if detected, null otherwise
     */
    private String applyIOSFallbackLogic(
            String importerPrimary, String importerDetailed,
            String parserCategory,
            String merchantName, String description, BigDecimal amount, String paymentChannel) {
        
        if (amount == null) {
            return null;
        }
        
        String descriptionLower = description != null ? description.toLowerCase() : "";
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String combinedText = (merchantLower + " " + descriptionLower).trim();
        
        // Rule 1: ACH credits (with paymentChannel == "ach") should be income/deposit
        // This overrides any category that might have been incorrectly assigned
        // CRITICAL: Check for ACH debit patterns first to avoid false positives
        // For channel-based detection without explicit credit/debit keywords, check amount sign
        // After normalization: positive = income (credit), negative = expense (debit)
        boolean isACHDebitPattern = (descriptionLower.contains("ach electronic debit") || 
                                     descriptionLower.contains("ach debit") ||
                                     (descriptionLower.contains("ach") && descriptionLower.contains("debit")));
        
        // ACH credit by channel: paymentChannel is "ach" AND not an ACH debit pattern AND positive amount
        // Note: Amount check is needed here because description may not have explicit "credit" keyword
        // but description-based detection (with "ACH Electronic Credit") doesn't need amount check
        if ("ach".equalsIgnoreCase(paymentChannel) && !isACHDebitPattern && 
            amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // Check if we already have a specific income category
            boolean hasSpecificIncomeCategory = isSpecificIncomeCategory(importerPrimary, importerDetailed, parserCategory);
            
            if (!hasSpecificIncomeCategory) {
                // Determine income category from description
                String incomeCategory = determineIncomeCategoryFromDescription(descriptionLower, merchantLower);
                
                if ("interest".equals(incomeCategory)) {
                    return "interest";
                } else if ("dividend".equals(incomeCategory) || "salary".equals(incomeCategory) || 
                          "stipend".equals(incomeCategory) || "rentincome".equals(incomeCategory) ||
                          "tips".equals(incomeCategory) || "otherincome".equals(incomeCategory)) {
                    return incomeCategory;
                } else {
                    // Default to "deposit" for ACH credits when we can't determine the specific type
                    return "deposit";
                }
            }
        }
        
        // Rule 2: Interest payment detection (handles misspellings)
        // Check for interest keywords in description (including misspellings)
        String[] interestKeywords = {
            "interest", "intrst", "intr ", "intrest", "intr payment", 
            "intrst payment", "intrst pymnt", "intr pymnt"
        };
        
        boolean isInterest = false;
        for (String keyword : interestKeywords) {
            if (descriptionLower.contains(keyword) || combinedText.contains(keyword)) {
                // Exclude CD interest (handled separately as investment)
                if (!descriptionLower.contains("cd interest") && 
                    !descriptionLower.contains("certificate") &&
                    !combinedText.contains("cd interest") &&
                    !combinedText.contains("certificate")) {
                    isInterest = true;
                    break;
                }
            }
        }
        
        if (isInterest) {
            // Override category to "interest" if it's currently "other" or generic "income"
            if ("other".equalsIgnoreCase(importerPrimary) || 
                "other".equalsIgnoreCase(parserCategory) ||
                ("income".equalsIgnoreCase(importerPrimary) && !isSpecificIncomeCategory(importerPrimary, importerDetailed, parserCategory))) {
                logger.debug("iOS fallback: Overrode category to 'interest' for transaction with description: {}", description);
                return "interest";
            }
        }
        
        return null; // No fallback category detected
    }
    
    /**
     * Checks if the category is a specific income category (not generic "income")
     */
    private boolean isSpecificIncomeCategory(String primary, String detailed, String parserCategory) {
        String[] specificIncomeCategories = {
            "deposit", "interest", "dividend", "salary", "stipend", 
            "rentincome", "tips", "otherincome", "payroll"
        };
        
        String primaryLower = primary != null ? primary.toLowerCase() : "";
        String detailedLower = detailed != null ? detailed.toLowerCase() : "";
        String parserLower = parserCategory != null ? parserCategory.toLowerCase() : "";
        
        for (String category : specificIncomeCategories) {
            if (primaryLower.equals(category) || detailedLower.equals(category) || parserLower.equals(category)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Determines income category from description and merchant name.
     * ✅ COMPLETED: Logic consolidated from iOS BackendModels.determineIncomeCategoryFromDescription()
     */
    private String determineIncomeCategoryFromDescription(String descriptionLower, String merchantLower) {
        String combinedText = (merchantLower + " " + descriptionLower).trim().toLowerCase();
        
        // Salary/Payroll
        if (combinedText.contains("salary") || combinedText.contains("payroll") || 
            combinedText.contains("paycheck") || combinedText.contains("direct deposit") ||
            combinedText.contains("wage") || combinedText.contains("compensation")) {
            return "salary";
        }
        
        // Interest (with misspelling handling)
        String[] interestKeywords = {
            "interest", "intrst", "intr ", "intrest", "intr payment", 
            "intrst payment", "intrst pymnt", "savings interest", "bank interest"
        };
        for (String keyword : interestKeywords) {
            if (combinedText.contains(keyword) && 
                !combinedText.contains("cd interest") && 
                !combinedText.contains("certificate")) {
                return "interest";
            }
        }
        
        // Dividend
        if (combinedText.contains("dividend") || combinedText.contains("div ")) {
            return "dividend";
        }
        
        // Stipend
        if (combinedText.contains("stipend")) {
            return "stipend";
        }
        
        // Rental Income
        if (combinedText.contains("rent income") || combinedText.contains("rental income") ||
            combinedText.contains("rent payment")) {
            return "rentincome";
        }
        
        // Tips
        if (combinedText.contains("tip") && !combinedText.contains("tip jar")) {
            return "tips";
        }
        
        // Default to generic "income" (caller should use "deposit" for ACH credits)
        return "income";
    }

    /**
     * Reasons about which category is best using rules
     */
    private CategoryResult reasonCategory(
            String importerPrimary, String importerDetailed,
            String parserCategory,
            String mlCategory, double mlConfidence,
            String accountHint,
            String merchantName, String description, BigDecimal amount,
            String paymentChannel, String importSource, String fallbackCategory, AccountTable account,
            CategoryResult mediumConfidenceMerchantResult) {
        
        // Rule 0: If iOS fallback logic detected a category, use it (high priority)
        if (fallbackCategory != null && !fallbackCategory.isEmpty()) {
            logger.debug("Using iOS fallback category: {}", fallbackCategory);
            return new CategoryResult(fallbackCategory, fallbackCategory, "IOS_FALLBACK", 0.9);
        }
        
        // CRITICAL FIX: Check for travel/transportation FIRST to prevent false credit card credit matches
        // This fixes: Delta Airlines showing as "credit" instead of "travel" on credit card accounts
        String descLower = description != null ? description.toLowerCase() : "";
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String combinedText = (merchantLower + " " + descLower).trim();
        
        // CRITICAL: Check for financial/banking terms FIRST
        // If financial terms are present and importer says "other", trust the importer category
        // Terms like "PROMOTIONAL APR", "OFFER", "ENDED" indicate account/financial transactions, not travel
        // BUT: "Amex Offer Credit", "Walmart Offer Credit" etc. are merchant credits, NOT pure financial transactions
        // So check for merchant names BEFORE flagging as financial transaction
        String[] financialTerms = {"promotional", "apr", "interest rate", "interestrate", "annual percentage",
                                   "ended", "credit card", "creditcard", "statement", "balance",
                                   "payment due", "paymentdue", "minimum payment", "minimumpayment",
                                   "cashback", "cash back", "rewards", "points", "bonus"};
        boolean isFinancialTransaction = false;
        
        // Check for "offer" BUT only if it's not a merchant offer credit (e.g., "Amex Offer Credit" for Estee Lauder)
        boolean hasOfferKeyword = combinedText.contains("offer");
        boolean isMerchantOfferCredit = false;
        if (hasOfferKeyword) {
            // Check if "offer" appears with merchant names (indicates merchant-specific offer, not pure financial)
            String[] merchantIndicators = {"amex offer", "amexoffer", "walmart", "target", "costco", "estee", "lauder",
                                          "tyrwhitt", "charles", "lululemon", "nordstrom", "macy", "sephora", "shirt"};
            for (String merchant : merchantIndicators) {
                if (combinedText.contains(merchant)) {
                    isMerchantOfferCredit = true;
                    break;
                }
            }
        }
        
        // Only flag as financial transaction if "offer" is present AND it's NOT a merchant offer credit
        for (String term : financialTerms) {
            if (combinedText.contains(term)) {
                isFinancialTransaction = true;
                break;
            }
        }
        if (hasOfferKeyword && !isMerchantOfferCredit) {
            isFinancialTransaction = true;
        }
        
        // If it's a financial transaction and importer says "other", trust the importer category
        // BUT: Allow merchant detection to run first to catch merchant offer credits
        if (isFinancialTransaction && "other".equalsIgnoreCase(importerPrimary) && !isMerchantOfferCredit) {
            logger.info("🏷️ reasonCategory: Financial transaction with importer category 'other' → trusting importer category");
            return new CategoryResult("other", "other", "IMPORTER_CATEGORY", 0.95);
        }
        
        // CRITICAL FIX: Merchant/description-based category detection (runs BEFORE trusting importer category)
        // This fixes cases where importer category is wrong (e.g., "utilities" for TST* DEEP DIVE)
        // BUT: Only override if importer category is generic/unreliable OR merchant detection is clearly better
        // Skip merchant detection for pure financial transactions (to avoid false positives)
        // CRITICAL: Run merchant detection for ALL amounts (positive and negative) to ensure credits match expense categories
        // CRITICAL: ALWAYS run merchant detection for merchant offer credits (Amex Offer Credit for Estee Lauder, etc.)
        String merchantBasedCategory = null;
        if (!isFinancialTransaction || isMerchantOfferCredit) {
            merchantBasedCategory = detectCategoryFromMerchantAndDescription(merchantName, description, merchantLower, descLower, combinedText);
            if (merchantBasedCategory != null) {
                // CRITICAL: Only override importer category if:
                // 1. Importer is generic/unreliable ("other", "UNKNOWN_CATEGORY", null) - always override
                // 2. Importer matches merchant detection - redundant but OK, use merchant
                // 3. Importer is clearly wrong and merchant is clearly right (e.g., "utilities" for "dining")
                // 4. Importer is from non-Plaid source (CSV/PDF) and might be less reliable
                boolean shouldOverride = false;
                String overrideReason = null;
                
                // Case 1: Importer is generic/unreliable - always override
                if (importerPrimary == null || importerPrimary.isEmpty() ||
                    "other".equalsIgnoreCase(importerPrimary) || 
                    "UNKNOWN_CATEGORY".equals(importerPrimary)) {
                    shouldOverride = true;
                    overrideReason = "importer category is generic/unreliable";
                }
                // Case 2: Importer matches merchant - redundant but OK, use merchant detection
                else if (merchantBasedCategory.equalsIgnoreCase(importerPrimary)) {
                    shouldOverride = true;
                    overrideReason = "merchant detection confirms importer category";
                }
                // Case 3: Importer is clearly wrong - override for known mismatches
                // E.g., "utilities" for TST* DEEP DIVE (restaurant) -> should be "dining"
                else if (isImporterCategoryClearlyWrong(importerPrimary, merchantBasedCategory, combinedText)) {
                    shouldOverride = true;
                    overrideReason = "importer category is clearly wrong";
                }
                // Case 4: Non-Plaid source (CSV/PDF) - merchant detection may be more reliable
                // For PDF/CSV sources, importer categories are parsed and may be incorrect
                // Override if merchant detection is clear and specific (not "other")
                // AND importer category seems wrong or is generic
                else if (!"PLAID".equals(importSource) && 
                        !merchantBasedCategory.equalsIgnoreCase("other") &&
                        (isImporterCategoryClearlyWrong(importerPrimary, merchantBasedCategory, combinedText) ||
                         !isHighConfidenceCategory(importerPrimary))) {
                    shouldOverride = true;
                    overrideReason = "non-Plaid source with potentially incorrect importer category, merchant detection is more reliable";
                }
                
                if (shouldOverride) {
                    logger.info("🏷️ reasonCategory: Merchant/description-based detection → '{}' (overriding importer category '{}' because {})", 
                        merchantBasedCategory, importerPrimary, overrideReason);
                    return new CategoryResult(merchantBasedCategory, merchantBasedCategory, "MERCHANT_DETECTION", 0.95);
                } else {
                    // Don't override - trust importer category
                    logger.debug("🏷️ reasonCategory: Merchant detection found '{}' but trusting importer category '{}' (high confidence)", 
                        merchantBasedCategory, importerPrimary);
                }
            }
        }
        
        // If it's a financial transaction (but importer didn't say "other"), still skip travel detection
        
        // Check for travel/transportation keywords (airlines, hotels, etc.)
        // CRITICAL: Make "united" check more specific to avoid matching "Remitly United" or other non-airline uses
        // Only match "united" if it's followed by airline keywords or appears in an airline context
        boolean isUnitedAirlines = (combinedText.contains("united airlines") || 
                                    combinedText.contains("unitedairlines") ||
                                    (combinedText.contains("united ") && 
                                     (combinedText.contains("flight") || combinedText.contains("airline") ||
                                      combinedText.contains("airlines") || combinedText.contains("airport") ||
                                      combinedText.contains("mileageplus") || combinedText.contains("mileage plus"))) ||
                                    (merchantLower != null && merchantLower.contains("united") && 
                                     (merchantLower.contains("airline") || merchantLower.contains("airlines"))));
        
        // Only check for travel if it's not a financial transaction
        boolean isTravel = !isFinancialTransaction && (
                          combinedText.contains("airline") || combinedText.contains("airlines") ||
                          combinedText.contains("delta") || isUnitedAirlines ||
                          combinedText.contains("american airlines") || combinedText.contains("southwest") ||
                          combinedText.contains("jetblue") || combinedText.contains("alaska") ||
                          combinedText.contains("hotel") || combinedText.contains("marriott") ||
                          combinedText.contains("hilton") || combinedText.contains("hyatt") ||
                          combinedText.contains("airbnb") || combinedText.contains("travel") ||
                          merchantLower.contains("airline") || merchantLower.contains("airlines") ||
                          merchantLower.contains("delta"));
        
        // Check for transportation keywords (ticket machines, transit, etc.)
        // CRITICAL FIX: "lul" must be checked with word boundaries to avoid matching "lululemon"
        // Only match if "lul" is followed by space/punctuation OR preceded/followed by "underground" (London Underground Line)
        boolean isTransportation = combinedText.contains("ticket machine") || combinedText.contains("ticketmachine") ||
                                  (combinedText.contains("lul ") || combinedText.contains(" lul") || 
                                   combinedText.contains("london underground") || combinedText.contains("lul underground") ||
                                   combinedText.contains("underground line") || combinedText.contains("lul line")) ||
                                  combinedText.contains("metro") ||
                                  combinedText.contains("transit") || combinedText.contains("subway") ||
                                  merchantLower.contains("ticket machine") || 
                                  (merchantLower.contains("lul ") || merchantLower.contains(" lul"));
        
        // If it's travel or transportation, return immediately (before credit card credit check)
        if (isTravel || isTransportation) {
            String travelCategory = isTravel ? "travel" : "transportation";
            logger.info("🏷️ reasonCategory: Detected travel/transportation transaction → '{}' (overriding credit card credit logic)", travelCategory);
            return new CategoryResult(travelCategory, travelCategory, "RULE_OVERRIDE", 0.95);
        }
        
        // CRITICAL: For credit card and loan accounts with positive amounts
        // If it's a payment (AUTOPAY/PAYMENT RECEIVED), category should be "payment"
        // Otherwise, category should be "credit" (not an expense category, but a credit to the account)
        // Also check payment patterns even when account is null (for PDF imports)
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // First check: If description/merchant contains payment received patterns, it's always a payment
            // This works even when account is null (e.g., during PDF import)
            if (isPaymentReceived(descLower, merchantLower)) {
                logger.debug("Category determined: Payment received pattern detected → payment");
                return new CategoryResult("payment", "payment", "RULE_OVERRIDE", 0.95);
            }
            
            // CRITICAL FIX: Check if importer/parser detected a specific category BEFORE defaulting to "credit"
            // If a specific category is detected (like "groceries", "subscriptions", etc.), use it
            // This fixes: Costco credits showing as "credit" instead of "groceries" when importer detects groceries
            // CRITICAL: Barrons is a financial education/investment publication, NOT a subscription
            String specificCategory = null;
            
            // CRITICAL: Override "subscriptions" to "education" for Barrons BEFORE using importer category
            boolean isBarrons = combinedText.contains("barrons") || combinedText.contains("barron");
            if (isBarrons && "subscriptions".equalsIgnoreCase(importerPrimary)) {
                specificCategory = "education";
                logger.info("🏷️ reasonCategory: Barrons detected with importer category 'subscriptions' → overriding to 'education'");
            } else if (importerPrimary != null && !importerPrimary.isEmpty() && 
                !"credit".equalsIgnoreCase(importerPrimary) && 
                !"payment".equalsIgnoreCase(importerPrimary) && 
                !"other".equalsIgnoreCase(importerPrimary) &&
                !"UNKNOWN_CATEGORY".equals(importerPrimary)) {
                specificCategory = importerPrimary;
                logger.info("🏷️ reasonCategory: Positive amount on credit card with importer category '{}' → using importer category instead of 'credit'", specificCategory);
            } else if (parserCategory != null && !parserCategory.isEmpty() && 
                       !"credit".equalsIgnoreCase(parserCategory) && 
                       !"payment".equalsIgnoreCase(parserCategory) && 
                       !"other".equalsIgnoreCase(parserCategory)) {
                // Also override parser category if it's "subscriptions" for Barrons
                if (isBarrons && "subscriptions".equalsIgnoreCase(parserCategory)) {
                    specificCategory = "education";
                    logger.info("🏷️ reasonCategory: Barrons detected with parser category 'subscriptions' → overriding to 'education'");
                } else {
                    specificCategory = parserCategory;
                    logger.info("🏷️ reasonCategory: Positive amount on credit card with parser category '{}' → using parser category instead of 'credit'", specificCategory);
                }
            } else if (mlCategory != null && !mlCategory.isEmpty() && mlConfidence > 0.7 &&
                       !"credit".equalsIgnoreCase(mlCategory) && 
                       !"payment".equalsIgnoreCase(mlCategory) && 
                       !"other".equalsIgnoreCase(mlCategory)) {
                // Also override ML category if it's "subscriptions" for Barrons
                if (isBarrons && "subscriptions".equalsIgnoreCase(mlCategory)) {
                    specificCategory = "education";
                    logger.info("🏷️ reasonCategory: Barrons detected with ML category 'subscriptions' → overriding to 'education'");
                } else {
                    specificCategory = mlCategory;
                    logger.info("🏷️ reasonCategory: Positive amount on credit card with ML category '{}' → using ML category instead of 'credit'", specificCategory);
                }
            }
            
            // If we found a specific category from importer/parser/ML, use it
            if (specificCategory != null) {
                String detailedCategory = importerDetailed != null && !importerDetailed.isEmpty() ? 
                                         importerDetailed : specificCategory;
                return new CategoryResult(specificCategory, detailedCategory, "IMPORTER_CATEGORY", 0.95);
            }
            
            // CRITICAL FIX: Check for known merchants by category in description (for ALL positive amounts, not just refunds)
            // This helps match categories even when importer category isn't available
            // This ensures Costco, Walmart, etc. are categorized correctly even without explicit "credit" or "refund" keywords
            if (specificCategory == null) {
                // CRITICAL: Barrons is a financial education/investment publication, NOT a subscription
                // Check for Barrons (should already be handled above, but check again as fallback)
                if (isBarrons) {
                    specificCategory = "education"; // Financial education publication
                    logger.info("🏷️ reasonCategory: Barrons detected → using 'education' instead of 'subscriptions' or 'credit'");
                }
                
                // Note: Subscription merchant detection removed per user request - will be handled differently later
                
                // Check for shopping merchants (Charles Tyrwhitt, Estee Lauder, etc.) and clothing keywords
                if (specificCategory == null) {
                    // Clothing keywords (shirts, trousers, dresses, suits, clothing)
                    if (combinedText.contains("shirt") || combinedText.contains("trouser") || 
                        combinedText.contains("dress") || combinedText.contains("suit") ||
                        combinedText.contains("clothing") || combinedText.contains("apparel")) {
                        specificCategory = "shopping";
                        logger.info("🏷️ reasonCategory: Positive amount on credit card with clothing keywords → using 'shopping' instead of 'credit'");
                    }
                    
                    // Shopping stores and brands
                    if (specificCategory == null) {
                        String[] shoppingMerchants = {"charles tyrwhitt", "tyrwhitt", "estee lauder", "estee",
                                                      "sephora", "ulta", "nordstrom", "macy's", "macys",
                                                      "lululemon", "best buy", "bestbuy"};
                        for (String merchant : shoppingMerchants) {
                            if (combinedText.contains(merchant)) {
                                specificCategory = "shopping";
                                logger.info("🏷️ reasonCategory: Positive amount on credit card with shopping merchant '{}' → using 'shopping' instead of 'credit'", merchant);
                                break;
                            }
                        }
                    }
                }
                
                // Check for grocery merchants (Walmart, Target, Kroger, etc.)
                if (specificCategory == null) {
                    String[] groceryMerchants = {"walmart", "wmt", "target", "kroger", "safeway", 
                                                  "whole foods", "costco", "aldi", "trader joe", 
                                                  "publix", "wegmans", "giant", "stop & shop",
                                                  "food lion", "harris teeter", "fred meyer", 
                                                  "fredmeyer", "fred-meyer", "ralphs", "vons", 
                                                  "smiths", "king soopers", "qfc", "fry's", "frys"};
                    for (String merchant : groceryMerchants) {
                        if (combinedText.contains(merchant)) {
                            specificCategory = "groceries";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with grocery merchant '{}' → using 'groceries' instead of 'credit'", merchant);
                            break;
                        }
                    }
                }
                
                // Check for dining/restaurant merchants (Starbucks, McDonald's, etc.)
                if (specificCategory == null) {
                    String[] diningMerchants = {"starbucks", "mcdonald", "subway", "pizza hut", 
                                                 "domino", "kfc", "burger king", "taco bell",
                                                 "chipotle", "panera", "olive garden", "applebee",
                                                 "dunkin", "wendy", "arby"};
                    for (String merchant : diningMerchants) {
                        if (combinedText.contains(merchant)) {
                            specificCategory = "dining";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with dining merchant '{}' → using 'dining' instead of 'credit'", merchant);
                            break;
                        }
                    }
                }
                
                // Check for gas station merchants (Shell, Chevron, Exxon, etc.)
                if (specificCategory == null) {
                    String[] gasMerchants = {"shell", "chevron", "exxon", "bp", "mobil", 
                                              "speedway", "valero", "citgo", "phillips 66",
                                              "arco", "marathon", "sunoco", "conoco",
                                              "76 station", "76 gas", "union 76"};
                    for (String merchant : gasMerchants) {
                        if (combinedText.contains(merchant) && 
                            (combinedText.contains("gas") || combinedText.contains("station") || 
                             combinedText.contains("fuel") || merchant.contains("76"))) {
                            specificCategory = "transportation";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with gas station merchant '{}' → using 'transportation' instead of 'credit'", merchant);
                            break;
                        }
                    }
                }
                
                // Check for travel merchants (airlines, hotels, airport lounges)
                if (specificCategory == null) {
                    // First check for airport lounges (Centurion Lounge, Priority Pass, Admirals Club, etc.)
                    String[] airportLounges = {"centurion lounge", "centurionlounge", "axp centurion", "axp centurion lounge",
                                               "priority pass", "prioritypass",
                                               "admirals club", "admiralsclub",
                                               "delta sky club", "deltaskyclub",
                                               "united club", "unitedclub",
                                               "american express lounge", "amex lounge",
                                               "plaza premium lounge", "plazapremiumlounge",
                                               "airport lounge", "airportlounge",
                                               "encalm lounge", "encalmlounge", "encalm"};
                    for (String lounge : airportLounges) {
                        if (combinedText.contains(lounge)) {
                            specificCategory = "travel";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with airport lounge '{}' → using 'travel' instead of 'credit'", lounge);
                            break;
                        }
                    }
                }
                
                // Check for airlines and hotels
                if (specificCategory == null) {
                    // CRITICAL: Make "united" check more specific to avoid matching "Remitly United" or other non-airline uses
                    boolean isUnitedAirlinesForCredit = combinedText.contains("united airlines") || 
                                                        combinedText.contains("unitedairlines") ||
                                                        (combinedText.contains("united ") && 
                                                         (combinedText.contains("flight") || combinedText.contains("airline") ||
                                                          combinedText.contains("airlines") || combinedText.contains("airport") ||
                                                          combinedText.contains("mileageplus") || combinedText.contains("mileage plus")));
                    
                    String[] travelMerchants = {"delta", "american airlines", "southwest",
                                                 "jetblue", "alaska", "spirit", "frontier",
                                                 "allegiant", "hawaiian", "hotel", "marriott", 
                                                 "hilton", "hyatt", "holiday inn", "holidayinn",
                                                 "airbnb", "expedia", "booking.com",
                                                 "travelocity", "priceline", "airline", "airlines",
                                                 "motel", "resort", "inn"};
                    for (String merchant : travelMerchants) {
                        if (combinedText.contains(merchant)) {
                            specificCategory = "travel";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with travel merchant '{}' → using 'travel' instead of 'credit'", merchant);
                            break;
                        }
                    }
                    // Check United Airlines separately with specific matching
                    if (specificCategory == null && isUnitedAirlinesForCredit) {
                        specificCategory = "travel";
                        logger.info("🏷️ reasonCategory: Positive amount on credit card with United Airlines → using 'travel' instead of 'credit'");
                    }
                }
                
                // Check for education/exam merchants (VUE, AAMC, SAT, TOEFL, GRE, GMAT, LSAT, MCAT, etc.)
                if (specificCategory == null) {
                    // First check for VUE (Pearson VUE) with exam keywords
                    if (combinedText.contains("vue") && 
                        (combinedText.contains("exam") || combinedText.contains("test") || 
                         combinedText.contains("aamc") || combinedText.contains("sat") || 
                         combinedText.contains("toefl") || combinedText.contains("gre") || 
                         combinedText.contains("gmat") || combinedText.contains("lsat") || 
                         combinedText.contains("mcat") || combinedText.contains("act"))) {
                        specificCategory = "education";
                        logger.info("🏷️ reasonCategory: Positive amount on credit card with VUE exam/testing → using 'education' instead of 'credit'");
                    } else {
                        // Check for exam/testing keywords (AAMC, SAT, TOEFL, GRE, GMAT, LSAT, MCAT, etc.)
                        String[] examKeywords = {"aamc", "sat", "toefl", "gre", "gmat", "lsat", "mcat", 
                                                 "act", "ap exam", "ib exam", "clep", "praxis", "bar exam",
                                                 "nclex", "usmle", "comlex", "test registration",
                                                 "test fee", "test center", "pearson vue", "ets", "prometric"};
                        for (String exam : examKeywords) {
                            if (combinedText.contains(exam)) {
                                specificCategory = "education";
                                logger.info("🏷️ reasonCategory: Positive amount on credit card with exam keyword '{}' → using 'education' instead of 'credit'", exam);
                                break;
                            }
                        }
                    }
                }
                
                // Check for education merchants (school, university, college, bookstore, etc.)
                if (specificCategory == null) {
                    // Regional school/college names (Indian, Spanish, French, German, Arabic, etc.)
                    String[] regionalSchoolTerms = {"gurukul", "vidyalaya", "shiksha", "pathshala",
                                                    "escuela", "colegio", "universidad",
                                                    "école", "collège", "université",
                                                    "schule", "universität",
                                                    "madrasa", "kuttab", "madrassa"};
                    for (String term : regionalSchoolTerms) {
                        if (combinedText.contains(term)) {
                            specificCategory = "education";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with regional school term '{}' → using 'education' instead of 'credit'", term);
                            break;
                        }
                    }
                }
                
                // Check for standard education merchants (school, university, college, bookstore, etc.)
                if (specificCategory == null) {
                    String[] educationMerchants = {"school", "university", "college", "tuition", 
                                                    "bookstore", "book store", "education", 
                                                    "bellevue school district", "schooldistrict",
                                                    "sp anki remote", "anki remote"};
                    for (String merchant : educationMerchants) {
                        if (combinedText.contains(merchant)) {
                            specificCategory = "education";
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with education merchant '{}' → using 'education' instead of 'credit'", merchant);
                            break;
                        }
                    }
                }
                
                // Check for entertainment/streaming merchants (Netflix, Hulu, etc.)
                // Note: These might overlap with subscriptions, but if not matched yet, check here
                if (specificCategory == null) {
                    String[] entertainmentMerchants = {"netflix", "hulu", "disney", "hbo", 
                                                        "paramount", "peacock", "spotify",
                                                        "youtube premium", "apple music"};
                    for (String merchant : entertainmentMerchants) {
                        if (combinedText.contains(merchant)) {
                            // Check if it's a subscription or entertainment
                            // If it contains "subscription" or "monthly", prefer subscriptions
                            if (combinedText.contains("subscription") || combinedText.contains("monthly")) {
                                specificCategory = "subscriptions";
                            } else {
                                specificCategory = "entertainment";
                            }
                            logger.info("🏷️ reasonCategory: Positive amount on credit card with entertainment/subscription merchant '{}' → using '{}' instead of 'credit'", merchant, specificCategory);
                            break;
                        }
                    }
                }
                
                // If we found a specific category from merchant matching, use it
                if (specificCategory != null) {
                    String detailedCategory = importerDetailed != null && !importerDetailed.isEmpty() ? 
                                             importerDetailed : specificCategory;
                    return new CategoryResult(specificCategory, detailedCategory, "MERCHANT_MATCH", 0.95);
                }
            }
            
            // Second check: If account is available and it's a credit card/loan account
            if (account != null && account.getAccountType() != null) {
                AccountTypeInfo accountInfo = getAccountTypeInfo(account);
                
                if (accountInfo.isCreditCard || accountInfo.isLoan) {
                    // Other positive amounts on credit card/loan accounts → category "credit"
                    logger.debug("Category determined: Credit card/loan credit → credit");
                    return new CategoryResult("credit", "credit", "RULE_OVERRIDE", 0.95);
                }
            }
        }
        
        // CRITICAL FIX: On checking accounts, credits should NOT be "payment" - they should be income/deposit
        // This fixes: Costco cash reward, Grisalin Management (rental income), Amazon Payroll, etc.
        // Note: descLower, merchantLower, and combinedText are already declared above for travel check
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // Positive amount = credit on checking account
            
            // Check if account is checking (or if we can infer from context)
            // Note: account is not available in reasonCategory, use accountHint or paymentChannel to infer
            boolean isCheckingAccount = false;
            if (accountHint != null) {
                String accountHintLower = accountHint.toLowerCase();
                isCheckingAccount = accountHintLower.contains("checking") || 
                                   accountHintLower.contains("depository") ||
                                   accountHintLower.contains("savings");
            } else if (paymentChannel != null && 
                      (paymentChannel.equalsIgnoreCase("ach") || paymentChannel.equalsIgnoreCase("online"))) {
                // Infer checking account from ACH payment channel
                isCheckingAccount = true;
            }
            
            if (isCheckingAccount) {
                // If category is "payment" but it's a credit, it should be income/deposit
                if ("payment".equalsIgnoreCase(importerPrimary) || "payment".equalsIgnoreCase(parserCategory) ||
                    "payment".equalsIgnoreCase(mlCategory)) {
                    
                    // Check for payroll/salary (Amazon Payroll, etc.)
                    if (combinedText.contains("payroll") || combinedText.contains("salary") || 
                        combinedText.contains("paycheck") || combinedText.contains("direct deposit") ||
                        combinedText.contains("wage") || combinedText.contains("compensation") ||
                        (merchantLower.contains("amazon") && combinedText.contains("payroll")) ||
                        (merchantLower.contains("amazon.com") && combinedText.contains("svcs"))) {
                        logger.info("🏷️ reasonCategory: Overriding 'payment' to 'income/salary' for payroll (credit on checking)");
                        return new CategoryResult("income", "salary", "RULE_OVERRIDE", 0.95);
                    }
                    
                    // Check for rental income (Grisalin Management, etc.)
                    if (combinedText.contains("grisalin") || merchantLower.contains("grisalin") ||
                        combinedText.contains("rental income") || combinedText.contains("rent income") ||
                        combinedText.contains("rent payment") || merchantLower.contains("rental") || 
                        merchantLower.contains("property management") || descLower.contains("sigonfile")) {
                        logger.info("🏷️ reasonCategory: Overriding 'payment' to 'income/rentIncome' for rental income (credit on checking)");
                        return new CategoryResult("income", "rentIncome", "RULE_OVERRIDE", 0.95);
                    }
                    
                    // Check for rewards/rebates
                    if (descLower.contains("reward") || descLower.contains("rebate") || descLower.contains("cash back") ||
                        merchantLower.contains("reward") || merchantLower.contains("rebate") || merchantLower.contains("cash back") ||
                        (descLower.contains("costco") && descLower.contains("cash"))) {
                        logger.info("🏷️ reasonCategory: Overriding 'payment' to 'income' for reward/rebate (credit on checking)");
                        return new CategoryResult("income", "income", "RULE_OVERRIDE", 0.95);
                    }
                    
                    // Default: credit on checking should be deposit, not payment
                    if (!descLower.contains("payment") && !merchantLower.contains("payment") &&
                        !descLower.contains("autopay") && !merchantLower.contains("autopay") &&
                        !descLower.contains("credit card") && !merchantLower.contains("credit card")) {
                        logger.info("🏷️ reasonCategory: Overriding 'payment' to 'deposit' for credit on checking account");
                        return new CategoryResult("deposit", "deposit", "RULE_OVERRIDE", 0.95);
                    }
                }
            }
        }
        
        // CRITICAL FIX: Check for travel/transportation to prevent false utilities matches
        // This fixes: Delta Airlines showing as Loan/Utilities instead of Expense/Travel
        // Note: Travel check for credit card accounts was already done earlier, this is for other cases
        // Re-check for travel/transportation keywords (airlines, hotels, etc.) for utilities override
        // CRITICAL: Make "united" check more specific to avoid matching "Remitly United" or other non-airline uses
        boolean isUnitedAirlinesForUtilities = (combinedText.contains("united airlines") || 
                                               combinedText.contains("unitedairlines") ||
                                               (combinedText.contains("united ") && 
                                                (combinedText.contains("flight") || combinedText.contains("airline") ||
                                                 combinedText.contains("airlines") || combinedText.contains("airport") ||
                                                 combinedText.contains("mileageplus") || combinedText.contains("mileage plus")))) ||
                                              (merchantLower.contains("united") && 
                                               (merchantLower.contains("airline") || merchantLower.contains("airlines")));
        
        boolean isTravelForUtilities = combinedText.contains("airline") || combinedText.contains("airlines") ||
                          combinedText.contains("delta") || isUnitedAirlinesForUtilities ||
                          combinedText.contains("american airlines") || combinedText.contains("southwest") ||
                          combinedText.contains("jetblue") || combinedText.contains("alaska") ||
                          combinedText.contains("hotel") || combinedText.contains("marriott") ||
                          combinedText.contains("hilton") || combinedText.contains("hyatt") ||
                          combinedText.contains("airbnb") || combinedText.contains("travel") ||
                          merchantLower.contains("airline") || merchantLower.contains("airlines") ||
                          merchantLower.contains("delta");
        
        // Check for transportation keywords (ticket machines, transit, etc.)
        // CRITICAL FIX: "lul" must be checked with word boundaries to avoid matching "lululemon"
        // Only match if "lul" is followed by space/punctuation OR preceded/followed by "underground" (London Underground Line)
        boolean isTransportationForUtilities = combinedText.contains("ticket machine") || combinedText.contains("ticketmachine") ||
                                  (combinedText.contains("lul ") || combinedText.contains(" lul") || 
                                   combinedText.contains("london underground") || combinedText.contains("lul underground") ||
                                   combinedText.contains("underground line") || combinedText.contains("lul line")) ||
                                  combinedText.contains("underground") || combinedText.contains("metro") ||
                                  combinedText.contains("transit") || combinedText.contains("subway") ||
                                  merchantLower.contains("ticket machine") || 
                                  (merchantLower.contains("lul ") || merchantLower.contains(" lul"));
        
        // If it's travel or transportation, ensure it's not misclassified as utilities or payment
        // CRITICAL: This must override account hints (e.g., credit card accounts default to "payment")
        if (isTravelForUtilities || isTransportationForUtilities) {
            String travelCategory = isTravelForUtilities ? "travel" : "transportation";
            // Override if category is payment, utilities, or if account hint would be payment (credit card accounts)
            boolean shouldOverride = "payment".equalsIgnoreCase(importerPrimary) || 
                                    "payment".equalsIgnoreCase(parserCategory) ||
                                    "payment".equalsIgnoreCase(mlCategory) ||
                                    "utilities".equalsIgnoreCase(importerPrimary) ||
                                    "utilities".equalsIgnoreCase(parserCategory) ||
                                    "utilities".equalsIgnoreCase(mlCategory) ||
                                    // Also override if account is credit card (which would default to "payment" hint)
                                    (account != null && account.getAccountType() != null && 
                                     (account.getAccountType().toLowerCase().contains("credit card") ||
                                      account.getAccountType().toLowerCase().contains("creditcard") ||
                                      account.getAccountType().toLowerCase().contains("charge card") ||
                                      account.getAccountType().toLowerCase().equals("credit") ||
                                      (account.getAccountSubtype() != null && 
                                       account.getAccountSubtype().toLowerCase().contains("credit card"))));
            if (shouldOverride) {
                logger.info("🏷️ reasonCategory: Overriding category to '{}' for travel/transportation transaction (was: payment/utilities/account hint)", travelCategory);
                return new CategoryResult(travelCategory, travelCategory, "RULE_OVERRIDE", 0.95);
            }
        }
        
        // CRITICAL FIX: Utilities should never be "payment" - they should be "utilities"
        // This fixes: Utilities showing as Loan/Utilities instead of Expense/Utilities
        // CRITICAL: Use word boundaries for "gas" to avoid matching "gas" in "airlines" or other words
        // Check for utilities keywords with better context
        boolean isUtilities = combinedText.contains("electric") || combinedText.contains("electricity") ||
                             combinedText.contains("water") || 
                             (combinedText.contains(" gas ") || combinedText.contains(" gas company") || 
                              combinedText.contains("gas company") || combinedText.endsWith(" gas") ||
                              combinedText.startsWith("gas ")) ||  // Word boundary checks for "gas"
                             combinedText.contains("internet") || combinedText.contains("phone") ||
                             combinedText.contains("cable") || combinedText.contains("utility") ||
                             combinedText.contains("utilities") || merchantLower.contains("utility");
        
        if (isUtilities && ("payment".equalsIgnoreCase(importerPrimary) || "payment".equalsIgnoreCase(parserCategory) ||
                            "payment".equalsIgnoreCase(mlCategory))) {
            logger.info("🏷️ reasonCategory: Overriding 'payment' to 'utilities' for utilities transaction");
            return new CategoryResult("utilities", "utilities", "RULE_OVERRIDE", 0.95);
        }
        
        // CRITICAL FIX: Deposit transactions with positive amounts should be income/deposit, not healthcare or other
        // This fixes: "DEPOSIT ID NUMBER 716081" showing as healthcare instead of deposit/income
        // Must check BEFORE Rule 7 (parser category override) to ensure deposits are correctly identified
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // Positive amount = credit/deposit
            if (combinedText.contains("deposit") || combinedText.contains("deposit id") ||
                combinedText.contains("deposit id number") || merchantLower.contains("deposit")) {
                // Check if it's actually a deposit (not a healthcare-related deposit)
                if (!combinedText.contains("health") && !combinedText.contains("medical") && 
                    !combinedText.contains("clinic") && !combinedText.contains("hospital")) {
                    logger.info("🏷️ reasonCategory: Deposit transaction detected → 'deposit' category");
                    return new CategoryResult("deposit", "deposit", "RULE_OVERRIDE", 0.95);
                }
            }
        }
        
        // CRITICAL FIX: Checks/transfers/PayPal transfers should be "transfer", not "payment"
        // This fixes: Checks showing as Loan/Payment instead of Expense/Transfer
        // CRITICAL: PayPal transfers (INST XFER) should be "transfer", not "payment"
        boolean isTransfer = descLower.contains("check") || descLower.contains("wire") ||
                            descLower.contains("transfer") || descLower.contains("ach transfer") ||
                            (descLower.contains("paypal") && (descLower.contains("xfer") || descLower.contains("inst xfer") || descLower.contains("instxfer"))) ||
                            merchantLower.contains("check") || merchantLower.contains("wire") ||
                            (merchantLower.contains("paypal") && (merchantLower.contains("xfer") || merchantLower.contains("inst xfer") || merchantLower.contains("instxfer")));
        
        if (isTransfer && ("payment".equalsIgnoreCase(importerPrimary) || "payment".equalsIgnoreCase(parserCategory) ||
                           "payment".equalsIgnoreCase(mlCategory))) {
            logger.info("🏷️ reasonCategory: Overriding 'payment' to 'transfer' for check/transfer/PayPal transfer transaction");
            return new CategoryResult("transfer", "transfer", "RULE_OVERRIDE", 0.95);
        }
        
        // Confidence-based fallback chain:
        // 1. High confidence (95%+): Exact merchant matches, MCC codes
        // 2. Medium confidence (90-95%): Fuzzy merchant matches
        // 3. Importer categories (Plaid, etc.)
        // 4. Parser categories
        // 5. ML categories
        // 6. Account hints
        // 7. Fallback category
        
        // Rule 0.5: Use medium-confidence merchant result if no high-confidence match found
        if (mediumConfidenceMerchantResult != null) {
            // Only use if no better option exists
            boolean hasBetterOption = (importerPrimary != null && !importerPrimary.isEmpty() && 
                                      !"other".equals(importerPrimary) && !"UNKNOWN_CATEGORY".equals(importerPrimary) &&
                                      "PLAID".equals(importSource)) ||
                                     (parserCategory != null && !parserCategory.isEmpty() && 
                                      !"other".equals(parserCategory)) ||
                                     (mlCategory != null && mlConfidence > 0.85);
            
            if (!hasBetterOption) {
                logger.debug("Using medium-confidence merchant result: {} (confidence: {:.2f})", 
                    mediumConfidenceMerchantResult.getCategoryPrimary(), 
                    mediumConfidenceMerchantResult.getConfidence());
                return mediumConfidenceMerchantResult;
            }
        }
        
        // Rule 1: If importer category is high-confidence (Plaid with specific mapping), prefer it
        if (importerPrimary != null && !importerPrimary.isEmpty() && 
            !"other".equals(importerPrimary) && !"UNKNOWN_CATEGORY".equals(importerPrimary)) {
            // Plaid categories are generally reliable
            if ("PLAID".equals(importSource)) {
                return new CategoryResult(importerPrimary, 
                    importerDetailed != null ? importerDetailed : importerPrimary,
                    "PLAID", 0.9);
            }
        }
        
        // Rule 2: If parser category matches importer category, high confidence
        if (parserCategory != null && importerPrimary != null &&
            parserCategory.equalsIgnoreCase(importerPrimary)) {
            return new CategoryResult(parserCategory, parserCategory, "HYBRID", 0.95);
        }
        
        // Rule 3: If ML category has high confidence and matches parser, use it
        if (mlCategory != null && mlConfidence > 0.8 && 
            parserCategory != null && mlCategory.equalsIgnoreCase(parserCategory)) {
            return new CategoryResult(mlCategory, mlCategory, "ML", mlConfidence);
        }
        
        // Rule 4: If parser category is more specific than importer, prefer parser
        if (parserCategory != null && importerPrimary != null &&
            !"other".equals(parserCategory) && "other".equals(importerPrimary)) {
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.85);
        }
        
        // Rule 5: If account hint is strong (investment/loan account), use it
        if (accountHint != null && !accountHint.isEmpty()) {
            return new CategoryResult(accountHint, accountHint, "ACCOUNT", 0.9);
        }
        
        // Rule 6: Prefer ML if confidence is high
        if (mlCategory != null && mlConfidence > 0.8) {
            return new CategoryResult(mlCategory, mlCategory, "ML", mlConfidence);
        }
        
        // Rule 7: Prefer parser over importer if importer is generic, BUT:
        // CRITICAL: If parser says "healthcare" but transaction has non-healthcare terms,
        // trust the importer "other" category instead of parser's false positive
        if (parserCategory != null && 
            (importerPrimary == null || "other".equals(importerPrimary) || "UNKNOWN_CATEGORY".equals(importerPrimary))) {
            // Check if parser category is healthcare but transaction has non-healthcare terms
            // that suggest it should be "other" instead
            if ("healthcare".equalsIgnoreCase(parserCategory)) {
                String[] nonHealthcareTerms = {"offer", "moved to", "standard purch", "deposit", 
                                               "id number", "usps", "post office", "postal service",
                                               "holdings", "limited", "trg holdings", "trg",
                                               "social club", "organization", "foundation",
                                               "non-profit", "nonprofit", "aow"};
                boolean hasNonHealthcareTerms = false;
                for (String term : nonHealthcareTerms) {
                    if (combinedText.contains(term)) {
                        hasNonHealthcareTerms = true;
                        break;
                    }
                }
                if (hasNonHealthcareTerms) {
                    // Trust importer "other" category instead of parser's false positive healthcare
                    logger.info("🏷️ reasonCategory: Parser says 'healthcare' but non-healthcare terms detected → using importer 'other' category");
                    if (importerPrimary != null && "other".equalsIgnoreCase(importerPrimary)) {
                        return new CategoryResult("other", "other", "IMPORTER_CATEGORY", 0.8);
                    }
                }
            }
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.8);
        }
        
        // Rule 8: Before falling back to importer "other" category, check merchant detection one more time
        // This ensures shopping merchants like Estee Lauder, Charles Tyrwhitt are detected even when importer says "other"
        if (importerPrimary != null && "other".equalsIgnoreCase(importerPrimary)) {
            // Re-run merchant detection for "other" category to catch shopping/other merchants
            String fallbackMerchantCategory = detectCategoryFromMerchantAndDescription(merchantName, description, merchantLower, descLower, combinedText);
            if (fallbackMerchantCategory != null) {
                logger.info("🏷️ reasonCategory: Merchant detection for 'other' importer category → '{}'", fallbackMerchantCategory);
                return new CategoryResult(fallbackMerchantCategory, fallbackMerchantCategory, "MERCHANT_DETECTION", 0.85);
            }
        }
        
        // Rule 9: Fallback to importer category
        if (importerPrimary != null && !importerPrimary.isEmpty()) {
            return new CategoryResult(importerPrimary,
                importerDetailed != null ? importerDetailed : importerPrimary,
                "IMPORTER", 0.7);
        }
        
        // Rule 10: Fallback to parser category
        if (parserCategory != null) {
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.7);
        }
        
        // Rule 11: Last resort
        return new CategoryResult("other", "other", "DEFAULT", 0.5);
    }

    /**
     * Account type information structure for intelligent transaction type and category determination
     */
    private static class AccountTypeInfo {
        boolean isCheckingOrSavings;
        boolean isCreditCard;
        boolean isInvestment;
        boolean isLoan;
        boolean isCD;
        boolean isMortgage;
        String accountType;
        String accountSubtype;
        
        AccountTypeInfo(String accountType, String accountSubtype) {
            this.accountType = accountType;
            this.accountSubtype = accountSubtype;
            String typeLower = accountType != null ? accountType.toLowerCase() : "";
            String subtypeLower = accountSubtype != null ? accountSubtype.toLowerCase() : "";
            
            // Checking/Savings accounts
            this.isCheckingOrSavings = typeLower.contains("checking") || 
                                      typeLower.contains("savings") ||
                                      typeLower.contains("depository") ||
                                      typeLower.contains("money market") ||
                                      typeLower.contains("moneymarket");
            
            // Credit card accounts
            // CRITICAL: Check for "credit card" and "creditcard" BEFORE "credit" to avoid matching credit lines
            // Only match "credit" if it's NOT part of "credit line" or "line of credit"
            boolean isCreditLine = typeLower.contains("credit line") || 
                                  typeLower.contains("creditline") ||
                                  typeLower.contains("line of credit") ||
                                  typeLower.contains("lineofcredit") ||
                                  subtypeLower.contains("credit line") ||
                                  subtypeLower.contains("creditline");
            
            this.isCreditCard = (typeLower.contains("credit card") || 
                               typeLower.contains("creditcard") ||
                               typeLower.contains("charge card")) ||
                               (!isCreditLine && typeLower.contains("credit"));
            
            // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, stocks, bonds, etc.)
            this.isInvestment = typeLower.contains("investment") || 
                               typeLower.contains("401k") ||
                               typeLower.contains("ira") ||
                               typeLower.contains("hsa") ||
                               typeLower.contains("529") ||
                               typeLower.contains("brokerage") ||
                               typeLower.contains("stocks") ||
                               typeLower.contains("bonds") ||
                               typeLower.contains("mutual fund") ||
                               typeLower.contains("mutualfund") ||
                               typeLower.contains("etf") ||
                               typeLower.contains("retirement") ||
                               typeLower.contains("certificate") ||
                               typeLower.contains("cd") ||
                               subtypeLower.contains("401k") ||
                               subtypeLower.contains("ira") ||
                               subtypeLower.contains("hsa") ||
                               subtypeLower.contains("529") ||
                               subtypeLower.contains("brokerage") ||
                               subtypeLower.contains("cd") ||
                               subtypeLower.contains("certificate");
            
            // CD accounts (subset of investment)
            this.isCD = typeLower.contains("certificate") || 
                       typeLower.contains("cd") ||
                       subtypeLower.contains("certificate") ||
                       subtypeLower.contains("cd");
            
            // Loan accounts (mortgage, student loan, car loan, personal loan, etc.)
            this.isLoan = typeLower.contains("loan") || 
                         typeLower.contains("mortgage") ||
                         typeLower.contains("student loan") ||
                         typeLower.contains("studentloan") ||
                         typeLower.contains("car loan") ||
                         typeLower.contains("carloan") ||
                         typeLower.contains("auto loan") ||
                         typeLower.contains("autoloan") ||
                         typeLower.contains("personal loan") ||
                         typeLower.contains("personalloan") ||
                         typeLower.contains("home loan") ||
                         typeLower.contains("homeloan") ||
                         subtypeLower.contains("loan") ||
                         subtypeLower.contains("mortgage");
            
            // Mortgage accounts (subset of loan)
            this.isMortgage = typeLower.contains("mortgage") || 
                             typeLower.contains("home loan") ||
                             typeLower.contains("homeloan") ||
                             subtypeLower.contains("mortgage");
        }
    }
    
    /**
     * Gets account type information for intelligent transaction type and category determination
     */
    private AccountTypeInfo getAccountTypeInfo(AccountTable account) {
        if (account == null) {
            return new AccountTypeInfo(null, null);
        }
        return new AccountTypeInfo(account.getAccountType(), account.getAccountSubtype());
    }
    
    /**
     * Gets account type information from account type string (for PDF/CSV imports)
     */
    private AccountTypeInfo getAccountTypeInfoFromString(String accountType, String accountSubtype) {
        return new AccountTypeInfo(accountType, accountSubtype);
    }
    
    /**
     * Gets category hint from account type (enhanced with comprehensive account type detection)
     */
    private String getAccountCategoryHint(AccountTable account) {
        if (account == null) return null;
        
        AccountTypeInfo accountInfo = getAccountTypeInfo(account);
        
        // Investment accounts → investment category
        if (accountInfo.isInvestment) {
            // CD accounts → cd category
            if (accountInfo.isCD) {
                return "cd";
            }
            return "investment";
        }
        
        // Loan accounts → payment category
        if (accountInfo.isLoan || accountInfo.isCreditCard) {
            return "payment";
        }
        
        // Checking/Savings accounts don't provide category hints (use transaction context)
        return null;
    }

    /**
     * Checks if a payment is actually a payment (credit card, mortgage, student loan, car loan, personal loan, home loan, etc.)
     * This is used to determine if "payment" category should map to PAYMENT type
     * 
     * @param combinedText Combined merchant name and description text (lowercase)
     * @param account Account associated with the transaction
     * @return true if this is a payment, false otherwise
     */
    private boolean isLoanPayment(String combinedText, AccountTable account) {
        if (combinedText == null || combinedText.isEmpty()) {
            // If no text, check account type
            return account != null && account.getAccountType() != null && isLoanAccountType(account.getAccountType());
        }
        
        String textLower = combinedText.toLowerCase();
        
        // Credit card payment keywords
        String[] creditCardKeywords = {
            "credit card", "creditcard", "cc payment", "card payment", 
            "visa payment", "mastercard payment", "amex payment", "american express",
            "discover payment", "chase payment", "capital one", "citi payment"
        };
        for (String keyword : creditCardKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Mortgage payment keywords
        String[] mortgageKeywords = {
            "mortgage", "mortgage payment", "home loan payment", "homeloan payment",
            "house payment", "property loan", "real estate loan"
        };
        for (String keyword : mortgageKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Student loan payment keywords
        String[] studentLoanKeywords = {
            "student loan", "studentloan", "education loan", "educationloan",
            "federal student loan", "private student loan", "navient", "sallie mae"
        };
        for (String keyword : studentLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Car loan / Auto loan payment keywords
        String[] carLoanKeywords = {
            "car loan", "carloan", "auto loan", "autoloan", "vehicle loan",
            "auto payment", "car payment", "vehicle payment", "car financing"
        };
        for (String keyword : carLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Personal loan payment keywords
        String[] personalLoanKeywords = {
            "personal loan", "personalloan", "unsecured loan", "signature loan",
            "personal line of credit", "ploc"
        };
        for (String keyword : personalLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Home loan payment keywords (separate from mortgage for clarity)
        String[] homeLoanKeywords = {
            "home loan", "homeloan", "home equity", "homeequity", "heloc",
            "home equity line of credit", "second mortgage"
        };
        for (String keyword : homeLoanKeywords) {
            if (textLower.contains(keyword)) {
                return true;
            }
        }
        
        // Other loan types
        String[] otherLoanKeywords = {
            "loan payment", "loanpay", "loan pay", "installment loan",
            "payday loan", "paydayloan", "title loan", "titleloan",
            "business loan", "businessloan", "commercial loan"
        };
        for (String keyword : otherLoanKeywords) {
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
    private boolean isLoanAccountType(String accountType) {
        if (accountType == null) {
            return false;
        }
        
        String accountTypeLower = accountType.toLowerCase();
        
        // Check for loan account types
        return accountTypeLower.contains("loan") ||
               accountTypeLower.contains("mortgage") ||
               accountTypeLower.contains("credit card") ||
               accountTypeLower.contains("creditcard") ||
               accountTypeLower.contains("student loan") ||
               accountTypeLower.contains("studentloan") ||
               accountTypeLower.contains("home loan") ||
               accountTypeLower.contains("homeloan") ||
               accountTypeLower.contains("car loan") ||
               accountTypeLower.contains("carloan") ||
               accountTypeLower.contains("auto loan") ||
               accountTypeLower.contains("autoloan") ||
               accountTypeLower.contains("personal loan") ||
               accountTypeLower.contains("personalloan");
    }

    /**
     * Checks if transaction is a credit card payment
     */
    private boolean isCreditCardPayment(String description, String merchantName, String categoryString, AccountTable account) {
        if (description == null && merchantName == null && categoryString == null) {
            return false;
        }
        
        // P3: Optimize string operations - use StringBuilder
        StringBuilder combinedBuilder = new StringBuilder();
        if (merchantName != null) combinedBuilder.append(merchantName).append(" ");
        if (description != null) combinedBuilder.append(description).append(" ");
        if (categoryString != null) combinedBuilder.append(categoryString);
        String combined = combinedBuilder.toString().toLowerCase();
        
        // Global Scale: Use region-specific credit card keywords
        // Try to detect region from account currency or use default
        String region = detectRegion(account); // TODO: Implement region detection from account/user
        List<String> creditCardKeywords = globalFinancialConfig.getCreditCardKeywordsForRegion(region);
        
        // P2: Use configuration for keywords instead of hard-coding
        for (String keyword : creditCardKeywords) {
            if (combined.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        // Fallback to default region keywords if region-specific didn't match
        // CRITICAL: Handle null region (defensive programming)
        String defaultRegion = globalFinancialConfig != null ? globalFinancialConfig.getDefaultRegion() : "US";
        if (region != null && !region.equals(defaultRegion)) {
            for (String keyword : importCategoryConfig.getCreditCardKeywords()) {
                if (combined.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if transaction description or merchant name indicates a payment received (AUTOPAY or PAYMENT RECEIVED - THANK YOU)
     * This is used to distinguish payments from charges on credit card and loan accounts
     * 
     * @param descriptionLower Lowercase transaction description
     * @param merchantNameLower Optional lowercase merchant name
     * @return true if description or merchant name contains payment received keywords
     */
    private boolean isPaymentReceived(String descriptionLower, String merchantNameLower) {
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
        if (combinedText.contains("autopay") || combinedText.contains("auto pay") || 
            combinedText.contains("auto-pay") || combinedText.contains("automatic payment")) {
            return true;
        }
        
        // Check for DIRECTPAY patterns (direct payment to credit card/loan)
        if (combinedText.contains("directpay") || combinedText.contains("direct pay") ||
            combinedText.contains("direct-pay") || combinedText.contains("direct payment")) {
            return true;
        }

        if (combinedText.contains("fullpay") || combinedText.contains("full pay") ||
            combinedText.contains("full-pay") || combinedText.contains("full payment")) {
            return true;
        }

        if (combinedText.contains("checkpay") || combinedText.contains("check pay") ||
            combinedText.contains("check payment")) {
            return true;
        }

        if (combinedText.contains("paritalpay") || combinedText.contains("partial pay") ||
            combinedText.contains("partial payment")) {
            return true;
        }
        
        // Check for PAYMENT RECEIVED - THANK YOU patterns
        if (combinedText.contains("payment received") && 
            (combinedText.contains("thank you") || combinedText.contains("thankyou"))) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Overloaded method for backward compatibility (description only)
     */
    private boolean isPaymentReceived(String descriptionLower) {
        return isPaymentReceived(descriptionLower, null);
    }
    
    /**
     * Detects region from account information (currency, account type, etc.)
     * Global Scale: Supports region-specific category determination
     * 
     * @param account Account information
     * @return Region code (US, UK, CA, AU, IN, EU, etc.)
     */
    private String detectRegion(AccountTable account) {
        if (account == null) {
            return globalFinancialConfig.getDefaultRegion();
        }
        
        // Detect region from currency code
        String currencyCode = account.getCurrencyCode();
        if (currencyCode != null) {
            String currencyUpper = currencyCode.toUpperCase();
            // Currency-based region detection
            if (currencyUpper.equals("USD")) return "US";
            if (currencyUpper.equals("GBP")) return "UK";
            if (currencyUpper.equals("CAD")) return "CA";
            if (currencyUpper.equals("AUD")) return "AU";
            if (currencyUpper.equals("INR")) return "IN";
            if (currencyUpper.equals("EUR")) return "EU";
            // Add more currency mappings as needed
        }
        
        // Detect region from account type (US-specific types)
        String accountType = account.getAccountType();
        if (accountType != null) {
            String accountTypeLower = accountType.toLowerCase();
            if (accountTypeLower.contains("401k") || accountTypeLower.contains("403b") ||
                accountTypeLower.contains("ira") || accountTypeLower.contains("hsa") ||
                accountTypeLower.contains("529")) {
                return "US";
            }
            // Add more region-specific account type detection
        }
        
        // Default to US for backward compatibility
        return globalFinancialConfig.getDefaultRegion();
    }
    
    /**
     * Helper method to determine if importer category is clearly wrong compared to merchant detection
     * This prevents incorrect overrides when importer is actually correct
     */
    private boolean isImporterCategoryClearlyWrong(String importerCategory, String merchantCategory, String combinedText) {
        if (importerCategory == null || merchantCategory == null) {
            return false;
        }
        
        String importerLower = importerCategory != null ? importerCategory.toLowerCase() : "";
        String merchantLower = merchantCategory != null ? merchantCategory.toLowerCase() : "";
        
        // Known clear mismatches where importer is wrong:
        // 1. "utilities" for dining/restaurant transactions (e.g., TST* DEEP DIVE, TPD, SQ*, RBL*)
        // CRITICAL: If merchant detection says "dining" and importer says "utilities", it's likely wrong
        // Restaurant POS patterns (TST*, SQ*, RBL*) or restaurant names indicate dining, not utilities
        if ("utilities".equals(importerLower) && "dining".equals(merchantLower)) {
            // Always override if merchant detection says dining and importer says utilities
            // Restaurant patterns are clear indicators
            return true;
        }
        
        // 2. "utilities" for travel transactions (e.g., AXP Centurion Lounge)
        if ("utilities".equals(importerLower) && "travel".equals(merchantLower)) {
            // Always override if merchant detection says travel and importer says utilities
            return true;
        }
        
        // 2b. "utilities" for health/transportation/education/other specific categories
        // If merchant detection is specific and importer is "utilities", likely wrong
        if ("utilities".equals(importerLower) && 
            ("health".equals(merchantLower) || "transportation".equals(merchantLower) ||
             "education".equals(merchantLower) || "pet".equals(merchantLower))) {
            // Always override utilities with specific categories from merchant detection
            return true;
        }
        
        // 3. "education" for non-education transactions (e.g., transfers, pets, fees, etc.)
        // If merchant detection is specific and importer says "education", likely wrong
        if ("education".equals(importerLower) && !"education".equals(merchantLower)) {
            // Always override if merchant detection found a specific category
            // Education is often a false positive from generic keyword matching
            return true;
        }
        
        // 4. "other" for specific categories (always override)
        if ("other".equals(importerLower)) {
            return true;
        }
        
        // 5. "healthcare" for non-healthcare transactions with specific indicators
        if ("healthcare".equals(importerLower) && !"healthcare".equals(merchantLower)) {
            // Check for non-healthcare indicators
            if (combinedText.contains("offer") || combinedText.contains("deposit") ||
                combinedText.contains("usps") || combinedText.contains("holdings") ||
                combinedText.contains("petcare") || combinedText.contains("pet care")) {
                return true;
            }
        }
        
        // 6. "transfer" vs "travel" - if merchant says travel and importer says transfer, check context
        // E.g., passport services should be travel, not transfer
        if ("transfer".equals(importerLower) && "travel".equals(merchantLower)) {
            if (combinedText.contains("passport") || combinedText.contains("visa")) {
                return true; // Passport services are travel, not transfer
            }
        }
        
        // 7. "shopping" vs specific categories - if merchant detection is more specific, override
        // E.g., "shopping" for "health" (ski rental) or "transportation" (gas station)
        if ("shopping".equals(importerLower) && 
            ("health".equals(merchantLower) || "transportation".equals(merchantLower) ||
             "pet".equals(merchantLower) || "travel".equals(merchantLower))) {
            // Merchant detection is more specific, override
            return true;
        }
        
        // 8. "entertainment" vs "education" - if merchant detection is education and importer is entertainment
        // E.g., AAMC exam should be education, not entertainment
        if ("entertainment".equals(importerLower) && "education".equals(merchantLower)) {
            return true; // Education is more specific than entertainment
        }
        
        return false;
    }
    
    /**
     * Helper method to determine if importer category is high-confidence
     * High-confidence categories are from reliable sources (Plaid) or specific categories
     */
    private boolean isHighConfidenceCategory(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        
        String categoryLower = category.toLowerCase();
        
        // Generic categories are not high-confidence
        if ("other".equals(categoryLower) || "unknown_category".equals(categoryLower)) {
            return false;
        }
        
        // Specific categories are generally high-confidence
        // This list can be expanded based on domain knowledge
        String[] highConfidenceCategories = {
            "groceries", "dining", "shopping", "transportation", "travel",
            "utilities", "rent", "healthcare", "entertainment", "education",
            "income", "deposit", "investment", "payment", "transfer"
        };
        
        for (String hcCategory : highConfidenceCategories) {
            if (hcCategory.equals(categoryLower)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detects category from merchant name and description patterns
     * This runs BEFORE trusting importer categories to fix incorrect categorizations
     * 
     * @param merchantName Original merchant name
     * @param description Original description
     * @param merchantLower Lowercase merchant name
     * @param descLower Lowercase description
     * @param combinedText Combined merchant + description (lowercase)
     * @return Detected category or null if no match
     */
    private String detectCategoryFromMerchantAndDescription(
            String merchantName, String description, 
            String merchantLower, String descLower, String combinedText) {
        
        // ========== Money Transfer Services Detection (HIGHEST PRIORITY - before travel) ==========
        // CRITICAL: Must check money transfer services FIRST to avoid false positives from travel
        // E.g., "Remitly United S PAYMENTS" should be "transfer", not "travel" (United is not United Airlines)
        
        // Money transfer services (Remitly, Western Union, MoneyGram, Wise, etc.)
        String[] moneyTransferServices = {"remitly", "western union", "westernunion", "moneygram", "money gram",
                                          "wise", "transferwise", "transfer wise", "xoom", "paypal send",
                                          "venmo", "zelle", "cash app", "cashapp", "revolut", "worldremit",
                                          "ria money transfer", "riamoneytransfer", "ria money", "riamoney"};
        for (String service : moneyTransferServices) {
            if (combinedText.contains(service)) {
                return "transfer";
            }
        }
        
        // Check for money transfer keywords when combined with payment keywords
        if ((combinedText.contains("remit") || combinedText.contains("wire transfer") || 
             combinedText.contains("wiretransfer") || combinedText.contains("money transfer") ||
             combinedText.contains("moneytransfer") || combinedText.contains("send money") ||
             combinedText.contains("sendmoney")) && 
            (combinedText.contains("payment") || combinedText.contains("payments"))) {
            return "transfer";
        }
        
        // ========== Post Office/USPS Detection (HIGHEST PRIORITY - before healthcare) ==========
        // CRITICAL: Must check USPS/post office FIRST to avoid false positives from healthcare
        
        // USPS (United States Postal Service) - postage, not healthcare
        if (combinedText.contains("usps") || combinedText.contains("u.s. postal service") ||
            combinedText.contains("united states postal service") || combinedText.contains("post office") ||
            combinedText.contains("postoffice") || combinedText.contains("us postal service") ||
            combinedText.contains("po ") || (combinedText.contains("po") && combinedText.contains("bellevue")) ||
            merchantLower.contains("usps") || merchantLower.contains("post office")) {
            return "other"; // Postage/shipping - "other" category
        }
        
        // ========== Holdings/Company Detection (before healthcare) ==========
        // CRITICAL: Must check holdings/companies FIRST to avoid false positives from healthcare
        
        // Holdings companies (TRG Holdings, etc.) - could be dining, shopping, or other, but NOT healthcare
        if (combinedText.contains("holdings") || combinedText.contains("holdings limited") ||
            combinedText.contains("holdingslimited") || combinedText.contains("trg holdings") ||
            combinedText.contains("trgholdings")) {
            // Check if it's actually healthcare-related
            if (!combinedText.contains("health") && !combinedText.contains("medical") && 
                !combinedText.contains("clinic") && !combinedText.contains("hospital") &&
                !combinedText.contains("healthcare")) {
                // Holdings companies are typically business entities, default to "other"
                // Could be dining if context suggests restaurant, but default to "other" to be safe
                return "other"; // Holdings companies default to "other"
            }
        }
        
        // ========== Social Clubs/Non-Profits Detection (before healthcare) ==========
        // CRITICAL: Must check social clubs FIRST to avoid false positives from healthcare
        
        // Social clubs and non-profit organizations (AOW = American Organization of Women, etc.)
        // These should be entertainment or other, not healthcare
        String[] socialClubPatterns = {"aow", "social club", "socialclub", "non-profit", "nonprofit",
                                       "organization", "foundation", "association", "society",
                                       "club membership", "clubmembership"};
        for (String pattern : socialClubPatterns) {
            if (combinedText.contains(pattern)) {
                // Check if it's actually healthcare-related (e.g., "health club" is already handled separately)
                if (!combinedText.contains("health club") && !combinedText.contains("healthclub") &&
                    !combinedText.contains("medical") && !combinedText.contains("healthcare")) {
                    return "entertainment"; // Social clubs are entertainment
                }
            }
        }
        
        // ========== Transfer/Deposit Detection (BEFORE education to prevent false positives) ==========
        // CRITICAL: Must check transfers/deposits FIRST to avoid false positives from education
        // Online transfers, PayPal transfers, bank transfers should be "transfer", not "education"
        
        // Online transfers (to/from accounts)
        if (combinedText.contains("online transfer") || combinedText.contains("onlinetransfer")) {
            if (combinedText.contains("online transfer to") || combinedText.contains("onlinetransfer to")) {
                return "transfer"; // Transfer out = transfer expense
            }
            if (combinedText.contains("online transfer from") || combinedText.contains("onlinetransfer from")) {
                return "deposit"; // Transfer in = deposit income
            }
            return "transfer"; // Default to transfer for online transfers
        }
        
        // PayPal transfers (must check for transfer keywords to avoid false positives)
        // CRITICAL: Check for "inst xfer" (instant transfer) which is a clear transfer indicator
        if (combinedText.contains("paypal")) {
            // Check for transfer keywords (case-insensitive matching via combinedText which is already lowercase)
            if (combinedText.contains("transfer") || combinedText.contains("xfer") || 
                combinedText.contains("inst xfer") || combinedText.contains("instxfer") ||
                combinedText.contains("inst xfer")) {
                return "transfer";
            }
        }
        
        // Bank transfers (ACH, wire transfers)
        if (combinedText.contains("ach transfer") || combinedText.contains("achtransfer") ||
            combinedText.contains("wire transfer") || combinedText.contains("wiretransfer") ||
            (combinedText.contains("transfer") && 
             (combinedText.contains("transaction") || combinedText.contains("chk") || 
              combinedText.contains("morganstanley") || combinedText.contains("morgan stanley")))) {
            if (combinedText.contains("transfer from")) {
                return "deposit"; // Transfer in = deposit
            }
            return "transfer"; // Transfer out = transfer
        }
        
        // ========== Pet Category Detection (BEFORE education to prevent false positives) ==========
        // CRITICAL: Must check pet stores/services FIRST to avoid false positives from education
        
        // Pet stores (PetSmart, Petco, etc.)
        if (combinedText.contains("petsmart") || combinedText.contains("pet smart") ||
            combinedText.contains("petco") || combinedText.contains("pet co") ||
            combinedText.contains("pets best") || combinedText.contains("petsbest") ||
            combinedText.contains("fido") || combinedText.contains("pet store") ||
            combinedText.contains("petstore")) {
            return "pet";
        }
        
        // Pet insurance and services
        if (combinedText.contains("pet insurance") || combinedText.contains("petinsurance") ||
            combinedText.contains("veterinary") || combinedText.contains("vet") ||
            (combinedText.contains("pet") && combinedText.contains("insurance"))) {
            return "pet";
        }
        
        // ========== Travel Category Detection (BEFORE fees to prevent false positives) ==========
        // CRITICAL: Must check travel-related services FIRST to avoid false positives from fees/education
        
        // VIASAT (satellite internet service provider, often used for travel/RV)
        if (combinedText.contains("viasat") || combinedText.contains("via sat") ||
            combinedText.contains("via-sat")) {
            return "travel";
        }
        
        // Airline fee reimbursements (MUST check BEFORE fees to avoid false positive)
        if (combinedText.contains("airline fee reimbursement") || 
            combinedText.contains("airlinefee reimbursement") ||
            combinedText.contains("airline fee reimbursement")) {
            return "travel"; // Airline reimbursement = travel, not fees
        }
        
        // ========== Transportation/Toll Detection (BEFORE education to prevent false positives) ==========
        // CRITICAL: Must check tolls FIRST to avoid false positives from education
        
        // Tolls (E-ZPass, Eractoll, etc.)
        if (combinedText.contains("eractoll") || combinedText.contains("erac toll") ||
            combinedText.contains("ezpass") || combinedText.contains("e-zpass") ||
            combinedText.contains("e zpass") || combinedText.contains("toll") ||
            combinedText.contains("fastrak") || combinedText.contains("fastrak") ||
            combinedText.contains("ipass") || combinedText.contains("i-pass")) {
            return "transportation";
        }
        
        // ========== Fees Category Detection (BEFORE education to prevent false positives) ==========
        // CRITICAL: Must check fees FIRST to avoid false positives from education
        // BUT: Must check AFTER travel to avoid false positive for airline fee reimbursements
        
        // Bank fees and transaction fees (but NOT airline fee reimbursements - already handled above)
        if (combinedText.contains("foreign transaction fee") || combinedText.contains("foreigntransactionfee") ||
            combinedText.contains("transaction fee") || combinedText.contains("transactionfee") ||
            combinedText.contains("atm fee") || combinedText.contains("atmfee") ||
            combinedText.contains("overdraft fee") || combinedText.contains("overdraftfee") ||
            combinedText.contains("monthly fee") || combinedText.contains("monthlyfee")) {
            // Exclude airline fee reimbursements (already handled as travel above)
            if (!combinedText.contains("airline fee reimbursement") && 
                !combinedText.contains("airlinefee reimbursement")) {
                return "fees";
            }
        }
        
        // General fee check (but exclude airline fee reimbursements)
        if (combinedText.contains("fee") && 
            !combinedText.contains("airline fee reimbursement") &&
            !combinedText.contains("airlinefee reimbursement") &&
            (combinedText.contains("foreign") || combinedText.contains("transaction") ||
             combinedText.contains("bank") || combinedText.contains("service"))) {
            return "fees";
        }
        
        // ========== Health Category Detection (BEFORE education for ski rentals) ==========
        // CRITICAL: Must check ski rentals/health activities FIRST to avoid false positives from education
        
        // Ski rentals and outdoor activities (act* pattern for ski rentals)
        if (combinedText.contains("act*minimountain") || combinedText.contains("act * minimountain") ||
            combinedText.contains("minimountain") || 
            (combinedText.contains("act*") && combinedText.contains("mountain"))) {
            return "health"; // Ski rental = health activity
        }
        
        // ========== Dining Category Detection (BEFORE education for restaurant POS) ==========
        // CRITICAL: Must check restaurant POS patterns FIRST to avoid false positives from education
        
        // RBL* pattern (restaurant POS system)
        if (descLower.contains("rbl*") || merchantLower.contains("rbl*") ||
            (merchantName != null && merchantName.toUpperCase().contains("RBL*")) ||
            (description != null && description.toUpperCase().contains("RBL*"))) {
            return "dining";
        }
        
        // ========== Education Category Detection (HIGHEST PRIORITY - before utilities) ==========
        // CRITICAL: Must check education FIRST to avoid false positives from utilities
        
        // University Book Store (must check before general utilities)
        if (combinedText.contains("university book store") || combinedText.contains("universitybookstore") ||
            combinedText.contains("university bookstore") || combinedText.contains("universitybook store")) {
            return "education";
        }
        
        // School districts and school names (handle abbreviations like "DISTRI" for "DISTRICT")
        if (combinedText.contains("bellevue school") || combinedText.contains("bellevueschool") ||
            combinedText.contains("school district") || combinedText.contains("schooldistrict") ||
            combinedText.contains("school distri") || combinedText.contains("schooldistri") ||
            combinedText.contains("middle school") || combinedText.contains("middleschool") ||
            combinedText.contains("high school") || combinedText.contains("highschool") ||
            combinedText.contains("elementary school") || combinedText.contains("elementaryschool") ||
            combinedText.contains("tyee middle school") || combinedText.contains("tyeemiddleschool")) {
            return "education";
        }
        
        // Anki (education software)
        if (combinedText.contains("anki") || combinedText.contains("sp anki remote")) {
            return "education";
        }
        
        // Exam keywords (AAMC, SAT, TOEFL, etc.)
        String[] examKeywords = {"aamc", "sat", "toefl", "gre", "gmat", "lsat", "mcat", 
                                 "act", "ap exam", "ib exam", "clep", "praxis", "bar exam",
                                 "nclex", "usmle", "comlex", "pearson vue", "ets", "prometric",
                                 "vue*aamc", "vue*aamc exam"};
        for (String exam : examKeywords) {
            if (combinedText.contains(exam)) {
                return "education";
            }
        }
        
        // Financial education publications (Barrons, WSJ, etc.) - education, NOT subscriptions
        // CRITICAL: Barrons is a financial education/investment publication
        if (combinedText.contains("barrons") || combinedText.contains("barron") ||
            combinedText.contains("barron's") || combinedText.contains("dj*barrons") ||
            combinedText.contains("d j*barrons") || combinedText.contains("d j*barrons")) {
            return "education"; // Financial education publication
        }
        
        // Regional school terms (Gurukul, Vidyalaya, etc.)
        String[] regionalSchoolTerms = {"gurukul", "vidyalaya", "shiksha", "pathshala",
                                        "escuela", "colegio", "universidad",
                                        "école", "collège", "université",
                                        "schule", "universität",
                                        "madrasa", "kuttab", "madrassa"};
        for (String term : regionalSchoolTerms) {
            if (combinedText.contains(term)) {
                return "education";
            }
        }
        
        // School types and educational media
        // CRITICAL: Be very specific to avoid false positives (e.g., "online transfer" contains "line" which might match)
        // Only match if it's clearly education-related context
        if (combinedText.contains("college") || combinedText.contains("university") ||
            combinedText.contains("phd") || combinedText.contains("ph.d")) {
            // But exclude non-education uses of these words
            if (!combinedText.contains("college football") && 
                !combinedText.contains("college basketball") &&
                !combinedText.contains("university of phoenix") &&
                !combinedText.contains("online transfer") && // Exclude "online transfer" (not education)
                !combinedText.contains("paypal transfer")) { // Exclude "paypal transfer" (not education)
                // Only match if there's educational context (school name, course, etc.)
                if (combinedText.contains("school") || combinedText.contains("course") ||
                    combinedText.contains("tuition") || combinedText.contains("textbook") ||
                    combinedText.contains("bookstore") || combinedText.contains("book store")) {
                    return "education";
                }
            }
        }
        
        // Educational books/newspapers/magazines - but ONLY if clearly educational context
        if (combinedText.contains("books") || combinedText.contains("newspaper") ||
            combinedText.contains("magazines") || combinedText.contains("journals") ||
            combinedText.contains("library")) {
            // Exclude transfers and other non-education contexts
            if (!combinedText.contains("transfer") && !combinedText.contains("deposit") &&
                !combinedText.contains("online") && !combinedText.contains("paypal") &&
                !combinedText.contains("transaction")) {
                // Only match if there's educational context (university, school, bookstore)
                if (combinedText.contains("bookstore") || combinedText.contains("book store") ||
                    combinedText.contains("university") || combinedText.contains("school") ||
                    combinedText.contains("library")) {
                    return "education";
                }
            }
        }
        
        // ========== Shopping Category Detection (HIGHEST PRIORITY - before health, dining, transportation, utilities) ==========
        // CRITICAL: Must check shopping FIRST to avoid false positives from other categories
        // E.g., "LULULEMON" contains "lul" which matches transportation keywords, so detect it as shopping first
        // E.g., "ESTEE LAUDER" should be shopping (cosmetics), not utilities or other
        // E.g., "CHARLES TYRWHITT SHIRTS" should be shopping (clothing), not other
        
        // Clothing keywords (shirts, trousers, dresses, suits, clothing) - shopping category
        if (combinedText.contains("shirt") || combinedText.contains("trouser") || 
            combinedText.contains("dress") || combinedText.contains("suit") ||
            combinedText.contains("clothing") || combinedText.contains("apparel") ||
            combinedText.contains("garment") || combinedText.contains("wardrobe")) {
            return "shopping";
        }
        
        // Shopping stores and brands (Charles Tyrwhitt, Estee Lauder, etc.)
        // CRITICAL: Must match these BEFORE other categories (utilities, transportation, etc.)
        // NOTE: Costco, Walmart, Target are groceries (handled separately), not shopping
        String[] shoppingStores = {"lululemon", "lulu lemon", "charles tyrwhitt", "tyrwhitt",
                                   "estee lauder", "estee lauder online", "estee",
                                   "sephora", "ulta", "nordstrom",
                                   "macy's", "macys",
                                   "best buy", "bestbuy", "home depot", "homedepot",
                                   "lowe's", "lowes", "tj maxx", "tjmaxx", "marshalls"};
        for (String store : shoppingStores) {
            if (combinedText.contains(store) || merchantLower.contains(store)) {
                return "shopping";
            }
        }
        
        // Additional checks for specific shopping merchants (handle variations)
        if (combinedText.contains("estee") && combinedText.contains("lauder")) {
            return "shopping";
        }
        if (combinedText.contains("tyrwhitt") || (combinedText.contains("charles") && combinedText.contains("tyrwhitt"))) {
            return "shopping";
        }
        
        // ========== Health Category Detection (before utilities) ==========
        // CRITICAL: Must check health FIRST to avoid false positives from utilities
        
        // Sports clubs and fitness centers (must check before utilities)
        if (combinedText.contains("badminton club") || combinedText.contains("badmintonclub") ||
            combinedText.contains("seattle badminton club") || combinedText.contains("seattlebadmintonclub") ||
            combinedText.contains("fitness club") || combinedText.contains("fitnessclub") ||
            combinedText.contains("health club") || combinedText.contains("healthclub") ||
            combinedText.contains("athletic club") || combinedText.contains("athleticclub") ||
            combinedText.contains("sports club") || combinedText.contains("sportsclub") ||
            combinedText.contains("gym") || combinedText.contains("fitness center") ||
            combinedText.contains("fitnesscenter")) {
            return "health";
        }
        
        // ========== Dining Category Detection (before utilities) ==========
        // CRITICAL: Must check dining BEFORE utilities to avoid false positives
        
        // TST* pattern (Toast POS system) - must check before utilities
        if (descLower.contains("tst*") || merchantLower.contains("tst*") ||
            (merchantName != null && merchantName.toUpperCase().contains("TST*")) ||
            (description != null && description.toUpperCase().contains("TST*"))) {
            return "dining";
        }
        
        // SQ* pattern (Square POS system) - must check before utilities
        if (descLower.contains("sq*") || descLower.contains("sq *") || merchantLower.contains("sq*") ||
            (merchantName != null && (merchantName.toUpperCase().contains("SQ*") || 
                                      merchantName.toUpperCase().contains("SQ *")))) {
            return "dining";
        }
        
        // TPD (Top Pot Donuts) - must check before utilities
        if (combinedText.contains("tpd") || combinedText.contains("top pot donuts") ||
            combinedText.contains("toppotdonuts") || combinedText.contains("top pot")) {
            return "dining";
        }
        
        // Burger and Kabob Hut - must check before utilities
        if (combinedText.contains("burger and kabob hut") || combinedText.contains("burgerandkabobhut")) {
            return "dining";
        }
        
        // Deep Dive (restaurant) - must check before utilities
        if (combinedText.contains("deep dive") || combinedText.contains("deepdive")) {
            return "dining";
        }
        
        // Sunny Honey Company (SQ* pattern) - already handled by SQ* check above, but adding explicit check
        if (combinedText.contains("sunny honey") || combinedText.contains("sunnyhoney")) {
            return "dining";
        }
        
        // ========== Transportation Category Detection (before utilities for parking) ==========
        // CRITICAL: Must check transportation FIRST for parking services to avoid false positives
        
        // Parking payment services (PAY BY PHONE, ParkMobile, etc.)
        // CRITICAL: Must check for "pay by phone" BEFORE "mobile" utilities to avoid matching "XFINITY MOBILE"
        if (combinedText.contains("pay by phone") || combinedText.contains("paybyphone") ||
            combinedText.contains("uw pay by phone") || combinedText.contains("uwpaybyphone") ||
            (combinedText.contains("park") && (combinedText.contains("parkmobile") || combinedText.contains("park mobile"))) ||
            combinedText.contains("impark") || combinedText.contains("metropolis parking")) {
            return "transportation";
        }
        
        // ========== Utilities Category Detection ==========
        // CRITICAL: Must check utilities AFTER education, health, dining, and transportation to avoid false positives
        // E.g., "XFINITY MOBILE" should be utilities, not transportation
        
        // Xfinity/Comcast (cable/internet/phone providers)
        if (combinedText.contains("xfinity") || combinedText.contains("comcast") ||
            merchantLower.contains("xfinity") || merchantLower.contains("comcast")) {
            return "utilities";
        }
        
        // Other utilities providers (Spectrum, Charter, Cox, Verizon, AT&T, etc.)
        String[] utilitiesProviders = {"spectrum", "charter", "cox", "verizon", "at&t", "att", "t-mobile", "tmobile",
                                       "sprint", "dish", "directv", "centurylink", "frontier", "windstream",
                                       "optimum", "suddenlink", "mediacom", "altice", "rcn", "grande communications"};
        for (String provider : utilitiesProviders) {
            if (combinedText.contains(provider)) {
                // But exclude "park mobile" (parking service, not utilities)
                if (!combinedText.contains("park " + provider) && !combinedText.contains("park" + provider)) {
                    return "utilities";
                }
            }
        }
        
        // Electric/gas/water utilities
        if (combinedText.contains("electric") || combinedText.contains("gas company") ||
            combinedText.contains("water company") || combinedText.contains("power company") ||
            combinedText.contains("utility") || combinedText.contains("utilities")) {
            // But exclude "park mobile" (parking service)
            if (!combinedText.contains("park mobile") && !combinedText.contains("parkmobile")) {
                return "utilities";
            }
        }
        
        
        // ========== Travel Category Detection ==========
        
        // Passport services and travel documents (must check BEFORE other checks to avoid false positives)
        // Passport services are travel-related expenses
        if (combinedText.contains("passport") || combinedText.contains("passportservice") ||
            combinedText.contains("passport service") || combinedText.contains("passportservices") ||
            combinedText.contains("visa service") || combinedText.contains("visaservice") ||
            combinedText.contains("visa application") || combinedText.contains("visaapplication") ||
            combinedText.contains("global entry") || combinedText.contains("globalentry") ||
            combinedText.contains("tsa precheck") || combinedText.contains("tsaprecheck") ||
            combinedText.contains("nexus") || combinedText.contains("sentri")) {
            return "travel";
        }
        
        // Airport lounges (Centurion Lounge, Priority Pass, etc.)
        // CRITICAL: Must check BEFORE utilities to avoid false positives
        // AXP CENTURION LOUNGE should be travel, not utilities
        String[] airportLounges = {"centurion lounge", "centurionlounge", "axp centurion", "axp centurion lounge",
                                   "axp centurion", "priority pass", "prioritypass",
                                   "admirals club", "admiralsclub",
                                   "delta sky club", "deltaskyclub",
                                   "united club", "unitedclub",
                                   "american express lounge", "amex lounge",
                                   "plaza premium lounge", "plazapremiumlounge",
                                   "airport lounge", "airportlounge"};
        for (String lounge : airportLounges) {
            if (combinedText.contains(lounge)) {
                return "travel";
            }
        }
        
        // ========== Transportation Category Detection (continued) ==========
        
        // Ride-sharing (Lyft, Uber) - but NOT subscriptions (Lyft Pink, Uber One)
        if (combinedText.contains("lyft")) {
            if (!combinedText.contains("lyft pink") && !combinedText.contains("lyftpink")) {
                return "transportation";
            }
        }
        if (combinedText.contains("uber")) {
            if (!combinedText.contains("uber one") && !combinedText.contains("uberone")) {
                // Uber Eats is dining, not transportation
                if (combinedText.contains("uber eats") || combinedText.contains("ubereats")) {
                    return "dining";
                }
                return "transportation";
            }
        }
        
        // Gas stations (Exxon, Shell, Chevron, Buc-ee's, etc.)
        // CRITICAL: Buc-ee's is a gas station/convenience store chain, not shopping
        // Must be detected BEFORE shopping to avoid false positives
        String[] gasStations = {"exxon", "shell", "chevron", "bp", "mobil", 
                                 "speedway", "valero", "citgo", "phillips 66",
                                 "arco", "marathon", "sunoco", "conoco",
                                 "76 station", "76 gas", "union 76", "gas station", "gasstation",
                                 "buc-ee", "bucee", "buc-ees", "bucees", "buc-ee's"};
        for (String gas : gasStations) {
            if (combinedText.contains(gas)) {
                return "transportation";
            }
        }
        
        // ========== Pet Category Detection ==========
        
        // Pet care clinics (specialized, not general healthcare)
        if (combinedText.contains("petcare clinic") || combinedText.contains("petcareclinic") ||
            combinedText.contains("pet care clinic") || combinedText.contains("petcareclinic")) {
            return "pet";
        }
        
        // ========== Groceries Category Detection ==========
        
        // Fred Meyer and other Kroger-owned stores
        if (combinedText.contains("fred meyer") || combinedText.contains("fredmeyer") ||
            combinedText.contains("fred-meyer") || combinedText.contains("ralphs") ||
            combinedText.contains("vons") || combinedText.contains("smiths") ||
            combinedText.contains("king soopers") || combinedText.contains("qfc") ||
            combinedText.contains("fry's") || combinedText.contains("frys")) {
            return "groceries";
        }
        
        return null; // No match found
    }
    
    /**
     * Extract MCC code from transaction description if available
     * Some banks include MCC codes in transaction descriptions
     */
    private String extractMCCFromDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }
        
        // Try to extract 4-digit MCC code
        // Pattern: "MCC: 5411" or "5411" at start/end, or standalone 4-digit number
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b(\\d{4})\\b");
        java.util.regex.Matcher matcher = pattern.matcher(description);
        
        while (matcher.find()) {
            String potentialMCC = matcher.group(1);
            // Validate it's a known MCC code (basic check: 4 digits, not all zeros)
            if (!potentialMCC.equals("0000") && potentialMCC.matches("\\d{4}")) {
                // Additional validation: check if it's in a reasonable MCC range
                int mcc = Integer.parseInt(potentialMCC);
                if (mcc >= 1000 && mcc <= 9999) {
                    return potentialMCC;
                }
            }
        }
        
        return null;
    }
}

