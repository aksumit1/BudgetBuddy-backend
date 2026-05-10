package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for multi-year export and form-specific export methods */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@DisplayName("Tax Export Multi-Year and Form-Specific Tests")
class TaxExportMultiYearAndFormTests {

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @InjectMocks private TaxExportService taxExportService;

    private String userId;

    @BeforeEach
    void setUp() {
        userId = "user123";
    }

    @Test
    @DisplayName("Should generate multi-year tax export")
    void testGenerateMultiYearTaxExportSuccess() {
        // Given
        final int[] years = {2022, 2023, 2024};

        // Mock transactions for each year
        when(transactionRepository.findByUserIdAndDateRange(eq(userId), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateMultiYearTaxExport(userId, years, null, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getSummary());
        assertNotNull(result.getTransactionsByCategory());

        // Verify generateTaxExport was called for each year
        verify(transactionRepository, times(3))
                .findByUserIdAndDateRange(eq(userId), anyString(), anyString());
    }

    @Test
    @DisplayName("Should throw exception for null userId in multi-year export")
    void testGenerateMultiYearTaxExportNullUserId() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateMultiYearTaxExport(null, new int[] {2024}, null, null);
                });
    }

    @Test
    @DisplayName("Should throw exception for empty years array")
    void testGenerateMultiYearTaxExportEmptyYears() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateMultiYearTaxExport(userId, new int[] {}, null, null);
                });
    }

    @Test
    @DisplayName("Should throw exception for invalid year in multi-year export")
    void testGenerateMultiYearTaxExportInvalidYear() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateMultiYearTaxExport(
                            userId, new int[] {1800, 2024}, null, null);
                });
    }

    @Test
    @DisplayName("Should export multi-year data to CSV")
    void testExportToCSVMultiYearSuccess() {
        // Given
        final int[] years = {2022, 2023, 2024};
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // Add some transactions
        final TaxExportService.TaxTransaction tx1 = new TaxExportService.TaxTransaction();
        tx1.setDate(java.time.LocalDate.of(2022, 6, 15));
        tx1.setDescription("Salary 2022");
        tx1.setAmount(new BigDecimal("50000.00"));
        tx1.setTaxTag("SALARY");

        final TaxExportService.TaxTransaction tx2 = new TaxExportService.TaxTransaction();
        tx2.setDate(java.time.LocalDate.of(2023, 6, 15));
        tx2.setDescription("Salary 2023");
        tx2.setAmount(new BigDecimal("55000.00"));
        tx2.setTaxTag("SALARY");

        result.getTransactionsByCategory().put("SALARY", Arrays.asList(tx1, tx2));

        // When
        final String csv = taxExportService.exportToCSVMultiYear(result, years);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Multi-Year Tax Export"));
        assertTrue(csv.contains("2022,2023,2024"));
        assertTrue(csv.contains("Salary 2022"));
        assertTrue(csv.contains("Salary 2023"));
    }

    @Test
    @DisplayName("Should export Schedule A format")
    void testExportToScheduleASuccess() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // Add Schedule A transactions
        final TaxExportService.TaxTransaction charityTx = new TaxExportService.TaxTransaction();
        charityTx.setDate(java.time.LocalDate.of(2024, 12, 20));
        charityTx.setDescription("Donation to Red Cross");
        charityTx.setAmount(new BigDecimal("-100.00"));
        charityTx.setTaxTag("CHARITY");

        final TaxExportService.TaxTransaction mortgageTx = new TaxExportService.TaxTransaction();
        mortgageTx.setDate(java.time.LocalDate.of(2024, 1, 1));
        mortgageTx.setDescription("Mortgage Interest Payment");
        mortgageTx.setAmount(new BigDecimal("-1200.00"));
        mortgageTx.setTaxTag("MORTGAGE_INTEREST");

        result.getTransactionsByCategory().put("CHARITY", Arrays.asList(charityTx));
        result.getTransactionsByCategory().put("MORTGAGE_INTEREST", Arrays.asList(mortgageTx));

        result.getSummary().setTotalCharity(new BigDecimal("100.00"));
        result.getSummary().setTotalMortgageInterest(new BigDecimal("1200.00"));

        // When
        final String csv = taxExportService.exportToScheduleA(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule A - Itemized Deductions"));
        assertTrue(csv.contains("Gifts to charity"));
        assertTrue(csv.contains("Home mortgage interest"));
        assertTrue(csv.contains("Line,Description,Amount"));
    }

    @Test
    @DisplayName("Should export Schedule B format")
    void testExportToScheduleBSuccess() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // Add Schedule B transactions
        final TaxExportService.TaxTransaction interestTx = new TaxExportService.TaxTransaction();
        interestTx.setDate(java.time.LocalDate.of(2024, 6, 1));
        interestTx.setDescription("Savings Account Interest");
        interestTx.setAmount(new BigDecimal("250.00"));
        interestTx.setTaxTag("INTEREST");

        final TaxExportService.TaxTransaction dividendTx = new TaxExportService.TaxTransaction();
        dividendTx.setDate(java.time.LocalDate.of(2024, 9, 1));
        dividendTx.setDescription("Stock Dividend");
        dividendTx.setAmount(new BigDecimal("500.00"));
        dividendTx.setTaxTag("DIVIDEND");

        result.getTransactionsByCategory().put("INTEREST", Arrays.asList(interestTx));
        result.getTransactionsByCategory().put("DIVIDEND", Arrays.asList(dividendTx));

        result.getSummary().setTotalInterest(new BigDecimal("250.00"));
        result.getSummary().setTotalDividends(new BigDecimal("500.00"));

        // When
        final String csv = taxExportService.exportToScheduleB(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule B - Interest and Dividends"));
        assertTrue(csv.contains("PART I - INTEREST INCOME"));
        assertTrue(csv.contains("PART II - DIVIDEND INCOME"));
        assertTrue(csv.contains("Savings Account Interest"));
        assertTrue(csv.contains("Stock Dividend"));
    }

    @Test
    @DisplayName("Should export Schedule D format")
    void testExportToScheduleDSuccess() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // Add Schedule D transactions
        final TaxExportService.TaxTransaction gainTx = new TaxExportService.TaxTransaction();
        gainTx.setDate(java.time.LocalDate.of(2024, 10, 10));
        gainTx.setDescription("Stock Sale Gain");
        gainTx.setAmount(new BigDecimal("500.00"));
        gainTx.setTaxTag("CAPITAL_GAIN");

        final TaxExportService.TaxTransaction lossTx = new TaxExportService.TaxTransaction();
        lossTx.setDate(java.time.LocalDate.of(2024, 11, 15));
        lossTx.setDescription("Stock Sale Loss");
        lossTx.setAmount(new BigDecimal("-200.00"));
        lossTx.setTaxTag("CAPITAL_LOSS");

        result.getTransactionsByCategory().put("CAPITAL_GAIN", Arrays.asList(gainTx));
        result.getTransactionsByCategory().put("CAPITAL_LOSS", Arrays.asList(lossTx));

        result.getSummary().setTotalCapitalGains(new BigDecimal("500.00"));
        result.getSummary().setTotalCapitalLosses(new BigDecimal("200.00"));

        // When
        final String csv = taxExportService.exportToScheduleD(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule D - Capital Gains and Losses"));
        assertTrue(csv.contains("Total Capital Gains"));
        assertTrue(csv.contains("Total Capital Losses"));
        assertTrue(csv.contains("Net Capital Gain/Loss"));
        assertTrue(csv.contains("Stock Sale Gain"));
        assertTrue(csv.contains("Stock Sale Loss"));
    }

    @Test
    @DisplayName("Should handle empty results in Schedule A export")
    void testExportToScheduleAEmptyResults() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // When
        final String csv = taxExportService.exportToScheduleA(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule A - Itemized Deductions"));
        assertTrue(csv.contains("Tax Year: 2024"));
    }

    @Test
    @DisplayName("Should handle empty results in Schedule B export")
    void testExportToScheduleBEmptyResults() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // When
        final String csv = taxExportService.exportToScheduleB(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule B - Interest and Dividends"));
        assertTrue(csv.contains("Tax Year: 2024"));
    }

    @Test
    @DisplayName("Should handle empty results in Schedule D export")
    void testExportToScheduleDEmptyResults() {
        // Given
        final int year = 2024;
        final TaxExportService.TaxExportResult result = new TaxExportService.TaxExportResult();

        // When
        final String csv = taxExportService.exportToScheduleD(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Schedule D - Capital Gains and Losses"));
        assertTrue(csv.contains("Tax Year: 2024"));
    }

    @Test
    @DisplayName("Should combine totals correctly in multi-year export")
    void testGenerateMultiYearTaxExportCombinesTotals() {
        // Given
        final int[] years = {2023, 2024};

        // Mock transactions for 2023
        final TransactionTable tx2023 =
                createTransaction(
                        "tx1",
                        "2023-06-15",
                        "Salary 2023",
                        "ADP",
                        new BigDecimal("50000.00"),
                        "salary",
                        "ach");

        // Mock transactions for 2024
        final TransactionTable tx2024 =
                createTransaction(
                        "tx2",
                        "2024-06-15",
                        "Salary 2024",
                        "ADP",
                        new BigDecimal("55000.00"),
                        "salary",
                        "ach");

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2023-01-01"), eq("2023-12-31")))
                .thenReturn(Arrays.asList(tx2023));
        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(tx2024));
        when(accountRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateMultiYearTaxExport(userId, years, null, null);

        // Then
        assertNotNull(result);
        // Combined salary should be sum of both years
        final BigDecimal expectedTotal = new BigDecimal("50000.00").add(new BigDecimal("55000.00"));
        assertEquals(0, result.getSummary().getTotalSalary().compareTo(expectedTotal));
    }

    private TransactionTable createTransaction(
            final String id,
            final String date,
            final String description,
            final String merchant,
            final BigDecimal amount,
            final String category,
            final String paymentChannel) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(userId);
        transaction.setTransactionDate(date);
        transaction.setDescription(description);
        transaction.setMerchantName(merchant);
        transaction.setAmount(amount);
        transaction.setCategoryPrimary(category);
        transaction.setCategoryDetailed(category);
        transaction.setPaymentChannel(paymentChannel);
        transaction.setCurrencyCode("USD");
        transaction.setTransactionType(
                amount.compareTo(BigDecimal.ZERO) < 0 ? "EXPENSE" : "INCOME");
        return transaction;
    }
}
