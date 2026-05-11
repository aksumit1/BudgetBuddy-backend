package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for credit card metadata extraction from PDF imports: - Payment due date - Minimum payment
 * due - Reward points
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PDFImportServiceCreditCardMetadataTest {

    private static final String EXTRACTMINIMUMPAYMENTDUE = "extractMinimumPaymentDue";
    private static final String EXTRACTPAYMENTDUEDATE = "extractPaymentDueDate";

    private PDFImportService pdfImportService;
    private RewardExtractor rewardExtractor;

    @BeforeEach
    void setUp() {
        final AccountDetectionService accountDetectionService =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final ImportCategoryParser importCategoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        final EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        rewardExtractor = new RewardExtractor();
        pdfImportService =
                new PDFImportService(
                        accountDetectionService,
                        importCategoryParser,
                        enhancedPatternMatcher,
                        null);
    }

    // ========== Payment Due Date Tests ==========

    @Test
    void testExtractPaymentDueDateStandardFormatExtractsCorrectly() throws Exception {
        // Given - PDF text with "Payment due date: 01/15/2024"
        final String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractPaymentDueDate method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(
                        EXTRACTPAYMENTDUEDATE, String[].class, Integer.class, boolean.class);
        method.setAccessible(true);

        // When
        final LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);

        // Then
        assertNotNull(result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }

    @Test
    void testExtractPaymentDueDateVariousFormatsAllExtracted() throws Exception {
        final String[] formats = {
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
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(
                        EXTRACTPAYMENTDUEDATE, String[].class, Integer.class, boolean.class);
        method.setAccessible(true);

        for (final String format : formats) {
            final String[] lines = {
                "Credit Card Statement",
                format,
                "Date Description Amount",
                "01/10/2024 Grocery Store $50.00"
            };

            // When
            final LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);

            // Then
            assertNotNull(result, "Should extract date from format: " + format);
            assertEquals(
                    LocalDate.of(2024, 1, 15),
                    result,
                    "Date should be 01/15/2024 for format: " + format);
        }
    }

    @Test
    void testExtractPaymentDueDateCaseInsensitiveExtractsCorrectly() throws Exception {
        // Given - PDF text with various case combinations
        final String[] lines = {
            "Credit Card Statement",
            "PAYMENT DUE DATE: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractPaymentDueDate method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(
                        EXTRACTPAYMENTDUEDATE, String[].class, Integer.class, boolean.class);
        method.setAccessible(true);

        // When
        final LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);

        // Then
        assertNotNull(result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }

    @Test
    void testExtractPaymentDueDateNoDueDateReturnsNull() throws Exception {
        // Given - PDF text without payment due date
        final String[] lines = {
            "Credit Card Statement",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractPaymentDueDate method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(
                        EXTRACTPAYMENTDUEDATE, String[].class, Integer.class, boolean.class);
        method.setAccessible(true);

        // When
        final LocalDate result = (LocalDate) method.invoke(pdfImportService, lines, 2024, true);

        // Then
        assertNull(result);
    }

    // ========== Minimum Payment Due Tests ==========

    @Test
    void testExtractMinimumPaymentDueStandardFormatExtractsCorrectly() throws Exception {
        // Given - PDF text with "Minimum Payment Due: $25.00"
        final String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractMinimumPaymentDue method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        method.setAccessible(true);

        // When
        final BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);

        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("25.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDueVariousFormatsAllExtracted() throws Exception {
        final String[] formats = {
            "Minimum Payment Due: $25.00",
            "Minimum Payment Due $25.00",
            "Min Payment Due: $25.00",
            "Min Payment Due $25.00",
            "Minimum Payment: $25.00",
            "Min Payment: $25.00"
        };

        // Use reflection to access private extractMinimumPaymentDue method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        method.setAccessible(true);

        for (final String format : formats) {
            final String[] lines = {
                "Credit Card Statement",
                format,
                "Date Description Amount",
                "01/10/2024 Grocery Store $50.00"
            };

            // When
            final BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);

            // Then
            assertNotNull(result, "Should extract amount from format: " + format);
            assertEquals(
                    new BigDecimal("25.00"),
                    result,
                    "Amount should be $25.00 for format: " + format);
        }
    }

    @Test
    void testExtractMinimumPaymentDueWithCommasExtractsCorrectly() throws Exception {
        // Given - PDF text with comma-separated amount
        final String[] lines = {
            "Credit Card Statement",
            "Minimum Payment Due: $1,250.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractMinimumPaymentDue method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        method.setAccessible(true);

        // When
        final BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);

        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("1250.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDueCaseInsensitiveExtractsCorrectly() throws Exception {
        // Given - PDF text with various case combinations
        final String[] lines = {
            "Credit Card Statement",
            "MINIMUM PAYMENT DUE: $25.00",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractMinimumPaymentDue method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        method.setAccessible(true);

        // When
        final BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);

        // Then
        assertNotNull(result);
        assertEquals(new BigDecimal("25.00"), result);
    }

    @Test
    void testExtractMinimumPaymentDueNoMinimumPaymentReturnsNull() throws Exception {
        // Given - PDF text without minimum payment
        final String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private extractMinimumPaymentDue method
        final java.lang.reflect.Method method =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        method.setAccessible(true);

        // When
        final BigDecimal result = (BigDecimal) method.invoke(pdfImportService, (Object) lines);

        // Then
        assertNull(result);
    }

    // ========== Reward Points Tests ==========

    @Test
    void testExtractRewardPointsSingleLineMembershipRewardsExtractsCorrectly() throws Exception {
        // Given - PDF text with "Membership Rewards Points: 12,345"
        final String[] lines = {
            "Credit Card Statement",
            "Membership Rewards Points: 12,345",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(12_345L, result);
    }

    @Test
    void testExtractRewardPointsSingleLineCitiThankYouExtractsCorrectly() throws Exception {
        // Given - PDF text with "Citi Thank You Points: 50,000"
        final String[] lines = {
            "Credit Card Statement",
            "Citi Thank You Points: 50,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(50_000L, result);
    }

    @Test
    void testExtractRewardPointsSingleLineSimplePointsExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points: 1,234"
        final String[] lines = {
            "Credit Card Statement",
            "Points: 1,234",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(1234L, result);
    }

    @Test
    void testExtractRewardPointsMultiLineAccountDetailsSecondLineExtractsCorrectly()
            throws Exception {
        // Given - PDF text with points on line 1, account details on line 2, points number on line
        // 3
        final String[] lines = {
            "Credit Card Statement",
            "Membership Rewards Points",
            "Account ending in 1234",
            "25,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(25_000L, result);
    }

    @Test
    void testExtractRewardPointsMultiLinePointsOnSecondLineExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points" on line 1, points number on line 2
        final String[] lines = {
            "Credit Card Statement",
            "Rewards Points",
            "10,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(10_000L, result);
    }

    @Test
    void testExtractRewardPointsWithAsOfDateExtractsCorrectly() throws Exception {
        // Given - PDF text with "Points as of 01/15/2024: 5,000"
        final String[] lines = {
            "Credit Card Statement",
            "Points as of 01/15/2024: 5,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(5000L, result);
    }

    @Test
    void testExtractRewardPointsMaxValueExtractsCorrectly() throws Exception {
        // Given - PDF text with maximum value (10 million)
        final String[] lines = {
            "Credit Card Statement",
            "Points: 10,000,000",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(10_000_000L, result);
    }

    @Test
    void testExtractRewardPointsZeroExtractsCorrectly() throws Exception {
        // Given - PDF text with zero points
        final String[] lines = {
            "Credit Card Statement",
            "Points: 0",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNotNull(result);
        assertEquals(0L, result);
    }

    @Test
    void testExtractRewardPointsNoPointsReturnsNull() throws Exception {
        // Given - PDF text without reward points
        final String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then
        assertNull(result);
    }

    @Test
    void testExtractRewardPointsExceedsMaxReturnsNull() throws Exception {
        // Given - PDF text with points exceeding the 100M reasonable-points cap
        final String[] lines = {
            "Credit Card Statement",
            "Points: 100,000,001",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // When
        final Long result = rewardExtractor.extractRewardPoints(lines);

        // Then - Should reject values above the reasonable-points cap
        assertNull(result);
    }

    // ========== Integration Tests ==========

    @Test
    void testExtractAllMetadataCompleteStatementExtractsAll() throws Exception {
        // Given - Complete credit card statement with all metadata
        final String[] lines = {
            "Credit Card Statement",
            "Payment due date: 01/15/2024",
            "Minimum Payment Due: $25.00",
            "Membership Rewards Points: 12,345",
            "Date Description Amount",
            "01/10/2024 Grocery Store $50.00"
        };

        // Use reflection to access private methods
        final java.lang.reflect.Method extractDueDateMethod =
                PDFImportService.class.getDeclaredMethod(
                        EXTRACTPAYMENTDUEDATE, String[].class, Integer.class, boolean.class);
        extractDueDateMethod.setAccessible(true);

        final java.lang.reflect.Method extractMinPaymentMethod =
                PDFImportService.class.getDeclaredMethod(EXTRACTMINIMUMPAYMENTDUE, String[].class);
        extractMinPaymentMethod.setAccessible(true);

        // When
        final LocalDate paymentDueDate =
                (LocalDate) extractDueDateMethod.invoke(pdfImportService, lines, 2024, true);
        final BigDecimal minimumPaymentDue =
                (BigDecimal) extractMinPaymentMethod.invoke(pdfImportService, (Object) lines);
        final Long rewardPoints = rewardExtractor.extractRewardPoints(lines);

        // Then - All metadata should be extracted
        assertNotNull(paymentDueDate);
        assertEquals(LocalDate.of(2024, 1, 15), paymentDueDate);

        assertNotNull(minimumPaymentDue);
        assertEquals(new BigDecimal("25.00"), minimumPaymentDue);

        assertNotNull(rewardPoints);
        assertEquals(12_345L, rewardPoints);
    }
}
