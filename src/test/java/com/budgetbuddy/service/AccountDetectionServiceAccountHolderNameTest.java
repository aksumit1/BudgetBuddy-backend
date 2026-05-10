package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for account holder name extraction from PDF in AccountDetectionService */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@DisplayName("AccountDetectionService Account Holder Name Extraction Tests")
public class AccountDetectionServiceAccountHolderNameTest {

    private AccountDetectionService accountDetectionService;
    private Method extractAccountHolderNameFromPDF;

    @BeforeEach
    void setUp() throws Exception {
        // Create mock for required dependency
        final com.budgetbuddy.repository.dynamodb.AccountRepository mockAccountRepository =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);

        // AccountDetectionService requires AccountRepository and BalanceExtractor
        accountDetectionService =
                new AccountDetectionService(
                        mockAccountRepository, new com.budgetbuddy.service.BalanceExtractor());

        extractAccountHolderNameFromPDF =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractAccountHolderNameFromPDF", String.class);
        extractAccountHolderNameFromPDF.setAccessible(true);
    }

    @Test
    @DisplayName("Should extract name from Card Member pattern")
    void testExtractCardMember() throws Exception {
        final String headerText = "Card Member: John Doe\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name before Address")
    void testExtractNameBeforeAddress() throws Exception {
        final String headerText = "John Doe\n123 Main Street\nNew York, NY 10001";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name before Member since")
    void testExtractNameBeforeMemberSince() throws Exception {
        final String headerText = "Jane Smith\nMember since 2015";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Jane Smith", name);
    }

    @Test
    @DisplayName("Should extract name before Account number")
    void testExtractNameBeforeAccountNumber() throws Exception {
        final String headerText = "Mary Johnson\nAccount Number: XXXX5678";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Mary Johnson", name);
    }

    @Test
    @DisplayName("Should extract name before Card ending in")
    void testExtractNameBeforeCardEnding() throws Exception {
        final String headerText = "Robert Williams\nCard ending in 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Robert Williams", name);
    }

    @Test
    @DisplayName("Should extract name from same line before Account Number")
    void testExtractNameSameLineAccountNumber() throws Exception {
        final String headerText = "David Brown Account Number: XXXX9012";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("David Brown", name);
    }

    @Test
    @DisplayName("Should extract name from same line before Card number")
    void testExtractNameSameLineCardNumber() throws Exception {
        final String headerText = "Sarah Davis Card ending in 3456";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Sarah Davis", name);
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'sale'")
    void testRejectExcludedWordSale() throws Exception {
        final String headerText = "John Sale\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'sale'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'date'")
    void testRejectExcludedWordDate() throws Exception {
        final String headerText = "Transaction Date\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'date'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'description'")
    void testRejectExcludedWordDescription() throws Exception {
        final String headerText = "John Description\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'description'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'amount'")
    void testRejectExcludedWordAmount() throws Exception {
        final String headerText = "Payment Amount\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'amount'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'payments'")
    void testRejectExcludedWordPayments() throws Exception {
        final String headerText = "John Payments\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'payments'");
    }

    @Test
    @DisplayName("Should reject names containing excluded words - 'summary'")
    void testRejectExcludedWordSummary() throws Exception {
        final String headerText = "Account Summary\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing 'summary'");
    }

    @Test
    @DisplayName("Should allow valid names even if they contain similar words")
    void testAllowValidNames() throws Exception {
        final String headerText = "John Doe\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract valid name 'John Doe'");
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name with ZIP code in next line")
    void testExtractNameBeforeZIPCode() throws Exception {
        final String headerText = "Michael Wilson\n12345 Main St\nNew York NY 10001";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Michael Wilson", name);
    }

    @Test
    @DisplayName("Should extract name before Customer since")
    void testExtractNameBeforeCustomerSince() throws Exception {
        final String headerText = "Lisa Anderson\nCustomer since 2018";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Lisa Anderson", name);
    }

    @Test
    @DisplayName("Should extract name before Account ending")
    void testExtractNameBeforeAccountEnding() throws Exception {
        final String headerText = "Thomas Moore\nAccount ending in 7890";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Thomas Moore", name);
    }

    @Test
    @DisplayName("Should handle names with middle initials")
    void testExtractNameWithMiddleInitial() throws Exception {
        final String headerText = "John M Doe\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John M Doe", name);
    }

    @Test
    @DisplayName("Should handle hyphenated names")
    void testExtractHyphenatedName() throws Exception {
        final String headerText = "Mary-Jane Smith\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("Mary-Jane Smith", name);
    }

    @Test
    @DisplayName("Should handle names with apostrophes")
    void testExtractNameWithApostrophe() throws Exception {
        final String headerText = "O'Brien\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("O'Brien", name);
    }

    @Test
    @DisplayName("Should reject names containing dates")
    void testRejectNameWithDate() throws Exception {
        final String headerText = "John 12/25/2025\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing date pattern");
    }

    @Test
    @DisplayName("Should reject names containing phone numbers")
    void testRejectNameWithPhoneNumber() throws Exception {
        final String headerText = "John 800-555-1234\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject name containing phone number");
    }
}
