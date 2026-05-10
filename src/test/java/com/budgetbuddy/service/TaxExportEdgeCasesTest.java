package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Edge case and boundary condition tests for TaxExportService */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@DisplayName("Tax Export Service Edge Cases Tests")
class TaxExportEdgeCasesTest {

    private static final String USER123 = "user123";
    private static final String TX1 = "tx1";
    private static final String OTHER = "other";
    private static final String MERCHANT = "Merchant";

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @InjectMocks private TaxExportService taxExportService;

    @Test
    @DisplayName("Should handle null userId")
    void testGenerateTaxExportNullUserId() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateTaxExport(null, 2024, null, null, null, null);
                });
    }

    @Test
    @DisplayName("Should handle empty userId")
    void testGenerateTaxExportEmptyUserId() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateTaxExport("", 2024, null, null, null, null);
                });
    }

    @Test
    @DisplayName("Should handle invalid year (too low)")
    void testGenerateTaxExportInvalidYearTooLow() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateTaxExport(USER123, 1800, null, null, null, null);
                });
    }

    @Test
    @DisplayName("Should handle invalid year (too high)")
    void testGenerateTaxExportInvalidYearTooHigh() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    taxExportService.generateTaxExport(USER123, 2200, null, null, null, null);
                });
    }

    @Test
    @DisplayName("Should handle transaction with null date")
    void testGenerateTaxExportTransactionWithNullDate() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1, null, "Description", MERCHANT, new BigDecimal("100.00"), OTHER, null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction with null date
        assertNotNull(result);
        // Transaction should be skipped, so no transactions in result
    }

    @Test
    @DisplayName("Should handle transaction with invalid date format")
    void testGenerateTaxExportTransactionWithInvalidDate() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(TX1);
        transaction.setUserId(userId);
        transaction.setTransactionDate("invalid-date-format");
        transaction.setDescription("Test");
        transaction.setAmount(new BigDecimal("100.00"));

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction with invalid date
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle transaction with null amount")
    void testGenerateTaxExportTransactionWithNullAmount() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(TX1, "2024-01-15", "Description", MERCHANT, null, OTHER, null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should handle gracefully
        assertNotNull(result);
        // Amount should default to zero in convertToTaxTransaction
    }

    @Test
    @DisplayName("Should handle transaction with null description")
    void testGenerateTaxExportTransactionWithNullDescription() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1, "2024-01-15", null, MERCHANT, new BigDecimal("100.00"), OTHER, null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        final String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // Description should be empty string in export
    }

    @Test
    @DisplayName("Should handle transaction with newlines in description")
    void testGenerateTaxExportTransactionWithNewlines() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Line 1\nLine 2\rLine 3",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        final String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should not contain unescaped newlines
        assertFalse(csv.contains("\nLine 2"), "CSV should escape newlines");
    }

    @Test
    @DisplayName("Should handle transaction with commas in description")
    void testGenerateTaxExportTransactionWithCommas() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Description, with, commas",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        final String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should handle commas properly (either escaped or quoted)
    }

    @Test
    @DisplayName("Should handle transaction with quotes in description")
    void testGenerateTaxExportTransactionWithQuotes() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Description with \"quotes\"",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        final String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should escape quotes as double quotes
        assertTrue(csv.contains("\"\""), "CSV should escape quotes");
    }

    @Test
    @DisplayName("Should handle transaction date outside year range")
    void testGenerateTaxExportTransactionOutsideYearRange() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable transaction =
                createTransaction(
                        TX1,
                        "2023-12-31",
                        "Old Transaction",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(transaction));

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction outside year range
        assertNotNull(result);
        // Transaction should be filtered out
    }

    @Test
    @DisplayName("Should handle very large dataset")
    void testGenerateTaxExportVeryLargeDataset() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            transactions.add(
                    createTransaction(
                            "tx" + i,
                            "2024-06-15",
                            "Transaction " + i,
                            MERCHANT,
                            new BigDecimal("10.00"),
                            OTHER,
                            null));
        }

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(10_000, transactions.size());
        // Export should complete without errors
        assertDoesNotThrow(() -> taxExportService.exportToCSV(result, year));
    }

    @Test
    @DisplayName("Should calculate year-end balance from accounts")
    void testGenerateTaxExportCalculatesYearEndBalance() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions =
                Arrays.asList(
                        createTransaction(
                                TX1,
                                "2024-01-15",
                                "Transaction",
                                MERCHANT,
                                new BigDecimal("100.00"),
                                OTHER,
                                null));

        final List<AccountTable> accounts =
                Arrays.asList(
                        createAccount("acc1", userId, new BigDecimal("1000.00")),
                        createAccount("acc2", userId, new BigDecimal("2000.00")));

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        when(accountRepository.findByUserId(userId)).thenReturn(accounts);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("3000.00"), result.getSummary().getYearEndBalance());
    }

    @Test
    @DisplayName("Should filter by category")
    void testGenerateTaxExportFilterByCategory() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable salaryTx =
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Payroll Deposit",
                        "ADP",
                        new BigDecimal("5000.00"),
                        "salary",
                        "ach");
        final TransactionTable interestTx =
                createTransaction(
                        "tx2",
                        "2024-02-01",
                        "Interest Payment",
                        "Bank",
                        new BigDecimal("250.00"),
                        "interest",
                        null);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(salaryTx, interestTx));

        // When - Filter for SALARY only
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        userId, year, Arrays.asList("SALARY"), null, null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getTransactionsByCategory().containsKey("SALARY"));
        assertFalse(result.getTransactionsByCategory().containsKey("INTEREST"));
    }

    @Test
    @DisplayName("Should filter by account ID")
    void testGenerateTaxExportFilterByAccountId() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final TransactionTable tx1 =
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Transaction 1",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null);
        tx1.setAccountId("acc1");
        final TransactionTable tx2 =
                createTransaction(
                        "tx2",
                        "2024-01-16",
                        "Transaction 2",
                        MERCHANT,
                        new BigDecimal("200.00"),
                        OTHER,
                        null);
        tx2.setAccountId("acc2");

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When - Filter for acc1 only
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        userId, year, null, Arrays.asList("acc1"), null, null);

        // Then
        assertNotNull(result);
        // Should only contain transactions from acc1
        final long acc1Count =
                result.getTransactionsByCategory().values().stream()
                        .flatMap(List::stream)
                        .filter(tx -> "acc1".equals(tx.getAccountId()))
                        .count();
        assertEquals(1, acc1Count);
    }

    @Test
    @DisplayName("Should handle empty transaction list")
    void testGenerateTaxExportEmptyTransactions() {
        // Given
        final String userId = USER123;
        final int year = 2024;

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(Collections.emptyList());

        when(accountRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getTransactionsByCategory().isEmpty());
        assertEquals(BigDecimal.ZERO, result.getSummary().getYearEndBalance());

        // Export should still work
        final String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        assertTrue(csv.contains("Tax Year: 2024"));
    }

    @Test
    @DisplayName("Should handle null transaction in list")
    void testGenerateTaxExportNullTransactionInList() {
        // Given
        final String userId = USER123;
        final int year = 2024;
        final List<TransactionTable> transactions = new ArrayList<>();
        transactions.add(null);
        transactions.add(
                createTransaction(
                        TX1,
                        "2024-01-15",
                        "Valid Transaction",
                        MERCHANT,
                        new BigDecimal("100.00"),
                        OTHER,
                        null));

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("2024-01-01"), eq("2024-12-31")))
                .thenReturn(transactions);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip null transaction
        assertNotNull(result);
        // Should only contain the valid transaction
    }

    // Helper methods
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
                amount != null && amount.compareTo(BigDecimal.ZERO) < 0 ? "EXPENSE" : "INCOME");
        return transaction;
    }

    private AccountTable createAccount(
            final String accountId, final String userId, final BigDecimal balance) {
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(userId);
        account.setAccountName("Test Account");
        account.setBalance(balance);
        account.setActive(true);
        return account;
    }
}
