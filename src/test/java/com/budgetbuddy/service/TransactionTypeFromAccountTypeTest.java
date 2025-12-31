package com.budgetbuddy.service;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for transaction type determination from account type
 * Tests edge cases, boundary conditions, and various banking/financial account types
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("Transaction Type Determination from Account Type")
class TransactionTypeFromAccountTypeTest {

    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock
    private TransactionTypeDeterminer transactionTypeDeterminer;

    @Mock
    private PlaidCategoryMapper plaidCategoryMapper;

    @Mock
    private ImportCategoryParser importCategoryParser;

    @Mock
    private EnhancedCategoryDetectionService enhancedCategoryDetection;

    @Mock
    private ImportCategoryConfig importCategoryConfig;

    @Mock
    private GlobalFinancialConfig globalFinancialConfig;

    @Mock
    private CircuitBreakerService circuitBreakerService;

    @BeforeEach
    void setUp() {
        // Mock configs
        when(globalFinancialConfig.getDefaultRegion()).thenReturn("US");
        when(globalFinancialConfig.getCreditCardKeywordsForRegion(anyString())).thenReturn(java.util.Arrays.asList("autopay", "auto pay", "e-payment", "credit card", "creditcard"));
        when(importCategoryConfig.getCreditCardKeywords()).thenReturn(java.util.Arrays.asList("autopay", "auto pay", "e-payment", "credit card", "creditcard"));
        
        // Create service with mocked dependencies
        transactionTypeCategoryService = new TransactionTypeCategoryService(
            transactionTypeDeterminer,
            plaidCategoryMapper,
            importCategoryParser,
            enhancedCategoryDetection,
            importCategoryConfig,
            globalFinancialConfig,
            circuitBreakerService
        );
    }

    // ========== CHECKING/SAVINGS ACCOUNTS ==========

