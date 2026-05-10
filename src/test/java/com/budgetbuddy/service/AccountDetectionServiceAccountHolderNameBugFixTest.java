package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for specific bug fixes in account holder name extraction */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@DisplayName("AccountDetectionService Account Holder Name - Bug Fix Tests")
public class AccountDetectionServiceAccountHolderNameBugFixTest {

    private AccountDetectionService accountDetectionService;
    private Method extractAccountHolderNameFromPDF;

    @BeforeEach
    void setUp() throws Exception {
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
    @DisplayName(
            "Should reject false positive: 'and account number' from 'account information: your name and account number.'")
    void testFalsePositiveAndAccountNumber() throws Exception {
        final String headerText =
                "account information: your name and account number.\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject 'and account number' as a name");
    }

    @Test
    @DisplayName("Should reject false positive: 'your name and account number'")
    void testFalsePositiveYourNameAndAccountNumber() throws Exception {
        final String headerText = "your name and account number\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNull(name, "Should reject 'your name and account number' as a name");
    }

    @Test
    @DisplayName("Should reject false positive: lines containing 'account information'")
    void testFalsePositiveAccountInformation() throws Exception {
        final String headerText =
                "Account Information: Your Name and Account Number.\nCard Member: John Doe";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        // Should extract "John Doe", not "Your Name and Account Number"
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name from 3-line address format: name, street, city state ZIP")
    void testThreeLineAddressFormat() throws Exception {
        final String headerText = "Roger A Fernandes\n12345 NE 17ST ST\nSEATTLE  WA  91114-3211";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from 3-line address format");
        assertEquals("Roger A Fernandes", name);
    }

    @Test
    @DisplayName("Should extract name from 3-line address format with different ZIP format")
    void testThreeLineAddressFormatZIPPlus4() throws Exception {
        final String headerText = "John Doe\n123 Main Street\nNew York NY 10001-1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from 3-line address format with ZIP+4");
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name from 3-line address format with space in ZIP+4")
    void testThreeLineAddressFormatZIPPlus4Space() throws Exception {
        final String headerText = "Jane Smith\n456 Oak Avenue\nLos Angeles CA 90001 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from 3-line address format with space in ZIP+4");
        assertEquals("Jane Smith", name);
    }

    @Test
    @DisplayName("Should extract name from 2-line address format: name, full address")
    void testTwoLineAddressFormat() throws Exception {
        final String headerText = "Mary Johnson\n123 Main Street, New York, NY 10001";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from 2-line address format");
        assertEquals("Mary Johnson", name);
    }

    @Test
    @DisplayName("Should still work with standard patterns after adding exclusion filters")
    void testStandardPatternStillWorks() throws Exception {
        final String headerText = "Card Member: John Doe\nAccount Number: XXXX1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name);
        assertEquals("John Doe", name);
    }

    // ========== Additional False Negative Patterns ==========

    @Test
    @DisplayName("Should extract name from same line before 'Card ending in' (no colon)")
    void testNameSameLineCardEndingInNoColon() throws Exception {
        final String headerText = "Roger A Fernandes  Card ending in 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from same line before 'Card ending in'");
        assertEquals("Roger A Fernandes", name);
    }

    @Test
    @DisplayName("Should extract name from same line before 'Card ending in' (uppercase)")
    void testNameSameLineCardEndingInUppercase() throws Exception {
        final String headerText = "SAKINA AHMED  Card ending in 5678";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from same line before 'Card ending in'");
        assertEquals("SAKINA AHMED", name);
    }

    @Test
    @DisplayName("Should extract name from previous line before 'Card Ending' with account number")
    void testNameBeforeCardEndingWithAccountNumber() throws Exception {
        final String headerText =
                "SAKINA PHILIPS AHMED\nCard Ending 7-12345   Monthly Spending Limit: $1,500";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from previous line before 'Card Ending'");
        assertEquals("SAKINA PHILIPS AHMED", name);
    }

    @Test
    @DisplayName("Should extract name from same line before account number pattern (1-23046)")
    void testNameSameLineAccountNumberPattern() throws Exception {
        final String headerText = "SAKINA PHILIPS AHMED 1-23046 $0.00 $1.11 $2.21";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from same line before account number pattern");
        assertEquals("SAKINA PHILIPS AHMED", name);
    }

    @Test
    @DisplayName(
            "Should extract name from previous line before 'Closing Date' and 'Account Ending'")
    void testNameBeforeClosingDateAccountEnding() throws Exception {
        final String headerText =
                "Morgan Stanley Platinum Card®\nROGER PHILIPS FERNANDES\nClosing Date 12/12/25 Account Ending 1-23456";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(
                name,
                "Should extract name from previous line before 'Closing Date' and 'Account Ending'");
        assertEquals("ROGER PHILIPS FERNANDES", name);
    }

    @Test
    @DisplayName("Should extract name from 3-line address format (Roger A Fernandes)")
    void testThreeLineAddressFormatRogerFernandes() throws Exception {
        final String headerText = "Roger A Fernandes\n12345 NE 17ST ST\nSEATTLE  WA  91114-3211";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from 3-line address format");
        assertEquals("Roger A Fernandes", name);
    }

    @Test
    @DisplayName("Should extract name before 'Card Ending' (no 'in')")
    void testNameBeforeCardEndingNoIn() throws Exception {
        final String headerText = "John Doe\nCard Ending 1234";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name before 'Card Ending'");
        assertEquals("John Doe", name);
    }

    @Test
    @DisplayName("Should extract name before 'Account Ending' with hyphenated number")
    void testNameBeforeAccountEndingHyphenatedNumber() throws Exception {
        final String headerText = "Jane Smith\nAccount Ending 1-23456";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name before 'Account Ending' with hyphenated number");
        assertEquals("Jane Smith", name);
    }

    @Test
    @DisplayName("Should extract name from same line before account number with hyphen (1-23046)")
    void testNameSameLineAccountNumberHyphen() throws Exception {
        final String headerText = "Robert Brown 1-23046";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name from same line before hyphenated account number");
        assertEquals("Robert Brown", name);
    }

    @Test
    @DisplayName("Should extract name before 'Closing Date' line")
    void testNameBeforeClosingDate() throws Exception {
        final String headerText = "Alice Johnson\nClosing Date 12/12/25";
        final String name =
                (String)
                        extractAccountHolderNameFromPDF.invoke(accountDetectionService, headerText);
        assertNotNull(name, "Should extract name before 'Closing Date'");
        assertEquals("Alice Johnson", name);
    }
}
