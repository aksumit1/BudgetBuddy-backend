package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.MerchantCategoryDataService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for the new transaction type logic changes: 1. Credit card category-based logic for
 * negative amounts 2. Investment account transfer/deposit detection 3. Checking account credit card
 * payment detection
 */
@ExtendWith(MockitoExtension.class)
public class TransactionTypeCategoryServiceNewLogicTest {

    @Mock private PlaidCategoryMapper plaidCategoryMapper;

    @Mock private ImportCategoryParser importCategoryParser;

    @Mock private EnhancedCategoryDetectionService enhancedCategoryDetection;

    @Mock private ImportCategoryConfig importCategoryConfig;

    @Mock private GlobalFinancialConfig globalFinancialConfig;

    @Mock private CircuitBreakerService circuitBreakerService;

    @Mock private MerchantCategoryDataService merchantCategoryDataService;

    @Mock private CategoryLearningService learningService;

    private TransactionTypeCategoryService service;

    @BeforeEach
    void setUp() {
        service =
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

    /**
     * Test: Credit card shopping purchase (negative amount, shopping category) → EXPENSE This fixes
     * the issue where Lululemon purchases were incorrectly classified as PAYMENT
     */
    @Test
    void testCreditCardShoppingPurchaseShouldBeExpense() {
        // Given: Credit card account, negative amount, shopping category
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");

        final BigDecimal amount = BigDecimal.valueOf(-100.00);
        final String categoryPrimary = "shopping";
        final String description = "LULULEMON ATHLETICA";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account,
                        categoryPrimary,
                        null, // categoryDetailed
                        amount,
                        null, // transactionTypeIndicator
                        description,
                        null // paymentChannel
                );

        // Then: Should be EXPENSE (not PAYMENT)
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /** Test: Credit card payment (negative amount, payment category) → PAYMENT */
    @Test
    void testCreditCardPaymentShouldBePayment() {
        // Given: Credit card account, negative amount, payment category
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");

        final BigDecimal amount = BigDecimal.valueOf(-500.00);
        final String categoryPrimary = "payment";
        final String description = "CHASE CREDIT CARD PAYMENT";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be PAYMENT
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /** Test: Credit card dining purchase (negative amount, dining category) → EXPENSE */
    @Test
    void testCreditCardDiningPurchaseShouldBeExpense() {
        // Given: Credit card account, negative amount, dining category
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");

        final BigDecimal amount = BigDecimal.valueOf(-50.00);
        final String categoryPrimary = "dining";
        final String description = "TST* RESTAURANT";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /** Test: Credit card payment with payment keywords (negative amount, no category) → PAYMENT */
    @Test
    void testCreditCardPaymentWithKeywordsShouldBePayment() {
        // Given: Credit card account, negative amount, payment keywords in description
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");

        final BigDecimal amount = BigDecimal.valueOf(-500.00);
        final String categoryPrimary = null;
        final String description = "AUTOPAY CHASE CREDIT CARD";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be PAYMENT
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /**
     * Test: Investment account transfer (positive amount, transfer description) → INVESTMENT This
     * fixes the issue where transfers were incorrectly classified as INCOME
     */
    @Test
    void testInvestmentAccountTransferShouldBeInvestment() {
        // Given: Investment account, positive amount, transfer description
        final AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("401k");

        final BigDecimal amount = BigDecimal.valueOf(1000.00);
        final String categoryPrimary = null;
        final String description = "TRANSFER FROM CHECKING ACCOUNT";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be INVESTMENT (not INCOME)
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    /** Test: Investment account deposit (positive amount, deposit description) → INVESTMENT */
    @Test
    void testInvestmentAccountDepositShouldBeInvestment() {
        // Given: Investment account, positive amount, deposit description
        final AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("ira");

        final BigDecimal amount = BigDecimal.valueOf(500.00);
        final String categoryPrimary = null;
        final String description = "DEPOSIT CONTRIBUTION";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be INVESTMENT
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    /** Test: Investment account dividend (positive amount, no transfer keywords) → INCOME */
    @Test
    void testInvestmentAccountDividendShouldBeIncome() {
        // Given: Investment account, positive amount, dividend description (no transfer keywords)
        final AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("brokerage");

        final BigDecimal amount = BigDecimal.valueOf(100.00);
        final String categoryPrimary = null;
        final String description = "DIVIDEND PAYMENT";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be INCOME (dividends, interest, distributions)
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    /** Test: Investment account fee (negative amount, fee description) → EXPENSE */
    @Test
    void testInvestmentAccountFeeShouldBeExpense() {
        // Given: Investment account, negative amount, fee description
        final AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("ira");

        final BigDecimal amount = BigDecimal.valueOf(-25.00);
        final String categoryPrimary = null;
        final String description = "CUSTODIAL FEE";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /**
     * Test: Checking account credit card payment (negative amount, payment keywords) → PAYMENT This
     * fixes the issue where credit card payments were incorrectly classified as EXPENSE
     */
    @Test
    void testCheckingAccountCreditCardPaymentShouldBePayment() {
        // Given: Checking account, negative amount, credit card payment description
        final AccountTable account = new AccountTable();
        account.setAccountType("depository");
        account.setAccountSubtype("checking");

        final BigDecimal amount = BigDecimal.valueOf(-500.00);
        final String categoryPrimary = null;
        final String description = "CHASE CREDIT CARD PAYMENT";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be PAYMENT (not EXPENSE)
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /** Test: Checking account regular expense (negative amount, no payment keywords) → EXPENSE */
    @Test
    void testCheckingAccountRegularExpenseShouldBeExpense() {
        // Given: Checking account, negative amount, regular expense description
        final AccountTable account = new AccountTable();
        account.setAccountType("depository");
        account.setAccountSubtype("checking");

        final BigDecimal amount = BigDecimal.valueOf(-50.00);
        final String categoryPrimary = "dining";
        final String description = "RESTAURANT PURCHASE";

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /**
     * Test: Credit card default for negative amounts without category or payment keywords → EXPENSE
     * This tests the new default behavior (changed from PAYMENT to EXPENSE)
     */
    @Test
    void testCreditCardNegativeAmountDefaultShouldBeExpense() {
        // Given: Credit card account, negative amount, no category, no payment keywords
        final AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");

        final BigDecimal amount = BigDecimal.valueOf(-100.00);
        final String categoryPrimary = null;
        final String description = "SOME CHARGE"; // No payment keywords

        // When: Determine transaction type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        account, categoryPrimary, null, amount, null, description, null);

        // Then: Should be EXPENSE (default for negative amounts without payment keywords)
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }
}
