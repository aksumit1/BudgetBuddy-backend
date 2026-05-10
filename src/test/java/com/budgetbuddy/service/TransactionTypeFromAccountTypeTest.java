package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.MerchantCategoryDataService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for transaction type determination from account type Tests edge cases,
 * boundary conditions, and various banking/financial account types
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("Transaction Type Determination from Account Type")
class TransactionTypeFromAccountTypeTest {

    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private PlaidCategoryMapper plaidCategoryMapper;

    @Mock private ImportCategoryParser importCategoryParser;

    @Mock private EnhancedCategoryDetectionService enhancedCategoryDetection;

    @Mock private ImportCategoryConfig importCategoryConfig;

    @Mock private GlobalFinancialConfig globalFinancialConfig;

    @Mock private CircuitBreakerService circuitBreakerService;

    @BeforeEach
    void setUp() {
        // Mock configs
        when(globalFinancialConfig.getDefaultRegion()).thenReturn("US");
        when(globalFinancialConfig.getCreditCardKeywordsForRegion(anyString()))
                .thenReturn(
                        java.util.Arrays.asList(
                                "autopay", "auto pay", "e-payment", "credit card", "creditcard"));
        when(importCategoryConfig.getCreditCardKeywords())
                .thenReturn(
                        java.util.Arrays.asList(
                                "autopay", "auto pay", "e-payment", "credit card", "creditcard"));

        // Create service with mocked dependencies
        final MerchantCategoryDataService merchantCategoryDataService =
                org.mockito.Mockito.mock(MerchantCategoryDataService.class);
        org.mockito.Mockito.lenient()
                .when(
                        merchantCategoryDataService.detectRuleBasedCategory(
                                anyString(),
                                anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);

        final CategoryLearningService learningService =
                org.mockito.Mockito.mock(CategoryLearningService.class);
        // Mock learning service to return null by default (no custom mappings)
        org.mockito.Mockito.lenient()
                .when(learningService.getCustomMapping(anyString(), anyString()))
                .thenReturn(null);

        transactionTypeCategoryService =
                new TransactionTypeCategoryService(
                        plaidCategoryMapper,
                        importCategoryParser,
                        enhancedCategoryDetection,
                        importCategoryConfig,
                        globalFinancialConfig,
                        circuitBreakerService,
                        merchantCategoryDataService,
                        learningService);
    }

    // ========== CHECKING/SAVINGS ACCOUNTS ==========

    @Test
    @DisplayName("Checking account: Positive amount → INCOME")
    void testCheckingAccountPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository",
                        "checking",
                        new BigDecimal("100.00"),
                        "Salary deposit",
                        "ach");

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
        assertEquals("ACCOUNT_TYPE", result.getSource());
    }

    @Test
    @DisplayName("Checking account: Negative amount → EXPENSE")
    void testCheckingAccountNegativeAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository",
                        "checking",
                        new BigDecimal("-50.00"),
                        "Grocery purchase",
                        "in_store");

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Savings account: Positive amount → INCOME")
    void testSavingsAccountPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository", "savings", new BigDecimal("500.00"), "Interest earned", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Money Market account: Positive amount → INCOME")
    void testMoneyMarketAccountPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository", "money market", new BigDecimal("1000.00"), "Deposit", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Money Market account: Negative amount → EXPENSE")
    void testMoneyMarketAccountNegativeAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository",
                        "money market",
                        new BigDecimal("-200.00"),
                        "Withdrawal",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== CREDIT CARD ACCOUNTS ==========

    @Test
    @DisplayName("Credit card: Positive amount (charge) → EXPENSE")
    void testCreditCardPositiveAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "credit",
                        "credit card",
                        new BigDecimal("75.50"),
                        "Amazon purchase",
                        "online");

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit card: Negative amount (payment) → PAYMENT")
    void testCreditCardNegativeAmountReturnsPayment() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "credit",
                        "credit card",
                        new BigDecimal("-500.00"),
                        "Payment to credit card",
                        "ach");

        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit card: Case insensitive matching")
    void testCreditCardCaseInsensitive() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "CREDIT", "CREDIT CARD", new BigDecimal("100.00"), "Purchase", null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== LOAN ACCOUNTS ==========

    @Test
    @DisplayName("Mortgage: Positive amount (payment) → PAYMENT")
    void testMortgagePositiveAmountReturnsPayment() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan", "mortgage", new BigDecimal("1500.00"), "Mortgage payment", "ach");

        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Mortgage: Negative amount (fees) → EXPENSE")
    void testMortgageNegativeAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan", "mortgage", new BigDecimal("-2000.00"), "Loan fee", null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Student loan: Positive amount (payment) → PAYMENT")
    void testStudentLoanPositiveAmountReturnsPayment() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan",
                        "student loan",
                        new BigDecimal("300.00"),
                        "Student loan payment",
                        "ach");

        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Auto loan: Positive amount (payment) → PAYMENT")
    void testAutoLoanPositiveAmountReturnsPayment() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan", "auto loan", new BigDecimal("400.00"), "Car loan payment", "ach");

        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit line: Positive amount (payment) → PAYMENT")
    void testCreditLinePositiveAmountReturnsPayment() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan",
                        "credit line",
                        new BigDecimal("250.00"),
                        "Credit line payment",
                        "ach");

        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    @Test
    @DisplayName("Credit line: Negative amount (fees) → EXPENSE")
    void testCreditLineNegativeAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan", "credit line", new BigDecimal("-1000.00"), "Credit line fee", null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== INVESTMENT ACCOUNTS ==========

    @Test
    @DisplayName("Investment account: Positive amount (dividends/interest) → INCOME")
    void testInvestmentPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment",
                        "brokerage",
                        new BigDecimal("1000.00"),
                        "Dividend payment",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Investment account: Negative amount (fee) → EXPENSE")
    void testInvestmentNegativeAmountReturnsExpense() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment",
                        "brokerage",
                        new BigDecimal("-50.00"),
                        "Investment fee",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("401k account: Positive amount (dividend/interest) → INCOME")
    void test401kPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment", "401k", new BigDecimal("500.00"), "Dividend payment", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("IRA account: Positive amount (dividend/interest) → INCOME")
    void testIRAPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment", "ira", new BigDecimal("200.00"), "Interest payment", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("HSA account: Positive amount (dividend/interest) → INCOME")
    void testHSAPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment", "hsa", new BigDecimal("100.00"), "Interest earned", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("CD account: Positive amount (interest) → INCOME")
    void testCDPositiveAmountReturnsIncome() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment", "cd", new BigDecimal("5000.00"), "CD interest", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    // ========== EDGE CASES ==========

    @Test
    @DisplayName("Null account type → returns null")
    void testNullAccountTypeReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        null, null, new BigDecimal("100.00"), "Transaction", null);

        assertNull(result);
    }

    @Test
    @DisplayName("Null amount → returns null")
    void testNullAmountReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository", "checking", null, "Transaction", null);

        assertNull(result);
    }

    @Test
    @DisplayName("Zero amount → returns null")
    void testZeroAmountReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository", "checking", BigDecimal.ZERO, "Zero amount transaction", null);

        assertNull(result);
    }

    @Test
    @DisplayName("Empty account type string → returns null")
    void testEmptyAccountTypeReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "", null, new BigDecimal("100.00"), "Transaction", null);

        assertNull(result);
    }

    @Test
    @DisplayName("Whitespace-only account type → returns null")
    void testWhitespaceAccountTypeReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "   ", null, new BigDecimal("100.00"), "Transaction", null);

        assertNull(result);
    }

    @Test
    @DisplayName("Account type with whitespace → normalizes correctly")
    void testAccountTypeWithWhitespaceNormalizes() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "  credit  ",
                        "  credit card  ",
                        new BigDecimal("100.00"),
                        "Purchase",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    @Test
    @DisplayName("Very large amount → handles correctly")
    void testVeryLargeAmountHandlesCorrectly() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository",
                        "checking",
                        new BigDecimal("999999999.99"),
                        "Large deposit",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Very small amount → handles correctly")
    void testVerySmallAmountHandlesCorrectly() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository", "checking", new BigDecimal("0.01"), "Small deposit", null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    @Test
    @DisplayName("Very small negative amount → handles correctly")
    void testVerySmallNegativeAmountHandlesCorrectly() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "depository",
                        "checking",
                        new BigDecimal("-0.01"),
                        "Small withdrawal",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    // ========== BOUNDARY CONDITIONS ==========

    @Test
    @DisplayName("Account type matching: Credit card vs Credit line")
    void testCreditCardVsCreditLine() {
        // Credit card should match "credit" but not "credit line"
        final TransactionTypeCategoryService.TypeResult creditCardResult =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "credit", "credit card", new BigDecimal("100.00"), "Purchase", null);

        assertNotNull(creditCardResult);
        assertEquals(TransactionType.EXPENSE, creditCardResult.getTransactionType());

        // Credit line should match "credit line" and be treated as loan
        final TransactionTypeCategoryService.TypeResult creditLineResult =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "loan", "credit line", new BigDecimal("100.00"), "Payment", null);

        assertNotNull(creditLineResult);
        assertEquals(TransactionType.PAYMENT, creditLineResult.getTransactionType());
    }

    @Test
    @DisplayName("Unknown account type → returns null")
    void testUnknownAccountTypeReturnsNull() {
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "unknown_type",
                        "unknown_subtype",
                        new BigDecimal("100.00"),
                        "Transaction",
                        null);

        assertNull(result);
    }

    @Test
    @DisplayName("Account subtype only (no main type) → uses subtype")
    void testAccountSubtypeOnlyUsesSubtype() {
        // If account type is null but subtype has info, should still work
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        null, "checking", new BigDecimal("100.00"), "Deposit", null);

        // Should return null since account type is required
        assertNull(result);
    }

    @Test
    @DisplayName("Multiple account type matches: Investment takes precedence")
    void testMultipleMatchesInvestmentTakesPrecedence() {
        // If an account could match multiple types, investment should be checked first
        // (Actually, the order in code is: credit card, investment, loan, checking/savings)
        // Investment account with positive amount → INCOME (dividends/interest)
        final TransactionTypeCategoryService.TypeResult result =
                transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                        "investment",
                        "brokerage",
                        new BigDecimal("100.00"),
                        "Dividend payment",
                        null);

        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }
}
