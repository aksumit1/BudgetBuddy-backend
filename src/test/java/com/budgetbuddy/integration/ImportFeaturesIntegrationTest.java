package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.ImportHistory;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ImportHistoryService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for import features:
 * - Import History
 * - Import Validation
 * - Bulk Import
 * - Partial Import & Resume
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@Import(AWSTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("Import Features Integration Tests")
class ImportFeaturesIntegrationTest {

    @Autowired
    private ImportHistoryService importHistoryService;

    @Autowired
    private UserService userService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // Create test user
        String email = "test-import-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        testUser = userService.createUserSecure(
                email,
                base64PasswordHash,
                "Test",
                "User"
        );
    }

    @Test
    @DisplayName("Should create and retrieve import history")
    void testImportHistoryCreation() {
        // Create import history
        ImportHistory history = importHistoryService.createImportHistory(
                testUser.getUserId(),
                "test.csv",
                "CSV",
                "CSV"
        );

        assertNotNull(history);
        assertNotNull(history.getImportId());
        assertEquals(testUser.getUserId(), history.getUserId());
        assertEquals("test.csv", history.getFileName());
        assertEquals("CSV", history.getFileType());
        assertEquals("IN_PROGRESS", history.getStatus());

        // Retrieve import history
        Optional<ImportHistory> retrieved = importHistoryService.getImportHistory(history.getImportId());
        assertTrue(retrieved.isPresent());
        assertEquals(history.getImportId(), retrieved.get().getImportId());
    }

    @Test
    @DisplayName("Should complete import history with statistics")
    void testCompleteImportHistory() {
        ImportHistory history = importHistoryService.createImportHistory(
                testUser.getUserId(),
                "test.csv",
                "CSV",
                "CSV"
        );

        importHistoryService.completeImportHistory(
                history.getImportId(),
                100, // successful
                5,   // failed
                10,  // skipped
                3    // duplicates
        );

        Optional<ImportHistory> completed = importHistoryService.getImportHistory(history.getImportId());
        assertTrue(completed.isPresent());
        assertEquals("COMPLETED", completed.get().getStatus());
        assertEquals(100, completed.get().getSuccessfulTransactions());
        assertEquals(5, completed.get().getFailedTransactions());
        assertEquals(10, completed.get().getSkippedTransactions());
        assertEquals(3, completed.get().getDuplicateTransactions());
    }

    @Test
    @DisplayName("Should mark import as partial and allow resume")
    void testPartialImportAndResume() {
        ImportHistory history = importHistoryService.createImportHistory(
                testUser.getUserId(),
                "test.csv",
                "CSV",
                "CSV"
        );

        String resumeToken = UUID.randomUUID().toString();
        importHistoryService.markPartialImport(
                history.getImportId(),
                50, // last processed index
                resumeToken
        );

        Optional<ImportHistory> partial = importHistoryService.getImportHistory(history.getImportId());
        assertTrue(partial.isPresent());
        assertEquals("PARTIAL", partial.get().getStatus());
        assertTrue(partial.get().isCanResume());
        assertEquals(50, partial.get().getLastProcessedIndex());
        assertEquals(resumeToken, partial.get().getResumeToken());

        // Resume import
        Optional<ImportHistory> resumed = importHistoryService.resumeImport(history.getImportId(), resumeToken);
        assertTrue(resumed.isPresent());
        assertEquals("IN_PROGRESS", resumed.get().getStatus());
    }

    @Test
    @DisplayName("Should get import statistics")
    void testImportStatistics() {
        // Create multiple imports
        ImportHistory history1 = importHistoryService.createImportHistory(
                testUser.getUserId(), "file1.csv", "CSV", "CSV");
        importHistoryService.completeImportHistory(history1.getImportId(), 50, 0, 0, 0);

        ImportHistory history2 = importHistoryService.createImportHistory(
                testUser.getUserId(), "file2.csv", "CSV", "CSV");
        importHistoryService.completeImportHistory(history2.getImportId(), 30, 5, 0, 2);

        ImportHistory history3 = importHistoryService.createImportHistory(
                testUser.getUserId(), "file3.csv", "CSV", "CSV");
        importHistoryService.failImportHistory(history3.getImportId(), "Test error");

        Map<String, Object> stats = importHistoryService.getImportStatistics(testUser.getUserId());
        assertNotNull(stats);
        // CRITICAL FIX: Handle both Integer and Long types (count() returns Long, size() returns int)
        assertTrue(getIntValue(stats.get("totalImports")) >= 3);
        assertTrue(getIntValue(stats.get("completedImports")) >= 2);
        assertTrue(getIntValue(stats.get("failedImports")) >= 1);
        assertTrue(getIntValue(stats.get("totalTransactionsImported")) >= 80);
    }

    // NOTE: ImportValidationService tests removed - service was deprecated and removed
    // Validation logic is now handled directly in CSVImportService, ExcelImportService, and PDFImportService
    
    /**
     * Helper method to safely convert Object to int, handling both Integer and Long types
     * (DynamoDB/Stream operations may return Long for count operations)
     */
    private int getIntValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return ((Long) value).intValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to int");
    }
}

