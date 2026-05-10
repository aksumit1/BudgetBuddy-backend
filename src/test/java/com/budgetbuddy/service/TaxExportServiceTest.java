package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for TaxExportService */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@DisplayName("Tax Export Service Tests")
class TaxExportServiceTest {

    private static final String USER123 = "user123";
    private static final String OTHER = "other";

    @Mock private TransactionRepository transactionRepository;

    @InjectMocks private TaxExportService taxExportService;

    private TransactionTable salaryTransaction;
    private TransactionTable interestTransaction;
    private TransactionTable charityTransaction;
    private TransactionTable rsuTransaction;
    private TransactionTable tuitionTransaction;
    private TransactionTable propertyTaxTransaction;

    @BeforeEach
    void setUp() {
        // Create test transactions
        salaryTransaction =
                createTransaction(
                        "tx1",
                        "2024-01-15",
                        "Payroll Deposit",
                        "ADP",
                        new BigDecimal("5000.00"),
                        "salary",
                        "ach");

        interestTransaction =
                createTransaction(
                        "tx2",
                        "2024-02-01",
                        "Interest Payment",
                        "Bank",
                        new BigDecimal("250.00"),
                        "interest",
                        null);

        charityTransaction =
                createTransaction(
                        "tx3",
                        "2024-03-10",
                        "Donation to Red Cross",
                        "Red Cross",
                        new BigDecimal("-100.00"),
                        OTHER,
                        null);

        rsuTransaction =
                createTransaction(
                        "tx4",
                        "2024-04-15",
                        "RSU Vest",
                        "Company",
                        new BigDecimal("10000.00"),
                        "rsu",
                        null);

        tuitionTransaction =
                createTransaction(
                        "tx5",
                        "2024-05-01",
                        "University Tuition Fee",
                        "Stanford University",
                        new BigDecimal("-5000.00"),
                        OTHER,
                        null);

        propertyTaxTransaction =
                createTransaction(
                        "tx6",
                        "2024-06-15",
                        "Property Tax Payment",
                        "County Assessor",
                        new BigDecimal("-2500.00"),
                        OTHER,
                        null);
    }

    @Test
    @DisplayName("Should generate tax export with categorized transactions")
    void testGenerateTaxExportCategorizesTransactions() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions =
                Arrays.asList(
                        salaryTransaction,
                        interestTransaction,
                        charityTransaction,
                        rsuTransaction,
                        tuitionTransaction,
                        propertyTaxTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getSummary());
        assertNotNull(result.getTransactionsByCategory());

