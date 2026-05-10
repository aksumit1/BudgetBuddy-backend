package com.budgetbuddy.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Unit tests for ConditionalCheckFailureMonitor */
class ConditionalCheckFailureMonitorTest {

    private static final String TRANSACTIONS = "Transactions";
    private static final String SAVEIFNOTEXISTS = "saveIfNotExists";
    private static final String OPERATION1 = "operation1";

    private ConditionalCheckFailureMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ConditionalCheckFailureMonitor();
    }

    @Test
    @DisplayName("Should record failure and increment counter")
    void testRecordFailureIncrementsCounter() {
        // Given
        final String operation = SAVEIFNOTEXISTS;
        final String tableName = TRANSACTIONS;
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
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
    void testRecordFailureWithNullValues() {
        // Given
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
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
    void testRecordFailureMultipleFailures() {
        // Given
        final String operation = "updateTransaction";
        final String tableName = TRANSACTIONS;
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
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
    void testRecordFailureDifferentOperations() {
        // Given
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
                        .message("Conditional check failed")
                        .build();

        // When
        monitor.recordFailure(SAVEIFNOTEXISTS, TRANSACTIONS, exception);
        monitor.recordFailure("updateTransaction", TRANSACTIONS, exception);
        monitor.recordFailure(SAVEIFNOTEXISTS, "Accounts", exception);

        // Then - saveIfNotExists was called twice (once for Transactions, once for Accounts)
        assertEquals(2, monitor.getFailureCount(SAVEIFNOTEXISTS));
        assertEquals(1, monitor.getFailureCount("updateTransaction"));
        assertEquals(2, monitor.getFailureCountByTable(TRANSACTIONS));
        assertEquals(1, monitor.getFailureCountByTable("Accounts"));
    }

    @Test
    @DisplayName("Should return zero for non-existent operation")
    void testGetFailureCountNonExistentOperation() {
        // When
        final long count = monitor.getFailureCount("nonExistent");

        // Then
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return zero for non-existent table")
    void testGetFailureCountByTableNonExistentTable() {
        // When
        final long count = monitor.getFailureCountByTable("nonExistent");

        // Then
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return all failure counts")
    void testGetAllFailureCounts() {
        // Given
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
                        .message("Conditional check failed")
                        .build();

        // When
        monitor.recordFailure(OPERATION1, "Table1", exception);
        monitor.recordFailure("operation2", "Table2", exception);
        monitor.recordFailure(OPERATION1, "Table1", exception);

        final Map<String, Long> allCounts = monitor.getAllFailureCounts();

        // Then
        assertNotNull(allCounts);
        assertEquals(2, allCounts.size());
        assertEquals(2, allCounts.get(OPERATION1));
        assertEquals(1, allCounts.get("operation2"));
    }

    @Test
    @DisplayName("Should reset all counters")
    void testResetCounters() {
        // Given
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
                        .message("Conditional check failed")
                        .build();
        monitor.recordFailure(OPERATION1, "Table1", exception);
        monitor.recordFailure("operation2", "Table2", exception);

        // When
        monitor.resetCounters();

        // Then
        assertEquals(0, monitor.getFailureCount(OPERATION1));
        assertEquals(0, monitor.getFailureCount("operation2"));
        assertEquals(0, monitor.getFailureCountByTable("Table1"));
        assertEquals(0, monitor.getFailureCountByTable("Table2"));
        assertTrue(monitor.getAllFailureCounts().isEmpty());
    }

    @Test
    @DisplayName("Should alert on high frequency failures (every 10th failure)")
    void testRecordFailureHighFrequencyAlert() {
        // Given
        final String operation = SAVEIFNOTEXISTS;
        final String tableName = TRANSACTIONS;
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
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
