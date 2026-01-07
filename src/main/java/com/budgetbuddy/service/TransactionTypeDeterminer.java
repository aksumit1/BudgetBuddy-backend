package com.budgetbuddy.service;

import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Determines the transaction type (Income, Investment, Payment, Expense) based on:
 * - Account type (for Investment and Payment)
 * - Transaction category (for Investment and Income)
 * - Amount sign (for Income vs Expense)
 * 
 * Note: LOAN type has been removed. Payments are now PAYMENT type.
 */
@Component
public class TransactionTypeDeterminer {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionTypeDeterminer.class);
    
    /**
     * Determines transaction type based on account type, category, and amount
     * 
     * Priority order:
     * 1. Investment: Account type is investment-related OR category is investment-related
     * 2. Loan: Account type is loan-related
     * 3. Income: categoryPrimary is "income" OR positive amount with income categories
     * 4. Expense: Everything else (default)
     * 
     * @param account Account associated with the transaction (can be null)
     * @param categoryPrimary Primary category (e.g., "income", "investment", "other")
     * @param categoryDetailed Detailed category (e.g., "interest", "cd", "groceries")
     * @param amount Transaction amount (positive = income, negative = expense typically)
     * @return TransactionType (INCOME, INVESTMENT, PAYMENT, or EXPENSE)
     */
    public TransactionType determineTransactionType(final AccountTable account,
                                                    final String categoryPrimary,
                                                    final String categoryDetailed,
                                                    final BigDecimal amount) {
        if (account == null && categoryPrimary == null && categoryDetailed == null) {
            // No information available - default to expense
            logger.debug("No account or category information available, defaulting to EXPENSE");
            return TransactionType.EXPENSE;
        }
        
        String accountType = account != null ? account.getAccountType() : null;
        String accountSubtype = account != null ? account.getAccountSubtype() : null;
        String categoryPrimaryLower = categoryPrimary != null ? categoryPrimary.toLowerCase() : null;
        String categoryDetailedLower = categoryDetailed != null ? categoryDetailed.toLowerCase() : null;
        
        // CRITICAL: ACH credits with "deposit" category should be INCOME, not INVESTMENT
        // Check this BEFORE investment account/category checks to ensure ACH credits are always income
        // "deposit" with "income" primary is always income (e.g., ACH Electronic Credit from Gusto)
        if ("income".equals(categoryPrimaryLower) && "deposit".equals(categoryDetailedLower)) {
            logger.debug("Transaction determined as INCOME based on income/deposit category (ACH credit)");
            return TransactionType.INCOME;
        }
        
        // 1. INVESTMENT: Check account type first (highest priority)
        if (isInvestmentAccount(accountType, accountSubtype)) {
            logger.debug("Transaction determined as INVESTMENT based on account type: {} / {}", accountType, accountSubtype);
            return TransactionType.INVESTMENT;
        }
        
        // 2. INVESTMENT: Check category (CD deposits, stocks, bonds, etc.)
        if (isInvestmentCategory(categoryPrimaryLower, categoryDetailedLower)) {
            logger.debug("Transaction determined as INVESTMENT based on category: {} / {}", categoryPrimary, categoryDetailed);
            return TransactionType.INVESTMENT;
        }
        
        // 3. Payment/Loan accounts: These are handled by TransactionTypeCategoryService
        // TransactionTypeDeterminer doesn't determine PAYMENT type - that's done by TransactionTypeCategoryService
        // based on payment patterns and account context
        
        // 4. INCOME: Check category primary
        if ("income".equals(categoryPrimaryLower)) {
            logger.debug("Transaction determined as INCOME based on categoryPrimary: {}", categoryPrimary);
            return TransactionType.INCOME;
        }
        
        // 5. INCOME: Check for income-related detailed categories
        if (isIncomeCategory(categoryDetailedLower)) {
            logger.debug("Transaction determined as INCOME based on categoryDetailed: {}", categoryDetailed);
            return TransactionType.INCOME;
        }
        
        // 6. INCOME: Positive amount (but not investment or loan)
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            // Positive amount is typically income, unless it's clearly an expense category
            // "other" category with positive amount should be income (not expense)
            if (categoryPrimaryLower == null || 
                !isExpenseCategory(categoryPrimaryLower, categoryDetailedLower) ||
                "other".equals(categoryPrimaryLower)) {
                logger.debug("Transaction determined as INCOME based on positive amount: {}", amount);
                return TransactionType.INCOME;
            }
        }
        
        // 7. EXPENSE: Default for everything else
        logger.debug("Transaction determined as EXPENSE (default)");
        return TransactionType.EXPENSE;
    }
    
    /**
     * Checks if account type is investment-related
     * Investment accounts: 401K, IRA, HSA, 529, Certificates, Bonds, Treasury, money market
     */
    private boolean isInvestmentAccount(final String accountType, final String accountSubtype) {
        if (accountType == null) {
            return false;
        }
        
        String accountTypeLower = accountType.toLowerCase();
        String accountSubtypeLower = accountSubtype != null ? accountSubtype.toLowerCase() : null;
        
        // Check account type
        if (accountTypeLower.contains("investment") ||
            accountTypeLower.contains("401k") ||
            accountTypeLower.contains("401(k)") ||
            accountTypeLower.contains("ira") ||
            accountTypeLower.contains("hsa") ||
            accountTypeLower.contains("529") ||
            accountTypeLower.contains("certificate") ||
            accountTypeLower.contains("cd") ||
            accountTypeLower.contains("bond") ||
            accountTypeLower.contains("treasury") ||
            accountTypeLower.contains("money market") ||
            accountTypeLower.contains("moneymarket") ||
            accountTypeLower.contains("brokerage") ||
            accountTypeLower.contains("retirement")) {
            return true;
        }
        
        // Check account subtype
        if (accountSubtypeLower != null) {
            if (accountSubtypeLower.contains("401k") ||
                accountSubtypeLower.contains("401(k)") ||
                accountSubtypeLower.contains("ira") ||
                accountSubtypeLower.contains("hsa") ||
                accountSubtypeLower.contains("529") ||
                accountSubtypeLower.contains("certificate") ||
                accountSubtypeLower.contains("cd") ||
                accountSubtypeLower.contains("bond") ||
                accountSubtypeLower.contains("treasury") ||
                accountSubtypeLower.contains("money market") ||
                accountSubtypeLower.contains("moneymarket") ||
                accountSubtypeLower.contains("brokerage") ||
                accountSubtypeLower.contains("retirement")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if category is investment-related
     * Investment categories: investment, cd, stocks, bonds, etc.
     */
    private boolean isInvestmentCategory(final String categoryPrimary, final String categoryDetailed) {
        if (categoryPrimary == null && categoryDetailed == null) {
            return false;
        }
        
        // Check primary category
        if (categoryPrimary != null) {
            String primaryLower = categoryPrimary.toLowerCase();
            if (primaryLower.equals("investment")) {
                return true;
            }
        }
        
        // Check detailed category
        if (categoryDetailed != null) {
            String detailedLower = categoryDetailed.toLowerCase();
            if (detailedLower.equals("cd") ||
                detailedLower.equals("stocks") ||
                detailedLower.equals("bonds") ||
                detailedLower.equals("treasury") ||
                detailedLower.equals("tbills") ||
                detailedLower.equals("municipalbonds") ||
                detailedLower.equals("mutualfunds") ||
                detailedLower.equals("etf") ||
                detailedLower.equals("ira") ||
                detailedLower.equals("fourzeroonek") ||
                detailedLower.equals("fivetwonine") ||
                detailedLower.equals("otherinvestment") ||
                detailedLower.equals("preciousmetals") ||
                detailedLower.equals("crypto") ||
                detailedLower.equals("moneymarket")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if account type is loan-related
     * Loan accounts: mortgage, credit card, home loan, student loan
     */
    private boolean isLoanAccount(final String accountType, final String accountSubtype) {
        if (accountType == null) {
            return false;
        }
        
        String accountTypeLower = accountType.toLowerCase();
        String accountSubtypeLower = accountSubtype != null ? accountSubtype.toLowerCase() : null;
        
        // Check account type
        if (accountTypeLower.contains("loan") ||
            accountTypeLower.contains("mortgage") ||
            accountTypeLower.contains("credit card") ||
            accountTypeLower.contains("creditcard") ||
            accountTypeLower.contains("student loan") ||
            accountTypeLower.contains("studentloan") ||
            accountTypeLower.contains("home loan") ||
            accountTypeLower.contains("homeloan")) {
            return true;
        }
        
        // Check account subtype
        if (accountSubtypeLower != null) {
            if (accountSubtypeLower.contains("loan") ||
                accountSubtypeLower.contains("mortgage") ||
                accountSubtypeLower.contains("credit card") ||
                accountSubtypeLower.contains("creditcard") ||
                accountSubtypeLower.contains("student loan") ||
                accountSubtypeLower.contains("studentloan") ||
                accountSubtypeLower.contains("home loan") ||
                accountSubtypeLower.contains("homeloan")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if category is income-related
     * Income categories: interest, salary, dividend, stipend, rentIncome, tips, otherIncome, deposit
     */
    private boolean isIncomeCategory(final String categoryDetailed) {
        if (categoryDetailed == null) {
            return false;
        }
        
        String detailedLower = categoryDetailed.toLowerCase();
        return detailedLower.equals("interest") ||
               detailedLower.equals("salary") ||
               detailedLower.equals("dividend") ||
               detailedLower.equals("stipend") ||
               detailedLower.equals("rentincome") ||
               detailedLower.equals("tips") ||
               detailedLower.equals("otherincome") ||
               detailedLower.equals("deposit");
    }
    
    /**
     * Checks if category is expense-related
     * Expense categories: groceries, dining, transportation, shopping, etc.
     */
    private boolean isExpenseCategory(final String categoryPrimary, final String categoryDetailed) {
        if (categoryPrimary == null && categoryDetailed == null) {
            return false;
        }
        
        // Common expense categories
        String[] expenseCategories = {
            "groceries", "dining", "transportation", "shopping", "entertainment",
            "utilities", "rent", "healthcare", "travel", "subscriptions", "other"
        };
        
        if (categoryPrimary != null) {
            String primaryLower = categoryPrimary.toLowerCase();
            for (String expense : expenseCategories) {
                if (primaryLower.equals(expense)) {
                    return true;
                }
            }
        }
        
        if (categoryDetailed != null) {
            String detailedLower = categoryDetailed.toLowerCase();
            for (String expense : expenseCategories) {
                if (detailedLower.equals(expense)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}

