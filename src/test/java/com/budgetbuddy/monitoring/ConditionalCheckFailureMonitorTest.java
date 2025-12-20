package com.budgetbuddy.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConditionalCheckFailureMonitor
 */
class ConditionalCheckFailureMonitorTest {

    private ConditionalCheckFailureMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ConditionalCheckFailureMonitor();
    }

    @Test
    @DisplayName("Should record failure and increment counter")
    void testRecordFailure_IncrementsCounter() {
        // Given
        String operation = "saveIfNotExists";
        String tableName = "Transactions";
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When
        monitor.recordFailure(operation, tableName, exception);

        // Then
        assertEquals(1, monitor.getFailureCount(operation));
        assertEquals(1, monitor.getFailureCountByTable(tableName));
    }

    @Test
    @DisplayName("Should handle null operation and table name")
    void testRecordFailure_WithNullValues() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When
        monitor.recordFailure(null, null, exception);

        // Then
        assertEquals(1, monitor.getFailureCount("unknown"));
        assertEquals(1, monitor.getFailureCountByTable("unknown"));
    }

    @Test
    @DisplayName("Should track multiple failures for same operation")
    void testRecordFailure_MultipleFailures() {
        // Given
        String operation = "updateTransaction";
        String tableName = "Transactions";
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When
        monitor.recordFailure(operation, tableName, exception);
        monitor.recordFailure(operation, tableName, exception);
        monitor.recordFailure(operation, tableName, exception);

        // Then
        assertEquals(3, monitor.getFailureCount(operation));
        assertEquals(3, monitor.getFailureCountByTable(tableName));
    }

    @Test
    @DisplayName("Should track failures for different operations")
    void testRecordFailure_DifferentOperations() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When
        monitor.recordFailure("saveIfNotExists", "Transactions", exception);
        monitor.recordFailure("updateTransaction", "Transactions", exception);
        monitor.recordFailure("saveIfNotExists", "Accounts", exception);

        // Then - saveIfNotExists was called twice (once for Transactions, once for Accounts)
        assertEquals(2, monitor.getFailureCount("saveIfNotExists"));
        assertEquals(1, monitor.getFailureCount("updateTransaction"));
        assertEquals(2, monitor.getFailureCountByTable("Transactions"));
        assertEquals(1, monitor.getFailureCountByTable("Accounts"));
    }

    @Test
    @DisplayName("Should return zero for non-existent operation")
    void testGetFailureCount_NonExistentOperation() {
        // When
        long count = monitor.getFailureCount("nonExistent");

        // Then
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return zero for non-existent table")
    void testGetFailureCountByTable_NonExistentTable() {
        // When
        long count = monitor.getFailureCountByTable("nonExistent");

        // Then
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return all failure counts")
    void testGetAllFailureCounts() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When
        monitor.recordFailure("operation1", "Table1", exception);
        monitor.recordFailure("operation2", "Table2", exception);
        monitor.recordFailure("operation1", "Table1", exception);

        Map<String, Long> allCounts = monitor.getAllFailureCounts();

        // Then
        assertNotNull(allCounts);
        assertEquals(2, allCounts.size());
        assertEquals(2, allCounts.get("operation1"));
        assertEquals(1, allCounts.get("operation2"));
    }

    @Test
    @DisplayName("Should reset all counters")
    void testResetCounters() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();
        monitor.recordFailure("operation1", "Table1", exception);
        monitor.recordFailure("operation2", "Table2", exception);

        // When
        monitor.resetCounters();

        // Then
        assertEquals(0, monitor.getFailureCount("operation1"));
        assertEquals(0, monitor.getFailureCount("operation2"));
        assertEquals(0, monitor.getFailureCountByTable("Table1"));
        assertEquals(0, monitor.getFailureCountByTable("Table2"));
        assertTrue(monitor.getAllFailureCounts().isEmpty());
    }

    @Test
    @DisplayName("Should alert on high frequency failures (every 10th failure)")
    void testRecordFailure_HighFrequencyAlert() {
        // Given
        String operation = "saveIfNotExists";
        String tableName = "Transactions";
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When - Record 25 failures (should alert at 10, 20)
        for (int i = 0; i < 25; i++) {
            monitor.recordFailure(operation, tableName, exception);
        }

        // Then
        assertEquals(25, monitor.getFailureCount(operation));
    }
}

