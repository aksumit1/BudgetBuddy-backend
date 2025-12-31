package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for duplicate detection during file imports
 * Tests scenarios:
 * 1. Reimporting same file - should not create duplicates
 * 2. Incremental update - file with additional transactions
 * 3. File with fewer transactions - should not delete existing
 * 4. File with some same and some new - only add new ones
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ImportDuplicateDetectionIntegrationTest {

    @Autowired
    private CSVImportService csvImportService;

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
        String testEmail = "import-dup-test-" + UUID.randomUUID() + "@example.com";
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
    void testReimportSameFile_ShouldNotCreateDuplicates() {
        // Given - Initial import
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00";
        InputStream inputStream1 = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test_statement.csv";
        String importBatchId1 = UUID.randomUUID().toString();

        // First import
        CSVImportService.ImportResult result1 = csvImportService.parseCSV(
                inputStream1, filename, testUser.getUserId(), null);

        // Create transactions from first import
        int created1 = 0;
        for (CSVImportService.ParsedTransaction parsed : result1.getTransactions()) {
            try {
                transactionService.createTransaction(
                        testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                        parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                        null, "Imported from CSV", null, null, parsed.getTransactionType(),
                        parsed.getCurrencyCode(), "CSV", importBatchId1, filename
                );
                created1++;
            } catch (Exception e) {
                fail("Failed to create transaction: " + e.getMessage());
            }
        }

        assertEquals(2, created1, "First import should create 2 transactions");

        // Verify transactions exist
        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size(), "Should have 2 transactions after first import");

        // When - Reimport same file
        InputStream inputStream2 = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String importBatchId2 = UUID.randomUUID().toString(); // Different batch ID

        CSVImportService.ImportResult result2 = csvImportService.parseCSV(
                inputStream2, filename, testUser.getUserId(), null);

        // Create transactions from second import
        int created2 = 0;
        int skipped2 = 0;
        for (CSVImportService.ParsedTransaction parsed : result2.getTransactions()) {
            try {
                TransactionTable result = transactionService.createTransaction(
                        testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                        parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                        null, "Imported from CSV", null, null, parsed.getTransactionType(),
                        parsed.getCurrencyCode(), "CSV", importBatchId2, filename
                );
                // CRITICAL: createTransaction returns existing transaction if duplicate detected
                // Check if this is the same transaction (same ID) from first import
                boolean isDuplicate = transactions1.stream()
                        .anyMatch(t -> t.getTransactionId().equals(result.getTransactionId()));
                if (isDuplicate) {
                    skipped2++;
                } else {
                    created2++;
                }
            } catch (Exception e) {
                fail("Failed to create transaction: " + e.getMessage());
            }
        }

        // Then - Should not create duplicates
        assertEquals(0, created2, "Reimport should not create new transactions");
        assertEquals(2, skipped2, "Reimport should skip 2 existing transactions");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions2.size(), "Should still have only 2 transactions after reimport");
    }

    @Test
    void testIncrementalUpdate_FileWithAdditionalTransactions_OnlyAddsNew() {
        // Given - Initial import with 2 transactions
        String csvContent1 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00";
        InputStream inputStream1 = new ByteArrayInputStream(csvContent1.getBytes(StandardCharsets.UTF_8));
        String filename = "test_statement.csv";
        String importBatchId1 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result1 = csvImportService.parseCSV(
                inputStream1, filename, testUser.getUserId(), null);

        for (CSVImportService.ParsedTransaction parsed : result1.getTransactions()) {
            transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId1, filename
            );
        }

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import file with additional transaction
        String csvContent2 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00\n2025-01-17,Restaurant,25.00";
        InputStream inputStream2 = new ByteArrayInputStream(csvContent2.getBytes(StandardCharsets.UTF_8));
        String importBatchId2 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result2 = csvImportService.parseCSV(
                inputStream2, filename, testUser.getUserId(), null);

        int created2 = 0;
        int skipped2 = 0;
        for (CSVImportService.ParsedTransaction parsed : result2.getTransactions()) {
            TransactionTable result = transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId2, filename
            );
            
            // CRITICAL: Check if this is a duplicate (same transaction ID from first import)
            boolean isDuplicate = transactions1.stream()
                    .anyMatch(t -> t.getTransactionId().equals(result.getTransactionId()));
            if (isDuplicate) {
                skipped2++;
            } else {
                created2++;
            }
        }

        // Then - Should only add the new transaction
        assertEquals(1, created2, "Should create 1 new transaction");
        assertEquals(2, skipped2, "Should skip 2 existing transactions");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(3, transactions2.size(), "Should have 3 transactions total");
    }

    @Test
    void testFileWithFewerTransactions_ShouldNotDeleteExisting() {
        // Given - Initial import with 3 transactions
        String csvContent1 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00\n2025-01-17,Restaurant,25.00";
        InputStream inputStream1 = new ByteArrayInputStream(csvContent1.getBytes(StandardCharsets.UTF_8));
        String filename = "test_statement.csv";
        String importBatchId1 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result1 = csvImportService.parseCSV(
                inputStream1, filename, testUser.getUserId(), null);

        for (CSVImportService.ParsedTransaction parsed : result1.getTransactions()) {
            transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId1, filename
            );
        }

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(3, transactions1.size());

        // When - Import file with fewer transactions (only 2)
        String csvContent2 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00";
        InputStream inputStream2 = new ByteArrayInputStream(csvContent2.getBytes(StandardCharsets.UTF_8));
        String importBatchId2 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result2 = csvImportService.parseCSV(
                inputStream2, filename, testUser.getUserId(), null);

        int created2 = 0;
        int skipped2 = 0;
        for (CSVImportService.ParsedTransaction parsed : result2.getTransactions()) {
            TransactionTable result = transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId2, filename
            );
            
            // CRITICAL: Check if this is a duplicate (same transaction ID from first import)
            boolean isDuplicate = transactions1.stream()
                    .anyMatch(t -> t.getTransactionId().equals(result.getTransactionId()));
            if (isDuplicate) {
                skipped2++;
            } else {
                created2++;
            }
        }

        // Then - Should not delete existing transactions, should skip duplicates
        assertEquals(0, created2, "Should not create new transactions");
        assertEquals(2, skipped2, "Should skip 2 existing transactions");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(3, transactions2.size(), "Should still have all 3 transactions (none deleted)");
    }

    @Test
    void testFileWithSomeSameAndSomeNew_OnlyAddsNew() {
        // Given - Initial import with 2 transactions
        String csvContent1 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00";
        InputStream inputStream1 = new ByteArrayInputStream(csvContent1.getBytes(StandardCharsets.UTF_8));
        String filename = "test_statement.csv";
        String importBatchId1 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result1 = csvImportService.parseCSV(
                inputStream1, filename, testUser.getUserId(), null);

        for (CSVImportService.ParsedTransaction parsed : result1.getTransactions()) {
            transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId1, filename
            );
        }

        List<TransactionTable> transactions1 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, transactions1.size());

        // When - Import file with 1 same transaction and 2 new ones
        String csvContent2 = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-17,Restaurant,25.00\n2025-01-18,Pharmacy,15.00";
        InputStream inputStream2 = new ByteArrayInputStream(csvContent2.getBytes(StandardCharsets.UTF_8));
        String importBatchId2 = UUID.randomUUID().toString();

        CSVImportService.ImportResult result2 = csvImportService.parseCSV(
                inputStream2, filename, testUser.getUserId(), null);

        int created2 = 0;
        int skipped2 = 0;
        for (CSVImportService.ParsedTransaction parsed : result2.getTransactions()) {
            TransactionTable result = transactionService.createTransaction(
                    testUser, testAccountId, parsed.getAmount(), parsed.getDate(),
                    parsed.getDescription(), parsed.getCategoryPrimary(), parsed.getCategoryDetailed(),
                    null, "Imported from CSV", null, null, parsed.getTransactionType(),
                    parsed.getCurrencyCode(), "CSV", importBatchId2, filename
            );
            
            // CRITICAL: Check if this is a duplicate (same transaction ID from first import)
            boolean isDuplicate = transactions1.stream()
                    .anyMatch(t -> t.getTransactionId().equals(result.getTransactionId()));
            if (isDuplicate) {
                skipped2++;
            } else {
                created2++;
            }
        }

        // Then - Should only add the 2 new transactions
        assertEquals(2, created2, "Should create 2 new transactions");
        assertEquals(1, skipped2, "Should skip 1 existing transaction");

        List<TransactionTable> transactions2 = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(4, transactions2.size(), "Should have 4 transactions total (2 original + 2 new)");
    }
}

