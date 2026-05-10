package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for extended CSV import formats: ICICI Direct, Paytm, Fidelity NetBenefits */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
class CSVImportServiceExtendedFormatsTest {

    private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        final AccountDetectionService accountDetectionService =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final com.budgetbuddy.service.ml.EnhancedCategoryDetectionService
                enhancedCategoryDetection =
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

    // MARK: - ICICI Direct Tests

    @Test
    void testParseCSVICICIDirectEquityTransactions() {
        // Given: ICICI Direct equity P&L statement format (brokerage account)
        final String csvContent =
                "Trade Date,Value Date,Description,Amount,Net Amount\n"
                        + "19/12/2025,20/12/2025,BUY INFOSYS LTD 100 SHARES,-50000.00,-50000.00\n"
                        + "20/12/2025,21/12/2025,SELL INFOSYS LTD 50 SHARES,30000.00,30000.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        // Verify first transaction (BUY)
        final CSVImportService.ParsedTransaction buyTx = transactions.get(0);
        assertEquals(
                LocalDate.of(2025, 12, 19),
                buyTx.getDate(),
                "Trade date should be parsed correctly (DD/MM/YYYY)");
        assertTrue(
                buyTx.getAmount().compareTo(BigDecimal.ZERO) < 0,
                "Buy transaction should be negative");
        assertTrue(
                buyTx.getDescription() != null
                        && (buyTx.getDescription().contains("INFOSYS")
                                || buyTx.getDescription().contains("BUY")
                                || buyTx.getMerchantName() != null),
                "Description should contain security name or transaction type");

        // Verify currency detection (should default to INR for ICICI Direct)
        // Note: Currency detection happens during amount parsing
    }

    @Test
    void testParseCSVICICIDirectDividendTransactions() {
        // Given: ICICI Direct dividend transactions
        // Note: Value Date column may be empty for dividend transactions —
        // express that as a true empty CSV field (`,,`) rather than `, X`,
        // otherwise the parser correctly slides "DIVIDEND ..." into the
        // Value Date column and the Amount column ends up empty.
        final String csvContent =
                "Trade Date,Value Date,Particulars,Amount\n"
                        + "15/12/2025,,DIVIDEND INFOSYS LTD,1000.00\n"
                        + "15/12/2025,,DIVIDEND TCS LTD,1500.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1, "Should parse at least one transaction");
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty(), "Transactions list should not be empty");

