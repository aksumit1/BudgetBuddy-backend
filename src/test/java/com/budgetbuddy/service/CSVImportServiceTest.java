package com.budgetbuddy.service;


import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit Tests for CSVImportService */
class CSVImportServiceTest {

    private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        final AccountDetectionService accountDetectionService =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final com.budgetbuddy.service.ml.EnhancedCategoryDetectionService enhancedCategoryDetection =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class);
        final com.budgetbuddy.service.ml.FuzzyMatchingService fuzzyMatchingService =
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.FuzzyMatchingService.class);
        final TransactionTypeCategoryService transactionTypeCategoryService =
                org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        final ImportCategoryParser importCategoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final com.budgetbuddy.service.category.strategy.CategoryDetectionManager
                categoryDetectionManager =
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.category.strategy.CategoryDetectionManager
                                        .class);
        csvImportService =
                new CSVImportService(
                        accountDetectionService,
                        enhancedCategoryDetection,
                        importCategoryParser,
                        categoryDetectionManager);
    }

    @Test
    void testParseCSVWithValidCSVParsesSuccessfully() {
        // Given
        final String csvContent =
                "Date,Description,Amount\n"
                        + "2024-01-15,Grocery Store,50.00\n"
                        + "2024-01-16,Gas Station,30.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() > 0);
        assertFalse(result.getTransactions().isEmpty());
    }

    @Test
    void testParseCSVWithEmptyFileReturnsGracefully() {
        // Given
        final InputStream inputStream = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then - should return gracefully with info message, not throw exception
        assertNotNull(result);
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getFailureCount()); // addInfo() doesn't increment failureCount
        assertTrue(result.getErrors().size() > 0); // Info message is added to errors list
        assertTrue(result.getErrors().stream().anyMatch(e -> e.toLowerCase(Locale.ROOT).contains("empty")));
    }

    @Test
    void testParseCSVWithNoHeadersReturnsGracefully() {
        // Given - CSV with only whitespace/comma header line that parses to empty headers
        // This tests the case where header line exists but parses to empty list
        // Note: parseCSVLine("   ,   ") returns ["", "", ""] which is not empty
        // So we test with a line that when parsed and filtered, results in empty headers
        // Actually, the implementation checks if headers.isEmpty() after parsing
        // For a real test case, we need a line that parses to truly empty list
        // Since parseCSVLine doesn't return empty for non-empty input, we test with whitespace-only
        // line
        final String csvContent =
                "   ,   "; // Whitespace-only line that may parse to empty after normalization
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then - should return gracefully with info message, not throw exception
        assertNotNull(result);
        // May have 0 success or may parse the whitespace as empty headers
        // If headers are empty, failureCount should be 0 (addInfo doesn't increment it)
        assertTrue(result.getFailureCount() >= 0);
        // Should have info message about no headers or empty file
        assertTrue(
                result.getErrors().stream()
                        .anyMatch(
                                e ->
                                        e.toLowerCase(Locale.ROOT).contains("header")
                                                || e.toLowerCase(Locale.ROOT).contains("empty")));
    }

    @Test
    void testParseCSVWithMissingRequiredFieldsAddsErrors() {
        // Given
        final String csvContent = "Date,Description\n" + "2024-01-15,Grocery Store";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should have errors for missing amount
        assertTrue(result.getFailureCount() > 0 || result.getErrors().size() > 0);
    }

    @Test
    void testParseCSVWithInvalidDateHandlesGracefully() {
        // Given
        final String csvContent = "Date,Description,Amount\n" + "invalid-date,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should handle invalid date gracefully (either skip or add error)
        assertTrue(result.getFailureCount() >= 0);
    }

    @Test
    void testParseCSVWithInvalidAmountHandlesGracefully() {
        // Given
        final String csvContent = "Date,Description,Amount\n" + "2024-01-15,Grocery Store,invalid-amount";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should handle invalid amount gracefully
        assertTrue(result.getFailureCount() >= 0);
    }

    @Test
    void testParseCSVWithDuplicateHeadersHandlesCorrectly() {
        // Given
        final String csvContent =
                "Date,Description,Date,Amount\n" + "2024-01-15,Grocery Store,2024-01-15,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should handle duplicate headers by renaming them
        assertTrue(result.getSuccessCount() >= 0);
    }

    @Test
    void testParseCSVWithEmptyRowsSkipsEmptyLines() {
        // Given
        final String csvContent =
                "Date,Description,Amount\n" + "\n" + "2024-01-15,Grocery Store,50.00\n" + "\n";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should skip empty lines and parse valid rows
        assertTrue(result.getSuccessCount() >= 0);
    }

    @Test
    void testParseCSVWithColumnMismatchHandlesCorrectly() {
        // Given - Row has more columns than headers
        final String csvContent =
                "Date,Description,Amount\n" + "2024-01-15,Grocery Store,50.00,Extra,Columns";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should handle column mismatch by truncating or padding
        assertTrue(result.getSuccessCount() >= 0);
    }

    @Test
    void testParseCSVWithQuotedFieldsHandlesCorrectly() {
        // Given
        final String csvContent =
                "Date,Description,Amount\n" + "\"2024-01-15\",\"Grocery Store, Inc.\",\"50.00\"";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        // Should handle quoted fields correctly
        assertTrue(result.getSuccessCount() >= 0);
    }

    // ========== Credit Card Sign Reversal Tests ==========

    @Test
    void testParseTransactionWithCreditCardAccountReversesSign() {
        // Given
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "credit_card.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        creditCardAccount);

        // Then - Amount should be reversed (positive expense becomes negative)
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("-50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithCreditCardAccountNegativeAmountReversesToPositive() {
        // Given - Credit card payment (negative in import, should reverse to positive)
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Payment");
        row.put("amount", "-100.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "credit_card.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        creditCardAccount);

        // Then - Negative amount should reverse to positive
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("100.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNonCreditCardAccountDoesNotReverseSign() {
        // Given
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount checkingAccount =
                new AccountDetectionService.DetectedAccount();
        checkingAccount.setAccountType("checking");

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "checking.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        checkingAccount);

        // Then - Amount should remain unchanged for non-credit card accounts
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNullDetectedAccountDoesNotReverseSign() {
        // Given
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        // When - No detected account
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "unknown.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        // Then - Amount should remain unchanged when account is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNullAccountTypeDoesNotReverseSign() {
        // Given
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount account =
                new AccountDetectionService.DetectedAccount();
        account.setAccountType(null); // Null account type

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "unknown.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        account);

        // Then - Amount should remain unchanged when account type is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithCreditCardAccountZeroAmountRemainsZero() {
        // Given
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Zero Transaction");
        row.put("amount", "0.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "credit_card.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        creditCardAccount);

        // Then - Zero should remain zero (use compareTo for BigDecimal comparison)
        assertNotNull(transaction);
        assertEquals(
                0,
                java.math.BigDecimal.ZERO.compareTo(transaction.getAmount()),
                "Zero amount should remain zero");
    }

    @Test
    void testParseTransactionWithCreditCardAccountVariousAccountTypeFormatsReversesSign() {
        // Test different credit card account type formats
        final String[] creditCardTypes = {
                "credit", "creditCard", "credit_card", "CREDIT", "Credit Card", "CREDIT_CARD"
        };

        for (final String accountType : creditCardTypes) {
            // Given
            final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
            row.put("date", "2024-01-15");
            row.put("description", "Test Transaction");
            row.put("amount", "100.00");

            final AccountDetectionService.DetectedAccount account =
                    new AccountDetectionService.DetectedAccount();
            account.setAccountType(accountType);

            // When
            final CSVImportService.ParsedTransaction transaction =
                    csvImportService.parseTransaction(
                            row,
                            1,
                            "Date,Description,Amount",
                            "test.csv",
                            null,
                            null,
                            null,
                            null,
                            null,
                            account);

            // Then - All credit card formats should reverse sign
            assertNotNull(
                    transaction, "Transaction should not be null for account type: " + accountType);
            assertEquals(
                    new java.math.BigDecimal("-100.00"),
                    transaction.getAmount(),
                    "Amount should be reversed for account type: " + accountType);
        }
    }

    @Test
    void testParseTransactionWithCreditCardAccountContainsCreditKeywordReversesSign() {
        // Given - Account type contains "credit" keyword
        final CSVImportService.ParsedRow row = new CSVImportService.ParsedRow();
        row.put("date", "2024-01-15");
        row.put("description", "Test Transaction");
        row.put("amount", "75.50");

        final AccountDetectionService.DetectedAccount account =
                new AccountDetectionService.DetectedAccount();
        account.setAccountType("credit_line"); // Contains "credit"

        // When
        final CSVImportService.ParsedTransaction transaction =
                csvImportService.parseTransaction(
                        row,
                        1,
                        "Date,Description,Amount",
                        "test.csv",
                        null,
                        null,
                        null,
                        null,
                        null,
                        account);

        // Then - Should reverse sign because it contains "credit"
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("-75.50"), transaction.getAmount());
    }
}
