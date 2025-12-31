package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.*;
import com.budgetbuddy.service.plaid.PlaidAccountSyncService;
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
 * Integration tests for account detection during import
 * Tests the full flow: file upload -> account detection -> account matching -> transaction creation
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ImportAccountDetectionIntegrationTest {

    @Autowired
    private CSVImportService csvImportService;

    @Autowired
    private AccountDetectionService accountDetectionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserService userService;

    private UserTable testUser;
    private AccountTable existingAccount;

    @BeforeEach
    void setUp() {
        // Create test user
        String testEmail = "account-detection-test-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Create existing account for matching
        existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountName("Chase Checking");
        existingAccount.setInstitutionName("Chase");
        existingAccount.setAccountType("depository");
        existingAccount.setAccountSubtype("checking");
        existingAccount.setAccountNumber("1234");
        existingAccount.setBalance(BigDecimal.ZERO);
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);
    }

    @Test
    void testCSVImport_WithAccountDetection_MatchesExistingAccount() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00\n2025-01-16,Gas Station,30.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "chase_checking_1234.csv";

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream, filename, testUser.getUserId(), null);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getSuccessCount());
        assertFalse(result.getTransactions().isEmpty());

        // Verify all transactions have the matched account ID
        for (CSVImportService.ParsedTransaction transaction : result.getTransactions()) {
            assertNotNull(transaction.getAccountId(), "Transaction should have account ID");
            assertEquals(existingAccount.getAccountId(), transaction.getAccountId(),
                    "Transaction should be matched to existing account");
        }
    }

    @Test
    void testCSVImport_WithAccountDetection_NoMatch_CreatesTransactionsWithoutAccountId() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "unknown_bank_9999.csv"; // Account number that doesn't exist

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream, filename, testUser.getUserId(), null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());

        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        // Account ID should be null if no match found (user can select account in UI)
        assertNull(transaction.getAccountId(), "Transaction should not have account ID when no match found");
    }

    @Test
    void testAccountDetection_FromFilename_DetectsCorrectly() {
        // Given
        String filename = "wells_fargo_savings_5678.csv";

        // When
        AccountDetectionService.DetectedAccount detected = accountDetectionService.detectFromFilename(filename);

        // Then
        assertNotNull(detected);
        assertEquals("Wells Fargo", detected.getInstitutionName());
        assertEquals("depository", detected.getAccountType());
        assertEquals("savings", detected.getAccountSubtype());
        assertEquals("5678", detected.getAccountNumber());
    }

    @Test
    void testAccountMatching_ByAccountNumberAndInstitution_MatchesCorrectly() {
        // Given
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber("1234");
        detected.setInstitutionName("Chase");
        detected.setAccountType("depository");

        // When
        String matchedId = accountDetectionService.matchToExistingAccount(testUser.getUserId(), detected);

        // Then
        assertNotNull(matchedId);
        assertEquals(existingAccount.getAccountId(), matchedId);
    }

    @Test
    void testAccountMatching_ByAccountNumberOnly_MatchesCorrectly() {
        // Given
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber("1234");
        // No institution name

        // When
        String matchedId = accountDetectionService.matchToExistingAccount(testUser.getUserId(), detected);

        // Then
        assertNotNull(matchedId);
        assertEquals(existingAccount.getAccountId(), matchedId);
    }
}

