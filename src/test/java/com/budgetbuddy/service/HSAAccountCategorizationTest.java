package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.plaid.PlaidDataExtractor;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for HSA account categorization - HSA accounts should be treated as investment accounts -
 * HSA deposits (positive amounts) should be categorized as investment - HSA debits (negative
 * amounts) should be categorized as expenses
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class HSAAccountCategorizationTest {

    private static final String PLAID_HSA_123 = "plaid-hsa-123";
    private static final String HEALTHCARE = "healthcare";
    private static final String HSA_ACCOUNT_123 = "hsa-account-123";

    @Mock private AccountRepository accountRepository;

    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;

    private PlaidDataExtractor plaidDataExtractor;

    private AccountTable hsaAccount;
    private TransactionTable transactionTable;

    @BeforeEach
    void setUp() {
        plaidDataExtractor =
                new PlaidDataExtractor(accountRepository, transactionTypeCategoryService);

        // Setup HSA account
        hsaAccount = new AccountTable();
        hsaAccount.setAccountId(HSA_ACCOUNT_123);
        hsaAccount.setAccountType("hsa");
        hsaAccount.setAccountName("Health Savings Account");
        hsaAccount.setPlaidAccountId(PLAID_HSA_123);

        // Setup transaction
        transactionTable = new TransactionTable();
        transactionTable.setAccountId(HSA_ACCOUNT_123);
        transactionTable.setTransactionId("txn-123");
    }

    @Test
    void testHSADepositCategorizedAsInvestment() {
        // Given: HSA deposit (positive amount)
        transactionTable.setAmount(new BigDecimal("1000.00"));
        transactionTable.setDescription("HSA Contribution");

        // Mock account lookup
        when(accountRepository.findById(HSA_ACCOUNT_123)).thenReturn(Optional.of(hsaAccount));
        when(accountRepository.findByPlaidAccountId(PLAID_HSA_123))
                .thenReturn(Optional.of(hsaAccount));

        // Mock TransactionTypeCategoryService to return "other" category (will be overridden by HSA
        // logic)
        final TransactionTypeCategoryService.CategoryResult defaultResult =
                new TransactionTypeCategoryService.CategoryResult("other", "other", "PLAID", 0.5);
        when(transactionTypeCategoryService.determineCategory(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(defaultResult);

        // Create Plaid transaction
        final Transaction plaidTransaction =
                createPlaidTransaction(
                        PLAID_HSA_123, "HSA Contribution", null, 1000.00, null, null);

        // When
        plaidDataExtractor.updateTransactionFromPlaid(transactionTable, plaidTransaction);

        // Then
        assertEquals(
                "investment",
                transactionTable.getCategoryPrimary(),
                "HSA deposit should be investment");
        assertEquals(
                "otherInvestment",
                transactionTable.getCategoryDetailed(),
                "HSA deposit should be otherInvestment");
        assertFalse(
                transactionTable.getCategoryOverridden(),
                "HSA deposit should not be user-overridden");
    }

    @Test
    void testHSADebitCategorizedAsExpense() {
        // Given: HSA debit (negative amount)
        transactionTable.setAmount(new BigDecimal("-150.00"));
        transactionTable.setDescription("Medical Expense");
        transactionTable.setMerchantName("Pharmacy");

        // Mock account lookup
        when(accountRepository.findById(HSA_ACCOUNT_123)).thenReturn(Optional.of(hsaAccount));
        when(accountRepository.findByPlaidAccountId(PLAID_HSA_123))
                .thenReturn(Optional.of(hsaAccount));

        // Mock TransactionTypeCategoryService - returns healthcare (HSA logic should preserve this)
        final TransactionTypeCategoryService.CategoryResult healthcareResult =
                new TransactionTypeCategoryService.CategoryResult(
                        HEALTHCARE, HEALTHCARE, "PLAID", 0.9);
        when(transactionTypeCategoryService.determineCategory(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(healthcareResult);

        // Create Plaid transaction
        final Transaction plaidTransaction =
                createPlaidTransaction(
                        PLAID_HSA_123,
                        "Medical Expense",
                        "Pharmacy",
                        -150.00,
                        "MEDICAL",
                        "PHARMACIES");

        // When
        plaidDataExtractor.updateTransactionFromPlaid(transactionTable, plaidTransaction);

        // Then
        assertEquals(
                HEALTHCARE,
                transactionTable.getCategoryPrimary(),
                "HSA debit should be expense (healthcare)");
        assertEquals(
                HEALTHCARE,
                transactionTable.getCategoryDetailed(),
                "HSA debit should be healthcare");
    }

    @Test
    void testHSADebitWithGenericCategoryDefaultsToHealthcare() {
        // Given: HSA debit with generic category
        transactionTable.setAmount(new BigDecimal("-200.00"));
        transactionTable.setDescription("HSA Withdrawal");

        // Mock account lookup
        when(accountRepository.findById(HSA_ACCOUNT_123)).thenReturn(Optional.of(hsaAccount));
        when(accountRepository.findByPlaidAccountId(PLAID_HSA_123))
                .thenReturn(Optional.of(hsaAccount));

        // Mock TransactionTypeCategoryService - returns generic "other" (HSA logic should override
        // to healthcare)
        final TransactionTypeCategoryService.CategoryResult otherResult =
                new TransactionTypeCategoryService.CategoryResult("other", "other", "PLAID", 0.5);
        when(transactionTypeCategoryService.determineCategory(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(otherResult);

        // Create Plaid transaction
        final Transaction plaidTransaction =
                createPlaidTransaction(PLAID_HSA_123, "HSA Withdrawal", null, -200.00, null, null);

        // When
        plaidDataExtractor.updateTransactionFromPlaid(transactionTable, plaidTransaction);

        // Then
        assertEquals(
                HEALTHCARE,
                transactionTable.getCategoryPrimary(),
                "HSA debit with generic category should default to healthcare");
        assertEquals(
                HEALTHCARE,
                transactionTable.getCategoryDetailed(),
                "HSA debit should be healthcare");
    }

    @Test
    void testNonHSAAccountNotAffected() {
        // Given: Non-HSA account transaction
        final AccountTable checkingAccount = new AccountTable();
        checkingAccount.setAccountId("checking-123");
        checkingAccount.setAccountType("checking");
        checkingAccount.setPlaidAccountId("plaid-checking-123");

        transactionTable.setAccountId("checking-123");
        transactionTable.setAmount(new BigDecimal("1000.00"));
        transactionTable.setDescription("Deposit");

        // Mock account lookup
        when(accountRepository.findById("checking-123")).thenReturn(Optional.of(checkingAccount));
        when(accountRepository.findByPlaidAccountId("plaid-checking-123"))
                .thenReturn(Optional.of(checkingAccount));

        // Mock TransactionTypeCategoryService - returns income (non-HSA accounts should not be
        // overridden)
        final TransactionTypeCategoryService.CategoryResult incomeResult =
                new TransactionTypeCategoryService.CategoryResult(
                        "income", "deposit", "PLAID", 0.9);
        when(transactionTypeCategoryService.determineCategory(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(incomeResult);

        // Create Plaid transaction
        final Transaction plaidTransaction =
                createPlaidTransaction(
                        "plaid-checking-123", "Deposit", null, 1000.00, "INCOME", "DEPOSIT");

        // When
        plaidDataExtractor.updateTransactionFromPlaid(transactionTable, plaidTransaction);

        // Then: Should use normal categorization, not investment
        assertEquals(
                "income",
                transactionTable.getCategoryPrimary(),
                "Non-HSA account should use normal categorization");
        assertEquals(
                "deposit",
                transactionTable.getCategoryDetailed(),
                "Non-HSA account should use normal categorization");
    }

    /** Creates a Plaid Transaction object for testing */
    private Transaction createPlaidTransaction(
            final String accountId,
            final String name,
            final String merchantName,
            final double amount,
            final String categoryPrimary,
            final String categoryDetailed) {
        final Transaction transaction = new Transaction();
        transaction.setAccountId(accountId);
        transaction.setName(name);
        transaction.setMerchantName(merchantName);
        transaction.setAmount(amount);
        transaction.setDate(LocalDate.now());
        transaction.setIsoCurrencyCode("USD");
        transaction.setPending(false);

        if (categoryPrimary != null || categoryDetailed != null) {
            final PersonalFinanceCategory pfc = new PersonalFinanceCategory();
            pfc.setPrimary(categoryPrimary);
            pfc.setDetailed(categoryDetailed);
            transaction.setPersonalFinanceCategory(pfc);
        }

        return transaction;
    }
}
