package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AdvancedAccountDetectionService Tests sophisticated pattern detection techniques
 * for global financial statements
 */
@ExtendWith(MockitoExtension.class)
class AdvancedAccountDetectionServiceTest {

    private static final String DATE = "Date";
    private static final String AMOUNT = "Amount";
    private static final String DESCRIPTION = "Description";
    private static final String DEPOSITORY = "depository";

    @Mock private AccountRepository accountRepository;

    @Mock private com.budgetbuddy.service.OCRService ocrService;

    @Mock private com.budgetbuddy.service.FormFieldDetectionService formFieldDetectionService;

    @Mock
    private com.budgetbuddy.service.TableStructureDetectionService tableStructureDetectionService;

    private AdvancedAccountDetectionService detectionService;

    @BeforeEach
    void setUp() {
        detectionService =
                new AdvancedAccountDetectionService(
                        ocrService, formFieldDetectionService, tableStructureDetectionService);
    }

    @Test
    void testDetectAccountNumberFromFilename() {
        // US format
        final String filename = "chase_checking_1234.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    void testDetectAccountNumberFromHeader() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number ending in: 5678", AMOUNT);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
    }

    @Test
    void testDetectAccountNumberFromDataColumn() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "****9012", "100.00"),
                        Arrays.asList("2024-01-02", "****9012", "200.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("9012", result.getAccountNumber());
    }

    @Test
    void testDetectAccountNumberIBANFormat() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, "IBAN: GB82WEST12345698765432", AMOUNT);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertNotNull(result.getAccountNumber());
    }

    @Test
    void testDetectInstitutionNameUSBank() {
        final String filename = "chase_bank_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("chase", result.getInstitutionName());
    }

    @Test
    void testDetectInstitutionNameBankOfAmericaAlias() {
        final String filename = "bofa_checking_1234.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("bank of america", result.getInstitutionName());
    }

    @Test
    void testDetectInstitutionNameUKBank() {
        final String filename = "hsbc_uk_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
    }

    @Test
    void testDetectInstitutionNameIndianBank() {
        final String filename = "sbi_savings_5678.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("state bank of india", result.getInstitutionName());
    }

    @Test
    void testDetectInstitutionNameChineseBank() {
        final String filename = "icbc_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
    }

    @Test
    void testDetectAccountTypeChecking() {
        final String filename = "checking_account_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountTypeCreditCard() {
        final String filename = "credit_card_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("credit", result.getAccountType());
    }

    @Test
    void testDetectAccountTypeGermanFormat() {
        final String filename = "girokonto_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountTypeFrenchFormat() {
        final String filename = "compte_courant_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountTypeFromTransactionPatterns() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, DESCRIPTION, AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "Check #1234 - Payment", "100.00"),
                        Arrays.asList("2024-01-02", "Check #1235 - Rent", "500.00"),
                        Arrays.asList("2024-01-03", "ACH Direct Deposit", "2000.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountNameFromDataColumn() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Name", AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "Chase Checking Account", "100.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("Chase Checking Account", result.getAccountName());
    }

    @Test
    void testDetectAccountNameConstructed() {
        final String filename = "chase_checking_1234.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertNotNull(result.getAccountName());
        assertTrue(result.getAccountName().contains("chase"));
    }

    @Test
    void testDetectAccountComprehensiveUSFormat() {
        final String filename = "chase_checking_1234_statement.csv";
        final List<String> headers =
                Arrays.asList(DATE, "Account Number ending in: 1234", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "****1234", "100.00", "Check #5678 - Payment"));
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
        assertNotNull(result.getAccountName());
    }

    @Test
    void testDetectAccountComprehensiveUKFormat() {
        final String filename = "hsbc_current_account_GB82WEST12345698765432.csv";
        final List<String> headers = Arrays.asList(DATE, "IBAN", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "GB82WEST12345698765432", "100.00", "Payment"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountComprehensiveIndianFormat() {
        final String filename = "sbi_savings_5678_statement.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "5678", "100.00", "Deposit"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("state bank of india", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountComprehensiveChineseFormat() {
        final String filename = "icbc_活期账户_1234.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "1234", "100.00", "Transaction"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountComprehensiveJapaneseFormat() {
        final String filename = "mufg_当座預金_1234.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "1234", "100.00", "Transaction"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testDetectAccountComprehensiveCreditCard() {
        final String filename = "amex_credit_card_5678.csv";
        final List<String> headers =
                Arrays.asList(DATE, "Card Number ending in: 5678", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "****5678", "100.00", "Purchase"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("american express", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }

    @Test
    void testDetectAccountComprehensiveInvestment() {
        final String filename = "fidelity_401k_statement.csv";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "123456789", "1000.00", "Contribution"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("fidelity", result.getInstitutionName());
        assertEquals("investment", result.getAccountType());
    }

    @Test
    void testDetectAccountEdgeCaseNoData() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should still work with just filename
    }

    @Test
    void testDetectAccountEdgeCaseEmptyFilename() {
        final String filename = "";
        final List<String> headers = Arrays.asList(DATE, "Account Number", AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "1234", "100.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should work with headers/data
    }

    @Test
    void testDetectAccountEdgeCaseMultipleAccountNumbers() {
        final String filename = "statement.csv";
        final List<String> headers =
                Arrays.asList(
                        DATE, "Account Number: 1234", AMOUNT, "Description: Account ending 5678");
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should pick the one with highest confidence
        assertNotNull(result.getAccountNumber());
    }
}
