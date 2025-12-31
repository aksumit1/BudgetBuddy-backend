package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

/**
 * Tests for account holder name extraction from PDF in AccountDetectionService
 */
@DisplayName("AccountDetectionService Account Holder Name Extraction Tests")
public class AccountDetectionServiceAccountHolderNameTest {

    private AccountDetectionService accountDetectionService;
    private Method extractAccountHolderNameFromPDF;

    @BeforeEach
    void setUp() throws Exception {
        // Create mock for required dependency
        com.budgetbuddy.repository.dynamodb.AccountRepository mockAccountRepository = 
            org.mockito.Mockito.mock(com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        
        // AccountDetectionService only requires AccountRepository
        accountDetectionService = new AccountDetectionService(mockAccountRepository);
        
        extractAccountHolderNameFromPDF = AccountDetectionService.class.getDeclaredMethod(
            "extractAccountHolderNameFromPDF", String.class);
        extractAccountHolderNameFromPDF.setAccessible(true);
    }

    @Test
    @DisplayName("Should extract name from Card Member pattern")
    void testExtractCardMember() throws Exception {
        String headerText = "Card Member: John Doe\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name before Address")
    void testExtractNameBeforeAddress() throws Exception {
        String headerText = "John Doe\n123 Main Street\nNew York, NY 10001";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name before Member since")
    void testExtractNameBeforeMemberSince() throws Exception {
        String headerText = "Jane Smith\nMember since 2015";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Jane Smith", name);
    }

    @Test
    @DisplayName("Should extract name before Account number")
    void testExtractNameBeforeAccountNumber() throws Exception {
        String headerText = "Mary Johnson\nAccount Number: XXXX5678";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Mary Johnson", name);
    }

    @Test
    @DisplayName("Should extract name before Card ending in")
    void testExtractNameBeforeCardEnding() throws Exception {
        String headerText = "Robert Williams\nCard ending in 1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Robert Williams", name);
    }

    @Test
    @DisplayName("Should extract name from same line before Account Number")
    void testExtractNameSameLineAccountNumber() throws Exception {
        String headerText = "David Brown Account Number: XXXX9012";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("David Brown", name);
    }

    @Test
    @DisplayName("Should extract name from same line before Card number")
    void testExtractNameSameLineCardNumber() throws Exception {
        String headerText = "Sarah Davis Card ending in 3456";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Sarah Davis", name);
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'sale'")
    void testRejectExcludedWord_Sale() throws Exception {
        String headerText = "John Sale\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'sale'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'date'")
    void testRejectExcludedWord_Date() throws Exception {
        String headerText = "Transaction Date\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'date'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'description'")
    void testRejectExcludedWord_Description() throws Exception {
        String headerText = "John Description\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'description'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'amount'")
    void testRejectExcludedWord_Amount() throws Exception {
        String headerText = "Payment Amount\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'amount'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'payments'")
    void testRejectExcludedWord_Payments() throws Exception {
        String headerText = "John Payments\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'payments'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'summary'")
    void testRejectExcludedWord_Summary() throws Exception {
        String headerText = "Account Summary\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'summary'");
    }

    @Test
    @DisplayName("Should allow valid names even if they contain similar words")
    void testAllowValidNames() throws Exception {
        String headerText = "John Doe\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract valid name 'John Doe'");
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name with ZIP code in next line")
    void testExtractNameBeforeZIPCode() throws Exception {
        String headerText = "Michael Wilson\n12345 Main St\nNew York NY 10001";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Michael Wilson", name);
    }

    @Test
    @DisplayName("Should extract name before Customer since")
    void testExtractNameBeforeCustomerSince() throws Exception {
        String headerText = "Lisa Anderson\nCustomer since 2018";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Lisa Anderson", name);
    }

    @Test
    @DisplayName("Should extract name before Account ending")
    void testExtractNameBeforeAccountEnding() throws Exception {
        String headerText = "Thomas Moore\nAccount ending in 7890";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Thomas Moore", name);
    }

    @Test
    @DisplayName("Should handle names with middle initials")
    void testExtractNameWithMiddleInitial() throws Exception {
        String headerText = "John M Doe\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John M Doe", name);
    }

    @Test
    @DisplayName("Should handle hyphenated names")
    void testExtractHyphenatedName() throws Exception {
        String headerText = "Mary-Jane Smith\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Mary-Jane Smith", name);
    }

    @Test
    @DisplayName("Should handle names with apostrophes")
    void testExtractNameWithApostrophe() throws Exception {
        String headerText = "O'Brien\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("O'Brien", name);
    }

    @Test
    @DisplayName("Should reject names containing dates")
    void testRejectNameWithDate() throws Exception {
        String headerText = "John 12/25/2025\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing date pattern");
    }

    @Test
    @DisplayName("Should reject names containing phone numbers")
    void testRejectNameWithPhoneNumber() throws Exception {
        String headerText = "John 800-555-1234\nAccount Number: XXXX1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing phone number");
    }
}

