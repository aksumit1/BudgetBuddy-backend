package com.budgetbuddy.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RetryHelper utility class
 */
class RetryHelperTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(RetryHelper.class);
        
        // Remove any existing appenders to avoid duplicates
        logger.detachAndStopAllAppenders();
        
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testExecuteWithRetry_WithSuccessfulOperation_ReturnsResult() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When
        String result = RetryHelper.executeWithRetry(() -> {
            attempts.incrementAndGet();
            return "success";
        }, 3, Duration.ofMillis(10), 2.0);

        // Then
        assertEquals("success", result, "Should return result on success");
        assertEquals(1, attempts.get(), "Should execute only once on success");
    }

    @Test
    void testExecuteWithRetry_WithFailureThenSuccess_RetriesAndSucceeds() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When
        String result = RetryHelper.executeWithRetry(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("Temporary failure");
            }
            return "success";
        }, 3, Duration.ofMillis(10), 2.0);

        // Then
        assertEquals("success", result, "Should succeed after retry");
        assertEquals(2, attempts.get(), "Should retry once then succeed");
    }

    @Test
    void testExecuteWithRetry_WithAllFailures_ThrowsException() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When/Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Always fails");
            }, 2, Duration.ofMillis(10), 2.0);
        }, "Should throw exception after all retries exhausted");

        // Then
        assertEquals(3, attempts.get(), "Should attempt initial + 2 retries = 3 total");
        assertTrue(exception.getMessage().contains("failed after 2 retries"), 
                "Exception message should indicate retry failure");
    }

    @Test
    void testExecuteWithRetry_WithDefaultSettings_Succeeds() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When
        String result = RetryHelper.executeWithRetry(() -> {
            attempts.incrementAndGet();
            return "success";
        });

        // Then
        assertEquals("success", result, "Should succeed with default settings");
        assertEquals(1, attempts.get(), "Should execute once");
    }

    @Test
    void testExecuteWithRetry_WithCustomMaxRetries_RespectsLimit() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When/Then
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Always fails");
            }, 1); // Only 1 retry
        }, "Should throw exception after custom retry limit");

        // Then
        assertEquals(2, attempts.get(), "Should attempt initial + 1 retry = 2 total");
    }

    @Test
    void testExecuteWithRetry_WithExponentialBackoff_RespectsDelay() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        
        // When
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Always fails");
            }, 2, Duration.ofMillis(50), 2.0); // 50ms initial, doubles each time
        });

        // Then
        long elapsed = System.currentTimeMillis() - startTime;
        // Should have at least: 50ms (first retry) + 100ms (second retry) = 150ms minimum
        // Plus execution time, so allow some buffer
        assertTrue(elapsed >= 100, "Should respect exponential backoff delays");
        assertEquals(3, attempts.get(), "Should attempt 3 times");
    }

    @Test
    void testExecuteWithRetryVoid_WithSuccessfulOperation_ExecutesOnce() {
        // Given
        AtomicInteger executions = new AtomicInteger(0);
        
        // When
        RetryHelper.executeWithRetryVoid(() -> {
            executions.incrementAndGet();
        });

        // Then
        assertEquals(1, executions.get(), "Should execute once on success");
    }

    @Test
    void testExecuteWithRetryVoid_WithFailureThenSuccess_RetriesAndSucceeds() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When
        RetryHelper.executeWithRetryVoid(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("Temporary failure");
            }
        }, 3, Duration.ofMillis(10), 2.0);

        // Then
        assertEquals(2, attempts.get(), "Should retry once then succeed");
    }

    @Test
    void testExecuteWithRetryVoid_WithAllFailures_ThrowsException() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When/Then
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetryVoid(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Always fails");
            }, 2, Duration.ofMillis(10), 2.0);
        }, "Should throw exception after all retries exhausted");

        // Then
        assertEquals(3, attempts.get(), "Should attempt 3 times");
    }

    @Test
    void testExecuteWithRetry_WithInterruptedThread_ThrowsException() {
        // Given
        Thread currentThread = Thread.currentThread();
        
        // When/Then
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                currentThread.interrupt();
                throw new RuntimeException("Failure");
            }, 2, Duration.ofMillis(100), 2.0);
        }, "Should handle thread interruption");

        // Then - Thread should be interrupted
        assertTrue(Thread.currentThread().isInterrupted(), "Thread should be interrupted");
        // Clear interrupt flag for other tests
        Thread.interrupted();
    }

    @Test
    void testExecuteWithRetry_WithZeroRetries_ExecutesOnce() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When/Then
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Failure");
            }, 0, Duration.ofMillis(10), 2.0);
        }, "Should throw exception with zero retries");

        // Then
        assertEquals(1, attempts.get(), "Should execute only once with zero retries");
        
        // Verify logging behavior - with zero retries, should log ERROR immediately (no WARN logs)
        List<ILoggingEvent> logEvents = logAppender.list;
        long warnLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .count();
        long errorLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        
        // Should have 0 WARN logs (no retries) and 1 ERROR log (immediate failure)
        assertEquals(0, warnLogs, "Should not log WARN with zero retries");
        assertEquals(1, errorLogs, "Should log ERROR when max retries is 0");
        
        // Verify ERROR log contains expected message
        // Use getFormattedMessage() to get the actual formatted message, not the template
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getFormattedMessage().contains("Operation failed after 0 retries"));
        
        assertTrue(foundErrorLog, "Should log ERROR with retry count message");
    }

    @Test
    void testExecuteWithRetry_WithDifferentExceptionTypes_HandlesAll() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When/Then
        assertThrows(RuntimeException.class, () -> {
            RetryHelper.executeWithRetry(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new IllegalArgumentException("Illegal argument");
                } else if (attempt == 2) {
                    throw new NullPointerException("Null pointer");
                } else {
                    throw new IllegalStateException("Illegal state");
                }
            }, 2, Duration.ofMillis(10), 2.0);
        }, "Should handle different exception types");

        // Then
        assertEquals(3, attempts.get(), "Should retry for all exception types");
        
        // Verify logging behavior - should log WARN for retries and ERROR for final failure
        List<ILoggingEvent> logEvents = logAppender.list;
        long warnLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .count();
        long errorLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        
        // Should have 2 WARN logs (for 2 retries) and 1 ERROR log (for final failure)
        assertEquals(2, warnLogs, "Should log WARN for each retry attempt");
        assertEquals(1, errorLogs, "Should log ERROR when all retries exhausted");
        
        // Verify ERROR log contains expected message
        // Use getFormattedMessage() to get the actual formatted message, not the template
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getFormattedMessage().contains("Operation failed after 2 retries"));
        
        assertTrue(foundErrorLog, "Should log ERROR with retry count message");
    }
}

