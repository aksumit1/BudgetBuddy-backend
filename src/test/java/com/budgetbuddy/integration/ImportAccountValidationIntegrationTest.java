package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for account validation during import
 * Tests that transactions cannot be created without valid account IDs
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ImportAccountValidationIntegrationTest {

    @Autowired
    private CSVImportService csvImportService;

    @Autowired
    private AccountDetectionService accountDetectionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Create test user
        String testEmail = "account-validation-test-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
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
    void testCSVImport_WithValidAccountId_CreatesTransactions() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test_checking_1234.csv";

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream, filename, testUser.getUserId(), null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNotNull(transaction.getAccountId(), "Transaction should have accountId when account is matched");
        assertEquals(testAccount.getAccountId(), transaction.getAccountId());
    }

    @Test
    void testCSVImport_WithoutAccountId_TransactionHasNullAccountId() {
        // Given - filename doesn't match any account
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "unknown_bank_9999.csv";

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream, filename, testUser.getUserId(), null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNull(transaction.getAccountId(), 
                "Transaction should have null accountId when no account is matched");
    }

    @Test
    void testAccountMatching_WithNullInstitutionName_HandlesCorrectly() {
        // Given
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber("1234");
        detected.setInstitutionName(null); // Null institution name

        // When
        String matchedId = accountDetectionService.matchToExistingAccount(testUser.getUserId(), detected);

        // Then - should match by account number only
        assertNotNull(matchedId);
        assertEquals(testAccount.getAccountId(), matchedId);
    }

    @Test
    void testAccountMatching_WithEmptyAccountNumber_HandlesCorrectly() {
        // Given
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber(""); // Empty account number
        detected.setInstitutionName("Test Bank");
        detected.setAccountType("depository");

        // When
        String matchedId = accountDetectionService.matchToExistingAccount(testUser.getUserId(), detected);

        // Then - should match by institution and type
        assertNotNull(matchedId);
        assertEquals(testAccount.getAccountId(), matchedId);
    }
}

