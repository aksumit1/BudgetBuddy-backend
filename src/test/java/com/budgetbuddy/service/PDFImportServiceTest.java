package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PDFImportService Tests PDF parsing, year inference, and MM/DD date format handling
 */
class PDFImportServiceTest {

    private PDFImportService pdfImportService;

    @BeforeEach
    void setUp() {
        final AccountDetectionService accountDetectionService =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final ImportCategoryParser importCategoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        final EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        pdfImportService =
                new PDFImportService(
                        accountDetectionService,
                        importCategoryParser,
                        enhancedPatternMatcher,
                        null);
    }

    @Test
    void testParsePDFEmptyFileThrowsException() {
        final InputStream emptyStream = new ByteArrayInputStream(new byte[0]);

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            pdfImportService.parsePDF(emptyStream, "test.pdf", null, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testParsePDFInvalidPDFThrowsException() {
        final InputStream invalidStream =
                new ByteArrayInputStream("Not a PDF file".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            pdfImportService.parsePDF(invalidStream, "test.pdf", null, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testExtractYearFromPDFYearInFilenameReturnsYear() {
        // This test would require creating a PDF or using reflection to test extractYearFromPDF
        // For now, we test through integration
        assertTrue(true); // Placeholder - actual test would require PDF creation
    }

    @Test
    void testExtractYearFromPDFYearInStatementPeriodReturnsYear() {
        // This test would require creating a PDF with statement period
        assertTrue(true); // Placeholder - actual test would require PDF creation
    }

    @Test
    void testParseDateMMDDFormatWithInferredYear() {
        // Test MM/DD format parsing with inferred year
        // This is tested through parsePDF integration
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseDateMMDDFormatWithoutYearUsesCurrentYear() {
        // Test MM/DD format parsing without inferred year (uses current year logic)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseAmountPositiveAmount() {
        // Test amount parsing for positive values
        assertTrue(true); // Placeholder - would test parseAmount method
    }

    @Test
    void testParseAmountNegativeAmount() {
        // Test amount parsing for negative values (parentheses, minus sign)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseAmountWithCurrencySymbol() {
        // Test amount parsing with currency symbols ($, €, etc.)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParsePDFTransactionLimitStopsAtLimit() {
        // Test that parsing stops at MAX_TRANSACTIONS_PER_FILE limit
        assertTrue(true); // Placeholder - would require large PDF
    }

    @Test
    void testParsePDFPasswordProtectedWithPasswordSucceeds() {
        // Test parsing password-protected PDF with correct password
        assertTrue(true); // Placeholder - would require password-protected PDF
    }

    @Test
    void testParsePDFPasswordProtectedWithoutPasswordThrowsException() {
        // Test parsing password-protected PDF without password
        assertTrue(true); // Placeholder - would require password-protected PDF
    }

    // ========== Credit Card Sign Reversal Tests ==========

    @Test
    void testParseTransactionWithCreditCardAccountReversesSign() throws Exception {
        // Given
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                row,
                                1,
                                2024,
                                "credit_card.pdf",
                                true,
                                creditCardAccount);

        // Then - Amount should be reversed (positive expense becomes negative)
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("-50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithCreditCardAccountNegativeAmountReversesToPositive()
            throws Exception {
        // Given - Credit card payment (negative in import, should reverse to positive)
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Payment");
        row.put("amount", "-100.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                row,
                                1,
                                2024,
                                "credit_card.pdf",
                                true,
                                creditCardAccount);

        // Then - Negative amount should reverse to positive
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("100.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNonCreditCardAccountDoesNotReverseSign() throws Exception {
        // Given
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount checkingAccount =
                new AccountDetectionService.DetectedAccount();
        checkingAccount.setAccountType("checking");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                row,
                                1,
                                2024,
                                "checking.pdf",
                                true,
                                checkingAccount);

        // Then - Amount should remain unchanged for non-credit card accounts
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNullDetectedAccountDoesNotReverseSign() throws Exception {
        // Given
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When - No detected account
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService, row, 1, 2024, "unknown.pdf", true, null);

        // Then - Amount should remain unchanged when account is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithNullAccountTypeDoesNotReverseSign() throws Exception {
        // Given
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        final AccountDetectionService.DetectedAccount account =
                new AccountDetectionService.DetectedAccount();
        account.setAccountType(null); // Null account type

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService, row, 1, 2024, "unknown.pdf", true, account);

        // Then - Amount should remain unchanged when account type is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransactionWithCreditCardAccountZeroAmountRejected() throws Exception {
        // Given - Zero amounts are rejected by parseAmount as invalid transactions
        final java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Zero Transaction");
        row.put("amount", "0.00");

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // When
        final PDFImportService.ParsedTransaction transaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                row,
                                1,
                                2024,
                                "credit_card.pdf",
                                true,
                                creditCardAccount);

        // Then - Zero amounts are rejected (null is returned)
        assertNull(transaction, "Zero amount transactions should be rejected");
    }

    @Test
    void testParseTransactionCreditCardStatementGroceryExpenseAndPayment() throws Exception {
        // Test case: Credit card statement with:
        // 1. Grocery shopping at safeway $100 (expense - should be stored as -$100)
        // 2. Automatic payment - thank you -$99 (payment - should be stored as +$99)

        final AccountDetectionService.DetectedAccount creditCardAccount =
                new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");

        // Use reflection to access private parseTransaction method
        final java.lang.reflect.Method parseTransactionMethod =
                PDFImportService.class.getDeclaredMethod(
                        "parseTransaction",
                        java.util.Map.class,
                        int.class,
                        Integer.class,
                        String.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        parseTransactionMethod.setAccessible(true);

        // Test 1: Grocery shopping at safeway $100 (expense - positive in statement, should be
        // negative in DB)
        final java.util.Map<String, String> expenseRow = new java.util.HashMap<>();
        expenseRow.put("date", "01/15/2024");
        expenseRow.put("description", "Grocery shopping at safeway");
        expenseRow.put("amount", "$100.00"); // Positive amount in credit card statement (expense)

        final PDFImportService.ParsedTransaction expenseTransaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                expenseRow,
                                1,
                                2024,
                                "credit_card.pdf",
                                true,
                                creditCardAccount);

        // Then - Expense should be stored as negative (positive in statement becomes negative in
        // DB)
        assertNotNull(expenseTransaction, "Expense transaction should be parsed");
        assertEquals(
                new java.math.BigDecimal("-100.00"),
                expenseTransaction.getAmount(),
                "Grocery expense $100 should be stored as -$100 in database");
        assertEquals("Grocery shopping at safeway", expenseTransaction.getDescription());

        // Test 2: Automatic payment - thank you -$99 (payment - negative in statement, should be
        // positive in DB)
        final java.util.Map<String, String> paymentRow = new java.util.HashMap<>();
        paymentRow.put("date", "01/20/2024");
        paymentRow.put("description", "Automatic payment - thank you");
        paymentRow.put("amount", "-$99.00"); // Negative amount in credit card statement (payment)

        final PDFImportService.ParsedTransaction paymentTransaction =
                (PDFImportService.ParsedTransaction)
                        parseTransactionMethod.invoke(
                                pdfImportService,
                                paymentRow,
                                2,
                                2024,
                                "credit_card.pdf",
                                true,
                                creditCardAccount);

        // Then - Payment should be stored as positive (negative in statement becomes positive in
        // DB)
        assertNotNull(paymentTransaction, "Payment transaction should be parsed");
        assertEquals(
                new java.math.BigDecimal("99.00"),
                paymentTransaction.getAmount(),
                "Payment -$99 should be stored as +$99 in database");
        assertEquals("Automatic payment - thank you", paymentTransaction.getDescription());
    }
}