        final CSVImportService.ParsedTransaction dividendTx = transactions.get(0);
        assertEquals(LocalDate.of(2025, 12, 15), dividendTx.getDate());
        assertTrue(
                dividendTx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                "Dividend should be positive");
        assertTrue(
                dividendTx.getDescription() != null
                        && (dividendTx.getDescription().contains("INFOSYS")
                                || dividendTx.getDescription().contains("DIVIDEND")
                                || dividendTx.getDescription().contains("TCS")),
                "Description should contain security name or dividend indicator. Got: "
                        + dividendTx.getDescription());
    }

    // MARK: - Paytm Tests

    @Test
    void testParseCSVPaytmWalletTransactions() {
        // Given: Paytm transaction history format
        final String csvContent =
                "Transaction Date & Time,Description,Merchant Name,Amount,Status\n"
                        + "19/12/2025 14:30,Payment to ABC Store,ABC Store,-500.00,Success\n"
                        + "18/12/2025 10:15,Money Received from XYZ,XYZ,1000.00,Success";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        // Verify payment transaction
        final CSVImportService.ParsedTransaction paymentTx = transactions.get(0);
        assertEquals(LocalDate.of(2025, 12, 19), paymentTx.getDate());
        assertTrue(
                paymentTx.getAmount().compareTo(BigDecimal.ZERO) < 0, "Payment should be negative");
        assertNotNull(paymentTx.getDescription());
        assertTrue(
                paymentTx.getDescription() != null || paymentTx.getMerchantName() != null,
                "Description or merchant name should be present");

        // Verify money received transaction
        if (transactions.size() > 1) {
            final CSVImportService.ParsedTransaction receivedTx = transactions.get(1);
            assertTrue(
                    receivedTx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                    "Money received should be positive");
        }
    }

    @Test
    void testParseCSVPaytmINRCurrency() {
        // Given: Paytm transaction with INR currency indicator
        final String csvContent =
                "Date,Description,Amount (INR),Status\n"
                        + "19/12/2025,Payment to Merchant,₹500.00,Success";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        final CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals("INR", tx.getCurrencyCode(), "Currency should be detected as INR");
        final BigDecimal expectedAmount = new BigDecimal("500.00");
        final BigDecimal actualAmount = tx.getAmount();
        final BigDecimal expectedAbs =
                expectedAmount.compareTo(BigDecimal.ZERO) < 0
                        ? expectedAmount.negate()
                        : expectedAmount;
        final BigDecimal actualAbs =
                actualAmount.compareTo(BigDecimal.ZERO) < 0 ? actualAmount.negate() : actualAmount;
        assertEquals(expectedAbs, actualAbs);
    }

    // MARK: - Fidelity NetBenefits Tests

    @Test
    void testParseCSVFidelityNetBenefitsContributionTransactions() {
        // Given: Fidelity NetBenefits transaction history format
        final String csvContent =
                "Run Date,Transaction Date,Transaction Type,Transaction Description,Amount\n"
                        + "2025-12-19,2025-12-19,Contribution,Employee Contribution,500.00\n"
                        + "2025-12-19,2025-12-19,Contribution,Employer Match,250.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        final CSVImportService.ParsedTransaction contributionTx = transactions.get(0);
        assertEquals(LocalDate.of(2025, 12, 19), contributionTx.getDate());
        assertTrue(
                contributionTx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                "Contribution should be positive");
        assertTrue(
                contributionTx.getDescription().contains("Contribution"),
                "Description should contain transaction type");
    }

    @Test
    void testParseCSVFidelityNetBenefitsDistributionTransactions() {
        // Given: Fidelity NetBenefits distribution/withdrawal
        final String csvContent =
                "Run Date,Transaction Date,Action,Transaction Description,Net Amount\n"
                        + "2025-12-20,2025-12-20,Withdrawal,Loan Repayment,-1000.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        final CSVImportService.ParsedTransaction withdrawalTx = transactions.get(0);
        assertEquals(LocalDate.of(2025, 12, 20), withdrawalTx.getDate());
        assertTrue(
                withdrawalTx.getAmount().compareTo(BigDecimal.ZERO) < 0,
                "Withdrawal should be negative");
    }

    @Test
    void testParseCSVFidelityNetBenefitsDividendTransactions() {
        // Given: Fidelity NetBenefits dividend payments
        final String csvContent =
                "Run Date,Transaction Date,Transaction Type,Security Name,Amount\n"
                        + "2025-12-15,2025-12-15,Dividend,S&P 500 Index Fund,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        final CSVImportService.ParsedTransaction dividendTx = transactions.get(0);
        assertEquals(LocalDate.of(2025, 12, 15), dividendTx.getDate());
        assertTrue(
                dividendTx.getAmount().compareTo(BigDecimal.ZERO) > 0,
                "Dividend should be positive");
        assertTrue(
                (dividendTx.getDescription() != null
                                && (dividendTx.getDescription().contains("S&P")
                                        || dividendTx.getDescription().contains("Index")
                                        || dividendTx.getDescription().contains("Fund")))
                        || (dividendTx.getMerchantName() != null
                                && (dividendTx.getMerchantName().contains("S&P")
                                        || dividendTx.getMerchantName().contains("Index")
                                        || dividendTx.getMerchantName().contains("Fund"))),
                "Description or merchant name should contain security name");
    }

    @Test
    void testParseCSVMixedFormatsAllSupported() {
        // Given: CSV with mixed date formats from different sources
        final String csvContent =
                "Date,Description,Amount\n"
                        + "19/12/2025,ICICI Direct Trade,50000.00\n"
                        + // DD/MM/YYYY (ICICI Direct)
                        "2025-12-19,Paytm Payment,-500.00\n"
                        + // yyyy-MM-dd (ISO)
                        "12/19/2025,Fidelity Contribution,1000.00"; // MM/dd/yyyy (US format)
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, "test.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 2, "Should parse at least 2 transactions");
        final List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();

        // Verify all dates are parsed correctly
        for (final CSVImportService.ParsedTransaction tx : transactions) {
            assertNotNull(tx.getDate(), "All transactions should have valid dates");
            assertEquals(
                    LocalDate.of(2025, 12, 19),
                    tx.getDate(),
                    "All dates should parse to 2025-12-19 regardless of format");
        }
    }
}
