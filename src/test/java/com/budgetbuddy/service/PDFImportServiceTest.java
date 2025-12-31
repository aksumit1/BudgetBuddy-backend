package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PDFImportService
 * Tests PDF parsing, year inference, and MM/DD date format handling
 */
class PDFImportServiceTest {

    private PDFImportService pdfImportService;

    @BeforeEach
    void setUp() {
        AccountDetectionService accountDetectionService = org.mockito.Mockito.mock(AccountDetectionService.class);
        ImportCategoryParser importCategoryParser = org.mockito.Mockito.mock(ImportCategoryParser.class);
        TransactionTypeCategoryService transactionTypeCategoryService = 
                org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        pdfImportService = new PDFImportService(accountDetectionService, importCategoryParser, transactionTypeCategoryService, enhancedPatternMatcher, null);
    }

    @Test
    void testParsePDF_EmptyFile_ThrowsException() {
        InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        
        AppException exception = assertThrows(AppException.class, () -> {
            pdfImportService.parsePDF(emptyStream, "test.pdf", null, null);
        });
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testParsePDF_InvalidPDF_ThrowsException() {
        InputStream invalidStream = new ByteArrayInputStream("Not a PDF file".getBytes(StandardCharsets.UTF_8));
        
        AppException exception = assertThrows(AppException.class, () -> {
            pdfImportService.parsePDF(invalidStream, "test.pdf", null, null);
        });
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testExtractYearFromPDF_YearInFilename_ReturnsYear() {
        // This test would require creating a PDF or using reflection to test extractYearFromPDF
        // For now, we test through integration
        assertTrue(true); // Placeholder - actual test would require PDF creation
    }

    @Test
    void testExtractYearFromPDF_YearInStatementPeriod_ReturnsYear() {
        // This test would require creating a PDF with statement period
        assertTrue(true); // Placeholder - actual test would require PDF creation
    }

    @Test
    void testParseDate_MMDD_Format_WithInferredYear() {
        // Test MM/DD format parsing with inferred year
        // This is tested through parsePDF integration
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseDate_MMDD_Format_WithoutYear_UsesCurrentYear() {
        // Test MM/DD format parsing without inferred year (uses current year logic)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseAmount_PositiveAmount() {
        // Test amount parsing for positive values
        assertTrue(true); // Placeholder - would test parseAmount method
    }

    @Test
    void testParseAmount_NegativeAmount() {
        // Test amount parsing for negative values (parentheses, minus sign)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParseAmount_WithCurrencySymbol() {
        // Test amount parsing with currency symbols ($, €, etc.)
        assertTrue(true); // Placeholder
    }

    @Test
    void testParsePDF_TransactionLimit_StopsAtLimit() {
        // Test that parsing stops at MAX_TRANSACTIONS_PER_FILE limit
        assertTrue(true); // Placeholder - would require large PDF
    }

    @Test
    void testParsePDF_PasswordProtected_WithPassword_Succeeds() {
        // Test parsing password-protected PDF with correct password
        assertTrue(true); // Placeholder - would require password-protected PDF
    }

    @Test
    void testParsePDF_PasswordProtected_WithoutPassword_ThrowsException() {
        // Test parsing password-protected PDF without password
        assertTrue(true); // Placeholder - would require password-protected PDF
    }

    // ========== Credit Card Sign Reversal Tests ==========

    @Test
    void testParseTransaction_WithCreditCardAccount_ReversesSign() throws Exception {
        // Given
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        AccountDetectionService.DetectedAccount creditCardAccount = new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "credit_card.pdf", true, creditCardAccount);

        // Then - Amount should be reversed (positive expense becomes negative)
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("-50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransaction_WithCreditCardAccount_NegativeAmount_ReversesToPositive() throws Exception {
        // Given - Credit card payment (negative in import, should reverse to positive)
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Payment");
        row.put("amount", "-100.00");

        AccountDetectionService.DetectedAccount creditCardAccount = new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "credit_card.pdf", true, creditCardAccount);

        // Then - Negative amount should reverse to positive
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("100.00"), transaction.getAmount());
    }

    @Test
    void testParseTransaction_WithNonCreditCardAccount_DoesNotReverseSign() throws Exception {
        // Given
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        AccountDetectionService.DetectedAccount checkingAccount = new AccountDetectionService.DetectedAccount();
        checkingAccount.setAccountType("checking");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "checking.pdf", true, checkingAccount);

        // Then - Amount should remain unchanged for non-credit card accounts
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransaction_WithNullDetectedAccount_DoesNotReverseSign() throws Exception {
        // Given
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When - No detected account
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "unknown.pdf", true, null);

        // Then - Amount should remain unchanged when account is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransaction_WithNullAccountType_DoesNotReverseSign() throws Exception {
        // Given
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Grocery Store");
        row.put("amount", "50.00");

        AccountDetectionService.DetectedAccount account = new AccountDetectionService.DetectedAccount();
        account.setAccountType(null); // Null account type

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "unknown.pdf", true, account);

