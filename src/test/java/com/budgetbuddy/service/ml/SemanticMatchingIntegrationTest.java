package com.budgetbuddy.service.ml;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Semantic Matching
 * Tests: error handling, race conditions, boundary conditions, comprehensive dictionary
 */

@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Semantic Matching Integration Tests")
class SemanticMatchingIntegrationTest {
    
    @Autowired
    private SemanticMatchingService semanticMatchingService;
    
    // ========== Bill Pay Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects bill pay transactions")
    void testSemanticMatching_BillPay() {
        String[] billPayCases = {
            "PUGET SOUND ENER BILLPAY",
            "Puget Sound Energy Bill Pay",
            "CITY OF BELLEVUE UTILITY BILL PAY",
            "Online Bill Payment",
            "Automatic Bill Pay",
            "Bill Payment Service",
            "Utility Bill Payment"
        };
        
        for (String merchant : billPayCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for bill pay: " + merchant);
            assertEquals("utilities", result.category, "Should match utilities for bill pay: " + merchant);
            assertTrue(result.similarity >= 0.6, "Should have good similarity: " + merchant);
        }
    }
    
    // ========== Credit Card Payment Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects credit card payments")
    void testSemanticMatching_CreditCardPayments() {
        String[] creditCardCases = {
            "CITI AUTOPAY PAYMENT",
            "Chase Credit Card Auto Pay",
            "WF Credit Card AUTO PAY",
            "DISCOVER E-PAYMENT",
            "AMZ_STORECRD_PMT PAYMENT",
            "Credit Card Payment",
            "Card Autopay",
            "Credit Card Auto Pay"
        };
        
        for (String merchant : creditCardCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for credit card payment: " + merchant);
            assertEquals("payment", result.category, "Should match payment for credit card: " + merchant);
        }
    }
    
    // ========== Loan Payment Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects loan payments")
    void testSemanticMatching_LoanPayments() {
        String[] loanCases = {
            "Loan Payment",
            "Car Loan Payment",
            "Student Loan Payment",
            "Mortgage Payment",
            "Loan Autopay",
            "Debt Payment",
            "Loan Repayment"
        };
        
        for (String merchant : loanCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for loan payment: " + merchant);
            assertEquals("payment", result.category, "Should match payment for loan: " + merchant);
        }
    }
    
