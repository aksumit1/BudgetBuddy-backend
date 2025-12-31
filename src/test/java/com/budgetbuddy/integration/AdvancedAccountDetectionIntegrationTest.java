package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.AdvancedAccountDetectionService;
import com.budgetbuddy.service.AccountDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AdvancedAccountDetectionService
 * Tests real-world scenarios with global CSV, Excel, and PDF statement formats
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AdvancedAccountDetectionIntegrationTest {

    @Autowired
    private AdvancedAccountDetectionService advancedDetectionService;
    
    @Test
    void testUSBank_ChaseChecking_CSV() {
        String filename = "chase_checking_1234_statement_2024.csv";
        List<String> headers = Arrays.asList(
            "Date", "Account Number ending in: 1234", "Description", "Debit", "Credit", "Balance"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****1234", "Check #5678 - Rent Payment", "1000.00", "", "5000.00"),
            Arrays.asList("2024-01-02", "****1234", "ACH Direct Deposit - Salary", "", "3000.00", "8000.00"),
            Arrays.asList("2024-01-03", "****1234", "Debit Card Purchase - Grocery Store", "150.00", "", "7850.00")
        );
        Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");
        metadata.put("institution", "JPMorgan Chase Bank");
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
        assertNotNull(result.getAccountName());
    }
    
    @Test
    void testUSBank_BankOfAmerica_CreditCard_CSV() {
        String filename = "bofa_credit_card_5678.csv";
        List<String> headers = Arrays.asList(
            "Transaction Date", "Card Number ending in: 5678", "Merchant", "Amount"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****5678", "AMAZON.COM", "50.00"),
            Arrays.asList("2024-01-02", "****5678", "STARBUCKS", "5.00"),
            Arrays.asList("2024-01-03", "****5678", "SHELL GAS STATION", "30.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("bank of america", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }
    
    @Test
    void testUKBank_HSBC_CurrentAccount_CSV() {
        String filename = "hsbc_current_account_GB82WEST12345698765432.csv";
        List<String> headers = Arrays.asList(
            "Date", "IBAN", "Description", "Debit", "Credit", "Balance"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "GB82WEST12345698765432", "Payment to John Doe", "100.00", "", "2000.00"),
            Arrays.asList("2024-01-02", "GB82WEST12345698765432", "Salary Payment", "", "3000.00", "5000.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testIndianBank_SBI_Savings_CSV() {
        String filename = "sbi_savings_5678_statement.csv";
        List<String> headers = Arrays.asList(
            "Date", "Account Number", "Transaction Details", "Debit", "Credit", "Balance"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "5678", "NEFT Credit - Salary", "", "50000.00", "100000.00"),
            Arrays.asList("2024-01-02", "5678", "UPI Payment - Groceries", "500.00", "", "99500.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("state bank of india", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testChineseBank_ICBC_Checking_CSV() {
        String filename = "icbc_活期账户_1234.csv";
        List<String> headers = Arrays.asList(
            "日期", "账户号码", "交易描述", "借方", "贷方", "余额"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "工资", "", "10000.00", "20000.00"),
            Arrays.asList("2024-01-02", "1234", "购物", "500.00", "", "19500.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testJapaneseBank_MUFG_Checking_CSV() {
        String filename = "mufg_当座預金_1234.csv";
        List<String> headers = Arrays.asList(
            "日付", "口座番号", "取引内容", "借方", "貸方", "残高"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "給与", "", "300000.00", "500000.00"),
            Arrays.asList("2024-01-02", "1234", "買い物", "5000.00", "", "495000.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testEuropeanBank_DeutscheBank_Girokonto_CSV() {
        String filename = "deutsche_bank_girokonto_1234.csv";
        List<String> headers = Arrays.asList(
            "Datum", "Kontonummer", "Beschreibung", "Soll", "Haben", "Saldo"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "Gehalt", "", "5000.00", "10000.00"),
            Arrays.asList("2024-01-02", "1234", "Einkauf", "100.00", "", "9900.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("deutsche bank", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testFrenchBank_BNPParibas_CompteCourant_CSV() {
        String filename = "bnp_paribas_compte_courant_1234.csv";
        List<String> headers = Arrays.asList(
            "Date", "Numéro de compte", "Description", "Débit", "Crédit", "Solde"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "Salaire", "", "4000.00", "8000.00"),
            Arrays.asList("2024-01-02", "1234", "Achat", "200.00", "", "7800.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("bnp paribas", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testInvestmentAccount_Fidelity_401k_CSV() {
        String filename = "fidelity_401k_statement.csv";
        List<String> headers = Arrays.asList(
            "Date", "Account Number", "Transaction Type", "Amount", "Description"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "123456789", "Contribution", "1000.00", "401k Contribution"),
            Arrays.asList("2024-01-02", "123456789", "Dividend", "50.00", "Stock Dividend")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("fidelity", result.getInstitutionName());
        assertEquals("investment", result.getAccountType());
    }
    
    @Test
    void testCreditCard_AmericanExpress_CSV() {
        String filename = "amex_credit_card_5678.csv";
        List<String> headers = Arrays.asList(
            "Date", "Card Number ending in: 5678", "Merchant", "Amount"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****5678", "HOTEL RESERVATION", "200.00"),
            Arrays.asList("2024-01-02", "****5678", "RESTAURANT", "50.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("american express", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }
    
    @Test
    void testAccountDetection_FromTransactionPatterns() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Description", "Amount");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "Check #1234 - Rent Payment", "1000.00"),
            Arrays.asList("2024-01-02", "Check #1235 - Utilities", "200.00"),
            Arrays.asList("2024-01-03", "ACH Direct Deposit - Salary", "3000.00"),
            Arrays.asList("2024-01-04", "ATM Withdrawal", "100.00"),
            Arrays.asList("2024-01-05", "Debit Card Purchase", "50.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testAccountDetection_MultipleSources() {
        String filename = "chase_checking_1234.csv";
        List<String> headers = Arrays.asList(
            "Date", "Account Number ending in: 1234", "Institution: JPMorgan Chase", "Amount"
        );
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****1234", "Chase Bank", "100.00")
        );
        Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");
        metadata.put("institution", "JPMorgan Chase Bank, N.A.");
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testAccountDetection_EdgeCase_NoAccountNumber() {
        String filename = "chase_checking_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "100.00", "Transaction")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        assertEquals("chase", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
        // Account number may be null if not found
    }
    
    @Test
    void testAccountDetection_EdgeCase_EmptyData() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = advancedDetectionService.detectAccount(filename, headers, dataRows, metadata);
        
        assertNotNull(result);
        // Should handle gracefully
    }
}

