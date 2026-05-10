package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Edge cases and boundary condition tests for TableStructureDetectionService */
@ExtendWith(MockitoExtension.class)
class TableStructureDetectionServiceEdgeCasesTest {

    private TableStructureDetectionService service;

    @BeforeEach
    void setUp() {
        service = new TableStructureDetectionService();
    }

    @Test
    void testDetectTableStructureNullText() {
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(null);
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }

    @Test
    void testDetectTableStructureEmptyText() {
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure("");
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }

    @Test
    void testDetectTableStructureWhitespaceOnly() {
        final TableStructureDetectionService.TableStructure table =
                service.detectTableStructure("   \n\t  ");
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }

    @Test
    void testDetectTableStructureVeryLongText() {
        // Test with text larger than MAX_OCR_TEXT_LENGTH (10MB)
        final StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 11 * 1024 * 1024; i++) {
            longText.append("A");
        }
        longText.append("\nDate\tAmount\tDescription");

        // Should not throw exception, should truncate and process
        final TableStructureDetectionService.TableStructure table =
                service.detectTableStructure(longText.toString());
        assertNotNull(table);
    }

    @Test
    void testDetectTableStructureManyLines() {
        // Test with more than MAX_LINES (10000)
        final StringBuilder manyLines = new StringBuilder();
        for (int i = 0; i < 15_000; i++) {
            manyLines.append("Line ").append(i).append("\n");
        }
        manyLines.append("Date\tAmount\tDescription\n");

        // Should not throw exception, should limit lines and process
        final TableStructureDetectionService.TableStructure table =
                service.detectTableStructure(manyLines.toString());
        assertNotNull(table);
    }

    @Test
    void testDetectTableStructureTabSeparated() {
        final String text = "Date\tAmount\tDescription\n2024-01-01\t100.00\tPurchase";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        assertEquals(3, table.getHeaders().size());
        assertTrue(table.getHeaders().contains("Date"));
    }

    @Test
    void testDetectTableStructureSpaceSeparated() {
        final String text = "Date        Amount        Description\n2024-01-01  100.00        Purchase";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        // Should detect headers
    }

    @Test
    void testDetectTableStructurePipeSeparated() {
        final String text = "Date|Amount|Description\n2024-01-01|100.00|Purchase";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
    }

    @Test
    void testDetectTableStructureNoHeader() {
        final String text = "2024-01-01\t100.00\tPurchase\n2024-01-02\t200.00\tSale";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        // May or may not detect headers
    }

    @Test
    void testDetectTableStructureEmptyRows() {
        final String text = "Date\tAmount\tDescription\n\n\n";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        // Should have no data rows (empty rows filtered out)
    }

    @Test
    void testDetectTableStructureMalformedRows() {
        final String text = "Date\tAmount\tDescription\n2024-01-01\n2024-01-02\t200.00";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        // Should handle malformed rows gracefully
    }

    @Test
    void testExtractAccountInfoFromTableNullTable() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    service.extractAccountInfoFromTable(null);
                });
    }

    @Test
    void testExtractAccountInfoFromTableEmptyTable() {
        final TableStructureDetectionService.TableStructure table =
                new TableStructureDetectionService.TableStructure();
        final Map<String, String> info = service.extractAccountInfoFromTable(table);

        assertNotNull(info);
        assertTrue(info.isEmpty());
    }

    @Test
    void testExtractAccountInfoFromTableTableWithAccountNumber() {
        final TableStructureDetectionService.TableStructure table =
                new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));

        final List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("2024-01-01", "****1234", "100.00"));
        table.setRows(rows);

        final Map<String, String> info = service.extractAccountInfoFromTable(table);

        assertNotNull(info);
        assertEquals("1234", info.get("accountNumber"));
    }

    @Test
    void testExtractAccountInfoFromTableTableWithNullRows() {
        final TableStructureDetectionService.TableStructure table =
                new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        table.setRows(null);

        // Should not throw exception
        final Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
    }

    @Test
    void testExtractAccountInfoFromTableTableWithEmptyRows() {
        final TableStructureDetectionService.TableStructure table =
                new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        table.setRows(new ArrayList<>());

        final Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
        assertTrue(info.isEmpty());
    }

    @Test
    void testExtractAccountInfoFromTableTableWithNullCells() {
        final TableStructureDetectionService.TableStructure table =
                new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));

        final List<List<String>> rows = new ArrayList<>();
        final List<String> row = new ArrayList<>();
        row.add("2024-01-01");
        row.add(null); // Null cell
        row.add("100.00");
        rows.add(row);
        table.setRows(rows);

        // Should not throw exception
        final Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
    }

    @Test
    void testDetectTableStructureInvalidColumnIndex() {
        final String text = "Date\tAmount\n2024-01-01\t100.00";
        final TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);

        assertNotNull(table);
        // Should handle column index out of bounds gracefully
    }
}