    // ========== Investment Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects investment transactions")
    void testSemanticMatching_Investments() {
        String[] investmentCases = {
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
        
        for (String merchant : investmentCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for investment: " + merchant);
            assertEquals("investment", result.category, "Should match investment: " + merchant);
        }
    }
    
    // ========== Stocks Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects stock transactions")
    void testSemanticMatching_Stocks() {
        String[] stockCases = {
            "Stock Purchase",
            "Stock Trading",
            "Equity Investment",
            "Share Purchase",
            "Stock Dividend",
            "Stock Broker",
            "Stock Account"
        };
        
        for (String merchant : stockCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for stocks: " + merchant);
            assertEquals("investment", result.category, "Should match investment for stocks: " + merchant);
        }
    }
    
    // ========== Store Tests ==========
    
    @Test
    @DisplayName("Semantic matching detects store purchases")
    void testSemanticMatching_Stores() {
        String[] storeCases = {
            "Store Purchase",
            "Retail Store",
            "Department Store",
            "Convenience Store",
            "In Store Purchase",
            "Store Shopping"
        };
        
        for (String merchant : storeCases) {
            SemanticMatchingService.SemanticMatchResult result = 
                semanticMatchingService.findBestSemanticMatch(merchant, null);
            
            assertNotNull(result, "Should find semantic match for store: " + merchant);
            assertEquals("shopping", result.category, "Should match shopping for store: " + merchant);
        }
    }
    
    // ========== Race Condition Tests ==========
    
    @Test
    @DisplayName("Semantic matching handles concurrent access (race conditions)")
    void testSemanticMatching_RaceConditions() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        // Test concurrent reads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            Future<Boolean> future = executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String merchant = "Test Merchant " + threadId + " " + j;
                        SemanticMatchingService.SemanticMatchResult result = 
                            semanticMatchingService.findBestSemanticMatch(merchant, "Description");
                        // Should not throw exception
                        assertDoesNotThrow(() -> {
                            if (result != null) {
                                result.category.toLowerCase();
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
            executor.submit(() -> {
                try {
                    Set<String> keywords = new HashSet<>(Arrays.asList("test" + threadId, "keyword" + threadId));
                    semanticMatchingService.addSemanticCluster("test_category_" + threadId, keywords);
                } catch (Exception e) {
                    // Should not throw
                }
            });
        }
        
        // Wait for all threads to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 seconds");
        
        // Verify all operations succeeded
        for (Future<Boolean> future : futures) {
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
    void testSemanticMatching_VeryLongText() {
        String longText = "Grocery Store " + "X".repeat(20000);
        SemanticMatchingService.SemanticMatchResult result = 
            semanticMatchingService.findBestSemanticMatch(longText, null);
        
        // Should handle gracefully (truncated to MAX_TEXT_LENGTH)
        assertDoesNotThrow(() -> {
            if (result != null) {
                result.category.toLowerCase();
            }
        });
    }
    
    @Test
    @DisplayName("Semantic matching handles null inputs gracefully")
    void testSemanticMatching_NullInputs() {
        assertNull(semanticMatchingService.findBestSemanticMatch(null, null));
        assertNull(semanticMatchingService.findBestSemanticMatch(null, ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("", null));
    }
    
    @Test
    @DisplayName("Semantic matching handles empty strings")
    void testSemanticMatching_EmptyStrings() {
        assertNull(semanticMatchingService.findBestSemanticMatch("", ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("   ", "   "));
    }
    
    @Test
    @DisplayName("Semantic matching handles special characters")
    void testSemanticMatching_SpecialCharacters() {
        String[] specialCases = {
            "Grocery Store!@#$%^&*()",
            "Grocery-Store",
            "Grocery_Store",
            "Grocery.Store",
            "Grocery/Store"
        };
        
        for (String merchant : specialCases) {
            assertDoesNotThrow(() -> {
                SemanticMatchingService.SemanticMatchResult result = 
                    semanticMatchingService.findBestSemanticMatch(merchant, null);
                // Should handle special characters gracefully
            });
        }
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    @DisplayName("Semantic matching handles exceptions gracefully")
    void testSemanticMatching_ErrorHandling() {
        // Test with various edge cases that might cause exceptions
        String[] edgeCases = {
            "\u0000", // Null character
            "\uFFFF", // Invalid Unicode
            new String(new char[100000]), // Very long string
            "Test", // Normal case (should work)
        };
        
        for (String merchant : edgeCases) {
            // Should not throw exception
            assertDoesNotThrow(() -> {
                SemanticMatchingService.SemanticMatchResult result = 
                    semanticMatchingService.findBestSemanticMatch(merchant, null);
                // Result might be null, but no exception should be thrown
            });
        }
    }
    
    // ========== Dictionary Coverage Tests ==========
    
    @Test
    @DisplayName("Semantic matching dictionary covers all required categories")
    void testSemanticMatching_DictionaryCoverage() {
        Set<String> allCategories = semanticMatchingService.getAllCategories();
        
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
        for (String category : allCategories) {
            if (category.startsWith("test_category_")) {
                continue; // Skip test categories created by concurrent tests
            }
            Set<String> cluster = semanticMatchingService.getSemanticCluster(category);
            assertTrue(cluster.size() >= 5, 
                "Category '" + category + "' should have at least 5 keywords, has: " + cluster.size());
        }
    }
    
    // ========== Performance Tests ==========
    
    @Test
    @DisplayName("Semantic matching performs well with many concurrent requests")
    void testSemanticMatching_Performance() throws InterruptedException {
        int requestCount = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(requestCount);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        
        for (int i = 0; i < requestCount; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    semanticMatchingService.findBestSemanticMatch(
                        "Test Merchant " + requestId, "Test Description " + requestId);
                    long endTime = System.currentTimeMillis();
                    times.add(endTime - startTime);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All requests should complete within 30 seconds");
        
        // Calculate statistics
        OptionalDouble avgTime = times.stream().mapToLong(Long::longValue).average();
        long maxTime = times.stream().mapToLong(Long::longValue).max().orElse(0);
        
        assertTrue(avgTime.isPresent(), "Should have timing data");
        assertTrue(avgTime.getAsDouble() < 100, 
            "Average time should be < 100ms, was: " + avgTime.getAsDouble());
        assertTrue(maxTime < 1000, 
            "Max time should be < 1000ms, was: " + maxTime);
        
        executor.shutdown();
    }
}