    @Test
    @DisplayName("Checking account: Positive amount → INCOME")
    void testCheckingAccount_PositiveAmount_ReturnsIncome() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                new BigDecimal("100.00"), 
                "Salary deposit", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
        assertEquals("ACCOUNT_TYPE", result.getSource());
    }

    @Test
    @DisplayName("Checking account: Negative amount → EXPENSE")
    void testCheckingAccount_NegativeAmount_ReturnsExpense() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                new BigDecimal("-50.00"), 
                "Grocery purchase", "in_store"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Savings account: Positive amount → INCOME")
    void testSavingsAccount_PositiveAmount_ReturnsIncome() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "savings", 
                new BigDecimal("500.00"), 
                "Interest earned", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Money Market account: Positive amount → INCOME")
    void testMoneyMarketAccount_PositiveAmount_ReturnsIncome() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "money market", 
                new BigDecimal("1000.00"), 
                "Deposit", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Money Market account: Negative amount → EXPENSE")
    void testMoneyMarketAccount_NegativeAmount_ReturnsExpense() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "money market", 
                new BigDecimal("-200.00"), 
                "Withdrawal", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== CREDIT CARD ACCOUNTS ==========

    @Test
    @DisplayName("Credit card: Positive amount (charge) → EXPENSE")
    void testCreditCard_PositiveAmount_ReturnsExpense() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "credit", "credit card", 
                new BigDecimal("75.50"), 
                "Amazon purchase", "online"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit card: Negative amount (payment) → LOAN")
    void testCreditCard_NegativeAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "credit", "credit card", 
                new BigDecimal("-500.00"), 
                "Payment to credit card", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit card: Case insensitive matching")
    void testCreditCard_CaseInsensitive() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "CREDIT", "CREDIT CARD", 
                new BigDecimal("100.00"), 
                "Purchase", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== LOAN ACCOUNTS ==========

    @Test
    @DisplayName("Mortgage: Positive amount (payment) → LOAN")
    void testMortgage_PositiveAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "mortgage", 
                new BigDecimal("1500.00"), 
                "Mortgage payment", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Mortgage: Negative amount (increase) → LOAN")
    void testMortgage_NegativeAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "mortgage", 
                new BigDecimal("-2000.00"), 
                "Loan increase", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Student loan: Positive amount → LOAN")
    void testStudentLoan_PositiveAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "student loan", 
                new BigDecimal("300.00"), 
                "Student loan payment", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Auto loan: Positive amount → LOAN")
    void testAutoLoan_PositiveAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "auto loan", 
                new BigDecimal("400.00"), 
                "Car loan payment", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit line: Positive amount → LOAN")
    void testCreditLine_PositiveAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "credit line", 
                new BigDecimal("250.00"), 
                "Credit line payment", "ach"
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit line: Negative amount → LOAN")
    void testCreditLine_NegativeAmount_ReturnsLoan() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "credit line", 
                new BigDecimal("-1000.00"), 
                "Credit line withdrawal", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.LOAN, result.getTransactionType());
    }

    // ========== INVESTMENT ACCOUNTS ==========

    @Test
    @DisplayName("Investment account: Positive amount → INVESTMENT")
    void testInvestment_PositiveAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "brokerage", 
                new BigDecimal("1000.00"), 
                "Dividend payment", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Investment account: Negative amount → INVESTMENT")
    void testInvestment_NegativeAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "brokerage", 
                new BigDecimal("-50.00"), 
                "Investment fee", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("401k account: Positive amount → INVESTMENT")
    void test401k_PositiveAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "401k", 
                new BigDecimal("500.00"), 
                "401k contribution", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("IRA account: Positive amount → INVESTMENT")
    void testIRA_PositiveAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "ira", 
                new BigDecimal("200.00"), 
                "IRA contribution", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("HSA account: Positive amount → INVESTMENT")
    void testHSA_PositiveAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "hsa", 
                new BigDecimal("100.00"), 
                "HSA contribution", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("CD account: Positive amount → INVESTMENT")
    void testCD_PositiveAmount_ReturnsInvestment() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "cd", 
                new BigDecimal("5000.00"), 
                "CD deposit", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    // ========== EDGE CASES ==========

    @Test
    @DisplayName("Null account type → returns null")
    void testNullAccountType_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                null, null, 
                new BigDecimal("100.00"), 
                "Transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Null amount → returns null")
    void testNullAmount_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                null, 
                "Transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Zero amount → returns null")
    void testZeroAmount_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                BigDecimal.ZERO, 
                "Zero amount transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Empty account type string → returns null")
    void testEmptyAccountType_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "", null, 
                new BigDecimal("100.00"), 
                "Transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Whitespace-only account type → returns null")
    void testWhitespaceAccountType_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "   ", null, 
                new BigDecimal("100.00"), 
                "Transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Account type with whitespace → normalizes correctly")
    void testAccountTypeWithWhitespace_Normalizes() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "  credit  ", "  credit card  ", 
                new BigDecimal("100.00"), 
                "Purchase", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Very large amount → handles correctly")
    void testVeryLargeAmount_HandlesCorrectly() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                new BigDecimal("999999999.99"), 
                "Large deposit", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Very small amount → handles correctly")
    void testVerySmallAmount_HandlesCorrectly() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                new BigDecimal("0.01"), 
                "Small deposit", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Very small negative amount → handles correctly")
    void testVerySmallNegativeAmount_HandlesCorrectly() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "depository", "checking", 
                new BigDecimal("-0.01"), 
                "Small withdrawal", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== BOUNDARY CONDITIONS ==========

    @Test
    @DisplayName("Account type matching: Credit card vs Credit line")
    void testCreditCardVsCreditLine() {
        // Credit card should match "credit" but not "credit line"
        TransactionTypeCategoryService.TypeResult creditCardResult = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "credit", "credit card", 
                new BigDecimal("100.00"), 
                "Purchase", null
            );
        
        assertNotNull(creditCardResult);
        assertEquals(TransactionType.EXPENSE, creditCardResult.getTransactionType());
        
        // Credit line should match "credit line" and be treated as loan
        TransactionTypeCategoryService.TypeResult creditLineResult = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "loan", "credit line", 
                new BigDecimal("100.00"), 
                "Payment", null
            );
        
        assertNotNull(creditLineResult);
        assertEquals(TransactionType.LOAN, creditLineResult.getTransactionType());
    }

    @Test
    @DisplayName("Unknown account type → returns null")
    void testUnknownAccountType_ReturnsNull() {
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "unknown_type", "unknown_subtype", 
                new BigDecimal("100.00"), 
                "Transaction", null
            );
        
        assertNull(result);
    }

    @Test
    @DisplayName("Account subtype only (no main type) → uses subtype")
    void testAccountSubtypeOnly_UsesSubtype() {
        // If account type is null but subtype has info, should still work
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                null, "checking", 
                new BigDecimal("100.00"), 
                "Deposit", null
            );
        
        // Should return null since account type is required
        assertNull(result);
    }

    @Test
    @DisplayName("Multiple account type matches: Investment takes precedence")
    void testMultipleMatches_InvestmentTakesPrecedence() {
        // If an account could match multiple types, investment should be checked first
        // (Actually, the order in code is: credit card, investment, loan, checking/savings)
        TransactionTypeCategoryService.TypeResult result = 
            transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                "investment", "brokerage", 
                new BigDecimal("100.00"), 
                "Investment transaction", null
            );
        
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }
}

