package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test CSV import date parsing to diagnose date storage issues Tests the specific transaction case:
 * CSV has 12/19/2025 but stored as 2025-12-18
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@DisplayName("CSV Import Date Parsing - Debug Test")
public class CSVImportDateParsingTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CSVImportDateParsingTest.class);
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
        csvImportService =
                new CSVImportService(
                        accountDetectionService,
                        enhancedCategoryDetection,
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.category.strategy.CategoryDetectionManager
                                        .class));
    }

    @Test
    @DisplayName("Test date parsing for transaction with 12/19/2025")
    void testDateParsing12192025() {
        // Simulate the CSV content similar to the actual transaction
        // The description contains "12/19" at the end, but date column should have "12/19/2025"
        final String csvContent =
                "Date,Description,Amount,Category\n"
                        + "12/19/2025,Online Transfer to CHK ...9994 transaction#: 27398998006 12/19,250,Other";

        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        LOGGER.info("=== Testing CSV Import Date Parsing ===");
        LOGGER.info("CSV Content:\n{}", csvContent);

        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        LOGGER.info("=== Parse Result ===");
        LOGGER.info("Success count: {}", result.getSuccessCount());
        LOGGER.info("Failure count: {}", result.getFailureCount());
        LOGGER.info("Errors: {}", result.getErrors());

        assertNotNull(result, "Import result should not be null");
        assertEquals(1, result.getSuccessCount(), "Should parse 1 transaction");
        assertEquals(0, result.getFailureCount(), "Should have no failures");
        assertTrue(result.getErrors().isEmpty(), "Should have no errors");

        assertFalse(result.getTransactions().isEmpty(), "Should have transactions");
        final CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);

        assertNotNull(transaction, "Transaction should not be null");
        assertNotNull(transaction.getDate(), "Transaction date should not be null");

        final LocalDate parsedDate = transaction.getDate();
        final String isoDate = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        LOGGER.info("=== Date Parsing Result ===");
        LOGGER.info("Parsed LocalDate: {}", parsedDate);
        LOGGER.info("ISO format: {}", isoDate);
        LOGGER.info("Expected: 2025-12-19");
        LOGGER.info(
                "Month: {}, Day: {}, Year: {}",
                parsedDate.getMonthValue(),
                parsedDate.getDayOfMonth(),
                parsedDate.getYear());

        // The date should be 2025-12-19 (December 19, 2025)
        assertEquals(2025, parsedDate.getYear(), "Year should be 2025");
        assertEquals(12, parsedDate.getMonthValue(), "Month should be 12 (December)");
        assertEquals(19, parsedDate.getDayOfMonth(), "Day should be 19");
        assertEquals("2025-12-19", isoDate, "ISO date should be 2025-12-19");

        // Additional assertions
        assertEquals(
                "Online Transfer to CHK ...9994 transaction#: 27398998006 12/19",
                transaction.getDescription(),
                "Description should match");
        assertEquals(250, transaction.getAmount().intValue(), "Amount should be 250");
    }

    @Test
    @DisplayName("Test date parsing with various date formats")
    void testDateParsingVariousFormats() {
        final String[] dateFormats = {
                "12/19/2025", // MM/dd/yyyy - US format
                "12/19/2025", // Should parse as Dec 19
                "2025-12-19", // ISO format
                "12-19-2025", // MM-dd-yyyy
                "Dec 19, 2025" // MMM dd, yyyy
        };

        final String[] expectedISO = {
                "2025-12-19", "2025-12-19", "2025-12-19", "2025-12-19", "2025-12-19"
        };

        for (int i = 0; i < dateFormats.length; i++) {
            final String dateStr = dateFormats[i];
            final String csvContent =
                    String.format("Date,Description,Amount\n%s,Test Transaction,100", dateStr);

            final InputStream inputStream =
                    new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
            final CSVImportService.ImportResult result =
                    csvImportService.parseCSV(inputStream, "test.csv", null, null);

            LOGGER.info("Testing date format: '{}'", dateStr);

            if (!result.getTransactions().isEmpty()) {
                final CSVImportService.ParsedTransaction tx = result.getTransactions().get(0);
                final String isoDate = tx.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
                LOGGER.info("  Parsed as: {}", isoDate);
                assertEquals(
                        expectedISO[i],
                        isoDate,
                        String.format(
                                "Date format '%s' should parse to %s", dateStr, expectedISO[i]));
            } else {
                LOGGER.warn(
                        "  Failed to parse date format: '{}' - errors: {}",
                        dateStr,
                        result.getErrors());
            }
        }
    }

    @Test
    @DisplayName("Test that description does not affect date parsing")
    void testDateParsingDescriptionWithDate() {
        // Description contains "12/19" but date column should still be parsed correctly
        final String csvContent =
                "Date,Description,Amount\n"
                        + "12/19/2025,Transaction with date 12/19 in description,100";

        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        assertNotNull(result);
        assertFalse(result.getTransactions().isEmpty(), "Should parse transaction");

        final CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        final LocalDate parsedDate = transaction.getDate();
        final String isoDate = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        LOGGER.info("Date from CSV: 12/19/2025");
        LOGGER.info("Parsed date: {} (ISO: {})", parsedDate, isoDate);

        // Should still parse as 12/19/2025 despite description containing "12/19"
        assertEquals(
                "2025-12-19",
                isoDate,
                "Date should be 2025-12-19 regardless of description content");
    }

    @Test
    @DisplayName("Test complete flow: Parse CSV and verify date format matches stored format")
    void testCompleteFlowDateParsingAndFormatting() {
        // Test the exact transaction from the user's example
        final String csvContent =
                "Date,Description,Amount,Category\n"
                        + "12/19/2025,Online Transfer to CHK ...9994 transaction#: 27398998006 12/19,250,Other";

        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        assertNotNull(result);
        assertEquals(1, result.getSuccessCount(), "Should parse 1 transaction");

        final CSVImportService.ParsedTransaction parsed = result.getTransactions().get(0);
        final LocalDate parsedDate = parsed.getDate();

        LOGGER.info("=== Complete Flow Test ===");
        LOGGER.info("CSV date string: 12/19/2025");
        LOGGER.info("Parsed LocalDate: {}", parsedDate);
        LOGGER.info("ISO format: {}", parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

        // Verify the date is correct
        assertEquals(2025, parsedDate.getYear(), "Year should be 2025");
        assertEquals(12, parsedDate.getMonthValue(), "Month should be 12");
        assertEquals(19, parsedDate.getDayOfMonth(), "Day should be 19");

        // Simulate what TransactionService.createTransaction would do
        final String storedDateStr = parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        LOGGER.info("Date that would be stored in DB: '{}'", storedDateStr);

        // This is what gets stored in TransactionTable.transactionDate
        assertEquals("2025-12-19", storedDateStr, "Stored date string should be 2025-12-19");

        // Verify it can be parsed back
        final LocalDate reParsed = LocalDate.parse(storedDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        assertEquals(parsedDate, reParsed, "Parsing stored date should give same LocalDate");
        LOGGER.info("Reparsed LocalDate: {} (matches original)", reParsed);
    }

    @Test
    @DisplayName("Test real-world CSV format with 'Posting Date' header and multiple transactions")
    void testRealWorldCSVFormatWithPostingDateHeader() {
        // Real CSV format from user - uses "Posting Date" header and has multiple transactions
        // Note: Converting tab-delimited to comma-delimited since CSVImportService expects
        // comma-separated
        final String csvContent =
                "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n"
                        + "DEBIT,12/19/2025,Online Transfer to CHK ...9994 transaction#: 27398998006 12/19,-250.00,ACCT_XFER,4714.71,\n"
                        + "DEBIT,12/19/2025,Online Transfer to CHK ...9994 transaction#: 27390930759 12/19,-600.00,ACCT_XFER,4964.71,\n"
                        + "DEBIT,12/18/2025,WITHDRAWAL 12/18,-2000.00,MISC_DEBIT,5564.71,";

        LOGGER.info("=== Testing Real-World CSV Format ===");
        LOGGER.info("CSV Content:\n{}", csvContent.replace("\t", " | "));

        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        LOGGER.info("=== Parse Result ===");
        LOGGER.info("Success count: {}", result.getSuccessCount());
        LOGGER.info("Failure count: {}", result.getFailureCount());
        LOGGER.info("Errors: {}", result.getErrors());

        assertNotNull(result, "Import result should not be null");
        assertEquals(3, result.getSuccessCount(), "Should parse 3 transactions");
        assertEquals(0, result.getFailureCount(), "Should have no failures");
        assertTrue(result.getErrors().isEmpty(), "Should have no errors: " + result.getErrors());

        assertFalse(result.getTransactions().isEmpty(), "Should have transactions");
        assertEquals(3, result.getTransactions().size(), "Should have exactly 3 transactions");

        // Verify first transaction (12/19/2025, -250.00)
        final CSVImportService.ParsedTransaction tx1 = result.getTransactions().get(0);
        LOGGER.info("=== Transaction 1 ===");
        LOGGER.info("Description: {}", tx1.getDescription());
        LOGGER.info("Amount: {}", tx1.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx1.getDate(),
                tx1.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-19",
                tx1.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "First transaction should be 2025-12-19");
        assertEquals(-250.00, tx1.getAmount().doubleValue(), 0.01, "Amount should be -250.00");
        assertTrue(
                tx1.getDescription().contains("27398998006"),
                "Description should contain transaction number");

        // Verify second transaction (12/19/2025, -600.00)
        final CSVImportService.ParsedTransaction tx2 = result.getTransactions().get(1);
        LOGGER.info("=== Transaction 2 ===");
        LOGGER.info("Description: {}", tx2.getDescription());
        LOGGER.info("Amount: {}", tx2.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx2.getDate(),
                tx2.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-19",
                tx2.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "Second transaction should be 2025-12-19");
        assertEquals(-600.00, tx2.getAmount().doubleValue(), 0.01, "Amount should be -600.00");
        assertTrue(
                tx2.getDescription().contains("27390930759"),
                "Description should contain transaction number");

        // Verify third transaction (12/18/2025, -2000.00)
        final CSVImportService.ParsedTransaction tx3 = result.getTransactions().get(2);
        LOGGER.info("=== Transaction 3 ===");
        LOGGER.info("Description: {}", tx3.getDescription());
        LOGGER.info("Amount: {}", tx3.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx3.getDate(),
                tx3.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-18",
                tx3.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "Third transaction should be 2025-12-18");
        assertEquals(-2000.00, tx3.getAmount().doubleValue(), 0.01, "Amount should be -2000.00");
        assertTrue(
                tx3.getDescription().contains("WITHDRAWAL"),
                "Description should contain WITHDRAWAL");

        LOGGER.info("=== All Transactions Verified ===");
        LOGGER.info("All 3 transactions parsed correctly with correct dates!");
    }

    @Test
    @DisplayName("Test CSV format with quoted descriptions (handles commas in descriptions)")
    void testCSVFormatWithQuotedDescriptions() {
        // CSV format with quoted descriptions that may contain commas
        final String csvContent =
                "Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #\n"
                        + "DEBIT,12/19/2025,\"Online Transfer to CHK ...9994 transaction#: 27398998006 12/19\",-250.00,ACCT_XFER,4714.71, \n"
                        + "DEBIT,12/19/2025,\"Online Transfer to CHK ...9994 transaction#: 27390930759 12/19\",-600.00,ACCT_XFER,4964.71, \n"
                        + "DEBIT,12/18/2025,\"WITHDRAWAL 12/18\",-2000.00,MISC_DEBIT,5564.71, ";

        LOGGER.info("=== Testing CSV Format with Quoted Descriptions ===");
        LOGGER.info("CSV Content:\n{}", csvContent);

        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        LOGGER.info("=== Parse Result ===");
        LOGGER.info("Success count: {}", result.getSuccessCount());
        LOGGER.info("Failure count: {}", result.getFailureCount());
        LOGGER.info("Errors: {}", result.getErrors());

        assertNotNull(result, "Import result should not be null");
        assertEquals(3, result.getSuccessCount(), "Should parse 3 transactions");
        assertEquals(0, result.getFailureCount(), "Should have no failures");
        assertTrue(result.getErrors().isEmpty(), "Should have no errors: " + result.getErrors());

        assertFalse(result.getTransactions().isEmpty(), "Should have transactions");
        assertEquals(3, result.getTransactions().size(), "Should have exactly 3 transactions");

        // Verify first transaction (12/19/2025, -250.00)
        final CSVImportService.ParsedTransaction tx1 = result.getTransactions().get(0);
        LOGGER.info("=== Transaction 1 (Quoted Description) ===");
        LOGGER.info("Description: {}", tx1.getDescription());
        LOGGER.info("Amount: {}", tx1.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx1.getDate(),
                tx1.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-19",
                tx1.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "First transaction should be 2025-12-19");
        assertEquals(-250.00, tx1.getAmount().doubleValue(), 0.01, "Amount should be -250.00");
        assertTrue(
                tx1.getDescription().contains("27398998006"),
                "Description should contain transaction number");
        assertEquals(
                "Online Transfer to CHK ...9994 transaction#: 27398998006 12/19",
                tx1.getDescription(),
                "Description should match exactly (quotes removed)");

        // Verify second transaction (12/19/2025, -600.00)
        final CSVImportService.ParsedTransaction tx2 = result.getTransactions().get(1);
        LOGGER.info("=== Transaction 2 (Quoted Description) ===");
        LOGGER.info("Description: {}", tx2.getDescription());
        LOGGER.info("Amount: {}", tx2.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx2.getDate(),
                tx2.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-19",
                tx2.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "Second transaction should be 2025-12-19");
        assertEquals(-600.00, tx2.getAmount().doubleValue(), 0.01, "Amount should be -600.00");
        assertTrue(
                tx2.getDescription().contains("27390930759"),
                "Description should contain transaction number");
        assertEquals(
                "Online Transfer to CHK ...9994 transaction#: 27390930759 12/19",
                tx2.getDescription(),
                "Description should match exactly (quotes removed)");

        // Verify third transaction (12/18/2025, -2000.00)
        final CSVImportService.ParsedTransaction tx3 = result.getTransactions().get(2);
        LOGGER.info("=== Transaction 3 (Quoted Description) ===");
        LOGGER.info("Description: {}", tx3.getDescription());
        LOGGER.info("Amount: {}", tx3.getAmount());
        LOGGER.info(
                "Date: {} (ISO: {})",
                tx3.getDate(),
                tx3.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        assertEquals(
                "2025-12-18",
                tx3.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "Third transaction should be 2025-12-18");
        assertEquals(-2000.00, tx3.getAmount().doubleValue(), 0.01, "Amount should be -2000.00");
        assertEquals(
                "WITHDRAWAL 12/18",
                tx3.getDescription(),
                "Description should match exactly (quotes removed)");

        LOGGER.info("=== All Quoted Description Transactions Verified ===");
        LOGGER.info(
                "✅ All 3 transactions with quoted descriptions parsed correctly with correct dates!");
        LOGGER.info("✅ Quotes were properly stripped from descriptions");
        LOGGER.info("✅ Dates are correct: 12/19/2025 -> 2025-12-19, 12/18/2025 -> 2025-12-18");
    }
}
