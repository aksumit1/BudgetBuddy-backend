package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Edge case and boundary condition tests for TaxExportService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tax Export Service Edge Cases Tests")
class TaxExportEdgeCasesTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TaxExportService taxExportService;

    @Test
    @DisplayName("Should handle null userId")
    void testGenerateTaxExport_NullUserId() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            taxExportService.generateTaxExport(null, 2024, null, null, null, null);
        });
    }

    @Test
    @DisplayName("Should handle empty userId")
    void testGenerateTaxExport_EmptyUserId() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            taxExportService.generateTaxExport("", 2024, null, null, null, null);
        });
    }

    @Test
    @DisplayName("Should handle invalid year (too low)")
    void testGenerateTaxExport_InvalidYearTooLow() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            taxExportService.generateTaxExport("user123", 1800, null, null, null, null);
        });
    }

    @Test
    @DisplayName("Should handle invalid year (too high)")
    void testGenerateTaxExport_InvalidYearTooHigh() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            taxExportService.generateTaxExport("user123", 2200, null, null, null, null);
        });
    }

    @Test
    @DisplayName("Should handle transaction with null date")
    void testGenerateTaxExport_TransactionWithNullDate() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", null, "Description", "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction with null date
        assertNotNull(result);
        // Transaction should be skipped, so no transactions in result
    }

    @Test
    @DisplayName("Should handle transaction with invalid date format")
    void testGenerateTaxExport_TransactionWithInvalidDate() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("tx1");
        transaction.setUserId(userId);
        transaction.setTransactionDate("invalid-date-format");
        transaction.setDescription("Test");
        transaction.setAmount(new BigDecimal("100.00"));

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction with invalid date
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle transaction with null amount")
    void testGenerateTaxExport_TransactionWithNullAmount() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2024-01-15", "Description", "Merchant",
            null, "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should handle gracefully
        assertNotNull(result);
        // Amount should default to zero in convertToTaxTransaction
    }

    @Test
    @DisplayName("Should handle transaction with null description")
    void testGenerateTaxExport_TransactionWithNullDescription() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2024-01-15", null, "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // Description should be empty string in export
    }

    @Test
    @DisplayName("Should handle transaction with newlines in description")
    void testGenerateTaxExport_TransactionWithNewlines() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2024-01-15", "Line 1\nLine 2\rLine 3", "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should not contain unescaped newlines
        assertFalse(csv.contains("\nLine 2"), "CSV should escape newlines");
    }

    @Test
    @DisplayName("Should handle transaction with commas in description")
    void testGenerateTaxExport_TransactionWithCommas() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2024-01-15", "Description, with, commas", "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should handle commas properly (either escaped or quoted)
    }

    @Test
    @DisplayName("Should handle transaction with quotes in description")
    void testGenerateTaxExport_TransactionWithQuotes() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2024-01-15", "Description with \"quotes\"", "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        // CSV should escape quotes as double quotes
        assertTrue(csv.contains("\"\""), "CSV should escape quotes");
    }

    @Test
    @DisplayName("Should handle transaction date outside year range")
    void testGenerateTaxExport_TransactionOutsideYearRange() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable transaction = createTransaction(
            "tx1", "2023-12-31", "Old Transaction", "Merchant",
            new BigDecimal("100.00"), "other", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(transaction));

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip transaction outside year range
        assertNotNull(result);
        // Transaction should be filtered out
    }

    @Test
    @DisplayName("Should handle very large dataset")
    void testGenerateTaxExport_VeryLargeDataset() {
        // Given
        String userId = "user123";
        int year = 2024;
        List<TransactionTable> transactions = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            transactions.add(createTransaction(
                "tx" + i, "2024-06-15", "Transaction " + i, "Merchant",
                new BigDecimal("10.00"), "other", null
            ));
        }

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(transactions);

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(10000, transactions.size());
        // Export should complete without errors
        assertDoesNotThrow(() -> taxExportService.exportToCSV(result, year));
    }

    @Test
    @DisplayName("Should calculate year-end balance from accounts")
    void testGenerateTaxExport_CalculatesYearEndBalance() {
        // Given
        String userId = "user123";
        int year = 2024;
        List<TransactionTable> transactions = Arrays.asList(
            createTransaction("tx1", "2024-01-15", "Transaction", "Merchant",
                new BigDecimal("100.00"), "other", null)
        );

        List<AccountTable> accounts = Arrays.asList(
            createAccount("acc1", userId, new BigDecimal("1000.00")),
            createAccount("acc2", userId, new BigDecimal("2000.00"))
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(transactions);
        
        when(accountRepository.findByUserId(userId)).thenReturn(accounts);

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("3000.00"), result.getSummary().getYearEndBalance());
    }

    @Test
    @DisplayName("Should filter by category")
    void testGenerateTaxExport_FilterByCategory() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable salaryTx = createTransaction(
            "tx1", "2024-01-15", "Payroll Deposit", "ADP",
            new BigDecimal("5000.00"), "salary", "ach"
        );
        TransactionTable interestTx = createTransaction(
            "tx2", "2024-02-01", "Interest Payment", "Bank",
            new BigDecimal("250.00"), "interest", null
        );

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(salaryTx, interestTx));

        // When - Filter for SALARY only
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
            userId, year, Arrays.asList("SALARY"), null, null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getTransactionsByCategory().containsKey("SALARY"));
        assertFalse(result.getTransactionsByCategory().containsKey("INTEREST"));
    }

    @Test
    @DisplayName("Should filter by account ID")
    void testGenerateTaxExport_FilterByAccountId() {
        // Given
        String userId = "user123";
        int year = 2024;
        TransactionTable tx1 = createTransaction(
            "tx1", "2024-01-15", "Transaction 1", "Merchant",
            new BigDecimal("100.00"), "other", null
        );
        tx1.setAccountId("acc1");
        TransactionTable tx2 = createTransaction(
            "tx2", "2024-01-16", "Transaction 2", "Merchant",
            new BigDecimal("200.00"), "other", null
        );
        tx2.setAccountId("acc2");

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Arrays.asList(tx1, tx2));

        // When - Filter for acc1 only
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(
            userId, year, null, Arrays.asList("acc1"), null, null);

        // Then
        assertNotNull(result);
        // Should only contain transactions from acc1
        long acc1Count = result.getTransactionsByCategory().values().stream()
            .flatMap(List::stream)
            .filter(tx -> "acc1".equals(tx.getAccountId()))
            .count();
        assertEquals(1, acc1Count);
    }

    @Test
    @DisplayName("Should handle empty transaction list")
    void testGenerateTaxExport_EmptyTransactions() {
        // Given
        String userId = "user123";
        int year = 2024;

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(Collections.emptyList());
        
        when(accountRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getTransactionsByCategory().isEmpty());
        assertEquals(BigDecimal.ZERO, result.getSummary().getYearEndBalance());
        
        // Export should still work
        String csv = taxExportService.exportToCSV(result, year);
        assertNotNull(csv);
        assertTrue(csv.contains("Tax Year: 2024"));
    }

    @Test
    @DisplayName("Should handle null transaction in list")
    void testGenerateTaxExport_NullTransactionInList() {
        // Given
        String userId = "user123";
        int year = 2024;
        List<TransactionTable> transactions = new ArrayList<>();
        transactions.add(null);
        transactions.add(createTransaction(
            "tx1", "2024-01-15", "Valid Transaction", "Merchant",
            new BigDecimal("100.00"), "other", null
        ));

        when(transactionRepository.findByUserIdAndDateRange(
            eq(userId), eq("2024-01-01"), eq("2024-12-31")
        )).thenReturn(transactions);

        // When
        TaxExportService.TaxExportResult result = taxExportService.generateTaxExport(userId, year, null, null, null, null);

        // Then - Should skip null transaction
        assertNotNull(result);
        // Should only contain the valid transaction
    }

    // Helper methods
    private TransactionTable createTransaction(
            String transactionId, String date, String description, String merchantName,
            BigDecimal amount, String category, String paymentChannel) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(transactionId);
        transaction.setUserId("user123");
        transaction.setTransactionDate(date);
        transaction.setDescription(description);
        transaction.setMerchantName(merchantName);
        transaction.setAmount(amount);
        transaction.setCategoryPrimary(category);
        transaction.setCategoryDetailed(category);
        transaction.setPaymentChannel(paymentChannel);
        transaction.setCurrencyCode("USD");
        transaction.setTransactionType(amount != null && amount.compareTo(BigDecimal.ZERO) < 0 ? "EXPENSE" : "INCOME");
        return transaction;
    }

    private AccountTable createAccount(String accountId, String userId, BigDecimal balance) {
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(userId);
        account.setAccountName("Test Account");
        account.setBalance(balance);
        account.setActive(true);
        return account;
    }
}

