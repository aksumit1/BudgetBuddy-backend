package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for RetryHelper
 */
class RetryHelperTest {

    @Test
    void testExecuteWithRetry_WithSuccessfulOperation_ReturnsResult() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        
        // When
        String result = RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                },
                3,
                Duration.ofMillis(10),
                2.0
        );

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteWithRetry_WithRetryableException_RetriesAndSucceeds() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        ProvisionedThroughputExceededException retryableException = 
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When
        String result = RetryHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw retryableException;
                    }
                    return "success";
                },
                3,
                Duration.ofMillis(10),
                2.0
        );

        // Then
        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testExecuteWithRetry_WithNonRetryableException_ThrowsImmediately() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        IllegalArgumentException nonRetryableException = new IllegalArgumentException("Invalid input");

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> 
                RetryHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            throw nonRetryableException;
                        },
                        3,
                        Duration.ofMillis(10),
                        2.0
                ));

        assertEquals(1, attempts.get(), "Should not retry non-retryable exceptions");
    }

    @Test
    void testExecuteWithRetry_WithMaxRetriesExceeded_ThrowsException() {
        // Given
        ProvisionedThroughputExceededException retryableException = 
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When/Then
        assertThrows(RuntimeException.class, () -> 
                RetryHelper.executeWithRetry(
                        () -> {
                            throw retryableException;
                        },
                        2,
                        Duration.ofMillis(10),
                        2.0
                ));
    }

    @Test
    void testExecuteWithRetry_WithDefaultParameters_Succeeds() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);

        // When
        String result = RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                }
        );

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteWithRetry_WithSdkServiceException_Retries() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        SdkServiceException serviceException = SdkServiceException.builder()
                .statusCode(503)
                .message("Service unavailable")
                .build();

        // When
        String result = RetryHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw serviceException;
                    }
                    return "success";
                },
                3,
                Duration.ofMillis(10),
                2.0
        );

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteWithRetry_WithSdkClientException_Retries() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        SdkClientException clientException = SdkClientException.builder()
                .message("Connection timeout")
                .build();

        // When
        String result = RetryHelper.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw clientException;
                    }
                    return "success";
                },
                3,
                Duration.ofMillis(10),
                2.0
        );

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteDynamoDbWithRetry_WithSuccessfulOperation_ReturnsResult() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);

        // When
        String result = RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return "success";
                }
        );

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteDynamoDbWithRetry_WithThrottlingException_Retries() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        ProvisionedThroughputExceededException throttlingException = 
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When
        String result = RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw throttlingException;
                    }
                    return "success";
                }
        );

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteDynamoDbWithRetry_WithInternalServerError_Retries() {
        // Given
        AtomicInteger attempts = new AtomicInteger(0);
        InternalServerErrorException serverError = 
                InternalServerErrorException.builder().message("Internal error").build();

        // When
        String result = RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 2) {
                        throw serverError;
                    }
                    return "success";
                }
        );

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }
}
