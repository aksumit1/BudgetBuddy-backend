package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Retry Helper Utility
 * Provides retry logic with exponential backoff for batch operations
 */
public class RetryHelper {

    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private RetryHelper() {
        // Utility class
    }

    /**
     * Execute operation with retry and exponential backoff
     *
     * @param operation Operation to execute
     * @param maxRetries Maximum number of retries
     * @param initialDelay Initial delay before first retry
     * @param backoffMultiplier Multiplier for exponential backoff
     * @return Result of the operation
     * @throws RuntimeException if operation fails after all retries
     */
    public static <T> T executeWithRetry(
            final Supplier<T> operation,
            final int maxRetries,
            final Duration initialDelay,
            final double backoffMultiplier) {
        int attempt = 0;
        Duration currentDelay = initialDelay;

        while (attempt <= maxRetries) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    logger.error("Operation failed after {} retries: {}", maxRetries, e.getMessage(), e);
                    throw new RuntimeException("Operation failed after " + maxRetries + " retries", e);
                }

                logger.warn("Operation failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, maxRetries, currentDelay.toMillis(), e.getMessage());

                try {
                    Thread.sleep(currentDelay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }

                currentDelay = Duration.ofMillis((long) (currentDelay.toMillis() * backoffMultiplier));
                attempt++;
            }
        }

        throw new RuntimeException("Operation failed after " + maxRetries + " retries");
    }

    /**
     * Execute operation with default retry settings
     */
    public static <T> T executeWithRetry(final Supplier<T> operation) {
        return executeWithRetry(operation, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }

    /**
     * Execute operation with custom max retries
     */
    public static <T> T executeWithRetry(final Supplier<T> operation, final int maxRetries) {
        return executeWithRetry(operation, maxRetries, DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }

    /**
     * Execute void operation with retry
     */
    public static void executeWithRetryVoid(
            final Runnable operation,
            final int maxRetries,
            final Duration initialDelay,
            final double backoffMultiplier) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, maxRetries, initialDelay, backoffMultiplier);
    }

    /**
     * Execute void operation with default retry settings
     */
    public static void executeWithRetryVoid(final Runnable operation) {
        executeWithRetryVoid(operation, DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_MULTIPLIER);
    }
}

