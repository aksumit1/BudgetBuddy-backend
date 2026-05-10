package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ExcelImportService;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for duplicate detection during Excel and PDF file imports Tests scenarios: 1.
 * Reimporting same Excel file - should not create duplicates 2. Reimporting same PDF file - should
 * not create duplicates 3. Incremental update for Excel - file with additional transactions 4.
 * Incremental update for PDF - file with additional transactions
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ExcelPDFImportDuplicateDetectionIntegrationTest {

    private static final String GAS = "Gas";
    private static final String GROCERIES = "Groceries";

    @Autowired private ExcelImportService excelImportService;

    @Autowired private PDFImportService pdfImportService;

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserService userService;

    private UserTable testUser;
    private AccountTable testAccount;
    private String testAccountId;

    @BeforeEach
    void setUp() {
        // Create test user
        final String testEmail = "excel-pdf-dup-test-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Create test account
        testAccount = new AccountTable();
        testAccountId = UUID.randomUUID().toString();
        testAccount.setAccountId(testAccountId);
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("depository");
        testAccount.setAccountSubtype("checking");
        testAccount.setAccountNumber("1234");
        testAccount.setBalance(BigDecimal.ZERO);
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);
    }

    @Test
    void testReimportSameExcelFileShouldNotCreateDuplicates() {
        // Given - Initial Excel import
        // Create a simple Excel file content (simplified for test)
        final String filename = "test_statement.xlsx";
        final String importBatchId1 = UUID.randomUUID().toString();

        // First import - create transactions directly (simulating Excel parse result)
        final TransactionTable tx1 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("50.00"),
                        java.time.LocalDate.parse("2025-01-15"),
                        "Grocery Store",
                        GROCERIES,
                        GROCERIES,
                        null,
                        "Imported from Excel",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "EXCEL",
                        importBatchId1,
                        filename);

        final TransactionTable tx2 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("30.00"),
                        java.time.LocalDate.parse("2025-01-16"),
                        "Gas Station",
                        GAS,
                        GAS,
                        null,
                        "Imported from Excel",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "EXCEL",
                        importBatchId1,
                        filename);

        final List<TransactionTable> transactions1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(
                2, transactions1.size(), "Should have 2 transactions after first Excel import");

        // When - Reimport same Excel file
        final String importBatchId2 = UUID.randomUUID().toString(); // Different batch ID

        final TransactionTable result1 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("50.00"),
                        java.time.LocalDate.parse("2025-01-15"),
                        "Grocery Store",
                        GROCERIES,
                        GROCERIES,
                        null,
                        "Imported from Excel",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "EXCEL",
                        importBatchId2,
                        filename);

        final TransactionTable result2 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("30.00"),
                        java.time.LocalDate.parse("2025-01-16"),
                        "Gas Station",
                        GAS,
                        GAS,
                        null,
                        "Imported from Excel",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "EXCEL",
                        importBatchId2,
                        filename);

        // Then - Should not create duplicates
        final boolean isDuplicate1 =
                transactions1.stream()
                        .anyMatch(t -> t.getTransactionId().equals(result1.getTransactionId()));
        final boolean isDuplicate2 =
                transactions1.stream()
                        .anyMatch(t -> t.getTransactionId().equals(result2.getTransactionId()));

        assertTrue(isDuplicate1, "First transaction should be detected as duplicate");
        assertTrue(isDuplicate2, "Second transaction should be detected as duplicate");

        final List<TransactionTable> transactions2 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(
                2,
                transactions2.size(),
                "Should still have only 2 transactions after Excel reimport");
    }

    @Test
    void testReimportSamePDFFileShouldNotCreateDuplicates() {
        // Given - Initial PDF import
        final String filename = "test_statement.pdf";
        final String importBatchId1 = UUID.randomUUID().toString();

        // First import
        final TransactionTable tx1 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("50.00"),
                        java.time.LocalDate.parse("2025-01-15"),
                        "Grocery Store",
                        GROCERIES,
                        GROCERIES,
                        null,
                        "Imported from PDF",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "PDF",
                        importBatchId1,
                        filename);

        final TransactionTable tx2 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("30.00"),
                        java.time.LocalDate.parse("2025-01-16"),
                        "Gas Station",
                        GAS,
                        GAS,
                        null,
                        "Imported from PDF",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "PDF",
                        importBatchId1,
                        filename);

        final List<TransactionTable> transactions1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size(), "Should have 2 transactions after first PDF import");

        // When - Reimport same PDF file
        final String importBatchId2 = UUID.randomUUID().toString(); // Different batch ID

        final TransactionTable result1 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("50.00"),
                        java.time.LocalDate.parse("2025-01-15"),
                        "Grocery Store",
                        GROCERIES,
                        GROCERIES,
                        null,
                        "Imported from PDF",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "PDF",
                        importBatchId2,
                        filename);

        final TransactionTable result2 =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("30.00"),
                        java.time.LocalDate.parse("2025-01-16"),
                        "Gas Station",
                        GAS,
                        GAS,
                        null,
                        "Imported from PDF",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "PDF",
                        importBatchId2,
                        filename);

        // Then - Should not create duplicates
        final boolean isDuplicate1 =
                transactions1.stream()
                        .anyMatch(t -> t.getTransactionId().equals(result1.getTransactionId()));
        final boolean isDuplicate2 =
                transactions1.stream()
                        .anyMatch(t -> t.getTransactionId().equals(result2.getTransactionId()));

        assertTrue(isDuplicate1, "First transaction should be detected as duplicate");
        assertTrue(isDuplicate2, "Second transaction should be detected as duplicate");

        final List<TransactionTable> transactions2 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(
                2,
                transactions2.size(),
                "Should still have only 2 transactions after PDF reimport");
    }

    @Test
    void testIncrementalExcelUpdateOnlyAddsNewTransactions() {
        // Given - Initial Excel import with 2 transactions
        final String filename = "test_statement.xlsx";
        final String importBatchId1 = UUID.randomUUID().toString();

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("50.00"),
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store",
                GROCERIES,
                GROCERIES,
                null,
                "Imported from Excel",
                null,
                null,
                "EXPENSE",
                "USD",
                "EXCEL",
                importBatchId1,
                filename);

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("30.00"),
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station",
                GAS,
                GAS,
                null,
                "Imported from Excel",
                null,
                null,
                "EXPENSE",
                "USD",
                "EXCEL",
                importBatchId1,
                filename);

        final List<TransactionTable> transactions1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import Excel file with additional transaction
        final String importBatchId2 = UUID.randomUUID().toString();

        // Reimport existing 2 (should be skipped)
        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("50.00"),
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store",
                GROCERIES,
                GROCERIES,
                null,
                "Imported from Excel",
                null,
                null,
                "EXPENSE",
                "USD",
                "EXCEL",
                importBatchId2,
                filename);

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("30.00"),
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station",
                GAS,
                GAS,
                null,
                "Imported from Excel",
                null,
                null,
                "EXPENSE",
                "USD",
                "EXCEL",
                importBatchId2,
                filename);

        // Add new transaction
        final TransactionTable newTx =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("25.00"),
                        java.time.LocalDate.parse("2025-01-17"),
                        "Restaurant",
                        "Restaurants",
                        "Restaurants",
                        null,
                        "Imported from Excel",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "EXCEL",
                        importBatchId2,
                        filename);

        // Then - Should only add the new transaction
        final boolean isNew =
                transactions1.stream()
                        .noneMatch(t -> t.getTransactionId().equals(newTx.getTransactionId()));
        assertTrue(isNew, "New transaction should be created");

        final List<TransactionTable> transactions2 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(
                3, transactions2.size(), "Should have 3 transactions total (2 original + 1 new)");
    }

    @Test
    void testIncrementalPDFUpdateOnlyAddsNewTransactions() {
        // Given - Initial PDF import with 2 transactions
        final String filename = "test_statement.pdf";
        final String importBatchId1 = UUID.randomUUID().toString();

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("50.00"),
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store",
                GROCERIES,
                GROCERIES,
                null,
                "Imported from PDF",
                null,
                null,
                "EXPENSE",
                "USD",
                "PDF",
                importBatchId1,
                filename);

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("30.00"),
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station",
                GAS,
                GAS,
                null,
                "Imported from PDF",
                null,
                null,
                "EXPENSE",
                "USD",
                "PDF",
                importBatchId1,
                filename);

        final List<TransactionTable> transactions1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import PDF file with additional transaction
        final String importBatchId2 = UUID.randomUUID().toString();

        // Reimport existing 2 (should be skipped)
        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("50.00"),
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store",
                GROCERIES,
                GROCERIES,
                null,
                "Imported from PDF",
                null,
                null,
                "EXPENSE",
                "USD",
                "PDF",
                importBatchId2,
                filename);

        transactionService.createTransaction(
                testUser,
                testAccountId,
                new BigDecimal("30.00"),
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station",
                GAS,
                GAS,
                null,
                "Imported from PDF",
                null,
                null,
                "EXPENSE",
                "USD",
                "PDF",
                importBatchId2,
                filename);

        // Add new transaction
        final TransactionTable newTx =
                transactionService.createTransaction(
                        testUser,
                        testAccountId,
                        new BigDecimal("25.00"),
                        java.time.LocalDate.parse("2025-01-17"),
                        "Restaurant",
                        "Restaurants",
                        "Restaurants",
                        null,
                        "Imported from PDF",
                        null,
                        null,
                        "EXPENSE",
                        "USD",
                        "PDF",
                        importBatchId2,
                        filename);

        // Then - Should only add the new transaction
        final boolean isNew =
                transactions1.stream()
                        .noneMatch(t -> t.getTransactionId().equals(newTx.getTransactionId()));
        assertTrue(isNew, "New transaction should be created");

        final List<TransactionTable> transactions2 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(
                3, transactions2.size(), "Should have 3 transactions total (2 original + 1 new)");
    }
}
