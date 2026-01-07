package com.budgetbuddy.model;

/**
 * Transaction type enumeration
 * Represents the high-level classification of a transaction
 * 
 * Transaction types are account-specific:
 * - Credit Cards: EXPENSE (charges), PAYMENT (payments)
 * - Checking/Savings: INCOME (credits), PAYMENT (debt payments), EXPENSE (debits)
 * - Investment: INCOME (dividends/interest), EXPENSE (fees), INVESTMENT (purchases)
 * - Loan: PAYMENT (payments), EXPENSE (fees), CREDIT (disbursements)
 */
public enum TransactionType {
    /**
     * Income transactions - money coming in (salary, interest, dividends, etc.)
     * Used for: Checking/Savings (+ve), Investment accounts (dividends, interest)
     */
    INCOME,
    
    /**
     * Investment transactions - money used to purchase investment instruments
     * (401K contributions, IRA contributions, stock purchases, bond purchases, mutual fund purchases, etc.)
     * Used for: Investment accounts (-ve amounts for purchases)
     */
    INVESTMENT,
    
    /**
     * Payment transactions - payments made to credit cards, loans, or other debts
     * (AUTOPAY, PAYMENT RECEIVED, automatic payment, e-payment, full payment, etc.)
     * Used for: Credit Cards (-ve), Checking/Savings (-ve for credit card payments), Loan accounts (+ve with payment keywords)
     */
    PAYMENT,
    
    /**
     * Expense transactions - money going out for purchases, bills, fees, etc.
     * Used for: Credit Cards (+ve charges), Checking/Savings (-ve debits), Investment accounts (-ve fees), Loan accounts (-ve fees)
     */
    EXPENSE
}