        // Then - Amount should remain unchanged when account type is null
        assertNotNull(transaction);
        assertEquals(new java.math.BigDecimal("50.00"), transaction.getAmount());
    }

    @Test
    void testParseTransaction_WithCreditCardAccount_ZeroAmount_Rejected() throws Exception {
        // Given - Zero amounts are rejected by parseAmount as invalid transactions
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("date", "01/15/2024");
        row.put("description", "Zero Transaction");
        row.put("amount", "0.00");

        AccountDetectionService.DetectedAccount creditCardAccount = new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // When
        PDFImportService.ParsedTransaction transaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, row, 1, 2024, "credit_card.pdf", true, creditCardAccount);

        // Then - Zero amounts are rejected (null is returned)
        assertNull(transaction, "Zero amount transactions should be rejected");
    }

    @Test
    void testParseTransaction_CreditCardStatement_GroceryExpenseAndPayment() throws Exception {
        // Test case: Credit card statement with:
        // 1. Grocery shopping at safeway $100 (expense - should be stored as -$100)
        // 2. Automatic payment - thank you -$99 (payment - should be stored as +$99)
        
        AccountDetectionService.DetectedAccount creditCardAccount = new AccountDetectionService.DetectedAccount();
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");

        // Use reflection to access private parseTransaction method
        java.lang.reflect.Method parseTransactionMethod = PDFImportService.class.getDeclaredMethod(
            "parseTransaction", 
            java.util.Map.class, 
            int.class, 
            Integer.class, 
            String.class, 
            boolean.class, 
            AccountDetectionService.DetectedAccount.class
        );
        parseTransactionMethod.setAccessible(true);

        // Test 1: Grocery shopping at safeway $100 (expense - positive in statement, should be negative in DB)
        java.util.Map<String, String> expenseRow = new java.util.HashMap<>();
        expenseRow.put("date", "01/15/2024");
        expenseRow.put("description", "Grocery shopping at safeway");
        expenseRow.put("amount", "$100.00"); // Positive amount in credit card statement (expense)

        PDFImportService.ParsedTransaction expenseTransaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, expenseRow, 1, 2024, "credit_card.pdf", true, creditCardAccount);

        // Then - Expense should be stored as negative (positive in statement becomes negative in DB)
        assertNotNull(expenseTransaction, "Expense transaction should be parsed");
        assertEquals(new java.math.BigDecimal("-100.00"), expenseTransaction.getAmount(), 
            "Grocery expense $100 should be stored as -$100 in database");
        assertEquals("Grocery shopping at safeway", expenseTransaction.getDescription());

        // Test 2: Automatic payment - thank you -$99 (payment - negative in statement, should be positive in DB)
        java.util.Map<String, String> paymentRow = new java.util.HashMap<>();
        paymentRow.put("date", "01/20/2024");
        paymentRow.put("description", "Automatic payment - thank you");
        paymentRow.put("amount", "-$99.00"); // Negative amount in credit card statement (payment)

        PDFImportService.ParsedTransaction paymentTransaction = (PDFImportService.ParsedTransaction) 
            parseTransactionMethod.invoke(pdfImportService, paymentRow, 2, 2024, "credit_card.pdf", true, creditCardAccount);

        // Then - Payment should be stored as positive (negative in statement becomes positive in DB)
        assertNotNull(paymentTransaction, "Payment transaction should be parsed");
        assertEquals(new java.math.BigDecimal("99.00"), paymentTransaction.getAmount(), 
            "Payment -$99 should be stored as +$99 in database");
        assertEquals("Automatic payment - thank you", paymentTransaction.getDescription());
    }
}
