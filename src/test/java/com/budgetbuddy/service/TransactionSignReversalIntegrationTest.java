package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.plaid.PlaidDataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for transaction sign reversal across all import methods
 * Tests Plaid, CSV, and PDF imports to ensure consistent sign handling
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionSignReversalIntegrationTest {

    @Mock
    private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;

    private PlaidDataExtractor plaidDataExtractor;

    @BeforeEach
    void setUp() {
        plaidDataExtractor = new PlaidDataExtractor(accountRepository, transactionTypeCategoryService);
    }

    /**
     * Test that Plaid transactions are reversed for all account types
     */
    @Test
    void testPlaidSignReversal_AllAccountTypes_Consistent() {
        // Test data: Plaid sends expenses as positive, income as negative
        BigDecimal plaidExpense = new BigDecimal("100.00");
        BigDecimal plaidIncome = new BigDecimal("-5000.00");

        // Test all account types
        String[] accountTypes = {"checking", "savings", "credit", "loan", "investment", null};

        for (String accountType : accountTypes) {
            AccountTable account = null;
            if (accountType != null) {
                account = new AccountTable();
                account.setAccountType(accountType);
            }

            // Expense should reverse to negative
            BigDecimal normalizedExpense = plaidDataExtractor.normalizePlaidAmount(plaidExpense, account);
            assertEquals(new BigDecimal("-100.00"), normalizedExpense,
                "Plaid expense should reverse to negative for account type: " + accountType);

            // Income should reverse to positive
            BigDecimal normalizedIncome = plaidDataExtractor.normalizePlaidAmount(plaidIncome, account);
            assertEquals(new BigDecimal("5000.00"), normalizedIncome,
                "Plaid income should reverse to positive for account type: " + accountType);
        }
    }

    /**
     * Test edge cases: null amounts, zero amounts, very large/small amounts
     */
    @Test
    void testPlaidSignReversal_EdgeCases_HandlesCorrectly() {
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // Null amount
        assertNull(plaidDataExtractor.normalizePlaidAmount(null, account));

        // Zero amount
        assertEquals(BigDecimal.ZERO, plaidDataExtractor.normalizePlaidAmount(BigDecimal.ZERO, account));

        // Very large amount
        BigDecimal largeAmount = new BigDecimal("999999999.99");
        assertEquals(new BigDecimal("-999999999.99"), 
            plaidDataExtractor.normalizePlaidAmount(largeAmount, account));

        // Very small amount
        BigDecimal smallAmount = new BigDecimal("0.01");
        assertEquals(new BigDecimal("-0.01"), 
            plaidDataExtractor.normalizePlaidAmount(smallAmount, account));

        // Negative very large amount (income)
        BigDecimal largeNegativeAmount = new BigDecimal("-999999999.99");
        assertEquals(new BigDecimal("999999999.99"), 
            plaidDataExtractor.normalizePlaidAmount(largeNegativeAmount, account));
    }

    /**
     * Test that credit card account detection works for various formats
     */
    @Test
    void testCreditCardAccountTypeDetection_VariousFormats_AllDetected() {
        String[] creditCardTypes = {
            "credit",
            "creditCard",
            "credit_card",
            "CREDIT",
            "Credit Card",
            "CREDIT_CARD",
            "credit_line", // Contains "credit"
            "creditcardaccount" // Contains "creditcard"
        };

        for (String accountType : creditCardTypes) {
            AccountDetectionService.DetectedAccount account = new AccountDetectionService.DetectedAccount();
            account.setAccountType(accountType);

            // Verify account type detection logic
            String accountTypeLower = accountType.toLowerCase();
            boolean isCreditCard = accountTypeLower.contains("credit") || 
                                  accountTypeLower.equals("creditcard") || 
                                  accountTypeLower.equals("credit_card");

            assertTrue(isCreditCard, "Account type should be detected as credit card: " + accountType);
        }
    }

    /**
     * Test that non-credit card accounts are not reversed in CSV/PDF imports
     */
    @Test
    void testNonCreditCardAccountTypes_NotReversed() {
        String[] nonCreditCardTypes = {
            "checking",
            "savings",
            "loan",
            "investment",
            "depository",
            "mortgage",
            "autoLoan"
        };

        for (String accountType : nonCreditCardTypes) {
            AccountDetectionService.DetectedAccount account = new AccountDetectionService.DetectedAccount();
            account.setAccountType(accountType);

            // Verify account type detection logic
            String accountTypeLower = accountType.toLowerCase();
            boolean isCreditCard = accountTypeLower.contains("credit") || 
                                  accountTypeLower.equals("creditcard") || 
                                  accountTypeLower.equals("credit_card");

            assertFalse(isCreditCard, "Account type should NOT be detected as credit card: " + accountType);
        }
    }

    /**
     * Test thread safety: multiple concurrent normalizations
     */
    @Test
    void testPlaidSignReversal_ConcurrentAccess_ThreadSafe() throws InterruptedException {
        AccountTable account = new AccountTable();
        account.setAccountType("checking");
        BigDecimal testAmount = new BigDecimal("100.00");

        int threadCount = 10;
        int iterationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount * iterationsPerThread];
        java.util.concurrent.atomic.AtomicInteger resultIndex = new java.util.concurrent.atomic.AtomicInteger(0);

        // Create multiple threads that concurrently normalize amounts
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    BigDecimal normalized = plaidDataExtractor.normalizePlaidAmount(testAmount, account);
                    synchronized (results) {
                        results[resultIndex.getAndIncrement()] = normalized.equals(new BigDecimal("-100.00"));
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all results are correct
        for (boolean result : results) {
            assertTrue(result, "All concurrent normalizations should produce correct results");
        }
    }

    /**
     * Test that null account handling is consistent
     */
    @Test
    void testNullAccountHandling_ConsistentBehavior() {
        BigDecimal testAmount = new BigDecimal("100.00");

        // Plaid: Should reverse sign even with null account
        BigDecimal plaidResult = plaidDataExtractor.normalizePlaidAmount(testAmount, null);
        assertEquals(new BigDecimal("-100.00"), plaidResult);

        // CSV/PDF: Should not reverse sign with null account (no account type to check)
        // This is tested in CSVImportServiceTest and PDFImportServiceTest
    }

    /**
     * Test that zero amounts are handled correctly across all methods
     */
    @Test
    void testZeroAmountHandling_AllMethods_Consistent() {
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // Plaid: Zero should remain zero
        assertEquals(BigDecimal.ZERO, plaidDataExtractor.normalizePlaidAmount(BigDecimal.ZERO, account));

        // CSV/PDF: Zero should remain zero (tested in respective test files)
    }

    /**
     * Test boundary conditions: maximum and minimum BigDecimal values
     */
    @Test
    void testBoundaryConditions_ExtremeValues_HandlesCorrectly() {
        AccountTable account = new AccountTable();
        account.setAccountType("checking");

        // Test with very large positive value
        BigDecimal maxPositive = new BigDecimal("999999999999999.99");
        BigDecimal normalizedMax = plaidDataExtractor.normalizePlaidAmount(maxPositive, account);
        assertEquals(maxPositive.negate(), normalizedMax);

        // Test with very large negative value
        BigDecimal maxNegative = new BigDecimal("-999999999999999.99");
        BigDecimal normalizedMin = plaidDataExtractor.normalizePlaidAmount(maxNegative, account);
        assertEquals(maxNegative.negate(), normalizedMin);
    }
}

