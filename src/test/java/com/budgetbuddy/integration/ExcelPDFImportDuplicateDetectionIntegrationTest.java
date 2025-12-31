package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for duplicate detection during Excel and PDF file imports
 * Tests scenarios:
 * 1. Reimporting same Excel file - should not create duplicates
 * 2. Reimporting same PDF file - should not create duplicates
 * 3. Incremental update for Excel - file with additional transactions
 * 4. Incremental update for PDF - file with additional transactions
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ExcelPDFImportDuplicateDetectionIntegrationTest {

    @Autowired
    private ExcelImportService excelImportService;

    @Autowired
    private PDFImportService pdfImportService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserService userService;

    private UserTable testUser;
    private AccountTable testAccount;
    private String testAccountId;

    @BeforeEach
    void setUp() {
        // Create test user
        String testEmail = "excel-pdf-dup-test-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
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
    void testReimportSameExcelFile_ShouldNotCreateDuplicates() {
        // Given - Initial Excel import
        // Create a simple Excel file content (simplified for test)
        String filename = "test_statement.xlsx";
        String importBatchId1 = UUID.randomUUID().toString();

        // First import - create transactions directly (simulating Excel parse result)
        TransactionTable tx1 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId1, filename
        );

        TransactionTable tx2 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId1, filename
        );

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size(), "Should have 2 transactions after first Excel import");

        // When - Reimport same Excel file
        String importBatchId2 = UUID.randomUUID().toString(); // Different batch ID

        TransactionTable result1 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId2, filename
        );

        TransactionTable result2 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId2, filename
        );

        // Then - Should not create duplicates
        boolean isDuplicate1 = transactions1.stream()
                .anyMatch(t -> t.getTransactionId().equals(result1.getTransactionId()));
        boolean isDuplicate2 = transactions1.stream()
                .anyMatch(t -> t.getTransactionId().equals(result2.getTransactionId()));

        assertTrue(isDuplicate1, "First transaction should be detected as duplicate");
        assertTrue(isDuplicate2, "Second transaction should be detected as duplicate");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions2.size(), "Should still have only 2 transactions after Excel reimport");
    }

    @Test
    void testReimportSamePDFFile_ShouldNotCreateDuplicates() {
        // Given - Initial PDF import
        String filename = "test_statement.pdf";
        String importBatchId1 = UUID.randomUUID().toString();

        // First import
        TransactionTable tx1 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId1, filename
        );

        TransactionTable tx2 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId1, filename
        );

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size(), "Should have 2 transactions after first PDF import");

        // When - Reimport same PDF file
        String importBatchId2 = UUID.randomUUID().toString(); // Different batch ID

        TransactionTable result1 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId2, filename
        );

        TransactionTable result2 = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId2, filename
        );

        // Then - Should not create duplicates
        boolean isDuplicate1 = transactions1.stream()
                .anyMatch(t -> t.getTransactionId().equals(result1.getTransactionId()));
        boolean isDuplicate2 = transactions1.stream()
                .anyMatch(t -> t.getTransactionId().equals(result2.getTransactionId()));

        assertTrue(isDuplicate1, "First transaction should be detected as duplicate");
        assertTrue(isDuplicate2, "Second transaction should be detected as duplicate");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions2.size(), "Should still have only 2 transactions after PDF reimport");
    }

    @Test
    void testIncrementalExcelUpdate_OnlyAddsNewTransactions() {
        // Given - Initial Excel import with 2 transactions
        String filename = "test_statement.xlsx";
        String importBatchId1 = UUID.randomUUID().toString();

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId1, filename
        );

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId1, filename
        );

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import Excel file with additional transaction
        String importBatchId2 = UUID.randomUUID().toString();

        // Reimport existing 2 (should be skipped)
        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId2, filename
        );

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId2, filename
        );

        // Add new transaction
        TransactionTable newTx = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("25.00"), 
                java.time.LocalDate.parse("2025-01-17"),
                "Restaurant", "Restaurants", "Restaurants",
                null, "Imported from Excel", null, null, "EXPENSE",
                "USD", "EXCEL", importBatchId2, filename
        );

        // Then - Should only add the new transaction
        boolean isNew = transactions1.stream()
                .noneMatch(t -> t.getTransactionId().equals(newTx.getTransactionId()));
        assertTrue(isNew, "New transaction should be created");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(3, transactions2.size(), "Should have 3 transactions total (2 original + 1 new)");
    }

    @Test
    void testIncrementalPDFUpdate_OnlyAddsNewTransactions() {
        // Given - Initial PDF import with 2 transactions
        String filename = "test_statement.pdf";
        String importBatchId1 = UUID.randomUUID().toString();

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId1, filename
        );

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId1, filename
        );

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import PDF file with additional transaction
        String importBatchId2 = UUID.randomUUID().toString();

        // Reimport existing 2 (should be skipped)
        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("50.00"), 
                java.time.LocalDate.parse("2025-01-15"),
                "Grocery Store", "Groceries", "Groceries",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId2, filename
        );

        transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("30.00"), 
                java.time.LocalDate.parse("2025-01-16"),
                "Gas Station", "Gas", "Gas",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId2, filename
        );

        // Add new transaction
        TransactionTable newTx = transactionService.createTransaction(
                testUser, testAccountId, new BigDecimal("25.00"), 
                java.time.LocalDate.parse("2025-01-17"),
                "Restaurant", "Restaurants", "Restaurants",
                null, "Imported from PDF", null, null, "EXPENSE",
                "USD", "PDF", importBatchId2, filename
        );

        // Then - Should only add the new transaction
        boolean isNew = transactions1.stream()
                .noneMatch(t -> t.getTransactionId().equals(newTx.getTransactionId()));
        assertTrue(isNew, "New transaction should be created");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(3, transactions2.size(), "Should have 3 transactions total (2 original + 1 new)");
    }
}

