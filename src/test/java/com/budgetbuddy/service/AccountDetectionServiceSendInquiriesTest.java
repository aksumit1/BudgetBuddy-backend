package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for "Send general inquiries to" false positive in account holder name extraction */
@DisplayName("AccountDetectionService - Send General Inquiries False Positive Test")
public class AccountDetectionServiceSendInquiriesTest {

    private AccountDetectionService accountDetectionService;
    private Method extractAccountHolderNameFromPDF;

    @BeforeEach
    void setUp() throws Exception {
        // Create mock for required dependency
        final com.budgetbuddy.repository.dynamodb.AccountRepository mockAccountRepository =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);

        accountDetectionService =
                new AccountDetectionService(
                        mockAccountRepository, new com.budgetbuddy.service.BalanceExtractor());

        extractAccountHolderNameFromPDF =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractAccountHolderNameFromPDF", String.class);
        extractAccountHolderNameFromPDF.setAccessible(true);
    }

    @Test
    @DisplayName("Should reject 'Send general inquiries to' as account holder name")
    void testFalsePositiveSendGeneralInquiriesTo() throws Exception {
        final String headerText = "Send general inquiries to\nAccount Number: 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);

        assertNull(name, "Should reject 'Send general inquiries to' as account holder name");
    }

    @Test
    @DisplayName("Should reject 'general inquiries' as account holder name")
    void testFalsePositiveGeneralInquiries() throws Exception {
        final String headerText = "general inquiries\nAccount Number: 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);

        assertNull(name, "Should reject 'general inquiries' as account holder name");
    }

    @Test
    @DisplayName("Should reject phrases starting with 'send' and containing 'inquir'")
    void testFalsePositiveSendInquiriesPattern() throws Exception {
        final String headerText = "Send inquiries to\nAccount Number: 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);

        assertNull(name, "Should reject 'Send inquiries to' as account holder name");
    }
}
