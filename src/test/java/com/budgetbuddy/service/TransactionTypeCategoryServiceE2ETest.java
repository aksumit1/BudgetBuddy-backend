package com.budgetbuddy.service;

import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import com.budgetbuddy.AWSTestConfiguration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for TransactionTypeCategoryService
 * Tests the complete flow from Plaid/Import to final category/type determination
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionTypeCategoryServiceE2ETest {

    @Autowired
    private TransactionTypeCategoryService service;

    private AccountTable checkingAccount;
    private AccountTable investmentAccount;
    private AccountTable loanAccount;

    @BeforeEach
    void setUp() {
        // Setup checking account
        checkingAccount = new AccountTable();
        checkingAccount.setAccountId("checking-account-id");
        checkingAccount.setAccountType("depository");
        checkingAccount.setAccountSubtype("checking");

        // Setup investment account
        investmentAccount = new AccountTable();
        investmentAccount.setAccountId("investment-account-id");
        investmentAccount.setAccountType("investment");

        // Setup loan account
        loanAccount = new AccountTable();
        loanAccount.setAccountId("loan-account-id");
        loanAccount.setAccountType("credit");
    }

    @Test
    void testE2E_PlaidTransaction_WithCategories() {
        // Given: Plaid transaction with categories
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "FOOD_AND_DRINK",  // Raw Plaid category
            "GROCERIES",
            checkingAccount,
            "Safeway",
            "Grocery purchase",
            BigDecimal.valueOf(-50),
            null,
            null,
            "PLAID"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            checkingAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-50),
            null,
            "Grocery purchase",
            null
        );

        // Then: Should be groceries expense
        assertNotNull(categoryResult);
        assertEquals("groceries", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testE2E_CSVImport_WithAccountDetection() {
        // Given: CSV import transaction
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",  // Importer category from parser
            "utilities",
            checkingAccount,
            "PUGET SOUND ENER BILLPAY",
            "Utility payment",
            BigDecimal.valueOf(-200),
            null,
            "DEBIT",
            "CSV"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            checkingAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-200),
            "DEBIT",
            "Utility payment",
            null
        );

        // Then: Should be utilities expense
        assertNotNull(categoryResult);
        assertEquals("utilities", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testE2E_CreditCardPayment() {
        // Given: Credit card payment transaction
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "payment",  // Importer category
            "payment",
            checkingAccount,
            "CITI AUTOPAY PAYMENT",
            "CITI AUTOPAY PAYMENT 291883502120566",
            BigDecimal.valueOf(-2681.98),
            null,
            "DEBIT",
            "CSV"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            checkingAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-2681.98),
            "DEBIT",
            "CITI AUTOPAY PAYMENT 291883502120566",
            null
        );

        // Then: Should be payment/loan
        assertNotNull(categoryResult);
        assertEquals("payment", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.LOAN, typeResult.getTransactionType());
    }

    @Test
    void testE2E_InvestmentTransfer() {
        // Given: Investment transfer transaction
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "investment",  // Importer category
            "investment",
            investmentAccount,
            "Morgan Stanley",
            "Online Transfer from Morganstanley",
            BigDecimal.valueOf(5000),
            null,
            "CREDIT",
            "CSV"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            investmentAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(5000),
            "CREDIT",
            "Online Transfer from Morganstanley",
            null
        );

        // Then: Should be investment
        assertNotNull(categoryResult);
        assertEquals("investment", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.INVESTMENT, typeResult.getTransactionType());
    }

    @Test
    void testE2E_IncomeTransaction() {
        // Given: Income transaction (ACH credit)
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "deposit",  // Importer category
            "deposit",
            checkingAccount,
            "Grisalin Managem",
            "Grisalin Managem SIGONFILE PPD ID: 9000281206",
            BigDecimal.valueOf(3415.31),
            "ach",
            "CREDIT",
            "CSV"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            checkingAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(3415.31),
            "CREDIT",
            "Grisalin Managem SIGONFILE PPD ID: 9000281206",
            "ach"
        );

        // Then: Should be deposit/income
        assertNotNull(categoryResult);
        assertEquals("deposit", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.INCOME, typeResult.getTransactionType());
    }

    @Test
    void testE2E_GlobalTransaction_IndianBank() {
        // Given: Transaction from Indian bank
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "groceries",  // Importer category
            "groceries",
            checkingAccount,
            "Big Bazaar",
            "Grocery purchase",
            BigDecimal.valueOf(-500),  // INR amount
            null,
            "DEBIT",
            "CSV"
        );

        // Then: Should handle international merchant names
        assertNotNull(categoryResult);
        assertNotNull(categoryResult.getCategoryPrimary());
        assertTrue(categoryResult.getConfidence() > 0.5);
    }

    @Test
    void testE2E_GlobalTransaction_EuropeanBank() {
        // Given: Transaction from European bank
        // When: Determine category and type
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "dining",  // Importer category
            "dining",
            checkingAccount,
            "Café de Paris",
            "Restaurant purchase",
            BigDecimal.valueOf(-25.50),  // EUR amount
            null,
            "DEBIT",
            "CSV"
        );

        // Then: Should handle international merchant names
        assertNotNull(categoryResult);
        assertNotNull(categoryResult.getCategoryPrimary());
        assertTrue(categoryResult.getConfidence() > 0.5);
    }

    @Test
    void testE2E_ErrorHandling_NullInputs() {
        // Given: All null inputs
        // When: Determine category
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "CSV"
        );

        // Then: Should return default category without throwing
        assertNotNull(categoryResult);
        assertNotNull(categoryResult.getCategoryPrimary());
        assertEquals("other", categoryResult.getCategoryPrimary());
    }

    @Test
    void testE2E_ErrorHandling_InvalidAmount() {
        // Given: Invalid amount
        // When: Determine category
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "groceries",
            "groceries",
            checkingAccount,
            "Safeway",
            "Grocery purchase",
            BigDecimal.valueOf(1_000_000_000_000L),  // Very large amount
            null,
            null,
            "CSV"
        );

        // Then: Should handle gracefully
        assertNotNull(categoryResult);
        assertNotNull(categoryResult.getCategoryPrimary());
    }

    @Test
    void testE2E_Performance_MultipleTransactions() {
        // Given: Multiple transactions
        int transactionCount = 100;
        long startTime = System.currentTimeMillis();

        // When: Process multiple transactions
        for (int i = 0; i < transactionCount; i++) {
            service.determineCategory(
                "groceries",
                "groceries",
                checkingAccount,
                "Safeway",
                "Grocery purchase " + i,
                BigDecimal.valueOf(-50),
                null,
                null,
                "CSV"
            );
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Then: Should complete in reasonable time (< 5 seconds for 100 transactions)
        assertTrue(duration < 5000, "Processing 100 transactions took too long: " + duration + "ms");
    }
}

