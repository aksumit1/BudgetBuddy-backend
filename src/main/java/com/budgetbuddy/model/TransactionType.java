package com.budgetbuddy.model;

/**
 * Transaction type enumeration
 * Represents the high-level classification of a transaction
 */
public enum TransactionType {
    /**
     * Income transactions - money coming in (salary, interest, dividends, etc.)
     */
    INCOME,
    
    /**
     * Investment transactions - money going into investments (401K, IRA, HSA, 529, CDs, bonds, stocks, etc.)
     */
    INVESTMENT,
    
    /**
     * Loan transactions - payments or charges related to loans (mortgage, credit card, student loan, etc.)
     */
    LOAN,
    
    /**
     * Expense transactions - money going out for purchases, bills, etc.
     */
    EXPENSE
}

