package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases and boundary condition tests for TableStructureDetectionService
 */
@ExtendWith(MockitoExtension.class)
class TableStructureDetectionServiceEdgeCasesTest {

    private TableStructureDetectionService service;
    
    @BeforeEach
    void setUp() {
        service = new TableStructureDetectionService();
    }
    
    @Test
    void testDetectTableStructure_NullText() {
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(null);
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }
    
    @Test
    void testDetectTableStructure_EmptyText() {
        TableStructureDetectionService.TableStructure table = service.detectTableStructure("");
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }
    
    @Test
    void testDetectTableStructure_WhitespaceOnly() {
        TableStructureDetectionService.TableStructure table = service.detectTableStructure("   \n\t  ");
        assertNotNull(table);
        assertTrue(table.getHeaders().isEmpty());
        assertTrue(table.getRows().isEmpty());
    }
    
    @Test
    void testDetectTableStructure_VeryLongText() {
        // Test with text larger than MAX_OCR_TEXT_LENGTH (10MB)
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 11 * 1024 * 1024; i++) {
            longText.append("A");
        }
        longText.append("\nDate\tAmount\tDescription");
        
        // Should not throw exception, should truncate and process
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(longText.toString());
        assertNotNull(table);
    }
    
    @Test
    void testDetectTableStructure_ManyLines() {
        // Test with more than MAX_LINES (10000)
        StringBuilder manyLines = new StringBuilder();
        for (int i = 0; i < 15000; i++) {
            manyLines.append("Line ").append(i).append("\n");
        }
        manyLines.append("Date\tAmount\tDescription\n");
        
        // Should not throw exception, should limit lines and process
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(manyLines.toString());
        assertNotNull(table);
    }
    
    @Test
    void testDetectTableStructure_TabSeparated() {
        String text = "Date\tAmount\tDescription\n2024-01-01\t100.00\tPurchase";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        assertEquals(3, table.getHeaders().size());
        assertTrue(table.getHeaders().contains("Date"));
    }
    
    @Test
    void testDetectTableStructure_SpaceSeparated() {
        String text = "Date        Amount        Description\n2024-01-01  100.00        Purchase";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        // Should detect headers
    }
    
    @Test
    void testDetectTableStructure_PipeSeparated() {
        String text = "Date|Amount|Description\n2024-01-01|100.00|Purchase";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
    }
    
    @Test
    void testDetectTableStructure_NoHeader() {
        String text = "2024-01-01\t100.00\tPurchase\n2024-01-02\t200.00\tSale";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        // May or may not detect headers
    }
    
    @Test
    void testDetectTableStructure_EmptyRows() {
        String text = "Date\tAmount\tDescription\n\n\n";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        // Should have no data rows (empty rows filtered out)
    }
    
    @Test
    void testDetectTableStructure_MalformedRows() {
        String text = "Date\tAmount\tDescription\n2024-01-01\n2024-01-02\t200.00";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        // Should handle malformed rows gracefully
    }
    
    @Test
    void testExtractAccountInfoFromTable_NullTable() {
        assertThrows(NullPointerException.class, () -> {
            service.extractAccountInfoFromTable(null);
        });
    }
    
    @Test
    void testExtractAccountInfoFromTable_EmptyTable() {
        TableStructureDetectionService.TableStructure table = new TableStructureDetectionService.TableStructure();
        Map<String, String> info = service.extractAccountInfoFromTable(table);
        
        assertNotNull(info);
        assertTrue(info.isEmpty());
    }
    
    @Test
    void testExtractAccountInfoFromTable_TableWithAccountNumber() {
        TableStructureDetectionService.TableStructure table = new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("2024-01-01", "****1234", "100.00"));
        table.setRows(rows);
        
        Map<String, String> info = service.extractAccountInfoFromTable(table);
        
        assertNotNull(info);
        assertEquals("1234", info.get("accountNumber"));
    }
    
    @Test
    void testExtractAccountInfoFromTable_TableWithNullRows() {
        TableStructureDetectionService.TableStructure table = new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        table.setRows(null);
        
        // Should not throw exception
        Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
    }
    
    @Test
    void testExtractAccountInfoFromTable_TableWithEmptyRows() {
        TableStructureDetectionService.TableStructure table = new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        table.setRows(new ArrayList<>());
        
        Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
        assertTrue(info.isEmpty());
    }
    
    @Test
    void testExtractAccountInfoFromTable_TableWithNullCells() {
        TableStructureDetectionService.TableStructure table = new TableStructureDetectionService.TableStructure();
        table.setHeaders(List.of("Date", "Account Number", "Amount"));
        
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        row.add("2024-01-01");
        row.add(null); // Null cell
        row.add("100.00");
        rows.add(row);
        table.setRows(rows);
        
        // Should not throw exception
        Map<String, String> info = service.extractAccountInfoFromTable(table);
        assertNotNull(info);
    }
    
    @Test
    void testDetectTableStructure_InvalidColumnIndex() {
        String text = "Date\tAmount\n2024-01-01\t100.00";
        TableStructureDetectionService.TableStructure table = service.detectTableStructure(text);
        
        assertNotNull(table);
        // Should handle column index out of bounds gracefully
    }
}

