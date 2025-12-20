package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;

import java.time.Duration;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Retry Helper Utility
 * Provides retry logic with exponential backoff for batch operations and DynamoDB operations
 * 
 * ENHANCED: Now handles DynamoDB-specific errors (throttling, service errors) with intelligent retry logic
 */
public class RetryHelper {

    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final Random random = new Random();

    private RetryHelper() {
        // Utility class
    }

    /**
     * Check if an exception is retryable (DynamoDB throttling or transient service errors)
     */
    private static boolean isRetryableException(final Exception e) {
        // DynamoDB-specific retryable exceptions
        if (e instanceof ProvisionedThroughputExceededException ||
            e instanceof InternalServerErrorException) {
            return true;
        }
        
        // SDK service exceptions with retryable status codes
        if (e instanceof SdkServiceException) {
            SdkServiceException sdkException = (SdkServiceException) e;
            int statusCode = sdkException.statusCode();
            // 500, 502, 503, 504 are retryable
            return statusCode >= 500 && statusCode < 600;
        }
        
        // SDK client exceptions (network issues) are retryable
        if (e instanceof SdkClientException) {
            return true;
        }
        
        // Check if exception message indicates retryable error
        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("throttl") ||
                   lowerMessage.contains("rate limit") ||
                   lowerMessage.contains("service unavailable") ||
                   lowerMessage.contains("internal server error") ||
                   lowerMessage.contains("timeout") ||
                   lowerMessage.contains("connection");
        }
        
        return false;
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
                // Only retry if exception is retryable
                if (!isRetryableException(e)) {
                    logger.debug("Exception is not retryable, throwing immediately: {}", e.getClass().getSimpleName());
                    throw e;
                }
                
                if (attempt >= maxRetries) {
                    logger.error("Operation failed after {} retries (retryable error): {}", maxRetries, e.getMessage(), e);
                    throw new RuntimeException("Operation failed after " + maxRetries + " retries", e);
                }

                // Add jitter to prevent thundering herd
                long jitter = random.nextInt((int) (currentDelay.toMillis() * 0.1)); // 10% jitter
                long delayWithJitter = currentDelay.toMillis() + jitter;

                logger.warn("Operation failed (attempt {}/{}), retrying in {}ms (retryable error: {}): {}",
                        attempt + 1, maxRetries, delayWithJitter, e.getClass().getSimpleName(), e.getMessage());

                try {
                    Thread.sleep(delayWithJitter);
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

    /**
     * Execute DynamoDB operation with retry for throttling and service errors
     * Uses longer delays for DynamoDB operations (throttling can be persistent)
     */
    public static <T> T executeDynamoDbWithRetry(final Supplier<T> operation) {
        return executeWithRetry(
                operation,
                DEFAULT_MAX_RETRIES,
                Duration.ofMillis(200), // Longer initial delay for DynamoDB
                DEFAULT_BACKOFF_MULTIPLIER
        );
    }

    /**
     * Execute DynamoDB operation with custom retry settings
     */
    public static <T> T executeDynamoDbWithRetry(
            final Supplier<T> operation,
            final int maxRetries) {
        return executeWithRetry(
                operation,
                maxRetries,
                Duration.ofMillis(200),
                DEFAULT_BACKOFF_MULTIPLIER
        );
    }
}

