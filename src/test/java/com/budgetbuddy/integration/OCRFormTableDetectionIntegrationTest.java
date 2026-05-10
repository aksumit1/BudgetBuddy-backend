package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.AdvancedAccountDetectionService;
import com.budgetbuddy.service.FormFieldDetectionService;
import com.budgetbuddy.service.OCRService;
import com.budgetbuddy.service.TableStructureDetectionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for OCR, Form Field, and Table Detection Tests real-world scenarios and edge
 * cases
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class OCRFormTableDetectionIntegrationTest {

    @Autowired(required = false)
    private OCRService ocrService;

    @Autowired private FormFieldDetectionService formFieldDetectionService;

    @Autowired private TableStructureDetectionService tableStructureDetectionService;

    @Autowired private AdvancedAccountDetectionService advancedDetectionService;

    @BeforeEach
    void setUp() {
        // Skip tests if OCR service is not available (Tesseract not installed in test environment)
        if (ocrService == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "OCR Service not available for testing");
        }
    }

    @Test
    void testFormFieldDetectionRealWorldStatement() {
        final String ocrText =
                """
                        CHASE BANK STATEMENT
                        Account Number: ****1234
                        Institution Name: JPMorgan Chase Bank, N.A.
                        Account Type: Checking
                        Statement Period: January 2024

                        Date        Description        Amount
                        2024-01-01  Check #5678        1000.00
                        2024-01-02  ACH Deposit        2000.00
                        """;

        final List<FormFieldDetectionService.FormField> fields =
                formFieldDetectionService.detectFormFields(ocrText);

        assertNotNull(fields);
        assertFalse(fields.isEmpty());

        // Verify account number was detected
        final boolean foundAccountNumber =
                fields.stream()
                        .anyMatch(
                                f ->
                                        f.getLabel()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("account number")
                                                && f.getValue().contains("1234"));
        assertTrue(foundAccountNumber, "Should detect account number from form fields");
    }

    @Test
    void testTableStructureDetectionRealWorldStatement() {
        final String ocrText =
                """
                        Date        Description        Amount        Balance
                        2024-01-01  Check #5678        1000.00      5000.00
                        2024-01-02  ACH Deposit        2000.00      7000.00
                        2024-01-03  Debit Purchase      50.00       6950.00
                        """;

        final TableStructureDetectionService.TableStructure table =
                tableStructureDetectionService.detectTableStructure(ocrText);

        assertNotNull(table);
        assertFalse(table.getHeaders().isEmpty());
        assertTrue(table.getHeaders().size() >= 3);
        assertFalse(table.getRows().isEmpty());
    }

    @Test
    void testAdvancedDetectionWithFormFields() {
        final String ocrText =
                """
                        CHASE BANK STATEMENT
                        Account Number: ****1234
                        Institution Name: JPMorgan Chase Bank
                        Account Type: Checking
                        """;

        // Simulate OCR extraction
        final List<String> headers = new ArrayList<>();
        final List<List<String>> dataRows = new ArrayList<>();
        final Map<String, String> metadata = new HashMap<>();

        // Extract form fields and add to metadata
        final List<FormFieldDetectionService.FormField> fields =
                formFieldDetectionService.detectFormFields(ocrText);
        final Map<String, String> accountInfo =
                formFieldDetectionService.extractAccountInfo(fields);
        metadata.putAll(accountInfo);

        final AccountDetectionService.DetectedAccount detected =
                advancedDetectionService.detectAccount(
                        "chase_statement.pdf", headers, dataRows, metadata);

        assertNotNull(detected);
        assertEquals("1234", detected.getAccountNumber());
        assertNotNull(detected.getInstitutionName());
    }

    @Test
    void testAdvancedDetectionWithTableStructure() {
        final String ocrText =
                """
                        Date        Account Number    Description        Amount
                        2024-01-01  ****1234          Check #5678        1000.00
                        2024-01-02  ****1234          ACH Deposit        2000.00
                        """;

        // Detect table structure
        final TableStructureDetectionService.TableStructure table =
                tableStructureDetectionService.detectTableStructure(ocrText);

        final List<String> headers = table.getHeaders();
        final List<List<String>> dataRows = table.getRows();
        final Map<String, String> metadata =
                tableStructureDetectionService.extractAccountInfoFromTable(table);

        final AccountDetectionService.DetectedAccount detected =
                advancedDetectionService.detectAccount(
                        "statement.pdf", headers, dataRows, metadata);

        assertNotNull(detected);
        assertEquals("1234", detected.getAccountNumber());
    }

    @Test
    void testConcurrentDetectionThreadSafety() throws InterruptedException {
        // Test thread safety of detection services
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];
        final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    final String ocrText =
                                            "Account Number: "
                                                    + threadId
                                                    + "234\nInstitution: Bank "
                                                    + threadId;
                                    List<FormFieldDetectionService.FormField> fields =
                                            formFieldDetectionService.detectFormFields(ocrText);

                                    TableStructureDetectionService.TableStructure table =
                                            tableStructureDetectionService.detectTableStructure(
                                                    ocrText);

                                    AccountDetectionService.DetectedAccount detected =
                                            advancedDetectionService.detectAccount(
                                                    "test_" + threadId + ".csv",
                                                    Arrays.asList("Date", "Amount"),
                                                    Arrays.asList(
                                                            Arrays.asList("2024-01-01", "100.00")),
                                                    new HashMap<>());
                                } catch (Exception e) {
                                    exceptions.add(e);
                                }
                            });
        }

        // Start all threads
        for (final Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (final Thread thread : threads) {
            thread.join();
        }

        // Verify no exceptions occurred
        assertTrue(
                exceptions.isEmpty(),
                "No exceptions should occur in concurrent execution: " + exceptions);
    }

    @Test
    void testBoundaryConditionMaximumSize() {
        // Test with maximum allowed size
        final StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10 * 1024 * 1024; i++) { // 10 MB
            largeText.append("A");
        }
        largeText.append("\nAccount Number: 1234");

        // Should not throw exception
        final List<FormFieldDetectionService.FormField> fields =
                formFieldDetectionService.detectFormFields(largeText.toString());
        assertNotNull(fields);
    }

    @Test
    void testBoundaryConditionExactMaxLines() {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            text.append("Line ").append(i).append("\n");
        }
        text.append("Account Number: 1234\n");

        // Should not throw exception
        final List<FormFieldDetectionService.FormField> fields =
                formFieldDetectionService.detectFormFields(text.toString());
        assertNotNull(fields);
    }
}
