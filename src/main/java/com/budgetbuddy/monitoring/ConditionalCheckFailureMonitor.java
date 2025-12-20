package com.budgetbuddy.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitoring service for ConditionalCheckFailedException
 * Tracks occurrences to identify race conditions and conflicts
 * 
 * MEDIUM PRIORITY: Provides visibility into concurrent update conflicts
 */
@Component
public class ConditionalCheckFailureMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ConditionalCheckFailureMonitor.class);
    
    // Track failures by operation type
    private final Map<String, AtomicLong> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failureCountsByTable = new ConcurrentHashMap<>();
    
    // Track last failure time for alerting
    private final Map<String, Long> lastFailureTime = new ConcurrentHashMap<>();

    /**
     * Record a conditional check failure
     * 
     * @param operation Operation name (e.g., "saveIfNotExists", "updateTransaction")
     * @param tableName Table name (e.g., "Transactions", "Accounts")
     * @param exception The exception that occurred
     */
    public void recordFailure(final String operation, final String tableName, final ConditionalCheckFailedException exception) {
        String operationKey = operation != null ? operation : "unknown";
        String tableKey = tableName != null ? tableName : "unknown";
        
        // Increment counters
        failureCounts.computeIfAbsent(operationKey, k -> new AtomicLong(0)).incrementAndGet();
        failureCountsByTable.computeIfAbsent(tableKey, k -> new AtomicLong(0)).incrementAndGet();
        lastFailureTime.put(operationKey, System.currentTimeMillis());
        
        // Log with context
        logger.warn("Conditional check failed - Operation: {}, Table: {}, Message: {}", 
                operationKey, tableKey, exception.getMessage());
        
        // Alert if failures are frequent (more than 10 in last minute)
        long count = failureCounts.get(operationKey).get();
        if (count % 10 == 0) {
            logger.warn("High frequency of conditional check failures detected - Operation: {}, Count: {}", 
                    operationKey, count);
        }
    }

    /**
     * Get failure count for an operation
     */
    public long getFailureCount(final String operation) {
        AtomicLong count = failureCounts.get(operation);
        return count != null ? count.get() : 0;
    }

    /**
     * Get failure count for a table
     */
    public long getFailureCountByTable(final String tableName) {
        AtomicLong count = failureCountsByTable.get(tableName);
        return count != null ? count.get() : 0;
    }

    /**
     * Get all failure statistics
     */
    public Map<String, Long> getAllFailureCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        failureCounts.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    /**
     * Reset counters (useful for testing or periodic resets)
     */
    public void resetCounters() {
        failureCounts.clear();
        failureCountsByTable.clear();
        lastFailureTime.clear();
        logger.info("Conditional check failure counters reset");
    }
}

