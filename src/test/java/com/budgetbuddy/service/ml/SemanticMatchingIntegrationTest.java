package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.budgetbuddy.AWSTestConfiguration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for Semantic Matching Tests: error handling, race conditions, boundary
 * conditions, comprehensive dictionary
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Semantic Matching Integration Tests")
class SemanticMatchingIntegrationTest {

    @Autowired private SemanticMatchingService semanticMatchingService;

    // ========== Bill Pay Tests ==========

    @Test
    @DisplayName("Semantic matching detects bill pay transactions")
    void testSemanticMatchingBillPay() {
        final String[] billPayCases = {
            "PUGET SOUND ENER BILLPAY",
            "Puget Sound Energy Bill Pay",
            "CITY OF BELLEVUE UTILITY BILL PAY",
            "Online Bill Payment",
            "Automatic Bill Pay",
            "Bill Payment Service",
            "Utility Bill Payment"
        };

        for (final String merchant : billPayCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for bill pay: " + merchant);
            assertEquals(
                    "utilities",
                    result.category,
                    "Should match utilities for bill pay: " + merchant);
            assertTrue(result.similarity >= 0.6, "Should have good similarity: " + merchant);
        }
    }

    // ========== Credit Card Payment Tests ==========

    @Test
    @DisplayName("Semantic matching detects credit card payments")
    void testSemanticMatchingCreditCardPayments() {
        final String[] creditCardCases = {
            "CITI AUTOPAY PAYMENT",
            "Chase Credit Card Auto Pay",
            "WF Credit Card AUTO PAY",
            "DISCOVER E-PAYMENT",
            "AMZ_STORECRD_PMT PAYMENT",
            "Credit Card Payment",
            "Card Autopay",
            "Credit Card Auto Pay"
        };

        for (final String merchant : creditCardCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(
                    result, "Should find semantic match for credit card payment: " + merchant);
            assertEquals(
                    "payment",
                    result.category,
                    "Should match payment for credit card: " + merchant);
        }
    }

    // ========== Loan Payment Tests ==========

    @Test
    @DisplayName("Semantic matching detects loan payments")
    void testSemanticMatchingLoanPayments() {
        final String[] loanCases = {
            "Loan Payment",
            "Car Loan Payment",
            "Student Loan Payment",
            "Mortgage Payment",
            "Loan Autopay",
            "Debt Payment",
            "Loan Repayment"
        };

        for (final String merchant : loanCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for loan payment: " + merchant);
            assertEquals("payment", result.category, "Should match payment for loan: " + merchant);
        }
    }

    // ========== Investment Tests ==========

    @Test
    @DisplayName("Semantic matching detects investment transactions")
    void testSemanticMatchingInvestments() {
        final String[] investmentCases = {
            "Online Transfer from Morganstanley",
            "Fidelity Investment",
            "Vanguard Fund Purchase",
            "Stock Purchase",
            "Bond Investment",
            "CD Investment",
            "Retirement Account",
            "401k Contribution",
            "IRA Investment"
        };

        for (final String merchant : investmentCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for investment: " + merchant);
            assertEquals("investment", result.category, "Should match investment: " + merchant);
        }
    }

    // ========== Stocks Tests ==========

    @Test
    @DisplayName("Semantic matching detects stock transactions")
    void testSemanticMatchingStocks() {
        final String[] stockCases = {
            "Stock Purchase",
            "Stock Trading",
            "Equity Investment",
            "Share Purchase",
            "Stock Dividend",
            "Stock Broker",
            "Stock Account"
        };

        for (final String merchant : stockCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for stocks: " + merchant);
            assertEquals(
                    "investment",
                    result.category,
                    "Should match investment for stocks: " + merchant);
        }
    }

    // ========== Store Tests ==========

    @Test
    @DisplayName("Semantic matching detects store purchases")
    void testSemanticMatchingStores() {
        final String[] storeCases = {
            "Store Purchase",
            "Retail Store",
            "Department Store",
            "Convenience Store",
            "In Store Purchase",
            "Store Shopping"
        };

        for (final String merchant : storeCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for store: " + merchant);
            assertEquals(
                    "shopping", result.category, "Should match shopping for store: " + merchant);
        }
    }

    // ========== Race Condition Tests ==========

    @Test
    @DisplayName("Semantic matching handles concurrent access (race conditions)")
    void testSemanticMatchingRaceConditions() throws InterruptedException {
        final int threadCount = 10;
        final int operationsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final List<Future<Boolean>> futures = new ArrayList<>();

        // Test concurrent reads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            final Future<Boolean> future =
                    executor.submit(
                            () -> {
                                try {
                                    for (int j = 0; j < operationsPerThread; j++) {
                                        final String merchant =
                                                "Test Merchant " + threadId + " " + j;
                                        final SemanticMatchingService.SemanticMatchResult result =
                                                semanticMatchingService.findBestSemanticMatch(
                                                        merchant, "Description");
                                        // Should not throw exception
                                        assertDoesNotThrow(
                                                () -> {
                                                    if (result != null) {
                                                        result.category.toLowerCase(Locale.ROOT);
                                                    }
                                                });
                                    }
                                    return true;
                                } catch (Exception e) {
                                    return false;
                                } finally {
                                    latch.countDown();
                                }
                            });
            futures.add(future);
        }

