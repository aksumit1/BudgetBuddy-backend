package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

/**
 * Tests for "Send general inquiries to" false positive in account holder name extraction
 */
@DisplayName("AccountDetectionService - Send General Inquiries False Positive Test")
public class AccountDetectionServiceSendInquiriesTest {
    
    private AccountDetectionService accountDetectionService;
    private Method extractAccountHolderNameFromPDF;

    @BeforeEach
    void setUp() throws Exception {
        // Create mock for required dependency
        com.budgetbuddy.repository.dynamodb.AccountRepository mockAccountRepository = 
            org.mockito.Mockito.mock(com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        
        accountDetectionService = new AccountDetectionService(mockAccountRepository);
        
        extractAccountHolderNameFromPDF = AccountDetectionService.class.getDeclaredMethod(
            "extractAccountHolderNameFromPDF", String.class);
        extractAccountHolderNameFromPDF.setAccessible(true);
    }

    @Test
    @DisplayName("Should reject 'Send general inquiries to' as account holder name")
    void testFalsePositive_SendGeneralInquiriesTo() throws Exception {
        String headerText = "Send general inquiries to\nAccount Number: 1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(
            accountDetectionService, headerText);
        
        assertNull(name, "Should reject 'Send general inquiries to' as account holder name");
    }

    @Test
    @DisplayName("Should reject 'general inquiries' as account holder name")
    void testFalsePositive_GeneralInquiries() throws Exception {
        String headerText = "general inquiries\nAccount Number: 1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(
            accountDetectionService, headerText);
        
        assertNull(name, "Should reject 'general inquiries' as account holder name");
    }

    @Test
    @DisplayName("Should reject phrases starting with 'send' and containing 'inquir'")
    void testFalsePositive_SendInquiriesPattern() throws Exception {
        String headerText = "Send inquiries to\nAccount Number: 1234";
        String name = (String) extractAccountHolderNameFromPDF.invoke(
            accountDetectionService, headerText);
        
        assertNull(name, "Should reject 'Send inquiries to' as account holder name");
    }
}

