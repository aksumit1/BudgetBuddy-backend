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
    // Note: fuzzyMatchingService is used via enhancedCategoryDetection, not directly

    public TransactionTypeCategoryService(
            TransactionTypeDeterminer transactionTypeDeterminer,
            PlaidCategoryMapper plaidCategoryMapper,
            ImportCategoryParser importCategoryParser,
            EnhancedCategoryDetectionService enhancedCategoryDetection,
            ImportCategoryConfig importCategoryConfig,
            GlobalFinancialConfig globalFinancialConfig,
            CircuitBreakerService circuitBreakerService) {
        this.transactionTypeDeterminer = transactionTypeDeterminer;
        this.plaidCategoryMapper = plaidCategoryMapper;
        this.importCategoryParser = importCategoryParser;
        this.enhancedCategoryDetection = enhancedCategoryDetection;
        this.importCategoryConfig = importCategoryConfig;
        this.globalFinancialConfig = globalFinancialConfig;
        this.circuitBreakerService = circuitBreakerService;
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
        
        // Credit card accounts: 
        // +amount (charge) = EXPENSE
        // -amount (payment to card) = LOAN (with category "payment")
        if (accountInfo.isCreditCard) {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined from account type string: Credit card charge → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                logger.debug("Transaction type determined from account type string: Credit card payment → LOAN");
                return new TypeResult(TransactionType.LOAN, "ACCOUNT_TYPE", 0.95);
            }
        }
        
        // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.): 
        // Both +amount (credit/transfer in/dividend) and -amount (debit/fees/withdrawal) = INVESTMENT type
        // Categories will distinguish: fees, cash, withdrawal, purchase
        if (accountInfo.isInvestment) {
            logger.debug("Transaction type determined from account type string: Investment account ({}) → INVESTMENT", accountType);
            return new TypeResult(TransactionType.INVESTMENT, "ACCOUNT_TYPE", 0.95);
        }
        
        // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
        // +amount (payment) = LOAN (with category "payment")
        // -amount (increase in loan/withdrawal) = LOAN
        if (accountInfo.isLoan) {
            logger.debug("Transaction type determined from account type string: Loan account ({}) → LOAN", accountType);
            return new TypeResult(TransactionType.LOAN, "ACCOUNT_TYPE", 0.95);
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
        
        // Priority 0: Account type-based intelligent inference (highest priority for account-specific logic)
        if (account != null && account.getAccountType() != null) {
            AccountTypeInfo accountInfo = getAccountTypeInfo(account);
            
            // Credit card accounts: 
            // +amount (charge) = EXPENSE
            // -amount (payment to card) = LOAN (with category "payment")
            if (accountInfo.isCreditCard) {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    logger.debug("Transaction type determined from account type: Credit card charge → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    logger.debug("Transaction type determined from account type: Credit card payment → LOAN");
                    return new TypeResult(TransactionType.LOAN, "ACCOUNT_TYPE", 0.95);
                }
            }
            
            // Investment accounts (401k, IRA, HSA, 529, brokerage, CD, etc.): 
            // Both +amount (credit/transfer in/dividend) and -amount (debit/fees/withdrawal) = INVESTMENT type
            // Categories will distinguish: fees, cash, withdrawal, purchase
            if (accountInfo.isInvestment) {
                logger.debug("Transaction type determined from account type: Investment account ({}) → INVESTMENT", account.getAccountType());
                return new TypeResult(TransactionType.INVESTMENT, "ACCOUNT_TYPE", 0.95);
            }
            
            // Loan accounts (mortgage, student loan, car loan, personal loan, etc.):
            // +amount (payment) = LOAN (with category "payment")
            // -amount (increase in loan/withdrawal) = LOAN
            if (accountInfo.isLoan) {
                logger.debug("Transaction type determined from account type: Loan account ({}) → LOAN", account.getAccountType());
                return new TypeResult(TransactionType.LOAN, "ACCOUNT_TYPE", 0.95);
            }
            
            // Checking/Savings/Money Market accounts:
            // +amount (credit) = INCOME
            // -amount (debit) = EXPENSE (unless it's a credit card payment, then LOAN)
            if (accountInfo.isCheckingOrSavings) {
                // Positive amount (credit) on checking/savings = INCOME (always)
                // This includes: payroll deposits, rental income, transfers in, interest, etc.
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    logger.debug("Transaction type determined from account type: Credit on checking/savings account → INCOME");
                    return new TypeResult(TransactionType.INCOME, "ACCOUNT_TYPE", 0.95);
                }
                // Negative amount (debit) on checking/savings = EXPENSE
                // EXCEPTION: Credit card payments are LOAN, not EXPENSE
                if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    // Check if this is a credit card payment before defaulting to EXPENSE
                    String descLower = description != null ? description.toLowerCase() : "";
                    if (isCreditCardPayment(description, null, categoryPrimary, account)) {
                        logger.debug("Transaction type determined from account type: Credit card payment on checking account → LOAN");
                        return new TypeResult(TransactionType.LOAN, "ACCOUNT_TYPE", 0.95);
                    }
                    logger.debug("Transaction type determined from account type: Debit on checking/savings account → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "ACCOUNT_TYPE", 0.95);
                }
            }
        }
        
        // Priority 1: Category-based type determination (before account/amount logic)
        if (categoryPrimary != null) {
            String categoryLower = categoryPrimary.toLowerCase();
            String categoryDetailedLower = categoryDetailed != null ? categoryDetailed.toLowerCase() : null;
            
            // CRITICAL FIX: Payment category should only be LOAN if it's actually a loan payment
            // On checking accounts, "payment" with positive amount (credit) should be INCOME
            // "payment" with "utilities" detailed category should be EXPENSE
            // "payment" for checks/transfers should be EXPENSE
            // Only actual loan payments (credit card, mortgage, student loan, etc.) should be LOAN
            if ("payment".equals(categoryLower)) {
                String descLower = description != null ? description.toLowerCase() : "";
                String combinedText = descLower; // merchantName not available in determineTransactionType
                
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
                
                // Check if it's a transfer (check, wire, etc.) = EXPENSE
                if (descLower.contains("check") || descLower.contains("wire") || 
                    descLower.contains("transfer") || descLower.contains("ach transfer")) {
                    logger.debug("Transaction type determined: payment/transfer → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
                }
                
                // CRITICAL: Only map to LOAN if it's actually a loan payment
                // Check for loan payment keywords: credit card, mortgage, student loan, car loan, personal loan, home loan, etc.
                if (isLoanPayment(combinedText, account)) {
                    logger.debug("Transaction type determined: payment category is actual loan payment → LOAN");
                    return new TypeResult(TransactionType.LOAN, "CATEGORY", 0.95);
                }
                
                // If it's not a loan payment and not on a loan account, default to EXPENSE
                // This handles generic "payment" categories that aren't loan-related
                boolean isLoanAccount = account != null && account.getAccountType() != null &&
                    isLoanAccountType(account.getAccountType());
                
                if (!isLoanAccount) {
                    logger.debug("Transaction type determined: payment category is not a loan payment → EXPENSE");
                    return new TypeResult(TransactionType.EXPENSE, "CATEGORY_OVERRIDE", 0.95);
                }
                
                // If it's on a loan account, it's likely a loan payment
                logger.debug("Transaction type: payment category on loan account → LOAN");
            }
            
            // Deposit category with positive amount → INCOME type
            if ("deposit".equals(categoryLower) && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("Transaction type determined from category: deposit → INCOME");
                return new TypeResult(TransactionType.INCOME, "CATEGORY", 0.95);
            }
            
            // Investment category → INVESTMENT type
            if ("investment".equals(categoryLower)) {
                logger.debug("Transaction type determined from category: investment → INVESTMENT");
                return new TypeResult(TransactionType.INVESTMENT, "CATEGORY", 0.95);
            }
            
            // Income category → INCOME type
            if ("income".equals(categoryLower) || "salary".equals(categoryLower)) {
                logger.debug("Transaction type determined from category: {} → INCOME", categoryLower);
                return new TypeResult(TransactionType.INCOME, "CATEGORY", 0.95);
            }
            
            // Utilities category → EXPENSE type (even if primary is "payment")
            if ("utilities".equals(categoryLower) || "utilities".equals(categoryDetailedLower)) {
                logger.debug("Transaction type determined from category: utilities → EXPENSE");
                return new TypeResult(TransactionType.EXPENSE, "CATEGORY", 0.95);
            }
        }
        
        // Priority 2: Check for loan payments (credit card, mortgage, student loan, car loan, etc.)
        // This should be checked before debit/credit indicators to ensure loan payments are correctly identified
        String descLower = description != null ? description.toLowerCase() : "";
        String combinedText = descLower; // merchantName not available in determineTransactionType
        
        if (isLoanPayment(combinedText, account) || isCreditCardPayment(description, null, null, account)) {
            logger.debug("Transaction type determined: Loan payment detected → LOAN");
            return new TypeResult(TransactionType.LOAN, "HYBRID", 0.95);
        }
        
        // Use existing TransactionTypeDeterminer as base (account + category + amount)
        TransactionType baseType = transactionTypeDeterminer.determineTransactionType(
            account, categoryPrimary, categoryDetailed, amount);
        
        // CRITICAL FIX: Override LOAN type for checking accounts with positive amounts (credits)
        // Credits on checking accounts should always be INCOME, not LOAN
        // This handles cases like payroll deposits, rental income, transfers in, etc.
        if (baseType == TransactionType.LOAN && account != null && account.getAccountType() != null) {
            String accountTypeLower = account.getAccountType().toLowerCase();
            boolean isCheckingAccount = accountTypeLower.contains("checking") ||
                                       accountTypeLower.contains("depository") ||
                                       accountTypeLower.contains("savings");
            
            if (isCheckingAccount && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                // Positive amount (credit) on checking account should always be INCOME, not LOAN
                logger.info("🏷️ Overriding LOAN type to INCOME for credit on checking account: amount={}, accountType={}", 
                        amount, account.getAccountType());
                baseType = TransactionType.INCOME;
            }
        }
        
        
        // CRITICAL FIX: Override LOAN type for utilities category
        // Utilities should always be EXPENSE, not LOAN
        if (baseType == TransactionType.LOAN && categoryDetailed != null) {
            String categoryDetailedLower = categoryDetailed.toLowerCase();
            if ("utilities".equals(categoryDetailedLower)) {
                logger.info("🏷️ Overriding LOAN type to EXPENSE for utilities category");
                baseType = TransactionType.EXPENSE;
            }
        }
        
        // Priority 3: Enhance with transaction parsing logic (debit/credit indicators)
        if (transactionTypeIndicator != null && !transactionTypeIndicator.trim().isEmpty()) {
            String indicator = transactionTypeIndicator.trim().toLowerCase();
            
            // Debit indicator → EXPENSE (unless already determined as investment/loan)
            if ((indicator.contains("debit") || indicator.contains("dr") || 
                 indicator.startsWith("db ") || indicator.startsWith("dr ") ||
                 indicator.equals("db") || indicator.equals("dr")) &&
                baseType != TransactionType.INVESTMENT && baseType != TransactionType.LOAN) {
                logger.debug("Transaction type enhanced: Debit indicator → EXPENSE (was: {})", baseType);
                return new TypeResult(TransactionType.EXPENSE, "HYBRID", 0.95);
            }
            
            // Credit indicator → INCOME (unless already determined as investment/loan)
            if ((indicator.contains("credit") || indicator.contains("cr") ||
                 indicator.startsWith("cr ") || indicator.equals("cr")) &&
                baseType != TransactionType.INVESTMENT && baseType != TransactionType.LOAN) {
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
    @Cacheable(value = "categoryDetermination", key = "#importerCategoryPrimary + '_' + #importerCategoryDetailed + '_' + (#account?.accountType ?: 'null') + '_' + (#merchantName ?: 'null') + '_' + (#description ?: 'null') + '_' + (#amount ?: 'null') + '_' + (#paymentChannel ?: 'null') + '_' + (#transactionTypeIndicator ?: 'null') + '_' + (#importSource ?: 'null') + '_' + (#account?.currencyCode ?: 'null')")
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
        } else if (!"PLAID".equals(importSource)) {
            // For other imports, use parser
            parserCategory = importCategoryParser.parseCategory(
                null, description, merchantName, amount, paymentChannel, transactionTypeIndicator);
        } else {
            // For Plaid, also get parser category as additional signal
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
        
        // Step 4: Get account-based hints
        String accountHint = getAccountCategoryHint(account);
        
        // Step 5: Apply iOS fallback logic (consolidated from iOS BackendModels.swift)
        // ✅ COMPLETED: iOS fallback logic is now fully consolidated in backend
        // This ensures consistent behavior across iOS and backend
        String fallbackCategory = applyIOSFallbackLogic(
            mappedImporterPrimary, mappedImporterDetailed,
            parserCategory,
            merchantName, description, amount, paymentChannel);
        
        // Step 6: Reason with rules to determine best category
        return reasonCategory(
            mappedImporterPrimary, mappedImporterDetailed,
            parserCategory,
            mlCategory, mlConfidence,
            accountHint,
            merchantName, description, amount, paymentChannel, importSource, fallbackCategory);
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
        
        // Rule 1: ACH credits (positive amounts with paymentChannel == "ach") should be income/deposit
        // This overrides any category that might have been incorrectly assigned
        if ("ach".equalsIgnoreCase(paymentChannel) && amount.compareTo(BigDecimal.ZERO) > 0) {
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
            String paymentChannel, String importSource, String fallbackCategory) {
        
        // Rule 0: If iOS fallback logic detected a category, use it (high priority)
        if (fallbackCategory != null && !fallbackCategory.isEmpty()) {
            logger.debug("Using iOS fallback category: {}", fallbackCategory);
            return new CategoryResult(fallbackCategory, fallbackCategory, "IOS_FALLBACK", 0.9);
        }
        
        // CRITICAL FIX: On checking accounts, credits should NOT be "payment" - they should be income/deposit
        // This fixes: Costco cash reward, Grisalin Management (rental income), Amazon Payroll, etc.
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // Positive amount = credit on checking account
            String descLower = description != null ? description.toLowerCase() : "";
            String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
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
        
        // CRITICAL FIX: Utilities should never be "payment" - they should be "utilities"
        // This fixes: Utilities showing as Loan/Utilities instead of Expense/Utilities
        String descLower = description != null ? description.toLowerCase() : "";
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String combinedText = (merchantLower + " " + descLower).trim();
        
        // Check for utilities keywords
        boolean isUtilities = combinedText.contains("electric") || combinedText.contains("electricity") ||
                             combinedText.contains("water") || combinedText.contains("gas") ||
                             combinedText.contains("internet") || combinedText.contains("phone") ||
                             combinedText.contains("cable") || combinedText.contains("utility") ||
                             combinedText.contains("utilities") || merchantLower.contains("utility");
        
        if (isUtilities && ("payment".equalsIgnoreCase(importerPrimary) || "payment".equalsIgnoreCase(parserCategory) ||
                            "payment".equalsIgnoreCase(mlCategory))) {
            logger.info("🏷️ reasonCategory: Overriding 'payment' to 'utilities' for utilities transaction");
            return new CategoryResult("utilities", "utilities", "RULE_OVERRIDE", 0.95);
        }
        
        // CRITICAL FIX: Checks/transfers should be "transfer", not "payment"
        // This fixes: Checks showing as Loan/Payment instead of Expense/Transfer
        boolean isTransfer = descLower.contains("check") || descLower.contains("wire") ||
                            descLower.contains("transfer") || descLower.contains("ach transfer") ||
                            merchantLower.contains("check") || merchantLower.contains("wire");
        
        if (isTransfer && ("payment".equalsIgnoreCase(importerPrimary) || "payment".equalsIgnoreCase(parserCategory) ||
                           "payment".equalsIgnoreCase(mlCategory))) {
            logger.info("🏷️ reasonCategory: Overriding 'payment' to 'transfer' for check/transfer transaction");
            return new CategoryResult("transfer", "transfer", "RULE_OVERRIDE", 0.95);
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
        
        // Rule 7: Prefer parser over importer if importer is generic
        if (parserCategory != null && 
            (importerPrimary == null || "other".equals(importerPrimary) || "UNKNOWN_CATEGORY".equals(importerPrimary))) {
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.8);
        }
        
        // Rule 8: Fallback to importer category
        if (importerPrimary != null && !importerPrimary.isEmpty()) {
            return new CategoryResult(importerPrimary,
                importerDetailed != null ? importerDetailed : importerPrimary,
                "IMPORTER", 0.7);
        }
        
        // Rule 9: Fallback to parser category
        if (parserCategory != null) {
            return new CategoryResult(parserCategory, parserCategory, "PARSER", 0.7);
        }
        
        // Rule 10: Last resort
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
     * Checks if a payment is actually a loan payment (credit card, mortgage, student loan, car loan, personal loan, home loan, etc.)
     * This is used to determine if "payment" category should map to LOAN type
     * 
     * @param combinedText Combined merchant name and description text (lowercase)
     * @param account Account associated with the transaction
     * @return true if this is a loan payment, false otherwise
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
}

