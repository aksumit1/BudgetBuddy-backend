package com.budgetbuddy.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Batch Operation Metrics Service Tracks performance metrics for batch operations */
@Component
public class BatchOperationMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchOperationMetrics.class);

    private final Map<String, OperationStats> operationStats = new ConcurrentHashMap<>();

    /** Record batch operation execution */
    public void recordBatchOperation(
            final String operationType,
            final int itemCount,
            final long durationMs,
            final boolean success) {
        final OperationStats stats =
                operationStats.computeIfAbsent(operationType, k -> new OperationStats());

        stats.incrementCount();
        stats.addItems(itemCount);
        stats.addDuration(durationMs);

        if (success) {
            stats.incrementSuccess();
        } else {
            stats.incrementFailure();
        }
    }

    /** Get statistics for an operation type */
    public OperationStats getStats(final String operationType) {
        return operationStats.getOrDefault(operationType, new OperationStats());
    }

    /** Get all statistics */
    public Map<String, OperationStats> getAllStats() {
        return new ConcurrentHashMap<>(operationStats);
    }

    /** Log batch operation statistics */
    public void logStats() {
        LOGGER.info("=== Batch Operation Statistics ===");
        for (final Map.Entry<String, OperationStats> entry : operationStats.entrySet()) {
            final OperationStats stats = entry.getValue();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Operation: {} | Count: {} | Success: {} | Failure: {} | Avg Items: {} | Avg Duration: {}ms",
                        entry.getKey(),
                        stats.getCount(),
                        stats.getSuccessCount(),
                        stats.getFailureCount(),
                        stats.getAverageItems(),
                        stats.getAverageDuration());
            }
        }
        LOGGER.info("==================================");
    }

    /** Operation statistics */
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
            final long cnt = count.get();
            return cnt > 0 ? (double) totalItems.get() / cnt : 0;
        }

        public double getAverageDuration() {
            final long cnt = count.get();
            return cnt > 0 ? (double) totalDuration.get() / cnt : 0;
        }
    }
}