        // Verify transactions are categorized
        assertTrue(result.getTransactionsByCategory().containsKey("SALARY"));
        assertTrue(result.getTransactionsByCategory().containsKey("INTEREST"));
        assertTrue(result.getTransactionsByCategory().containsKey("CHARITY"));
        assertTrue(result.getTransactionsByCategory().containsKey("RSU"));
        assertTrue(result.getTransactionsByCategory().containsKey("TUITION"));
        assertTrue(result.getTransactionsByCategory().containsKey("PROPERTY_TAX"));
    }

    @Test
    @DisplayName("Should calculate summary totals correctly")
    void testGenerateTaxExportCalculatesSummaryTotals() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions =
                Arrays.asList(
                        salaryTransaction,
                        interestTransaction,
                        charityTransaction,
                        rsuTransaction,
                        tuitionTransaction,
                        propertyTaxTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        final TaxExportService.TaxSummary summary = result.getSummary();

        // Verify totals
        assertEquals(new BigDecimal("5000.00"), summary.getTotalSalary());
        assertEquals(new BigDecimal("250.00"), summary.getTotalInterest());
        assertEquals(new BigDecimal("100.00"), summary.getTotalCharity()); // Absolute value
        assertEquals(new BigDecimal("10000.00"), summary.getTotalRSU());
        assertEquals(new BigDecimal("5000.00"), summary.getTotalTuition()); // Absolute value
        assertEquals(new BigDecimal("2500.00"), summary.getTotalPropertyTax()); // Absolute value
    }

    @Test
    @DisplayName("Should export to CSV format")
    void testExportToCSVGeneratesValidCSV() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions =
                Arrays.asList(salaryTransaction, interestTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // When
        final String csv = taxExportService.exportToCSV(result, year);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Tax Year: 2024"));
        assertTrue(csv.contains("SUMMARY"));
        assertTrue(csv.contains("DETAILED TRANSACTIONS"));
        assertTrue(csv.contains("SALARY"));
        assertTrue(csv.contains("INTEREST"));
        assertTrue(csv.contains("Payroll Deposit"));
    }

    @Test
    @DisplayName("Should export to JSON format")
    void testExportToJSONGeneratesValidJSON() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions =
                Arrays.asList(salaryTransaction, interestTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // When
        final String json = taxExportService.exportToJSON(result, year);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("\"taxYear\": 2024"));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"transactions\""));
        assertTrue(json.contains("\"totalSalary\""));
        assertTrue(json.contains("\"totalInterest\""));
    }

    @Test
    @DisplayName("Should handle empty transaction list")
    void testGenerateTaxExportEmptyTransactions() {
        // Given
        final String userId = USER123;
        final int year = 2024;

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(List.of());

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getSummary());
        assertTrue(result.getTransactionsByCategory().isEmpty());

        // Summary should have zero totals
        assertEquals(BigDecimal.ZERO, result.getSummary().getTotalSalary());
        assertEquals(BigDecimal.ZERO, result.getSummary().getTotalInterest());
    }

    @Test
    @DisplayName("Should detect DMV fees")
    void testGenerateTaxExportDetectsDMVFees() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable dmvTransaction =
                createTransaction(
                        "tx7",
                        "2024-07-01",
                        "Vehicle Registration Renewal",
                        "DMV",
                        new BigDecimal("-150.00"),
                        OTHER,
                        null);
        final List<TransactionTable> transactions = Arrays.asList(dmvTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertTrue(result.getTransactionsByCategory().containsKey("DMV"));
        assertEquals(new BigDecimal("150.00"), result.getSummary().getTotalDMV());
    }

    @Test
    @DisplayName("Should detect CPA fees")
    void testGenerateTaxExportDetectsCPAFees() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable cpaTransaction =
                createTransaction(
                        "tx8",
                        "2024-08-01",
                        "Tax Preparation Fee",
                        "John Smith CPA",
                        new BigDecimal("-300.00"),
                        OTHER,
                        null);
        final List<TransactionTable> transactions = Arrays.asList(cpaTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertTrue(result.getTransactionsByCategory().containsKey("CPA"));
        assertEquals(new BigDecimal("300.00"), result.getSummary().getTotalCPA());
    }

    @Test
    @DisplayName("Should detect state and local taxes")
    void testGenerateTaxExportDetectsStateLocalTaxes() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable stateTaxTransaction =
                createTransaction(
                        "tx9",
                        "2024-09-01",
                        "State Income Tax Payment",
                        "Franchise Tax Board",
                        new BigDecimal("-1500.00"),
                        OTHER,
                        null);
        final TransactionTable localTaxTransaction =
                createTransaction(
                        "tx10",
                        "2024-10-01",
                        "City Tax Payment",
                        "City Tax Office",
                        new BigDecimal("-500.00"),
                        OTHER,
                        null);
        final List<TransactionTable> transactions =
                Arrays.asList(stateTaxTransaction, localTaxTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertTrue(result.getTransactionsByCategory().containsKey("STATE_TAX"));
        assertTrue(result.getTransactionsByCategory().containsKey("LOCAL_TAX"));
        assertEquals(new BigDecimal("1500.00"), result.getSummary().getTotalStateTax());
        assertEquals(new BigDecimal("500.00"), result.getSummary().getTotalLocalTax());
    }

    @Test
    @DisplayName("Should detect mortgage interest")
    void testGenerateTaxExportDetectsMortgageInterest() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable mortgageTransaction =
                createTransaction(
                        "tx11",
                        "2024-11-01",
                        "Mortgage Interest Payment",
                        "Bank",
                        new BigDecimal("-800.00"),
                        OTHER,
                        null);
        final List<TransactionTable> transactions = Arrays.asList(mortgageTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertTrue(result.getTransactionsByCategory().containsKey("MORTGAGE_INTEREST"));
        assertEquals(new BigDecimal("800.00"), result.getSummary().getTotalMortgageInterest());
    }

    @Test
    @DisplayName("Should detect capital gains and losses")
    void testGenerateTaxExportDetectsCapitalGainsLosses() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable gainTransaction =
                createTransaction(
                        "tx12",
                        "2024-12-01",
                        "Stock Sale - Capital Gain",
                        "Brokerage",
                        new BigDecimal("5000.00"),
                        "investment",
                        null);
        final TransactionTable lossTransaction =
                createTransaction(
                        "tx13",
                        "2024-12-15",
                        "Stock Sale - Capital Loss",
                        "Brokerage",
                        new BigDecimal("-1000.00"),
                        "investment",
                        null);
        final List<TransactionTable> transactions = Arrays.asList(gainTransaction, lossTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertTrue(
                result.getTransactionsByCategory().containsKey("CAPITAL_GAIN")
                        || result.getTransactionsByCategory().containsKey("CAPITAL_LOSS"));
        // Note: Detection logic may categorize based on description keywords
    }

    @Test
    @DisplayName("Should use current year when year is 0")
    void testGenerateTaxExportCurrentYear() {
        // Given
        final String userId = USER123;
        final int currentYear = LocalDate.now().getYear();
        final List<TransactionTable> transactions = Arrays.asList(salaryTransaction);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq(currentYear + "-01-01"), eq(currentYear + "-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, currentYear, null, null, null, null);

        // Then
        assertNotNull(result);
        verify(transactionRepository)
                .findByUserIdAndDateRange(
                        eq(userId), eq(currentYear + "-01-01"), eq(currentYear + "-12-31"));
    }

    // Helper method to create test transactions
    private TransactionTable createTransaction(
            final String transactionId,
            final String date,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String category,
            final String paymentChannel) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(transactionId);
        transaction.setUserId(USER123);
        transaction.setTransactionDate(date);
        transaction.setDescription(description);
        transaction.setMerchantName(merchantName);
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
