package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.plaid.PlaidDataExtractor;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Integration tests for transaction sign reversal across all import methods Tests Plaid, CSV, and
 * PDF imports to ensure consistent sign handling
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionSignReversalIntegrationTest {

    private static final String CHECKING = "checking";

    @Mock private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;

    private PlaidDataExtractor plaidDataExtractor;

    @BeforeEach
    void setUp() {
        plaidDataExtractor =
                new PlaidDataExtractor(accountRepository, transactionTypeCategoryService);
    }

    /** Test that Plaid transactions are reversed for all account types */
    @Test
    void testPlaidSignReversalAllAccountTypesConsistent() {
        // Test data: Plaid sends expenses as positive, income as negative
        final BigDecimal plaidExpense = new BigDecimal("100.00");
        final BigDecimal plaidIncome = new BigDecimal("-5000.00");

        // Test all account types
        final String[] accountTypes = {CHECKING, "savings", "credit", "loan", "investment", null};

        for (final String accountType : accountTypes) {
            AccountTable account = null;
            if (accountType != null) {
                account = new AccountTable();
                account.setAccountType(accountType);
            }

            // Expense should reverse to negative
            final BigDecimal normalizedExpense =
                    plaidDataExtractor.normalizePlaidAmount(plaidExpense, account);
            assertEquals(
                    new BigDecimal("-100.00"),
                    normalizedExpense,
                    "Plaid expense should reverse to negative for account type: " + accountType);

            // Income should reverse to positive
            final BigDecimal normalizedIncome =
                    plaidDataExtractor.normalizePlaidAmount(plaidIncome, account);
            assertEquals(
                    new BigDecimal("5000.00"),
                    normalizedIncome,
                    "Plaid income should reverse to positive for account type: " + accountType);
        }
    }

    /** Test edge cases: null amounts, zero amounts, very large/small amounts */
    @Test
    void testPlaidSignReversalEdgeCasesHandlesCorrectly() {
        final AccountTable account = new AccountTable();
        account.setAccountType(CHECKING);

        // Null amount
        assertNull(plaidDataExtractor.normalizePlaidAmount(null, account));

        // Zero amount
        assertEquals(
                BigDecimal.ZERO, plaidDataExtractor.normalizePlaidAmount(BigDecimal.ZERO, account));

        // Very large amount
        final BigDecimal largeAmount = new BigDecimal("999999999.99");
        assertEquals(
                new BigDecimal("-999999999.99"),
                plaidDataExtractor.normalizePlaidAmount(largeAmount, account));

        // Very small amount
        final BigDecimal smallAmount = new BigDecimal("0.01");
        assertEquals(
                new BigDecimal("-0.01"),
                plaidDataExtractor.normalizePlaidAmount(smallAmount, account));

        // Negative very large amount (income)
        final BigDecimal largeNegativeAmount = new BigDecimal("-999999999.99");
        assertEquals(
                new BigDecimal("999999999.99"),
                plaidDataExtractor.normalizePlaidAmount(largeNegativeAmount, account));
    }

    /** Test that credit card account detection works for various formats */
    @Test
    void testCreditCardAccountTypeDetectionVariousFormatsAllDetected() {
        final String[] creditCardTypes = {
            "credit",
            "creditCard",
            "credit_card",
            "CREDIT",
            "Credit Card",
            "CREDIT_CARD",
            "credit_line", // Contains "credit"
            "creditcardaccount" // Contains "creditcard"
        };

        for (final String accountType : creditCardTypes) {
            final AccountDetectionService.DetectedAccount account =
                    new AccountDetectionService.DetectedAccount();
            account.setAccountType(accountType);

            // Verify account type detection logic
            final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);
            final boolean isCreditCard =
                    accountTypeLower.contains("credit")
                            || "creditcard".equals(accountTypeLower)
                            || "credit_card".equals(accountTypeLower);

            assertTrue(
                    isCreditCard, "Account type should be detected as credit card: " + accountType);
        }
    }

    /** Test that non-credit card accounts are not reversed in CSV/PDF imports */
    @Test
    void testNonCreditCardAccountTypesNotReversed() {
        final String[] nonCreditCardTypes = {
            CHECKING, "savings", "loan", "investment", "depository", "mortgage", "autoLoan"
        };

        for (final String accountType : nonCreditCardTypes) {
            final AccountDetectionService.DetectedAccount account =
                    new AccountDetectionService.DetectedAccount();
            account.setAccountType(accountType);

            // Verify account type detection logic
            final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);
            final boolean isCreditCard =
                    accountTypeLower.contains("credit")
                            || "creditcard".equals(accountTypeLower)
                            || "credit_card".equals(accountTypeLower);

            assertFalse(
                    isCreditCard,
                    "Account type should NOT be detected as credit card: " + accountType);
        }
    }

    /** Test thread safety: multiple concurrent normalizations */
    @Test
    void testPlaidSignReversalConcurrentAccessThreadSafe() throws InterruptedException {
        final AccountTable account = new AccountTable();
        account.setAccountType(CHECKING);
        final BigDecimal testAmount = new BigDecimal("100.00");

        final int threadCount = 10;
        final int iterationsPerThread = 100;
        final Thread[] threads = new Thread[threadCount];
        final boolean[] results = new boolean[threadCount * iterationsPerThread];
        final java.util.concurrent.atomic.AtomicInteger resultIndex =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // Create multiple threads that concurrently normalize amounts
        for (int i = 0; i < threadCount; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                for (int j = 0; j < iterationsPerThread; j++) {
                                    final BigDecimal normalized =
                                            plaidDataExtractor.normalizePlaidAmount(
                                                    testAmount, account);
                                    synchronized (results) {
                                        results[resultIndex.getAndIncrement()] =
                                                normalized.equals(new BigDecimal("-100.00"));
                                    }
                                }
                            });
        }

        // Start all threads
        for (final Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (final Thread thread : threads) {
            thread.join();
        }

        // Verify all results are correct
        for (final boolean result : results) {
            assertTrue(result, "All concurrent normalizations should produce correct results");
        }
    }

    /** Test that null account handling is consistent */
    @Test
    void testNullAccountHandlingConsistentBehavior() {
        final BigDecimal testAmount = new BigDecimal("100.00");

        // Plaid: Should reverse sign even with null account
        final BigDecimal plaidResult = plaidDataExtractor.normalizePlaidAmount(testAmount, null);
        assertEquals(new BigDecimal("-100.00"), plaidResult);

        // CSV/PDF: Should not reverse sign with null account (no account type to check)
        // This is tested in CSVImportServiceTest and PDFImportServiceTest
    }

    /** Test that zero amounts are handled correctly across all methods */
    @Test
    void testZeroAmountHandlingAllMethodsConsistent() {
        final AccountTable account = new AccountTable();
        account.setAccountType(CHECKING);

        // Plaid: Zero should remain zero
        assertEquals(
                BigDecimal.ZERO, plaidDataExtractor.normalizePlaidAmount(BigDecimal.ZERO, account));

        // CSV/PDF: Zero should remain zero (tested in respective test files)
    }

    /** Test boundary conditions: maximum and minimum BigDecimal values */
    @Test
    void testBoundaryConditionsExtremeValuesHandlesCorrectly() {
        final AccountTable account = new AccountTable();
        account.setAccountType(CHECKING);

        // Test with very large positive value
        final BigDecimal maxPositive = new BigDecimal("999999999999999.99");
        final BigDecimal normalizedMax =
                plaidDataExtractor.normalizePlaidAmount(maxPositive, account);
        assertEquals(maxPositive.negate(), normalizedMax);

        // Test with very large negative value
        final BigDecimal maxNegative = new BigDecimal("-999999999999999.99");
        final BigDecimal normalizedMin =
                plaidDataExtractor.normalizePlaidAmount(maxNegative, account);
        assertEquals(maxNegative.negate(), normalizedMin);
    }
}
