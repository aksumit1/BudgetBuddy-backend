package com.budgetbuddy.service;

import com.budgetbuddy.config.GlobalFinancialConfig;
import com.budgetbuddy.config.ImportCategoryConfig;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.circuitbreaker.CircuitBreakerService;
import com.budgetbuddy.service.category.InMemoryMerchantService;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for the new transaction type logic changes:
 * 1. Credit card category-based logic for negative amounts
 * 2. Investment account transfer/deposit detection
 * 3. Checking account credit card payment detection
 */
@ExtendWith(MockitoExtension.class)
public class TransactionTypeCategoryServiceNewLogicTest {

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
    
    @Mock
    private InMemoryMerchantService merchantService;
    
    @Mock
    private CategoryLearningService learningService;

    private TransactionTypeCategoryService service;

    @BeforeEach
    void setUp() {
        service = new TransactionTypeCategoryService(
            transactionTypeDeterminer,
            plaidCategoryMapper,
            importCategoryParser,
            enhancedCategoryDetection,
            importCategoryConfig,
            globalFinancialConfig,
            circuitBreakerService,
            merchantService,
            learningService
        );
    }

    /**
     * Test: Credit card shopping purchase (negative amount, shopping category) → EXPENSE
     * This fixes the issue where Lululemon purchases were incorrectly classified as PAYMENT
     */
    @Test
    void testCreditCardShoppingPurchase_ShouldBeExpense() {
        // Given: Credit card account, negative amount, shopping category
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");
        
        BigDecimal amount = BigDecimal.valueOf(-100.00);
        String categoryPrimary = "shopping";
        String description = "LULULEMON ATHLETICA";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
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

    /**
     * Test: Credit card payment (negative amount, payment category) → PAYMENT
     */
    @Test
    void testCreditCardPayment_ShouldBePayment() {
        // Given: Credit card account, negative amount, payment category
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");
        
        BigDecimal amount = BigDecimal.valueOf(-500.00);
        String categoryPrimary = "payment";
        String description = "CHASE CREDIT CARD PAYMENT";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be PAYMENT
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /**
     * Test: Credit card dining purchase (negative amount, dining category) → EXPENSE
     */
    @Test
    void testCreditCardDiningPurchase_ShouldBeExpense() {
        // Given: Credit card account, negative amount, dining category
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");
        
        BigDecimal amount = BigDecimal.valueOf(-50.00);
        String categoryPrimary = "dining";
        String description = "TST* RESTAURANT";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /**
     * Test: Credit card payment with payment keywords (negative amount, no category) → PAYMENT
     */
    @Test
    void testCreditCardPaymentWithKeywords_ShouldBePayment() {
        // Given: Credit card account, negative amount, payment keywords in description
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");
        
        BigDecimal amount = BigDecimal.valueOf(-500.00);
        String categoryPrimary = null;
        String description = "AUTOPAY CHASE CREDIT CARD";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be PAYMENT
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /**
     * Test: Investment account transfer (positive amount, transfer description) → INVESTMENT
     * This fixes the issue where transfers were incorrectly classified as INCOME
     */
    @Test
    void testInvestmentAccountTransfer_ShouldBeInvestment() {
        // Given: Investment account, positive amount, transfer description
        AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("401k");
        
        BigDecimal amount = BigDecimal.valueOf(1000.00);
        String categoryPrimary = null;
        String description = "TRANSFER FROM CHECKING ACCOUNT";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be INVESTMENT (not INCOME)
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    /**
     * Test: Investment account deposit (positive amount, deposit description) → INVESTMENT
     */
    @Test
    void testInvestmentAccountDeposit_ShouldBeInvestment() {
        // Given: Investment account, positive amount, deposit description
        AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("ira");
        
        BigDecimal amount = BigDecimal.valueOf(500.00);
        String categoryPrimary = null;
        String description = "DEPOSIT CONTRIBUTION";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be INVESTMENT
        assertNotNull(result);
        assertEquals(TransactionType.INVESTMENT, result.getTransactionType());
    }

    /**
     * Test: Investment account dividend (positive amount, no transfer keywords) → INCOME
     */
    @Test
    void testInvestmentAccountDividend_ShouldBeIncome() {
        // Given: Investment account, positive amount, dividend description (no transfer keywords)
        AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("brokerage");
        
        BigDecimal amount = BigDecimal.valueOf(100.00);
        String categoryPrimary = null;
        String description = "DIVIDEND PAYMENT";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be INCOME (dividends, interest, distributions)
        assertNotNull(result);
        assertEquals(TransactionType.INCOME, result.getTransactionType());
    }

    /**
     * Test: Investment account fee (negative amount, fee description) → EXPENSE
     */
    @Test
    void testInvestmentAccountFee_ShouldBeExpense() {
        // Given: Investment account, negative amount, fee description
        AccountTable account = new AccountTable();
        account.setAccountType("investment");
        account.setAccountSubtype("ira");
        
        BigDecimal amount = BigDecimal.valueOf(-25.00);
        String categoryPrimary = null;
        String description = "CUSTODIAL FEE";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /**
     * Test: Checking account credit card payment (negative amount, payment keywords) → PAYMENT
     * This fixes the issue where credit card payments were incorrectly classified as EXPENSE
     */
    @Test
    void testCheckingAccountCreditCardPayment_ShouldBePayment() {
        // Given: Checking account, negative amount, credit card payment description
        AccountTable account = new AccountTable();
        account.setAccountType("depository");
        account.setAccountSubtype("checking");
        
        BigDecimal amount = BigDecimal.valueOf(-500.00);
        String categoryPrimary = null;
        String description = "CHASE CREDIT CARD PAYMENT";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be PAYMENT (not EXPENSE)
        assertNotNull(result);
        assertEquals(TransactionType.PAYMENT, result.getTransactionType());
    }

    /**
     * Test: Checking account regular expense (negative amount, no payment keywords) → EXPENSE
     */
    @Test
    void testCheckingAccountRegularExpense_ShouldBeExpense() {
        // Given: Checking account, negative amount, regular expense description
        AccountTable account = new AccountTable();
        account.setAccountType("depository");
        account.setAccountSubtype("checking");
        
        BigDecimal amount = BigDecimal.valueOf(-50.00);
        String categoryPrimary = "dining";
        String description = "RESTAURANT PURCHASE";

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be EXPENSE
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }

    /**
     * Test: Credit card default for negative amounts without category or payment keywords → EXPENSE
     * This tests the new default behavior (changed from PAYMENT to EXPENSE)
     */
    @Test
    void testCreditCardNegativeAmountDefault_ShouldBeExpense() {
        // Given: Credit card account, negative amount, no category, no payment keywords
        AccountTable account = new AccountTable();
        account.setAccountType("credit");
        account.setAccountSubtype("credit_card");
        
        BigDecimal amount = BigDecimal.valueOf(-100.00);
        String categoryPrimary = null;
        String description = "SOME CHARGE"; // No payment keywords

        // When: Determine transaction type
        TransactionTypeCategoryService.TypeResult result = service.determineTransactionType(
            account,
            categoryPrimary,
            null,
            amount,
            null,
            description,
            null
        );

        // Then: Should be EXPENSE (default for negative amounts without payment keywords)
        assertNotNull(result);
        assertEquals(TransactionType.EXPENSE, result.getTransactionType());
    }
}
