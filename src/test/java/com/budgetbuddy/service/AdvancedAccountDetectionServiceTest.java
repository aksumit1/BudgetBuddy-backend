package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.budgetbuddy.repository.dynamodb.AccountRepository;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AdvancedAccountDetectionService
 * Tests sophisticated pattern detection techniques for global financial statements
 */
@ExtendWith(MockitoExtension.class)
class AdvancedAccountDetectionServiceTest {

    @Mock
    private AccountRepository accountRepository;
    
    @Mock
    private com.budgetbuddy.service.OCRService ocrService;
    
    @Mock
    private com.budgetbuddy.service.FormFieldDetectionService formFieldDetectionService;
    
    @Mock
    private com.budgetbuddy.service.TableStructureDetectionService tableStructureDetectionService;
    
    private AdvancedAccountDetectionService detectionService;
    
    @BeforeEach
    void setUp() {
        detectionService = new AdvancedAccountDetectionService(ocrService, formFieldDetectionService, tableStructureDetectionService);
    }
    
    @Test
    void testDetectAccountNumber_FromFilename() {
        // US format
        String filename = "chase_checking_1234.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }
    
    @Test
    void testDetectAccountNumber_FromHeader() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number ending in: 5678", "Amount");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
    }
    
    @Test
    void testDetectAccountNumber_FromDataColumn() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****9012", "100.00"),
            Arrays.asList("2024-01-02", "****9012", "200.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("9012", result.getAccountNumber());
    }
    
    @Test
    void testDetectAccountNumber_IBANFormat() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "IBAN: GB82WEST12345698765432", "Amount");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertNotNull(result.getAccountNumber());
    }
    
    @Test
    void testDetectInstitutionName_USBank() {
        String filename = "chase_bank_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("chase", result.getInstitutionName());
    }
    
    @Test
    void testDetectInstitutionName_BankOfAmericaAlias() {
        String filename = "bofa_checking_1234.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("bank of america", result.getInstitutionName());
    }
    
    @Test
    void testDetectInstitutionName_UKBank() {
        String filename = "hsbc_uk_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
    }
    
    @Test
    void testDetectInstitutionName_IndianBank() {
        String filename = "sbi_savings_5678.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("state bank of india", result.getInstitutionName());
    }
    
    @Test
    void testDetectInstitutionName_ChineseBank() {
        String filename = "icbc_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
    }
    
    @Test
    void testDetectAccountType_Checking() {
        String filename = "checking_account_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccountType_CreditCard() {
        String filename = "credit_card_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("credit", result.getAccountType());
    }
    
    @Test
    void testDetectAccountType_GermanFormat() {
        String filename = "girokonto_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccountType_FrenchFormat() {
        String filename = "compte_courant_statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccountType_FromTransactionPatterns() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Description", "Amount");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "Check #1234 - Payment", "100.00"),
            Arrays.asList("2024-01-02", "Check #1235 - Rent", "500.00"),
            Arrays.asList("2024-01-03", "ACH Direct Deposit", "2000.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccountName_FromDataColumn() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Name", "Amount");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "Chase Checking Account", "100.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("Chase Checking Account", result.getAccountName());
    }
    
    @Test
    void testDetectAccountName_Constructed() {
        String filename = "chase_checking_1234.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertNotNull(result.getAccountName());
        assertTrue(result.getAccountName().contains("chase"));
    }
    
    @Test
    void testDetectAccount_Comprehensive_USFormat() {
        String filename = "chase_checking_1234_statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number ending in: 1234", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****1234", "100.00", "Check #5678 - Payment")
        );
        Map<String, String> metadata = new HashMap<>();
        metadata.put("title", "Chase Bank Statement");
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("chase", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
        assertNotNull(result.getAccountName());
    }
    
    @Test
    void testDetectAccount_Comprehensive_UKFormat() {
        String filename = "hsbc_current_account_GB82WEST12345698765432.csv";
        List<String> headers = Arrays.asList("Date", "IBAN", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "GB82WEST12345698765432", "100.00", "Payment")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("hsbc", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_Comprehensive_IndianFormat() {
        String filename = "sbi_savings_5678_statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "5678", "100.00", "Deposit")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("state bank of india", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_Comprehensive_ChineseFormat() {
        String filename = "icbc_活期账户_1234.csv";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "100.00", "Transaction")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("industrial and commercial bank of china", result.getInstitutionName());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_Comprehensive_JapaneseFormat() {
        String filename = "mufg_当座預金_1234.csv";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "100.00", "Transaction")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
        assertEquals("depository", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_Comprehensive_CreditCard() {
        String filename = "amex_credit_card_5678.csv";
        List<String> headers = Arrays.asList("Date", "Card Number ending in: 5678", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "****5678", "100.00", "Purchase")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("5678", result.getAccountNumber());
        assertEquals("american express", result.getInstitutionName());
        assertEquals("credit", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_Comprehensive_Investment() {
        String filename = "fidelity_401k_statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount", "Description");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "123456789", "1000.00", "Contribution")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        assertEquals("fidelity", result.getInstitutionName());
        assertEquals("investment", result.getAccountType());
    }
    
    @Test
    void testDetectAccount_EdgeCase_NoData() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Amount", "Description");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should still work with just filename
    }
    
    @Test
    void testDetectAccount_EdgeCase_EmptyFilename() {
        String filename = "";
        List<String> headers = Arrays.asList("Date", "Account Number", "Amount");
        List<List<String>> dataRows = Arrays.asList(
            Arrays.asList("2024-01-01", "1234", "100.00")
        );
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should work with headers/data
    }
    
    @Test
    void testDetectAccount_EdgeCase_MultipleAccountNumbers() {
        String filename = "statement.csv";
        List<String> headers = Arrays.asList("Date", "Account Number: 1234", "Amount", "Description: Account ending 5678");
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        var result = detectionService.detectAccount(filename, headers, dataRows, metadata);
        assertNotNull(result);
        // Should pick the one with highest confidence
        assertNotNull(result.getAccountNumber());
    }
}

