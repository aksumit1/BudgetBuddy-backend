package com.budgetbuddy.service;

import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransactionTypeDeterminer
 */
class TransactionTypeDeterminerTest {

    private TransactionTypeDeterminer determiner;

    @BeforeEach
    void setUp() {
        determiner = new TransactionTypeDeterminer();
    }

    // ========== INVESTMENT TESTS ==========

    @Test
    void testDetermineTransactionType_InvestmentAccount_401K() {
        // Given: 401K account
        AccountTable account = createAccount("investment", "401k");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_InvestmentAccount_IRA() {
        // Given: IRA account
        AccountTable account = createAccount("investment", "ira");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_InvestmentAccount_HSA() {
        // Given: HSA account
        AccountTable account = createAccount("depository", "hsa");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_InvestmentAccount_529() {
        // Given: 529 account
        AccountTable account = createAccount("investment", "529");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_InvestmentCategory_CD() {
        // Given: CD category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "cd", BigDecimal.valueOf(-100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_InvestmentCategory_Stocks() {
        // Given: Stocks category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "investment", "stocks", BigDecimal.valueOf(-100));
        
        // Then
        assertEquals(TransactionType.INVESTMENT, result);
    }

    // ========== LOAN TESTS ==========

    @Test
    void testDetermineTransactionType_LoanAccount_Mortgage() {
        // Given: Mortgage account
        AccountTable account = createAccount("loan", "mortgage");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-1000));
        
        // Then
        assertEquals(TransactionType.LOAN, result);
    }

    @Test
    void testDetermineTransactionType_LoanAccount_CreditCard() {
        // Given: Credit card account
        AccountTable account = createAccount("credit", "credit card");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-50));
        
        // Then
        assertEquals(TransactionType.LOAN, result);
    }

    @Test
    void testDetermineTransactionType_LoanAccount_StudentLoan() {
        // Given: Student loan account
        AccountTable account = createAccount("loan", "student loan");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-200));
        
        // Then
        assertEquals(TransactionType.LOAN, result);
    }

    // ========== INCOME TESTS ==========

    @Test
    void testDetermineTransactionType_IncomeCategoryPrimary() {
        // Given: Income category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "income", "salary", BigDecimal.valueOf(5000));
        
        // Then
        assertEquals(TransactionType.INCOME, result);
    }

    @Test
    void testDetermineTransactionType_IncomeCategoryDetailed_Interest() {
        // Given: Interest category
        AccountTable account = createAccount("depository", "savings");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "interest", BigDecimal.valueOf(100));
        
        // Then
        assertEquals(TransactionType.INCOME, result);
    }

    @Test
    void testDetermineTransactionType_IncomeCategoryDetailed_Salary() {
        // Given: Salary category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "salary", BigDecimal.valueOf(5000));
        
        // Then
        assertEquals(TransactionType.INCOME, result);
    }

    @Test
    void testDetermineTransactionType_Income_PositiveAmount() {
        // Given: Positive amount without expense category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(100));
        
        // Then
        assertEquals(TransactionType.INCOME, result);
    }

    // ========== EXPENSE TESTS ==========

    @Test
    void testDetermineTransactionType_Expense_NegativeAmount() {
        // Given: Negative amount with expense category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "groceries", "groceries", BigDecimal.valueOf(-50));
        
        // Then
        assertEquals(TransactionType.EXPENSE, result);
    }

    @Test
    void testDetermineTransactionType_Expense_Default() {
        // Given: No account, no category
        // When
        TransactionType result = determiner.determineTransactionType(
                null, null, null, BigDecimal.valueOf(-50));
        
        // Then
        assertEquals(TransactionType.EXPENSE, result);
    }

    @Test
    void testDetermineTransactionType_Expense_Dining() {
        // Given: Dining category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "dining", "dining", BigDecimal.valueOf(-30));
        
        // Then
        assertEquals(TransactionType.EXPENSE, result);
    }

    // ========== PRIORITY TESTS ==========

    @Test
    void testDetermineTransactionType_Priority_InvestmentOverIncome() {
        // Given: Investment account with income category
        AccountTable account = createAccount("investment", "401k");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "income", "interest", BigDecimal.valueOf(100));
        
        // Then: Investment takes priority
        assertEquals(TransactionType.INVESTMENT, result);
    }

    @Test
    void testDetermineTransactionType_Priority_LoanOverExpense() {
        // Given: Loan account with expense category
        AccountTable account = createAccount("loan", "mortgage");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "other", "other", BigDecimal.valueOf(-1000));
        
        // Then: Loan takes priority
        assertEquals(TransactionType.LOAN, result);
    }

    @Test
    void testDetermineTransactionType_Priority_InvestmentCategoryOverIncome() {
        // Given: Investment category (CD) with income category
        AccountTable account = createAccount("depository", "checking");
        
        // When
        TransactionType result = determiner.determineTransactionType(
                account, "income", "cd", BigDecimal.valueOf(100));
        
        // Then: Investment category takes priority
        assertEquals(TransactionType.INVESTMENT, result);
    }

    // ========== HELPER METHODS ==========

    private AccountTable createAccount(String accountType, String accountSubtype) {
        AccountTable account = new AccountTable();
        account.setAccountType(accountType);
        account.setAccountSubtype(accountSubtype);
        return account;
    }
}