        // Test concurrent writes (add semantic clusters)
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(
                    () -> {
                        try {
                            final Set<String> keywords =
                                    new HashSet<>(
                                            Arrays.asList("test" + threadId, "keyword" + threadId));
                            semanticMatchingService.addSemanticCluster(
                                    "test_category_" + threadId, keywords);
                        } catch (Exception e) {
                            // Should not throw
                        }
                    });
        }

        // Wait for all threads to complete
        assertTrue(
                latch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds");

        // Verify all operations succeeded
        for (final Future<Boolean> future : futures) {
            try {
                assertTrue(future.get(), "All concurrent operations should succeed");
            } catch (ExecutionException | InterruptedException e) {
                fail("Concurrent operation failed: " + e.getMessage());
            }
        }

        executor.shutdown();
    }

    // ========== Boundary Condition Tests ==========

    @Test
    @DisplayName("Semantic matching handles very long text")
    void testSemanticMatchingVeryLongText() {
        final String longText = "Grocery Store " + "X".repeat(20_000);
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch(longText, null);

        // Should handle gracefully (truncated to MAX_TEXT_LENGTH)
        assertDoesNotThrow(
                () -> {
                    if (result != null) {
                        result.category.toLowerCase(Locale.ROOT);
                    }
                });
    }

    @Test
    @DisplayName("Semantic matching handles null inputs gracefully")
    void testSemanticMatchingNullInputs() {
        assertNull(semanticMatchingService.findBestSemanticMatch(null, null));
        assertNull(semanticMatchingService.findBestSemanticMatch(null, ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("", null));
    }

    @Test
    @DisplayName("Semantic matching handles empty strings")
    void testSemanticMatchingEmptyStrings() {
        assertNull(semanticMatchingService.findBestSemanticMatch("", ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("   ", "   "));
    }

    @Test
    @DisplayName("Semantic matching handles special characters")
    void testSemanticMatchingSpecialCharacters() {
        final String[] specialCases = {
            "Grocery Store!@#$%^&*()",
            "Grocery-Store",
            "Grocery_Store",
            "Grocery.Store",
            "Grocery/Store"
        };

        for (final String merchant : specialCases) {
            assertDoesNotThrow(
                    () -> {
                        final SemanticMatchingService.SemanticMatchResult result =
                                semanticMatchingService.findBestSemanticMatch(merchant, null);
                        // Should handle special characters gracefully
                    });
        }
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("Semantic matching handles exceptions gracefully")
    void testSemanticMatchingErrorHandling() {
        // Test with various edge cases that might cause exceptions
        final String[] edgeCases = {
            "\u0000", // Null character
            "\uFFFF", // Invalid Unicode
            new String(new char[100_000]), // Very long string
            "Test", // Normal case (should work)
        };

        for (final String merchant : edgeCases) {
            // Should not throw exception
            assertDoesNotThrow(
                    () -> {
                        final SemanticMatchingService.SemanticMatchResult result =
                                semanticMatchingService.findBestSemanticMatch(merchant, null);
                        // Result might be null, but no exception should be thrown
                    });
        }
    }

    // ========== Dictionary Coverage Tests ==========

    @Test
    @DisplayName("Semantic matching dictionary covers all required categories")
    void testSemanticMatchingDictionaryCoverage() {
        final Set<String> allCategories = semanticMatchingService.getAllCategories();

        // Verify required categories exist
        assertTrue(allCategories.contains("groceries"), "Should have groceries category");
        assertTrue(allCategories.contains("dining"), "Should have dining category");
        assertTrue(allCategories.contains("transportation"), "Should have transportation category");
        assertTrue(allCategories.contains("utilities"), "Should have utilities category");
        assertTrue(allCategories.contains("payment"), "Should have payment category");
        assertTrue(allCategories.contains("investment"), "Should have investment category");
        assertTrue(allCategories.contains("shopping"), "Should have shopping category");
        assertTrue(allCategories.contains("transfer"), "Should have transfer category");
        assertTrue(allCategories.contains("cash"), "Should have cash category");

        // Verify dictionary is comprehensive (has many keywords)
        // Exclude test categories created by concurrent tests
        for (final String category : allCategories) {
            if (category.startsWith("test_category_")) {
                continue; // Skip test categories created by concurrent tests
            }
            final Set<String> cluster = semanticMatchingService.getSemanticCluster(category);
            assertTrue(
                    cluster.size() >= 5,
                    "Category '"
                            + category
                            + "' should have at least 5 keywords, has: "
                            + cluster.size());
        }
    }

    // ========== Performance Tests ==========

    @Test
    @DisplayName("Semantic matching performs well with many concurrent requests")
    void testSemanticMatchingPerformance() throws InterruptedException {
        final int requestCount = 1000;
        final ExecutorService executor = Executors.newFixedThreadPool(10);
        final CountDownLatch latch = new CountDownLatch(requestCount);
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < requestCount; i++) {
            final int requestId = i;
            executor.submit(
                    () -> {
                        try {
                            final long startTime = System.currentTimeMillis();
                            semanticMatchingService.findBestSemanticMatch(
                                    "Test Merchant " + requestId, "Test Description " + requestId);
                            final long endTime = System.currentTimeMillis();
                            times.add(endTime - startTime);
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(30, TimeUnit.SECONDS),
                "All requests should complete within 30 seconds");

        // Calculate statistics
        final OptionalDouble avgTime = times.stream().mapToLong(Long::longValue).average();
        final long maxTime = times.stream().mapToLong(Long::longValue).max().orElse(0);

        assertTrue(avgTime.isPresent(), "Should have timing data");
        assertTrue(
                avgTime.getAsDouble() < 100,
                "Average time should be < 100ms, was: " + avgTime.getAsDouble());
        assertTrue(maxTime < 1000, "Max time should be < 1000ms, was: " + maxTime);

        executor.shutdown();
    }
}
