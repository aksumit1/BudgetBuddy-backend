package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OCR, Form Field, and Table Detection
 * Tests real-world scenarios and edge cases
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class OCRFormTableDetectionIntegrationTest {

    @Autowired(required = false)
    private OCRService ocrService;
    
    @Autowired
    private FormFieldDetectionService formFieldDetectionService;
    
    @Autowired
    private TableStructureDetectionService tableStructureDetectionService;
    
    @Autowired
    private AdvancedAccountDetectionService advancedDetectionService;
    
    @BeforeEach
    void setUp() {
        // Skip tests if OCR service is not available (Tesseract not installed in test environment)
        if (ocrService == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "OCR Service not available for testing");
        }
    }
    
    @Test
    void testFormFieldDetection_RealWorldStatement() {
        String ocrText = """
            CHASE BANK STATEMENT
            Account Number: ****1234
            Institution Name: JPMorgan Chase Bank, N.A.
            Account Type: Checking
            Statement Period: January 2024
            
            Date        Description        Amount
            2024-01-01  Check #5678        1000.00
            2024-01-02  ACH Deposit        2000.00
            """;
        
        List<FormFieldDetectionService.FormField> fields = formFieldDetectionService.detectFormFields(ocrText);
        
        assertNotNull(fields);
        assertFalse(fields.isEmpty());
        
        // Verify account number was detected
        boolean foundAccountNumber = fields.stream()
            .anyMatch(f -> f.getLabel().toLowerCase().contains("account number") && 
                         f.getValue().contains("1234"));
        assertTrue(foundAccountNumber, "Should detect account number from form fields");
    }
    
    @Test
    void testTableStructureDetection_RealWorldStatement() {
        String ocrText = """
            Date        Description        Amount        Balance
            2024-01-01  Check #5678        1000.00      5000.00
            2024-01-02  ACH Deposit        2000.00      7000.00
            2024-01-03  Debit Purchase      50.00       6950.00
            """;
        
        TableStructureDetectionService.TableStructure table = tableStructureDetectionService.detectTableStructure(ocrText);
        
        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        assertTrue(table.getHeaders().size() >= 3);
        assertFalse(table.getRows().isEmpty());
    }
    
    @Test
    void testAdvancedDetection_WithFormFields() {
        String ocrText = """
            CHASE BANK STATEMENT
            Account Number: ****1234
            Institution Name: JPMorgan Chase Bank
            Account Type: Checking
            """;
        
        // Simulate OCR extraction
        List<String> headers = new ArrayList<>();
        List<List<String>> dataRows = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        
        // Extract form fields and add to metadata
        List<FormFieldDetectionService.FormField> fields = formFieldDetectionService.detectFormFields(ocrText);
        Map<String, String> accountInfo = formFieldDetectionService.extractAccountInfo(fields);
        metadata.putAll(accountInfo);
        
        AccountDetectionService.DetectedAccount detected = advancedDetectionService.detectAccount(
            "chase_statement.pdf", headers, dataRows, metadata
        );
        
        assertNotNull(detected);
        assertEquals("1234", detected.getAccountNumber());
        assertNotNull(detected.getInstitutionName());
    }
    
    @Test
    void testAdvancedDetection_WithTableStructure() {
        String ocrText = """
            Date        Account Number    Description        Amount
            2024-01-01  ****1234          Check #5678        1000.00
            2024-01-02  ****1234          ACH Deposit        2000.00
            """;
        
        // Detect table structure
        TableStructureDetectionService.TableStructure table = tableStructureDetectionService.detectTableStructure(ocrText);
        
        List<String> headers = table.getHeaders();
        List<List<String>> dataRows = table.getRows();
        Map<String, String> metadata = tableStructureDetectionService.extractAccountInfoFromTable(table);
        
        AccountDetectionService.DetectedAccount detected = advancedDetectionService.detectAccount(
            "statement.pdf", headers, dataRows, metadata
        );
        
        assertNotNull(detected);
        assertEquals("1234", detected.getAccountNumber());
    }
    
    @Test
    void testConcurrentDetection_ThreadSafety() throws InterruptedException {
        // Test thread safety of detection services
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    String ocrText = "Account Number: " + threadId + "234\nInstitution: Bank " + threadId;
                    List<FormFieldDetectionService.FormField> fields = formFieldDetectionService.detectFormFields(ocrText);
                    
                    TableStructureDetectionService.TableStructure table = 
                        tableStructureDetectionService.detectTableStructure(ocrText);
                    
                    AccountDetectionService.DetectedAccount detected = advancedDetectionService.detectAccount(
                        "test_" + threadId + ".csv", 
                        Arrays.asList("Date", "Amount"), 
                        Arrays.asList(Arrays.asList("2024-01-01", "100.00")),
                        new HashMap<>()
                    );
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify no exceptions occurred
        assertTrue(exceptions.isEmpty(), "No exceptions should occur in concurrent execution: " + exceptions);
    }
    
    @Test
    void testBoundaryCondition_MaximumSize() {
        // Test with maximum allowed size
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10 * 1024 * 1024; i++) { // 10 MB
            largeText.append("A");
        }
        largeText.append("\nAccount Number: 1234");
        
        // Should not throw exception
        List<FormFieldDetectionService.FormField> fields = formFieldDetectionService.detectFormFields(largeText.toString());
        assertNotNull(fields);
    }
    
    @Test
    void testBoundaryCondition_ExactMaxLines() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            text.append("Line ").append(i).append("\n");
        }
        text.append("Account Number: 1234\n");
        
        // Should not throw exception
        List<FormFieldDetectionService.FormField> fields = formFieldDetectionService.detectFormFields(text.toString());
        assertNotNull(fields);
    }
}

