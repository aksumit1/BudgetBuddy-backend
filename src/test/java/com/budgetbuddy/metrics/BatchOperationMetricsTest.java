package com.budgetbuddy.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit Tests for Batch Operation Metrics */
class BatchOperationMetricsTest {

    private static final String SYNC = "sync";

    private BatchOperationMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BatchOperationMetrics();
    }

    @Test
    void testRecordBatchOperationWithSuccessRecordsSuccess() {
        // When
        metrics.recordBatchOperation(SYNC, 100, 500, true);

        // Then
        final BatchOperationMetrics.OperationStats stats = metrics.getStats(SYNC);
        assertEquals(1, stats.getCount());
        assertEquals(1, stats.getSuccessCount());
        assertEquals(0, stats.getFailureCount());
        assertEquals(100, stats.getAverageItems(), 0.01);
        assertEquals(500, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testRecordBatchOperationWithFailureRecordsFailure() {
        // When
        metrics.recordBatchOperation(SYNC, 50, 200, false);

        // Then
        final BatchOperationMetrics.OperationStats stats = metrics.getStats(SYNC);
        assertEquals(1, stats.getCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(1, stats.getFailureCount());
    }

    @Test
    void testRecordBatchOperationMultipleOperationsAggregatesStats() {
        // When
        metrics.recordBatchOperation(SYNC, 100, 500, true);
        metrics.recordBatchOperation(SYNC, 200, 600, true);
        metrics.recordBatchOperation(SYNC, 50, 300, false);

        // Then
        final BatchOperationMetrics.OperationStats stats = metrics.getStats(SYNC);
        assertEquals(3, stats.getCount());
        assertEquals(2, stats.getSuccessCount());
        assertEquals(1, stats.getFailureCount());
        assertEquals(116.67, stats.getAverageItems(), 0.1);
        assertEquals(466.67, stats.getAverageDuration(), 0.1);
    }

    @Test
    void testGetStatsWithNonExistentOperationReturnsEmptyStats() {
        // When
        final BatchOperationMetrics.OperationStats stats = metrics.getStats("non-existent");

        // Then
        assertNotNull(stats);
        assertEquals(0, stats.getCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(0, stats.getFailureCount());
        assertEquals(0, stats.getAverageItems(), 0.01);
        assertEquals(0, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testGetAllStatsReturnsAllOperations() {
        // Given
        metrics.recordBatchOperation(SYNC, 100, 500, true);
        metrics.recordBatchOperation("import", 50, 200, true);

        // When
        final Map<String, BatchOperationMetrics.OperationStats> allStats = metrics.getAllStats();

        // Then
        assertNotNull(allStats);
        assertEquals(2, allStats.size());
        assertTrue(allStats.containsKey(SYNC));
        assertTrue(allStats.containsKey("import"));
    }

    @Test
    void testGetAllStatsReturnsCopy() {
        // Given
        metrics.recordBatchOperation(SYNC, 100, 500, true);
        final Map<String, BatchOperationMetrics.OperationStats> stats1 = metrics.getAllStats();

        // When
        metrics.recordBatchOperation("import", 50, 200, true);
        final Map<String, BatchOperationMetrics.OperationStats> stats2 = metrics.getAllStats();

        // Then - stats1 should not be affected by new operations
        assertEquals(1, stats1.size());
        assertEquals(2, stats2.size());
    }

    @Test
    void testLogStatsDoesNotThrowException() {
        // Given
        metrics.recordBatchOperation(SYNC, 100, 500, true);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    metrics.logStats();
                });
    }

    @Test
    void testOperationStatsAverageCalculationsWithZeroCount() {
        // Given
        final BatchOperationMetrics.OperationStats stats =
                new BatchOperationMetrics.OperationStats();

        // When/Then
        assertEquals(0, stats.getAverageItems(), 0.01);
        assertEquals(0, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testOperationStatsIncrementMethods() {
        // Given
        final BatchOperationMetrics.OperationStats stats =
                new BatchOperationMetrics.OperationStats();

        // When
        stats.incrementCount();
        stats.incrementSuccess();
        stats.incrementFailure();
        stats.addItems(100);
        stats.addDuration(500);

        // Then
        assertEquals(1, stats.getCount());
        assertEquals(1, stats.getSuccessCount());
        assertEquals(1, stats.getFailureCount());
        assertEquals(100, stats.getAverageItems(), 0.01);
        assertEquals(500, stats.getAverageDuration(), 0.01);
    }
}
