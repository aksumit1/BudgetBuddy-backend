package com.budgetbuddy.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch Operation Metrics Service
 * Tracks performance metrics for batch operations
 */
@Component
public class BatchOperationMetrics {

    private static final Logger logger = LoggerFactory.getLogger(BatchOperationMetrics.class);

    private final Map<String, OperationStats> operationStats = new ConcurrentHashMap<>();

    /**
     * Record batch operation execution
     */
    public void recordBatchOperation(
            final String operationType,
            final int itemCount,
            final long durationMs,
            final boolean success) {
        OperationStats stats = operationStats.computeIfAbsent(operationType, k -> new OperationStats());
        
        stats.incrementCount();
        stats.addItems(itemCount);
        stats.addDuration(durationMs);
        
        if (success) {
            stats.incrementSuccess();
        } else {
            stats.incrementFailure();
        }
    }

    /**
     * Get statistics for an operation type
     */
    public OperationStats getStats(final String operationType) {
        return operationStats.getOrDefault(operationType, new OperationStats());
    }

    /**
     * Get all statistics
     */
    public Map<String, OperationStats> getAllStats() {
        return new ConcurrentHashMap<>(operationStats);
    }

    /**
     * Log batch operation statistics
     */
    public void logStats() {
        logger.info("=== Batch Operation Statistics ===");
        for (Map.Entry<String, OperationStats> entry : operationStats.entrySet()) {
            OperationStats stats = entry.getValue();
            logger.info("Operation: {} | Count: {} | Success: {} | Failure: {} | Avg Items: {} | Avg Duration: {}ms",
                    entry.getKey(),
                    stats.getCount(),
                    stats.getSuccessCount(),
                    stats.getFailureCount(),
                    stats.getAverageItems(),
                    stats.getAverageDuration());
        }
        logger.info("==================================");
    }

    /**
     * Operation statistics
     */
    public static class OperationStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong totalItems = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);

        public void incrementCount() {
            count.incrementAndGet();
        }

        public void incrementSuccess() {
            successCount.incrementAndGet();
        }

        public void incrementFailure() {
            failureCount.incrementAndGet();
        }

        public void addItems(final int items) {
            totalItems.addAndGet(items);
        }

        public void addDuration(final long durationMs) {
            totalDuration.addAndGet(durationMs);
        }

        public long getCount() {
            return count.get();
        }

        public long getSuccessCount() {
            return successCount.get();
        }

        public long getFailureCount() {
            return failureCount.get();
        }

        public double getAverageItems() {
            long cnt = count.get();
            return cnt > 0 ? (double) totalItems.get() / cnt : 0;
        }

        public double getAverageDuration() {
            long cnt = count.get();
            return cnt > 0 ? (double) totalDuration.get() / cnt : 0;
        }
    }
}

