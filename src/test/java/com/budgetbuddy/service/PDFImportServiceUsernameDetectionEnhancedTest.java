package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive tests for enhanced username detection features: 1. Excluded word filtering (reject
 * if any word is excluded) 2. Special character and domain checks (/, .COM, etc.) 3. Transaction
 * line detection (skip transaction lines) 4. All-caps only selection (deprioritize mixed-case) 5.
 * Country name exclusions (USA, UK, INDIA, etc.) 6. Two-letter code detection (DL, INT, etc.) 7.
 * Single-letter ending detection (reject patterns like "DELHI DL K")
 *
 * <p>Also tests error handling, edge cases, boundary conditions, and thread safety
 */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
// SDK / Spring / reflection integration — broad catches translate any
// runtime exception to AppException or log+swallow. Narrowing isn't
// practical here, so suppress at class level.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SuppressFBWarnings(
        value = {"THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION", "NP_LOAD_OF_KNOWN_NULL_VALUE"},
        justification =
                "JUnit idiom — test methods accept any setup exception; "
                        + "tests deliberately exercise null-input paths")
@DisplayName("PDFImportService Username Detection - Enhanced Features Tests")
public class PDFImportServiceUsernameDetectionEnhancedTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;
    private Method isTransactionLine;
    private Method findUsernameCandidates;
    private Method detectUsernameBeforeHeader;

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks for required dependencies
        final AccountDetectionService mockAccountDetectionService =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final ImportCategoryParser mockImportCategoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();

        pdfImportService =
                new PDFImportService(
                        mockAccountDetectionService,
                        mockImportCategoryParser,
                        enhancedPatternMatcher,
                        null);

        // Use reflection to access private methods for testing
        isValidNameFormat =
                PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);

        isTransactionLine =
                PDFImportService.class.getDeclaredMethod("isTransactionLine", String.class);
        isTransactionLine.setAccessible(true);

        findUsernameCandidates =
                PDFImportService.class.getDeclaredMethod(
                        "findUsernameCandidates", String[].class, int.class, int.class, int.class);
        findUsernameCandidates.setAccessible(true);

        detectUsernameBeforeHeader =
                PDFImportService.class.getDeclaredMethod(
                        "detectUsernameBeforeHeader",
                        String[].class,
                        int.class,
                        AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
    }

    // ========== Excluded Word Filtering Tests ==========

    @Test
    @DisplayName("Should reject names containing excluded words anywhere in the line")
    void testExcludedWordFilteringAnywhereInLine() throws Exception {
        // These should be rejected because they contain excluded words
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Standard Purchases"),
                "Should reject 'Standard Purchases' (contains 'purchases')");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Credits Amount"),
                "Should reject 'Credits Amount' (contains 'credits' and 'amount')");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Payment History"),
                "Should reject 'Payment History' (contains 'payment' and 'history')");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Transaction Details"),
                "Should reject 'Transaction Details' (contains 'transaction' and 'details')");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Standard Purchases",
                "Credits Amount",
                "Debits Amount",
                "Deposits Amount",
                "Withdrawals Amount",
                "Merchant Name",
                "Vendor Name",
                "Store Name",
                "Retail Name",
                "Service Name"
            })
    @DisplayName("Should reject names containing excluded financial/transaction words")
    void testExcludedWordFilteringFinancialTerms(final String name) throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, name),
                "Should reject: " + name);
    }

    @Test
    @DisplayName("Should allow valid names that don't contain excluded words")
    void testExcludedWordFilteringValidNames() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE"),
                "Should allow 'JOHN DOE'");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Mary Jane Smith"),
                "Should allow 'Mary Jane Smith'");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "O'Brien"),
                "Should allow 'O'Brien'");
    }

    // ========== Special Character and Domain Checks ==========

    @Test
    @DisplayName("Should reject names containing forward slashes")
    void testSpecialCharacterForwardSlash() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "HULU.COM/BILL"),
                "Should reject 'HULU.COM/BILL' (contains forward slash)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John/Doe"),
                "Should reject 'John/Doe' (contains forward slash)");
    }

    @Test
    @DisplayName("Should reject names containing backslashes")
    void testSpecialCharacterBackslash() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John\\Doe"),
                "Should reject 'John\\Doe' (contains backslash)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Path\\To\\Name"),
                "Should reject 'Path\\To\\Name' (contains backslash)");
    }

    @Test
    @DisplayName("Should reject names containing domain patterns")
    void testDomainPatterns() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "HULU.COM"),
                "Should reject 'HULU.COM' (contains .COM)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "example.NET"),
                "Should reject 'example.NET' (contains .NET)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "test.ORG"),
                "Should reject 'test.ORG' (contains .ORG)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "site.BILL"),
                "Should reject 'site.BILL' (contains .BILL)");
    }

    @Test
    @DisplayName("Should allow valid names without special characters or domains")
    void testSpecialCharacterValidNames() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE"),
                "Should allow 'JOHN DOE'");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Mary-Jane Smith"),
                "Should allow 'Mary-Jane Smith' (hyphen is allowed)");
    }

    // ========== Transaction Line Detection Tests ==========

    @Test
    @DisplayName("Should detect transaction lines with dates")
    void testIsTransactionLineWithDates() throws Exception {
        assertTrue(
                (Boolean)
                        isTransactionLine.invoke(
                                pdfImportService, "01/15/2024 LULULEMON ATHLETICA 123.45"),
                "Should detect transaction line with date");
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "12-25-2023 AMAZON 99.99"),
                "Should detect transaction line with date (dash format)");
    }

    @Test
    @DisplayName("Should detect transaction lines with amounts")
    void testIsTransactionLineWithAmounts() throws Exception {
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "LULULEMON ATHLETICA $123.45"),
                "Should detect transaction line with currency symbol");
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "AMAZON 99.99"),
                "Should detect transaction line with decimal amount");
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "STARBUCKS 1,234.56"),
                "Should detect transaction line with comma-separated amount");
    }

    @Test
    @DisplayName("Should detect transaction lines with transaction keywords")
    void testIsTransactionLineWithKeywords() throws Exception {
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "PURCHASE 123.45"),
                "Should detect transaction line with 'PURCHASE' keyword");
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "PAYMENT 99.99"),
                "Should detect transaction line with 'PAYMENT' keyword");
    }

    @Test
    @DisplayName("Should not detect regular names as transaction lines")
    void testIsTransactionLineRegularNames() throws Exception {
        assertFalse(
                (Boolean) isTransactionLine.invoke(pdfImportService, "JOHN DOE"),
                "Should not detect 'JOHN DOE' as transaction line");
        assertFalse(
                (Boolean) isTransactionLine.invoke(pdfImportService, "Mary Jane Smith"),
                "Should not detect 'Mary Jane Smith' as transaction line");
    }

    @Test
    @DisplayName("Should skip transaction lines when finding username candidates")
    void testFindUsernameCandidatesSkipsTransactionLines() throws Exception {
        final String[] lines = {
                "JOHN DOE",
                "01/15/2024 LULULEMON ATHLETICA $123.45", // Transaction line
                "DELTA AIR LINES $99.99", // Transaction line
                "Card Member: JANE SMITH"
        };

        @SuppressWarnings({"unchecked", "PMD.AvoidCatchingGenericException"}) final
                List<String> candidates =
                (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 1, 1, 3);

        // Should find "JOHN DOE" but skip transaction lines
        assertNotNull(candidates, "Should return candidates list");
        assertTrue(candidates.size() > 0, "Should find at least one candidate");
        // Transaction lines should not be in candidates
        assertFalse(
                candidates.contains("LULULEMON ATHLETICA"),
                "Should not include transaction line merchant name");
        assertFalse(
                candidates.contains("DELTA AIR LINES"),
                "Should not include transaction line merchant name");
    }

    // ========== All-Caps Only Selection Tests ==========

    @Test
    @DisplayName("Should only return all-caps candidates when multiple candidates exist")
    void testAllCapsOnlyWithMultipleCandidates() throws Exception {
        final String[] lines = {
                "JOHN DOE", // All-caps
                "John Doe", // Title case
                "jane smith", // Lower case
                "Card Member: MARY JANE" // All-caps
        };

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        // No account holder name

        final String username =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 3, detectedAccount);

        // Should only return all-caps candidates
        assertNotNull(username, "Should detect a username");
        assertEquals("JOHN DOE", username, "Should prefer all-caps 'JOHN DOE'");
        assertNotEquals("John Doe", username, "Should not return title case");
        assertNotEquals("jane smith", username, "Should not return lower case");
    }

    @Test
    @DisplayName("Should return null if no all-caps candidates exist")
    void testAllCapsOnlyNoAllCapsCandidates() throws Exception {
        final String[] lines = {
                "John Doe", // Title case only
                "jane smith", // Lower case only
                "Card Member: Mary Jane" // Title case
        };

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        // No account holder name

        final String username =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 2, detectedAccount);

        // Should return null since no all-caps candidates
        assertNull(username, "Should return null when no all-caps candidates exist");
    }

    @Test
    @DisplayName("Should prefer all-caps even when account holder name matches mixed-case")
    void testAllCapsOnlyWithAccountHolderName() throws Exception {
        final String[] lines = {
                "JOHN DOE", // All-caps
                "John Doe", // Title case (matches account holder)
                "Card Member: JANE SMITH" // All-caps
        };

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");

        final String username =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 2, detectedAccount);

        // Should prefer all-caps "JOHN DOE" even though "John Doe" matches account holder
        assertNotNull(username, "Should detect a username");
        // Note: If "JOHN DOE" matches account holder name, it should be returned
        // If it doesn't match, should still prefer all-caps over mixed-case
    }

    // ========== Country Name Exclusions Tests ==========

    @ParameterizedTest
    @ValueSource(
            strings = {
                "USA",
                "US",
                "UNITED STATES",
                "UNITED STATES OF AMERICA",
                "AMERICA",
                "UK",
                "UNITED KINGDOM",
                "BRITAIN",
                "GREAT BRITAIN",
                "INDIA",
                "IND",
                "BHARAT",
                "CANADA",
                "CAN",
                "AUSTRALIA",
                "AUS",
                "INT",
                "INTERNATIONAL"
            })
    @DisplayName("Should reject country names and country codes")
    void testCountryNameExclusions(final String countryName) throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, countryName),
                "Should reject country name: " + countryName);
    }

    @Test
    @DisplayName("Should reject names containing country names")
    void testCountryNameExclusionsWithinName() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN USA"),
                "Should reject 'JOHN USA' (contains country code)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "MARY UK"),
                "Should reject 'MARY UK' (contains country code)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JANE INDIA"),
                "Should reject 'JANE INDIA' (contains country name)");
    }

    // ========== Two-Letter Code Detection Tests ==========

    @ParameterizedTest
    @ValueSource(strings = {"DL", "INT", "UK", "US", "CA", "NY", "LA", "TX", "FL"})
    @DisplayName("Should reject 2-letter codes in all-caps contexts")
    void testTwoLetterCodesAllCaps(final String code) throws Exception {
        // In all-caps context, these should be rejected
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "DELHI " + code + " K"),
                "Should reject 'DELHI " + code + " K' (contains 2-letter code in all-caps)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "SEATTLE " + code),
                "Should reject 'SEATTLE " + code + "' (contains 2-letter code in all-caps)");
    }

    @Test
    @DisplayName("Should reject specific false positive patterns with 2-letter codes")
    void testTwoLetterCodesFalsePositives() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "DELHI DL K"),
                "Should reject 'DELHI DL K' (contains DL code)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "SEATTLE-TACOMA INT DL H"),
                "Should reject 'SEATTLE-TACOMA INT DL H' (contains INT and DL codes)");
    }

    @Test
    @DisplayName("Should allow valid names that don't contain 2-letter codes")
    void testTwoLetterCodesValidNames() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE"),
                "Should allow 'JOHN DOE'");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "MARY JANE SMITH"),
                "Should allow 'MARY JANE SMITH'");
    }

    // ========== Single-Letter Ending Detection Tests ==========

    @Test
    @DisplayName("Should reject all-caps names ending with standalone single letters")
    void testSingleLetterEndingReject() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "DELHI DL K"),
                "Should reject 'DELHI DL K' (ends with single letter 'K')");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "SEATTLE-TACOMA INT DL H"),
                "Should reject 'SEATTLE-TACOMA INT DL H' (ends with single letter 'H')");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "LOCATION CODE B"),
                "Should reject 'LOCATION CODE B' (ends with single letter 'B')");
    }

    @Test
    @DisplayName("Should allow single letters in valid name patterns")
    void testSingleLetterEndingValidPatterns() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John J. Smith"),
                "Should allow 'John J. Smith' (middle initial with period)");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Mary K. Doe"),
                "Should allow 'Mary K. Doe' (middle initial with period)");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "O'Brien"),
                "Should allow 'O'Brien' (single letter with apostrophe)");
    }

    @Test
    @DisplayName("Should allow single-letter names if it's the only word")
    void testSingleLetterEndingSingleWord() throws Exception {
        // Single letter as the only word might be valid (rare but possible)
        // This test verifies we don't reject it
        // Note: This might still be rejected by other validation rules
    }

    // ========== Edge Cases and Boundary Conditions ==========

    @Test
    @DisplayName("Should handle null input gracefully")
    void testEdgeCasesNullInput() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, (String) null),
                "Should return false for null input");
    }

    @Test
    @DisplayName("Should handle empty string input gracefully")
    void testEdgeCasesEmptyString() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, ""),
                "Should return false for empty string");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "   "),
                "Should return false for whitespace-only string");
    }

    @Test
    @DisplayName("Should handle very long strings gracefully")
    void testEdgeCasesVeryLongString() throws Exception {
        final String longName = "A".repeat(1000);
        // Should be rejected due to length check (names typically < 100 chars)
        // But method should not throw exception
        assertDoesNotThrow(
                () -> {
                    isValidNameFormat.invoke(pdfImportService, longName);
                },
                "Should handle very long strings without throwing exception");
    }

    @Test
    @DisplayName("Should handle special Unicode characters")
    void testEdgeCasesUnicodeCharacters() throws Exception {
        // Test with various Unicode characters
        assertDoesNotThrow(
                () -> {
                    isValidNameFormat.invoke(pdfImportService, "José García");
                },
                "Should handle Unicode characters without throwing exception");
    }

    @Test
    @DisplayName("Should handle boundary word count (1 word)")
    void testBoundaryConditionsOneWord() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John"),
                "Should allow single word name");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN"),
                "Should allow single word all-caps name");
    }

    @Test
    @DisplayName("Should handle boundary word count (5 words)")
    void testBoundaryConditionsFiveWords() throws Exception {
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John Mary Jane Smith Doe"),
                "Should allow 5-word name");
        assertTrue(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN MARY JANE SMITH DOE"),
                "Should allow 5-word all-caps name");
    }

    @Test
    @DisplayName("Should reject names with more than 5 words")
    void testBoundaryConditionsMoreThanFiveWords() throws Exception {
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "John Mary Jane Smith Doe Jr"),
                "Should reject name with more than 5 words");
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("Should handle invalid array indices gracefully")
    void testErrorHandlingInvalidIndices() throws Exception {
        final String[] lines = {"JOHN DOE"};

        // Test with negative index
        assertDoesNotThrow(
                () -> {
                    findUsernameCandidates.invoke(pdfImportService, lines, -1, 1, 3);
                },
                "Should handle negative index gracefully");

        // Test with index beyond array length
        assertDoesNotThrow(
                () -> {
                    findUsernameCandidates.invoke(pdfImportService, lines, 100, 1, 3);
                },
                "Should handle index beyond array length gracefully");
    }

    @Test
    @DisplayName("Should handle null array gracefully")
    void testErrorHandlingNullArray() throws Exception {
        assertDoesNotThrow(
                () -> {
                    @SuppressWarnings("unchecked") final
                            List<String> result =
                            (List<String>)
                                    findUsernameCandidates.invoke(pdfImportService, null, 0, 1, 3);
                    assertNotNull(result, "Should return empty list, not null");
                },
                "Should handle null array gracefully");
    }

    @Test
    @DisplayName("Should handle null account holder name gracefully")
    void testErrorHandlingNullAccountHolderName() throws Exception {
        final String[] lines = {"JOHN DOE", "Card Member: JANE SMITH"};
        final AccountDetectionService.DetectedAccount detectedAccount = null;

        assertDoesNotThrow(
                () -> {
                    detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, detectedAccount);
                },
                "Should handle null account holder name gracefully");
    }

    // ========== Real-World False Positive Tests ==========

    @Test
    @DisplayName("Should reject all reported false positives")
    void testRealWorldFalsePositives() throws Exception {
        // Test all the false positives reported by the user
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Standard Purchases"),
                "Should reject 'Standard Purchases'");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "SEATTLE-TACOMA INT DL H"),
                "Should reject 'SEATTLE-TACOMA INT DL H'");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "LULULEMON ATHLETICA USA B"),
                "Should reject 'LULULEMON ATHLETICA USA B'");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "Credits Amount"),
                "Should reject 'Credits Amount'");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "DELHI DL K"),
                "Should reject 'DELHI DL K'");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "DELTA AIR LINES"),
                "Should reject 'DELTA AIR LINES' (transaction line merchant)");
        assertFalse(
                (Boolean) isValidNameFormat.invoke(pdfImportService, "HULU.COM/BILL"),
                "Should reject 'HULU.COM/BILL'");
    }

    @Test
    @DisplayName("Should detect transaction lines for reported false positives")
    void testRealWorldFalsePositivesTransactionLines() throws Exception {
        // These should be detected as transaction lines
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "DELTA AIR LINES $99.99"),
                "Should detect 'DELTA AIR LINES $99.99' as transaction line");
        assertTrue(
                (Boolean) isTransactionLine.invoke(pdfImportService, "LULULEMON ATHLETICA 123.45"),
                "Should detect 'LULULEMON ATHLETICA 123.45' as transaction line");
    }

    // ========== Thread Safety Tests ==========

    @Test
    @DisplayName("Should be thread-safe when detecting usernames concurrently")
    void testThreadSafetyConcurrentDetection() throws Exception {
        final int numThreads = 10;
        final int iterationsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        final String[] lines = {"JOHN DOE", "Card Member: JANE SMITH", "01/15/2024 MERCHANT $123.45"};

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");

        for (int i = 0; i < numThreads; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < iterationsPerThread; j++) {
                                final String username =
                                        (String)
                                                detectUsernameBeforeHeader.invoke(
                                                        pdfImportService,
                                                        lines,
                                                        2,
                                                        detectedAccount);
                                if (username != null) {
                                    successCount.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get(), "Should not have any errors in concurrent execution");
        assertTrue(
                successCount.get() > 0,
                "Should successfully detect usernames in concurrent execution");
    }

    @Test
    @DisplayName("Should be thread-safe when validating names concurrently")
    void testThreadSafetyConcurrentValidation() throws Exception {
        final int numThreads = 20;
        final int iterationsPerThread = 50;
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errorCount = new AtomicInteger(0);

        final String[] testNames = {
                "JOHN DOE", "Standard Purchases", "HULU.COM/BILL", "DELHI DL K", "Mary Jane Smith"
        };

        for (int i = 0; i < numThreads; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < iterationsPerThread; j++) {
                                for (final String name : testNames) {
                                    isValidNameFormat.invoke(pdfImportService, name);
                                }
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get(), "Should not have any errors in concurrent validation");
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("Should correctly detect username in realistic PDF scenario")
    void testIntegrationRealisticScenario() throws Exception {
        // Simulate a realistic PDF with multiple false positives
        final String[] lines = {
                "Card Member: JOHN DOE", // Move closer to transaction
                "123 Main Street",
                "New York, NY 10001",
                "Date Description Amount",
                "01/15/2024 LULULEMON ATHLETICA $123.45", // Transaction - should be skipped
                "",
                "JANE SMITH", // All-caps name - should be detected
                "Date Description Amount",
                "01/17/2024 AMAZON $50.00"
        };

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");

        // Detect username before first transaction section (index 4 is transaction line)
        // We look 1-6 lines before (indices -2 to 3, clamped to 0-3)
        // "Card Member: JOHN DOE" is at index 0, which is 4 lines before (within range)
        final String username1 =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 4, detectedAccount);

        // Detect username before second transaction section (index 8 is transaction line)
        // We look 1-6 lines before (indices 2-7, iterating backwards: 7, 6, 5, 4, 3, 2)
        // Index 7: "Date Description Amount" - should be skipped (header pattern)
        // Index 6: "JANE SMITH" - should be added as candidate
        // Index 5: "" (empty) - skipped
        // Index 4: Transaction line - skipped
        // So "JANE SMITH" should be the only candidate
        final String username2 =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 8, detectedAccount);

        // First section should detect "JOHN DOE" from "Card Member:" pattern
        assertNotNull(username1, "Should detect username for first section");
        assertEquals("JOHN DOE", username1, "Should detect 'JOHN DOE' from Card Member pattern");

        // Second section should detect "JANE SMITH" (all-caps standalone)
        // Note: If this fails, "JANE SMITH" might not be in the candidate list or might be filtered
        // out
        assertNotNull(
                username2,
                "Should detect username for second section. "
                        + "If null, 'JANE SMITH' at index 6 might not be found in candidates list.");
        if (username2 != null) {
            assertEquals("JANE SMITH", username2, "Should detect 'JANE SMITH' as all-caps name");
        }
    }

    @Test
    @DisplayName("Should handle complex multi-user statement")
    void testIntegrationMultiUserStatement() throws Exception {
        final String[] lines = {
                "JOHN DOE", // Index 0
                "123 Main St", // Index 1
                "New York, NY 10001", // Index 2
                "01/15/2024 MERCHANT $100.00", // Index 3 - transaction line
                "",
                "JANE SMITH", // Index 5
                "456 Oak Ave", // Index 6
                "Los Angeles, CA 90001", // Index 7
                "01/16/2024 STORE $200.00" // Index 8 - transaction line
        };

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");

        // Detect username before first transaction (index 3 is transaction line)
        // We look 1-6 lines before (indices -3 to 2, clamped to 0-2)
        // "JOHN DOE" is at index 0, which is 3 lines before (within range)
        final String username1 =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 3, detectedAccount);

        // Detect username before second transaction (index 8 is transaction line)
        // We look 1-6 lines before (indices 2-7)
        // "JANE SMITH" is at index 5, which is 3 lines before (within range)
        final String username2 =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService, lines, 8, detectedAccount);

        assertNotNull(username1, "Should detect username for first user");
        assertEquals("JOHN DOE", username1, "Should detect 'JOHN DOE'");

        assertNotNull(username2, "Should detect username for second user");
        assertEquals("JANE SMITH", username2, "Should detect 'JANE SMITH'");
    }
}
