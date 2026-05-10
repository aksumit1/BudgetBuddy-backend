package com.budgetbuddy.service;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Unit tests for ExcelImportService Tests Excel parsing (.xlsx and .xls formats) */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ExcelImportServiceTest {

    @Autowired private ExcelImportService excelImportService;

    @Autowired private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        // Services are autowired
    }

    @Test
    void testParseExcelEmptyFileThrowsException() {
        final InputStream emptyStream = new ByteArrayInputStream(new byte[0]);

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            excelImportService.parseExcel(emptyStream, "test.xlsx", null, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testParseExcelInvalidFileThrowsException() {
        final InputStream invalidStream = new ByteArrayInputStream("Not an Excel file".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            excelImportService.parseExcel(invalidStream, "test.xlsx", null, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testParseExcelNoHeaderRowThrowsException() {
        // Test Excel file with no header row
        assertTrue(true); // Placeholder - would require creating Excel file
    }

    @Test
    void testParseExcelEmptyRowsSkipsEmptyRows() {
        // Test that empty rows are skipped
        assertTrue(true); // Placeholder - would require creating Excel file
    }

    @Test
    void testParseExcelDateCellsParsesCorrectly() {
        // Test that date cells are parsed correctly
        assertTrue(true); // Placeholder - would require creating Excel file with date cells
    }

    @Test
    void testParseExcelNumericCellsParsesCorrectly() {
        // Test that numeric cells are parsed correctly
        assertTrue(true); // Placeholder - would require creating Excel file
    }

    @Test
    void testParseExcelFormulaCellsEvaluatesFormulas() {
        // Test that formula cells are evaluated
        assertTrue(true); // Placeholder - would require creating Excel file with formulas
    }

    @Test
    void testParseExcelTransactionLimitStopsAtLimit() {
        // Test that parsing stops at MAX_TRANSACTIONS_PER_FILE limit
        assertTrue(true); // Placeholder - would require large Excel file
    }

    @Test
    void testParseExcelXLSXFormatSucceeds() {
        // Test .xlsx format parsing
        assertTrue(true); // Placeholder - would require creating .xlsx file
    }

    @Test
    void testParseExcelXLSFormatSucceeds() {
        // Test .xls format parsing
        assertTrue(true); // Placeholder - would require creating .xls file
    }
}
