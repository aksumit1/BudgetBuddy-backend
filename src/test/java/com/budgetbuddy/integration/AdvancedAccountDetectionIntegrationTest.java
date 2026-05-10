package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.AdvancedAccountDetectionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for AdvancedAccountDetectionService Tests real-world scenarios with global CSV,
 * Excel, and PDF statement formats
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AdvancedAccountDetectionIntegrationTest {

    private static final String DATE = "Date";
    private static final String DEPOSITORY = "depository";
    private static final String AMOUNT = "Amount";
    private static final String DESCRIPTION = "Description";

    @Autowired private AdvancedAccountDetectionService advancedDetectionService;

    @Test
    void testUSBankChaseCheckingCSV() {
        final String filename = "chase_checking_1234_statement_2024.csv";
        final List<String> headers =
                Arrays.asList(
                        DATE,
                        "Account Number ending in: 1234",
                        DESCRIPTION,
                        "Debit",
                        "Credit",
                        "Balance");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList(
                                "2024-01-01",
                                "****1234",
                                "Check #5678 - Rent Payment",
                                "1000.00",
                                "",
                                "5000.00"),
                        Arrays.asList(
                                "2024-01-02",
                                "****1234",
                                "ACH Direct Deposit - Salary",
                                "",
                                "3000.00",
                                "8000.00"),
                        Arrays.asList(
                                "2024-01-03",
                                "****1234",
                                "Debit Card Purchase - Grocery Store",
                                "150.00",
                                "",
                                "7850.00"));
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");
        metadata.put("institution", "JPMorgan Chase Bank");

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
        assertNotNull(result.getAccountName());
    }

    @Test
    void testUSBankBankOfAmericaCreditCardCSV() {
        final String filename = "bofa_credit_card_5678.csv";
        final List<String> headers =
                Arrays.asList(
                        "Transaction Date", "Card Number ending in: 5678", "Merchant", AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "****5678", "AMAZON.COM", "50.00"),
                        Arrays.asList("2024-01-02", "****5678", "STARBUCKS", "5.00"),
                        Arrays.asList("2024-01-03", "****5678", "SHELL GAS STATION", "30.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("bank of america", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }

    @Test
    void testUKBankHSBCCurrentAccountCSV() {
        final String filename = "hsbc_current_account_GB82WEST12345698765432.csv";
        final List<String> headers =
                Arrays.asList(DATE, "IBAN", DESCRIPTION, "Debit", "Credit", "Balance");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList(
                                "2024-01-01",
                                "GB82WEST12345698765432",
                                "Payment to John Doe",
                                "100.00",
                                "",
                                "2000.00"),
                        Arrays.asList(
                                "2024-01-02",
                                "GB82WEST12345698765432",
                                "Salary Payment",
                                "",
                                "3000.00",
                                "5000.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testIndianBankSBISavingsCSV() {
        final String filename = "sbi_savings_5678_statement.csv";
        final List<String> headers =
                Arrays.asList(
                        DATE,
                        "Account Number",
                        "Transaction Details",
                        "Debit",
                        "Credit",
                        "Balance");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList(
                                "2024-01-01",
                                "5678",
                                "NEFT Credit - Salary",
                                "",
                                "50000.00",
                                "100000.00"),
                        Arrays.asList(
                                "2024-01-02",
                                "5678",
                                "UPI Payment - Groceries",
                                "500.00",
                                "",
                                "99500.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("state bank of india", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testChineseBankICBCCheckingCSV() {
        final String filename = "icbc_活期账户_1234.csv";
        final List<String> headers = Arrays.asList("日期", "账户号码", "交易描述", "借方", "贷方", "余额");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "1234", "工资", "", "10000.00", "20000.00"),
                        Arrays.asList("2024-01-02", "1234", "购物", "500.00", "", "19500.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testJapaneseBankMUFGCheckingCSV() {
        final String filename = "mufg_当座預金_1234.csv";
        final List<String> headers = Arrays.asList("日付", "口座番号", "取引内容", "借方", "貸方", "残高");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "1234", "給与", "", "300000.00", "500000.00"),
                        Arrays.asList("2024-01-02", "1234", "買い物", "5000.00", "", "495000.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testEuropeanBankDeutscheBankGirokontoCSV() {
        final String filename = "deutsche_bank_girokonto_1234.csv";
        final List<String> headers =
                Arrays.asList("Datum", "Kontonummer", "Beschreibung", "Soll", "Haben", "Saldo");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "1234", "Gehalt", "", "5000.00", "10000.00"),
                        Arrays.asList("2024-01-02", "1234", "Einkauf", "100.00", "", "9900.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("deutsche bank", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testFrenchBankBNPParibasCompteCourantCSV() {
        final String filename = "bnp_paribas_compte_courant_1234.csv";
        final List<String> headers =
                Arrays.asList(DATE, "Numéro de compte", DESCRIPTION, "Débit", "Crédit", "Solde");
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "1234", "Salaire", "", "4000.00", "8000.00"),
                        Arrays.asList("2024-01-02", "1234", "Achat", "200.00", "", "7800.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("bnp paribas", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testInvestmentAccountFidelity401kCSV() {
        final String filename = "fidelity_401k_statement.csv";
        final List<String> headers =
                Arrays.asList(DATE, "Account Number", "Transaction Type", AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList(
                                "2024-01-01",
                                "123456789",
                                "Contribution",
                                "1000.00",
                                "401k Contribution"),
                        Arrays.asList(
                                "2024-01-02", "123456789", "Dividend", "50.00", "Stock Dividend"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("fidelity", result.getInstitutionName());
        assertEquals("investment", result.getAccountType());
    }

    @Test
    void testCreditCardAmericanExpressCSV() {
        final String filename = "amex_credit_card_5678.csv";
        final List<String> headers =
                Arrays.asList(DATE, "Card Number ending in: 5678", "Merchant", AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "****5678", "HOTEL RESERVATION", "200.00"),
                        Arrays.asList("2024-01-02", "****5678", "RESTAURANT", "50.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("american express", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }

    @Test
    void testAccountDetectionFromTransactionPatterns() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, DESCRIPTION, AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(
                        Arrays.asList("2024-01-01", "Check #1234 - Rent Payment", "1000.00"),
                        Arrays.asList("2024-01-02", "Check #1235 - Utilities", "200.00"),
                        Arrays.asList("2024-01-03", "ACH Direct Deposit - Salary", "3000.00"),
                        Arrays.asList("2024-01-04", "ATM Withdrawal", "100.00"),
                        Arrays.asList("2024-01-05", "Debit Card Purchase", "50.00"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testAccountDetectionMultipleSources() {
        final String filename = "chase_checking_1234.csv";
        final List<String> headers =
                Arrays.asList(
                        DATE,
                        "Account Number ending in: 1234",
                        "Institution: JPMorgan Chase",
                        AMOUNT);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "****1234", "Chase Bank", "100.00"));
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");
        metadata.put("institution", "JPMorgan Chase Bank, N.A.");

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
    }

    @Test
    void testAccountDetectionEdgeCaseNoAccountNumber() {
        final String filename = "chase_checking_statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows =
                Arrays.asList(Arrays.asList("2024-01-01", "100.00", "Transaction"));
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        assertEquals("chase", result.getInstitutionName());
        assertEquals(DEPOSITORY, result.getAccountType());
        // Account number may be null if not found
    }

    @Test
    void testAccountDetectionEdgeCaseEmptyData() {
        final String filename = "statement.csv";
        final List<String> headers = Arrays.asList(DATE, AMOUNT, DESCRIPTION);
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        final var result =
                advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);

        assertNotNull(result);
        // Should handle gracefully
    }
}
