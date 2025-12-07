package com.budgetbuddy.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for Batch Operation Metrics
 */
class BatchOperationMetricsTest {

    private BatchOperationMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BatchOperationMetrics();
    }

    @Test
    void testRecordBatchOperation_WithSuccess_RecordsSuccess() {
        // When
        metrics.recordBatchOperation("sync", 100, 500, true);
        
        // Then
        BatchOperationMetrics.OperationStats stats = metrics.getStats("sync");
        assertEquals(1, stats.getCount());
        assertEquals(1, stats.getSuccessCount());
        assertEquals(0, stats.getFailureCount());
        assertEquals(100, stats.getAverageItems(), 0.01);
        assertEquals(500, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testRecordBatchOperation_WithFailure_RecordsFailure() {
        // When
        metrics.recordBatchOperation("sync", 50, 200, false);
        
        // Then
        BatchOperationMetrics.OperationStats stats = metrics.getStats("sync");
        assertEquals(1, stats.getCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(1, stats.getFailureCount());
    }

    @Test
    void testRecordBatchOperation_MultipleOperations_AggregatesStats() {
        // When
        metrics.recordBatchOperation("sync", 100, 500, true);
        metrics.recordBatchOperation("sync", 200, 600, true);
        metrics.recordBatchOperation("sync", 50, 300, false);
        
        // Then
        BatchOperationMetrics.OperationStats stats = metrics.getStats("sync");
        assertEquals(3, stats.getCount());
        assertEquals(2, stats.getSuccessCount());
        assertEquals(1, stats.getFailureCount());
        assertEquals(116.67, stats.getAverageItems(), 0.1);
        assertEquals(466.67, stats.getAverageDuration(), 0.1);
    }

    @Test
    void testGetStats_WithNonExistentOperation_ReturnsEmptyStats() {
        // When
        BatchOperationMetrics.OperationStats stats = metrics.getStats("non-existent");
        
        // Then
        assertNotNull(stats);
        assertEquals(0, stats.getCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(0, stats.getFailureCount());
        assertEquals(0, stats.getAverageItems(), 0.01);
        assertEquals(0, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testGetAllStats_ReturnsAllOperations() {
        // Given
        metrics.recordBatchOperation("sync", 100, 500, true);
        metrics.recordBatchOperation("import", 50, 200, true);
        
        // When
        Map<String, BatchOperationMetrics.OperationStats> allStats = metrics.getAllStats();
        
        // Then
        assertNotNull(allStats);
        assertEquals(2, allStats.size());
        assertTrue(allStats.containsKey("sync"));
        assertTrue(allStats.containsKey("import"));
    }

    @Test
    void testGetAllStats_ReturnsCopy() {
        // Given
        metrics.recordBatchOperation("sync", 100, 500, true);
        Map<String, BatchOperationMetrics.OperationStats> stats1 = metrics.getAllStats();
        
        // When
        metrics.recordBatchOperation("import", 50, 200, true);
        Map<String, BatchOperationMetrics.OperationStats> stats2 = metrics.getAllStats();
        
        // Then - stats1 should not be affected by new operations
        assertEquals(1, stats1.size());
        assertEquals(2, stats2.size());
    }

    @Test
    void testLogStats_DoesNotThrowException() {
        // Given
        metrics.recordBatchOperation("sync", 100, 500, true);
        
        // When/Then
        assertDoesNotThrow(() -> {
            metrics.logStats();
        });
    }

    @Test
    void testOperationStats_AverageCalculations_WithZeroCount() {
        // Given
        BatchOperationMetrics.OperationStats stats = new BatchOperationMetrics.OperationStats();
        
        // When/Then
        assertEquals(0, stats.getAverageItems(), 0.01);
        assertEquals(0, stats.getAverageDuration(), 0.01);
    }

    @Test
    void testOperationStats_IncrementMethods() {
        // Given
        BatchOperationMetrics.OperationStats stats = new BatchOperationMetrics.OperationStats();
        
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

