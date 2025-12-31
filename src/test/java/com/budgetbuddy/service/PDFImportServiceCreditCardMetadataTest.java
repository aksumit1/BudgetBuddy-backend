package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for credit card metadata extraction from PDF imports:
 * - Payment due date
 * - Minimum payment due
 * - Reward points
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PDFImportServiceCreditCardMetadataTest {

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

    // ========== Payment Due Date Tests ==========

    @Test
    void testExtractPaymentDueDate_StandardFormat_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Payment due date: 01/15/2024"
        String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractPaymentDueDate method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractPaymentDueDate", 
            String[].class, 
            Integer.class, 
            boolean.class
        );
        method.setAccessible(true);
        
        // When
        LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);
        
        // Then
        assertNotNull(result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }

    @Test
    void testExtractPaymentDueDate_VariousFormats_AllExtracted() throws Exception {
        String[] formats = {
            "Payment due date: 01/15/2024",
            "Payment due date 01/15/2024",
            "Due date: 01/15/2024",
            "Due date 01/15/2024",
            "Payment due: 01/15/2024",
            "Due: 01/15/2024",
            "Payment due on 01/15/2024",
            "Due on 01/15/2024"
        };
        
        // Use reflection to access private extractPaymentDueDate method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractPaymentDueDate", 
            String[].class, 
            Integer.class, 
            boolean.class
        );
        method.setAccessible(true);
        
        for (String format : formats) {
            String[] lines = {
                "Credit Card Statement",
                format,
                "Date Description Amount",
                "01/10/2024 Grocery Store $50.00"
            };
            
            // When
            LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);
            
            // Then
            assertNotNull(result, "Should extract date from format: " + format);
            assertEquals(LocalDate.of(2024, 1, 15), result, 
                "Date should be 01/15/2024 for format: " + format);
        }
    }

    @Test
    void testExtractPaymentDueDate_CaseInsensitive_ExtractsCorrectly() throws Exception {
        // Given - PDF text with various case combinations
        String[] lines = {
            "Credit Card Statement",
            "PAYMENT DUE DATE: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractPaymentDueDate method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractPaymentDueDate", 
            String[].class, 
            Integer.class, 
            boolean.class
        );
        method.setAccessible(true);
        
        // When
        LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);
        
        // Then
        assertNotNull(result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }

    @Test
    void testExtractPaymentDueDate_NoDueDate_ReturnsNull() throws Exception {
        // Given - PDF text without payment due date
        String[] lines = {
            "Credit Card Statement",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractPaymentDueDate method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractPaymentDueDate", 
            String[].class, 
            Integer.class, 
            boolean.class
        );
        method.setAccessible(true);
        
        // When
        LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);
        
        // Then
        assertNull(result);
    }

    // ========== Minimum Payment Due Tests ==========

    @Test
    void testExtractMinimumPaymentDue_StandardFormat_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Minimum Payment Due: $25.00"
        String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractMinimumPaymentDue method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("25.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDue_VariousFormats_AllExtracted() throws Exception {
        String[] formats = {
            "Minimum Payment Due: $25.00",
            "Minimum Payment Due $25.00",
            "Min Payment Due: $25.00",
            "Min Payment Due $25.00",
            "Minimum Payment: $25.00",
            "Min Payment: $25.00"
        };
        
        // Use reflection to access private extractMinimumPaymentDue method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        method.setAccessible(true);
        
        for (String format : formats) {
            String[] lines = {
                "Credit Card Statement",
                format,
                "Date Description Amount",
                "01/10/2024 Grocery Store $50.00"
            };
            
            // When
            BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);
            
            // Then
            assertNotNull(result, "Should extract amount from format: " + format);
            assertEquals(new BigDecimal("25.00"), result, 
                "Amount should be $25.00 for format: " + format);
        }
    }

    @Test
    void testExtractMinimumPaymentDue_WithCommas_ExtractsCorrectly() throws Exception {
        // Given - PDF text with comma-separated amount
        String[] lines = {
            "Credit Card Statement",
            "Minimum Payment Due: $1,250.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractMinimumPaymentDue method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("1250.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDue_CaseInsensitive_ExtractsCorrectly() throws Exception {
        // Given - PDF text with various case combinations
        String[] lines = {
            "Credit Card Statement",
            "MINIMUM PAYMENT DUE: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractMinimumPaymentDue method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("25.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDue_NoMinimumPayment_ReturnsNull() throws Exception {
        // Given - PDF text without minimum payment
        String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractMinimumPaymentDue method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNull(result);
    }

    // ========== Reward Points Tests ==========

    @Test
    void testExtractRewardPoints_SingleLine_MembershipRewards_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Membership Rewards Points: 12,345"
        String[] lines = {
            "Credit Card Statement",
            "Membership Rewards Points: 12,345",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(12345L, result);
    }

    @Test
    void testExtractRewardPoints_SingleLine_CitiThankYou_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Citi Thank You Points: 50,000"
        String[] lines = {
            "Credit Card Statement",
            "Citi Thank You Points: 50,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(50000L, result);
    }

    @Test
    void testExtractRewardPoints_SingleLine_SimplePoints_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points: 1,234"
        String[] lines = {
            "Credit Card Statement",
            "Points: 1,234",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(1234L, result);
    }

    @Test
    void testExtractRewardPoints_MultiLine_AccountDetailsSecondLine_ExtractsCorrectly() throws Exception {
        // Given - PDF text with points on line 1, account details on line 2, points number on line 3
        String[] lines = {
            "Credit Card Statement",
            "Membership Rewards Points",
            "Account ending in 1234",
            "25,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(25000L, result);
    }

    @Test
    void testExtractRewardPoints_MultiLine_PointsOnSecondLine_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points" on line 1, points number on line 2
        String[] lines = {
            "Credit Card Statement",
            "Rewards Points",
            "10,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(10000L, result);
    }

    @Test
    void testExtractRewardPoints_WithAsOfDate_ExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points as of 01/15/2024: 5,000"
        String[] lines = {
            "Credit Card Statement",
            "Points as of 01/15/2024: 5,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(5000L, result);
    }

    @Test
    void testExtractRewardPoints_MaxValue_ExtractsCorrectly() throws Exception {
        // Given - PDF text with maximum value (10 million)
        String[] lines = {
            "Credit Card Statement",
            "Points: 10,000,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(10_000_000L, result);
    }

    @Test
    void testExtractRewardPoints_Zero_ExtractsCorrectly() throws Exception {
        // Given - PDF text with zero points
        String[] lines = {
            "Credit Card Statement",
            "Points: 0",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNotNull(result);
        assertEquals(0L, result);
    }

    @Test
    void testExtractRewardPoints_NoPoints_ReturnsNull() throws Exception {
        // Given - PDF text without reward points
        String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then
        assertNull(result);
    }

    @Test
    void testExtractRewardPoints_ExceedsMax_ReturnsNull() throws Exception {
        // Given - PDF text with points exceeding 10 million
        String[] lines = {
            "Credit Card Statement",
            "Points: 10,000,001",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private extractRewardPoints method
        java.lang.reflect.Method method = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        method.setAccessible(true);
        
        // When
        Long result = (Long) method.invoke(pdfImportService, (Object) lines);
        
        // Then - Should not extract points exceeding 10 million
        assertNull(result);
    }

    // ========== Integration Tests ==========

    @Test
    void testExtractAllMetadata_CompleteStatement_ExtractsAll() throws Exception {
        // Given - Complete credit card statement with all metadata
        String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Membership Rewards Points: 12,345",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };
        
        // Use reflection to access private methods
        java.lang.reflect.Method extractDueDateMethod = PDFImportService.class.getDeclaredMethod(
            "extractPaymentDueDate", 
            String[].class, 
            Integer.class, 
            boolean.class
        );
        extractDueDateMethod.setAccessible(true);
        
        java.lang.reflect.Method extractMinPaymentMethod = PDFImportService.class.getDeclaredMethod(
            "extractMinimumPaymentDue", 
            String[].class
        );
        extractMinPaymentMethod.setAccessible(true);
        
        java.lang.reflect.Method extractPointsMethod = PDFImportService.class.getDeclaredMethod(
            "extractRewardPoints", 
            String[].class
        );
        extractPointsMethod.setAccessible(true);
        
        // When
        LocalDate paymentDueDate = (LocalDate) extractDueDateMethod.invoke(pdfImportService, lines, 2024, true);
        BigDecimal minimumPaymentDue = (BigDecimal) extractMinPaymentMethod.invoke(pdfImportService, (Object) lines);
        Long rewardPoints = (Long) extractPointsMethod.invoke(pdfImportService, (Object) lines);
        
        // Then - All metadata should be extracted
        assertNotNull(paymentDueDate);
        assertEquals(LocalDate.of(2024, 1, 15), paymentDueDate);
        
        assertNotNull(minimumPaymentDue);
        assertEquals(new BigDecimal("25.00"), minimumPaymentDue);
        
        assertNotNull(rewardPoints);
        assertEquals(12345L, rewardPoints);
    }
}

