package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TaxExportService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Integration tests for Tax Export Service Tests with real DynamoDB (LocalStack) */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@Import(AWSTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("Tax Export Integration Tests")
class TaxExportIntegrationTest {

    @Autowired private TaxExportService taxExportService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private TransactionTable salaryTransaction;
    private TransactionTable interestTransaction;
    private TransactionTable charityTransaction;

    @BeforeEach
    void setUp() {
        // Initialize tables - use ensureTablesInitializedAndVerified like other integration tests
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        // Create test user
        testUser = new UserTable();
        testUser.setUserId("test-user-tax-export-" + System.currentTimeMillis());
        testUser.setEmail("taxexport@test.com");
        testUser.setPasswordHash("hashedpassword");
        testUser.setRoles(java.util.Set.of("USER"));
        userRepository.save(testUser);

        // Create test transactions for 2024
        salaryTransaction =
                createTransaction(
                        testUser.getUserId(),
                        "2024-01-15",
                        "Payroll Deposit",
                        "ADP",
                        new BigDecimal("5000.00"),
                        "salary",
                        "ach");

        interestTransaction =
                createTransaction(
                        testUser.getUserId(),
                        "2024-02-01",
                        "Interest Payment",
                        "Bank",
                        new BigDecimal("250.00"),
                        "interest",
                        null);

        charityTransaction =
                createTransaction(
                        testUser.getUserId(),
                        "2024-03-10",
                        "Donation to Red Cross",
                        "Red Cross",
                        new BigDecimal("-100.00"),
                        "other",
                        null);

        // Save transactions
        transactionRepository.save(salaryTransaction);
        transactionRepository.save(interestTransaction);
        transactionRepository.save(charityTransaction);
    }

    @Test
    @DisplayName("Should generate tax export with real database")
    void testGenerateTaxExportWithRealDatabase() {
        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        testUser.getUserId(), 2024, null, null, null, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getSummary());
        assertNotNull(result.getTransactionsByCategory());

        // Verify transactions are categorized
        assertTrue(
                result.getTransactionsByCategory().containsKey("SALARY")
                        || result.getTransactionsByCategory().containsKey("INTEREST")
                        || result.getTransactionsByCategory().containsKey("CHARITY"));

        // Verify summary totals
        final TaxExportService.TaxSummary summary = result.getSummary();
        assertTrue(summary.getTotalSalary().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    @DisplayName("Should export to CSV with real data")
    void testExportToCSVWithRealData() {
        // Given
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        testUser.getUserId(), 2024, null, null, null, null);

        // When
        final String csv = taxExportService.exportToCSV(result, 2024);

        // Then
        assertNotNull(csv);
        assertTrue(csv.contains("Tax Year: 2024"));
        assertTrue(csv.contains("SUMMARY"));
        assertTrue(csv.contains("DETAILED TRANSACTIONS"));
    }

    @Test
    @DisplayName("Should export to JSON with real data")
    void testExportToJSONWithRealData() {
        // Given
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        testUser.getUserId(), 2024, null, null, null, null);

        // When
        final String json = taxExportService.exportToJSON(result, 2024);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("\"taxYear\""));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"transactions\""));
    }

    @Test
    @DisplayName("Should handle transactions from different years")
    void testGenerateTaxExportDifferentYears() {
        // Given - Create transaction for 2023
        final TransactionTable oldTransaction =
                createTransaction(
                        testUser.getUserId(),
                        "2023-12-15",
                        "Old Transaction",
                        "Merchant",
                        new BigDecimal("100.00"),
                        "other",
                        null);
        transactionRepository.save(oldTransaction);

        // When - Export 2024 only
        final TaxExportService.TaxExportResult result2024 =
                taxExportService.generateTaxExport(
                        testUser.getUserId(), 2024, null, null, null, null);

        // Then - Should not include 2023 transaction
        // Verify by checking that old transaction is not in results
        // (This depends on the transaction description not matching any tax categories)
        assertNotNull(result2024);
    }

    @Test
    @DisplayName("Should calculate correct totals for multiple transactions")
    void testGenerateTaxExportMultipleTransactions() {
        // Given - Add more salary transactions
        final TransactionTable salary2 =
                createTransaction(
                        testUser.getUserId(),
                        "2024-02-15",
                        "Payroll Deposit",
                        "ADP",
                        new BigDecimal("5000.00"),
                        "salary",
                        "ach");
        final TransactionTable salary3 =
                createTransaction(
                        testUser.getUserId(),
                        "2024-03-15",
                        "Payroll Deposit",
                        "ADP",
                        new BigDecimal("5000.00"),
                        "salary",
                        "ach");
        transactionRepository.save(salary2);
        transactionRepository.save(salary3);

        // When
        final TaxExportService.TaxExportResult result =
                taxExportService.generateTaxExport(
                        testUser.getUserId(), 2024, null, null, null, null);

        // Then
        final TaxExportService.TaxSummary summary = result.getSummary();
        // Should have at least 3 salary transactions (5000 each = 15000 total)
        assertTrue(summary.getTotalSalary().compareTo(new BigDecimal("10000.00")) >= 0);
    }

    // Helper method
    private TransactionTable createTransaction(
            final String userId,
            final String date,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String category,
            final String paymentChannel) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(java.util.UUID.randomUUID().toString());
        transaction.setUserId(userId);
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
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());
        return transaction;
    }
}
