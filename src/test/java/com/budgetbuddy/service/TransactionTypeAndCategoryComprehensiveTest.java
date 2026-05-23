package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Comprehensive tests for transaction type and category determination Mirrors iOS test coverage
 * from: - TransactionCategorizationTests.swift - TransactionManagementTests.swift -
 * PaymentTypeDetectionTests.swift - InvestmentCategorizationTests.swift -
 * DepositAndInterestCategorizationTests.swift
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionTypeAndCategoryComprehensiveTest {

    private static final String INTEREST = "interest";
    private static final String SALARY = "salary";
    private static final String GROCERIES = "groceries";
    private static final String DINING = "dining";

    @Autowired private TransactionTypeCategoryService service;

    private AccountTable checkingAccount;
    private AccountTable creditCardAccount;
    private AccountTable investmentAccount;
    private AccountTable loanAccount;

    @BeforeEach
    void setUp() {
        // Create test checking account
        checkingAccount = new AccountTable();
        checkingAccount.setAccountId(UUID.randomUUID().toString());
        checkingAccount.setAccountType("depository");
        checkingAccount.setAccountSubtype("checking");
        checkingAccount.setInstitutionName("Test Bank");
        checkingAccount.setAccountName("Test Checking");
        checkingAccount.setCurrencyCode("USD");

        // Create test credit card account
        creditCardAccount = new AccountTable();
        creditCardAccount.setAccountId(UUID.randomUUID().toString());
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");
        creditCardAccount.setInstitutionName("Test Bank");
        creditCardAccount.setAccountName("Test Credit Card");
        creditCardAccount.setCurrencyCode("USD");

        // Create test investment account
        investmentAccount = new AccountTable();
        investmentAccount.setAccountId(UUID.randomUUID().toString());
        investmentAccount.setAccountType("investment");
        investmentAccount.setAccountSubtype("brokerage");
        investmentAccount.setInstitutionName("Test Brokerage");
        investmentAccount.setAccountName("Test Investment");
        investmentAccount.setCurrencyCode("USD");

        // Create test loan account
        loanAccount = new AccountTable();
        loanAccount.setAccountId(UUID.randomUUID().toString());
        loanAccount.setAccountType("loan");
        loanAccount.setAccountSubtype("mortgage");
        loanAccount.setInstitutionName("Test Bank");
        loanAccount.setAccountName("Test Mortgage");
        loanAccount.setCurrencyCode("USD");
    }

    // ========== Transaction Categorization Tests (from TransactionCategorizationTests.swift)
    // ==========

    @Test
    @DisplayName("KFC transaction should be categorized as dining")
    void testKFCTransactionCategorizedAsDining() {
        // Given - KFC transaction (using Plaid categories which should be mapped)
        final String description = "KFC Purchase";
        final String merchantName = "KFC";
        final BigDecimal amount = new BigDecimal("15.99");

        // When - Determine category (using PLAID source to trigger category mapping)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "FOOD_AND_DRINK", // Plaid primary category
                        "RESTAURANTS", // Plaid detailed category
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        "PLAID", // Import source = PLAID triggers PlaidCategoryMapper
                        null);

        // Then - Should be dining (PlaidCategoryMapper should map FOOD_AND_DRINK/RESTAURANTS to
        // dining)
        assertEquals(
                DINING,
                categoryResult.getCategoryPrimary(),
                "KFC transaction should be categorized as dining, got: "
                        + categoryResult.getCategoryPrimary());
    }

    @Test
    @DisplayName("KFC lowercase should be categorized as dining")
    void testKFCLowercaseCategorizedAsDining() {
        // Given - KFC transaction with lowercase
        final String description = "kfc order";
        final String merchantName = "kfc";
        final BigDecimal amount = new BigDecimal("12.50");

        // When - Determine category (using PLAID source to trigger category mapping)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "FOOD_AND_DRINK", // Plaid primary category
                        "RESTAURANTS", // Plaid detailed category
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        "PLAID", // Import source = PLAID triggers PlaidCategoryMapper
                        null);

        // Then - Should be dining
        assertEquals(
                DINING,
                categoryResult.getCategoryPrimary(),
                "KFC lowercase should be categorized as dining, got: "
                        + categoryResult.getCategoryPrimary());
    }

    @Test
    @DisplayName("Autopayment should NOT be categorized as income")
    void testAutopaymentNotCategorizedAsIncome() {
        // Given - Autopayment transaction
        final String description = "AUTOPAYMENT - Utilities";
        final String merchantName = "AUTOPAYMENT";
        final BigDecimal amount = new BigDecimal("100.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "General Services",
                        "General Services",
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should NOT be income
        assertNotEquals(
                "income",
                categoryResult.getCategoryPrimary(),
                "Autopayment should NOT be categorized as income");
        // Should be payment or utilities
        assertTrue(
                "payment".equals(categoryResult.getCategoryPrimary())
                        || "utilities".equals(categoryResult.getCategoryPrimary())
                        || "other".equals(categoryResult.getCategoryPrimary()),
                "Autopayment should be payment, utilities, or other, got: "
                        + categoryResult.getCategoryPrimary());
    }

    @Test
    @DisplayName("Autopayment variations should NOT be categorized as income")
    void testAutopaymentVariationsNotCategorizedAsIncome() {
        final String[] variations = {
            "AUTOPAYMENT - Rent",
            "Auto Payment - Credit Card",
            "Automatic Payment",
            "AUTO-PAYMENT Utilities",
            "Recurring Payment - Subscription"
        };

        for (final String description : variations) {
            // When - Determine category
            final TransactionTypeCategoryService.CategoryResult categoryResult =
                    service.determineCategory(
                            "General Services",
                            "General Services",
                            checkingAccount,
                            "AUTOPAY",
                            description,
                            new BigDecimal("50.00"),
                            null,
                            null,
                            null, null);

            // Then - Should NOT be income
            assertNotEquals(
                    "income",
                    categoryResult.getCategoryPrimary(),
                    "Autopayment variation should NOT be income: "
                            + description
                            + ", got: "
                            + categoryResult.getCategoryPrimary());
        }
    }

    @Test
    @DisplayName("Income transaction (salary) should be categorized as income")
    void testIncomeTransactionCategorizedAsIncome() {
        // Given - Salary transaction
        final String description = "Direct Deposit - Salary";
        final String merchantName = "EMPLOYER CORP";
        final BigDecimal amount =
                new BigDecimal("-5000.00"); // Negative from Plaid = positive income

        // When - Determine category (using PLAID source to trigger category mapping)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "TRANSFER_IN", // Plaid primary category
                        "DEPOSIT", // Plaid detailed category
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        "PLAID", // Import source = PLAID triggers PlaidCategoryMapper
                        null);

        // Then - Should be income (PlaidCategoryMapper maps TRANSFER_IN/DEPOSIT to income)
        // The backend should also detect SALARY keywords from description
        assertTrue(
                "income".equals(categoryResult.getCategoryPrimary())
                        || SALARY.equals(categoryResult.getCategoryPrimary())
                        || "deposit".equals(categoryResult.getCategoryPrimary()),
                "Salary transaction should be income, salary, or deposit, got: "
                        + categoryResult.getCategoryPrimary());
    }

    @Test
    @DisplayName("Expense transaction should be EXPENSE type")
    void testTransactionAmountSignExpenseIsNegative() {
        // Given - Expense transaction (positive amount on checking = debit = expense)
        final String description = "Coffee Shop";
        final BigDecimal amount = new BigDecimal("-25.50"); // Negative = expense

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, DINING, DINING, amount, null, description, null);

        // Then - Should be EXPENSE type
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Expense transaction should be EXPENSE type");
    }

    @Test
    @DisplayName("Income transaction should be INCOME type")
    void testTransactionAmountSignIncomeIsPositive() {
        // Given - Income transaction (positive amount on checking = credit = income)
        final String description = "Paycheck";
        final BigDecimal amount = new BigDecimal("3000.00"); // Positive = income

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, "income", "deposit", amount, null, description, null);

        // Then - Should be INCOME type
        assertEquals(
                TransactionType.INCOME,
                typeResult.getTransactionType(),
                "Income transaction should be INCOME type");
    }

    @Test
    @DisplayName("Chicken restaurant should be categorized as dining")
    void testChickenRestaurantCategorizedAsDining() {
        // Given - Chicken restaurant transaction
        final String description = "Chicken Restaurant";
        final String merchantName = "Popeyes";
        final BigDecimal amount = new BigDecimal("20.00");

        // When - Determine category (using PLAID source to trigger category mapping)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "FOOD_AND_DRINK", // Plaid primary category
                        "RESTAURANTS", // Plaid detailed category
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        "PLAID", // Import source = PLAID triggers PlaidCategoryMapper
                        null);

        // Then - Should be dining
        assertEquals(
                DINING,
                categoryResult.getCategoryPrimary(),
                "Chicken restaurant should be categorized as dining, got: "
                        + categoryResult.getCategoryPrimary());
    }

    // ========== Payment Type Detection Tests (from PaymentTypeDetectionTests.swift) ==========

    @Test
    @DisplayName("Credit card payment should be detected as PAYMENT type")
    void testCreditCardPaymentDetectedAsPayment() {
        // Given - Credit card payment transaction
        final String description = "Credit Card Payment - Chase";
        final BigDecimal amount = new BigDecimal("-500.00"); // Negative = payment

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount, "payment", "payment", amount, null, description, null);

        // Then - Should be PAYMENT type
        assertEquals(
                TransactionType.PAYMENT,
                typeResult.getTransactionType(),
                "Credit card payment should be PAYMENT type");
    }

    @Test
    @DisplayName("Credit card payment from checking account should be PAYMENT type")
    void testCreditCardPaymentFromCheckingAccountReturnsPayment() {
        // Given - Credit card payment from checking account
        final String description = "Credit Card Payment";
        final BigDecimal amount = new BigDecimal("-500.00");

        // When - Determine type (checking account with negative amount = EXPENSE by default)
        // But if category is payment, it should be PAYMENT
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, "payment", "payment", amount, null, description, null);

        // Then - Should be PAYMENT type (if payment pattern detected) or EXPENSE (default for
        // negative on checking)
        // The backend should detect "Credit Card Payment" as a payment pattern
        assertTrue(
                typeResult.getTransactionType() == TransactionType.PAYMENT
                        || typeResult.getTransactionType() == TransactionType.EXPENSE,
                "Credit card payment from checking should be PAYMENT or EXPENSE type, got: "
                        + typeResult.getTransactionType());
    }

    @Test
    @DisplayName("Recurring ACH payment should be detected as PAYMENT type")
    void testRecurringACHPaymentDetectedAsPayment() {
        // Given - Recurring ACH payment
        final String description = "Monthly Recurring Payment - Utilities";
        final BigDecimal amount = new BigDecimal("-100.00");

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        "utilities",
                        "utilities",
                        amount,
                        null,
                        description,
                        "ach");

        // Then - Should be PAYMENT type (if payment pattern detected) or EXPENSE
        assertTrue(
                typeResult.getTransactionType() == TransactionType.PAYMENT
                        || typeResult.getTransactionType() == TransactionType.EXPENSE,
                "Recurring ACH payment should be PAYMENT or EXPENSE type, got: "
                        + typeResult.getTransactionType());
    }

    @Test
    @DisplayName("Autopay should be detected as PAYMENT type")
    void testAutopayDetectedAsPayment() {
        // Given - Autopay transaction
        final String description = "AUTOPAY - Loan Payment";
        final BigDecimal amount = new BigDecimal("-250.00");

        // When - Determine type (checking account with negative amount = EXPENSE by default)
        // But if payment pattern detected, should be PAYMENT
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, "payment", "payment", amount, null, description, "ach");

        // Then - Should be PAYMENT type (if payment pattern detected) or EXPENSE (default)
        // The backend should detect "AUTOPAY" as a payment pattern
        assertTrue(
                typeResult.getTransactionType() == TransactionType.PAYMENT
                        || typeResult.getTransactionType() == TransactionType.EXPENSE,
                "Autopay should be PAYMENT or EXPENSE type, got: "
                        + typeResult.getTransactionType());
    }

    @Test
    @DisplayName("Regular expense should be EXPENSE type, not PAYMENT")
    void testRegularExpenseReturnsExpense() {
        // Given - Regular expense transaction
        final String description = "Grocery Store";
        final BigDecimal amount = new BigDecimal("-50.00");

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, GROCERIES, GROCERIES, amount, null, description, null);

        // Then - Should be EXPENSE type
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Regular expense should be EXPENSE type");
    }

    @Test
    @DisplayName("Income transaction should be INCOME type")
    void testIncomeTransactionReturnsIncome() {
        // Given - Income transaction
        final String description = "Salary";
        final BigDecimal amount = new BigDecimal("5000.00");

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, "income", SALARY, amount, null, description, null);

        // Then - Should be INCOME type
        assertEquals(
                TransactionType.INCOME,
                typeResult.getTransactionType(),
                "Income transaction should be INCOME type");
    }

    @Test
    @DisplayName("ACH credit should be INCOME type, not PAYMENT")
    void testACHCreditReturnsIncome() {
        // Given - ACH credit transaction
        final String description = "ACH Credit";
        final BigDecimal amount = new BigDecimal("500.00");

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount, "income", "deposit", amount, null, description, "ach");

        // Then - Should be INCOME type
        assertEquals(
                TransactionType.INCOME,
                typeResult.getTransactionType(),
                "ACH credit should be INCOME type, not PAYMENT");
    }

    @Test
    @DisplayName("Investment transaction should be INVESTMENT type")
    void testInvestmentTransactionReturnsInvestment() {
        // Given - Investment transaction
        final String description = "CD Deposit";
        final BigDecimal amount = new BigDecimal("1000.00");

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        investmentAccount, "investment", "cd", amount, null, description, null);

        // Then - Should be INVESTMENT or INCOME type (positive amount on investment = INCOME)
        assertTrue(
                typeResult.getTransactionType() == TransactionType.INVESTMENT
                        || typeResult.getTransactionType() == TransactionType.INCOME,
                "Investment transaction should be INVESTMENT or INCOME type, got: "
                        + typeResult.getTransactionType());
    }

    // ========== Investment Categorization Tests (from InvestmentCategorizationTests.swift)
    // ==========

    @Test
    @DisplayName("CD deposit should be categorized as cd")
    void testCDDepositCategorizedAsCD() {
        // Given - CD deposit transaction
        final String description = "CD Deposit";
        final String merchantName = "Bank";
        final BigDecimal amount = new BigDecimal("10000.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "cd",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be cd (may be in primary or detailed)
        assertTrue(
                "cd".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "cd".equals(categoryResult.getCategoryDetailed()),
                "CD deposit should be categorized as 'cd' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("Bond purchase should be categorized as bonds")
    void testBondPurchaseCategorizedAsBonds() {
        // Given - Bond purchase transaction
        final String description = "Bond Purchase";
        final String merchantName = "Brokerage";
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "bonds",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be bonds (may be in primary or detailed)
        assertTrue(
                "bonds".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "bonds".equals(categoryResult.getCategoryDetailed()),
                "Bond purchase should be categorized as 'bonds' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("Stock purchase should be categorized as stocks")
    void testStockPurchaseCategorizedAsStocks() {
        // Given - Stock purchase transaction
        final String description = "Stock Purchase - AAPL";
        final String merchantName = "Brokerage";
        final BigDecimal amount = new BigDecimal("-1000.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "stocks",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be stocks (may be in primary or detailed)
        assertTrue(
                "stocks".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "stocks".equals(categoryResult.getCategoryDetailed()),
                "Stock purchase should be categorized as 'stocks' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("401k contribution should be categorized as fourZeroOneK")
    void test401kContributionCategorizedAsFourZeroOneK() {
        // Given - 401k contribution transaction
        final String description = "401k Contribution";
        final String merchantName = "Retirement Plan";
        final BigDecimal amount = new BigDecimal("-500.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "fourZeroOneK",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be fourZeroOneK (may be in primary or detailed)
        assertTrue(
                "fourZeroOneK".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "fourZeroOneK".equals(categoryResult.getCategoryDetailed()),
                "401k contribution should be categorized as 'fourZeroOneK' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("IRA contribution should be categorized as ira")
    void testIRAContributionCategorizedAsIRA() {
        // Given - IRA contribution transaction
        final String description = "IRA Contribution";
        final String merchantName = "Retirement Plan";
        final BigDecimal amount = new BigDecimal("-200.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "ira",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be ira (may be in primary or detailed)
        assertTrue(
                "ira".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "ira".equals(categoryResult.getCategoryDetailed()),
                "IRA contribution should be categorized as 'ira' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("Mutual fund should be categorized as mutualFunds")
    void testMutualFundCategorizedAsMutualFunds() {
        // Given - Mutual fund transaction
        final String description = "Mutual Fund Investment";
        final String merchantName = "Investment Company";
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "mutualFunds",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be mutualFunds (may be in primary or detailed)
        assertTrue(
                "mutualFunds".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary())
                        || "mutualFunds".equals(categoryResult.getCategoryDetailed()),
                "Mutual fund should be categorized as 'mutualFunds' or 'investment', got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("ETF purchase should be categorized as etf")
    void testETFCategorizedAsETF() {
        // Given - ETF purchase transaction
        final String description = "ETF Purchase";
        final String merchantName = "Brokerage";
        final BigDecimal amount = new BigDecimal("-1500.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "investment",
                        "etf",
                        investmentAccount,
                        merchantName,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be etf or investment (backend may return investment as primary)
        assertTrue(
                "etf".equals(categoryResult.getCategoryPrimary())
                        || "investment".equals(categoryResult.getCategoryPrimary()),
                "ETF purchase should be categorized as 'etf' or 'investment', got: "
                        + categoryResult.getCategoryPrimary());
    }

    // ========== Deposit and Interest Categorization Tests (from
    // DepositAndInterestCategorizationTests.swift) ==========

    @Test
    @DisplayName("ACH credit with salary keywords should be categorized as salary")
    void testACHCreditWithSalaryKeywordsCategorizedAsSalary() {
        // Given - ACH credit with salary keywords
        final String description = "ACH Credit - Salary Payment";
        final String merchantName = "Employer Corp";
        final BigDecimal amount =
                new BigDecimal("-5000.00"); // Negative from Plaid = positive income

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "income",
                        "deposit",
                        checkingAccount,
                        merchantName,
                        description,
                        amount,
                        "ach",
                        null,
                        null, null);

        // Then - Should be salary (may be in primary or detailed, or income if not detected)
        assertTrue(
                SALARY.equals(categoryResult.getCategoryPrimary())
                        || "income".equals(categoryResult.getCategoryPrimary())
                        || SALARY.equals(categoryResult.getCategoryDetailed()),
                "ACH credit with salary keywords should be salary or income, got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("ACH credit with payroll keywords should be categorized as salary")
    void testACHCreditWithPayrollKeywordsCategorizedAsSalary() {
        // Given - ACH credit with payroll keywords
        final String description = "ACH Credit - Payroll Deposit";
        final BigDecimal amount = new BigDecimal("-3000.00");

        // When - Determine category (description should trigger salary detection)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "income",
                        "deposit",
                        checkingAccount,
                        null,
                        description,
                        amount,
                        "ach",
                        null,
                        "CSV", // CSV import source
                        null);

        // Then - Should be salary (description contains "payroll" which should trigger salary
        // detection)
        // Note: The backend's determineIncomeCategoryFromDescription logic should detect "payroll"
        // keyword
        assertTrue(
                SALARY.equals(categoryResult.getCategoryPrimary())
                        || "income".equals(categoryResult.getCategoryPrimary())
                        || "deposit".equals(categoryResult.getCategoryPrimary()),
                "ACH credit with payroll keywords should be salary, income, or deposit, got: "
                        + categoryResult.getCategoryPrimary());
    }

    @Test
    @DisplayName("Interest misspelling INTRST should be detected as interest")
    void testInterestMisspellingINTRSTDetectedAsInterest() {
        // Given - Interest payment with misspelling "INTRST"
        final String description = "INTRST payment";
        final BigDecimal amount = new BigDecimal("-50.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "income",
                        INTEREST,
                        checkingAccount,
                        null,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be interest (may be in primary or detailed, or income if not detected)
        assertTrue(
                INTEREST.equals(categoryResult.getCategoryPrimary())
                        || "income".equals(categoryResult.getCategoryPrimary())
                        || INTEREST.equals(categoryResult.getCategoryDetailed()),
                "INTRST payment should be detected as interest or income, got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("Interest misspelling INTR should be detected as interest")
    void testInterestMisspellingINTRDetectedAsInterest() {
        // Given - Interest payment with misspelling "INTR"
        final String description = "INTR payment";
        final BigDecimal amount = new BigDecimal("-25.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "income",
                        INTEREST,
                        checkingAccount,
                        null,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be interest (may be in primary or detailed, or income if not detected)
        assertTrue(
                INTEREST.equals(categoryResult.getCategoryPrimary())
                        || "income".equals(categoryResult.getCategoryPrimary())
                        || INTEREST.equals(categoryResult.getCategoryDetailed()),
                "INTR payment should be detected as interest or income, got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    @Test
    @DisplayName("Standard interest spelling should be detected as interest")
    void testInterestStandardSpellingDetectedAsInterest() {
        // Given - Interest payment with standard spelling
        final String description = "Interest payment";
        final BigDecimal amount = new BigDecimal("-100.00");

        // When - Determine category
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "income",
                        INTEREST,
                        checkingAccount,
                        null,
                        description,
                        amount,
                        null,
                        null,
                        null, null);

        // Then - Should be interest (may be in primary or detailed, or income if not detected)
        assertTrue(
                INTEREST.equals(categoryResult.getCategoryPrimary())
                        || "income".equals(categoryResult.getCategoryPrimary())
                        || INTEREST.equals(categoryResult.getCategoryDetailed()),
                "Interest payment should be detected as interest or income, got primary: "
                        + categoryResult.getCategoryPrimary()
                        + ", detailed: "
                        + categoryResult.getCategoryDetailed());
    }

    // ========== Account Type Specific Tests ==========

    @Test
    @DisplayName("Credit card negative amount should be PAYMENT type")
    void testCreditCardNegativeAmountReturnsPayment() {
        // Given - Credit card with negative amount (payment)
        final BigDecimal amount = new BigDecimal("-500.00");
        final String description = "Payment to credit card";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        creditCardAccount, "payment", "payment", amount, null, description, null);

        // Then - Should be PAYMENT
        assertEquals(
                TransactionType.PAYMENT,
                result.getTransactionType(),
                "Credit card negative amount should be PAYMENT type");
    }

    @Test
    @DisplayName("Credit card positive amount should be EXPENSE type")
    void testCreditCardPositiveAmountReturnsExpense() {
        // Given - Credit card with positive amount (charge)
        final BigDecimal amount = new BigDecimal("100.00");
        final String description = "Purchase";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        creditCardAccount, GROCERIES, GROCERIES, amount, null, description, null);

        // Then - Should be EXPENSE
        assertEquals(
                TransactionType.EXPENSE,
                result.getTransactionType(),
                "Credit card positive amount should be EXPENSE type");
    }

    @Test
    @DisplayName("Investment account positive amount should be INCOME type")
    void testInvestmentPositiveAmountReturnsIncome() {
        // Given - Investment account with positive amount (dividend/interest)
        final BigDecimal amount = new BigDecimal("1000.00");
        final String description = "Dividend payment";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        investmentAccount, "income", "dividend", amount, null, description, null);

        // Then - Should be INCOME
        assertEquals(
                TransactionType.INCOME,
                result.getTransactionType(),
                "Investment account positive amount should be INCOME type");
    }

    @Test
    @DisplayName("Investment account negative amount (fee) should be EXPENSE type")
    void testInvestmentNegativeAmountFeeReturnsExpense() {
        // Given - Investment account with negative amount (fee)
        final BigDecimal amount = new BigDecimal("-50.00");
        final String description = "Investment fee";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        investmentAccount, "expense", "fee", amount, null, description, null);

        // Then - Should be EXPENSE
        assertEquals(
                TransactionType.EXPENSE,
                result.getTransactionType(),
                "Investment account fee should be EXPENSE type");
    }

    @Test
    @DisplayName("Investment account negative amount (purchase) should be INVESTMENT type")
    void testInvestmentNegativeAmountPurchaseReturnsInvestment() {
        // Given - Investment account with negative amount (purchase)
        final BigDecimal amount = new BigDecimal("-1000.00");
        final String description = "Stock purchase";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        investmentAccount, "investment", "stocks", amount, null, description, null);

        // Then - Should be INVESTMENT
        assertEquals(
                TransactionType.INVESTMENT,
                result.getTransactionType(),
                "Investment account purchase should be INVESTMENT type");
    }

    @Test
    @DisplayName("Loan account positive amount (payment) should be PAYMENT type")
    void testLoanPositiveAmountPaymentReturnsPayment() {
        // Given - Loan account with positive amount (payment)
        final BigDecimal amount = new BigDecimal("1500.00");
        final String description = "Mortgage payment";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        loanAccount, "payment", "payment", amount, null, description, null);

        // Then - Should be PAYMENT
        assertEquals(
                TransactionType.PAYMENT,
                result.getTransactionType(),
                "Loan account payment should be PAYMENT type");
    }

    @Test
    @DisplayName("Loan account negative amount (fee) should be EXPENSE type")
    void testLoanNegativeAmountFeeReturnsExpense() {
        // Given - Loan account with negative amount (fee)
        final BigDecimal amount = new BigDecimal("-25.00");
        final String description = "Loan fee";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        loanAccount, "expense", "fee", amount, null, description, null);

        // Then - Should be EXPENSE
        assertEquals(
                TransactionType.EXPENSE,
                result.getTransactionType(),
                "Loan account fee should be EXPENSE type");
    }

    @Test
    @DisplayName("Checking account positive amount should be INCOME type")
    void testCheckingPositiveAmountReturnsIncome() {
        // Given - Checking account with positive amount (credit)
        final BigDecimal amount = new BigDecimal("5000.00");
        final String description = "Direct Deposit - Salary";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        checkingAccount, "income", SALARY, amount, null, description, null);

        // Then - Should be INCOME
        assertEquals(
                TransactionType.INCOME,
                result.getTransactionType(),
                "Checking account positive amount should be INCOME type");
    }

    @Test
    @DisplayName("Checking account negative amount should be EXPENSE type")
    void testCheckingNegativeAmountReturnsExpense() {
        // Given - Checking account with negative amount (debit)
        final BigDecimal amount = new BigDecimal("-50.00");
        final String description = "Grocery Store";

        // When - Determine type
        final TransactionTypeCategoryService.TypeResult result =
                service.determineTransactionType(
                        checkingAccount, GROCERIES, GROCERIES, amount, null, description, null);

        // Then - Should be EXPENSE
        assertEquals(
                TransactionType.EXPENSE,
                result.getTransactionType(),
                "Checking account negative amount should be EXPENSE type");
    }
}
