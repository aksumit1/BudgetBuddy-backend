package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

/** Unit Tests for RetryHelper */
class RetryHelperTest {

    @Test
    void testExecuteWithRetryWithSuccessfulOperationReturnsResult() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);

        // When
        final String result =
                RetryHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            return "success";
                        },
                        3,
                        Duration.ofMillis(10),
                        2.0);

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteWithRetryWithRetryableExceptionRetriesAndSucceeds() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final ProvisionedThroughputExceededException retryableException =
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When
        final String result =
                RetryHelper.executeWithRetry(
                        () -> {
                            final int attempt = attempts.incrementAndGet();
                            if (attempt < 3) {
                                throw retryableException;
                            }
                            return "success";
                        },
                        3,
                        Duration.ofMillis(10),
                        2.0);

        // Then
        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testExecuteWithRetryWithNonRetryableExceptionThrowsImmediately() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final IllegalArgumentException nonRetryableException =
                new IllegalArgumentException("Invalid input");

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RetryHelper.executeWithRetry(
                                () -> {
                                    attempts.incrementAndGet();
                                    throw nonRetryableException;
                                },
                                3,
                                Duration.ofMillis(10),
                                2.0));

        assertEquals(1, attempts.get(), "Should not retry non-retryable exceptions");
    }

    @Test
    void testExecuteWithRetryWithMaxRetriesExceededThrowsException() {
        // Given
        final ProvisionedThroughputExceededException retryableException =
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When/Then
        assertThrows(
                RuntimeException.class,
                () ->
                        RetryHelper.executeWithRetry(
                                () -> {
                                    throw retryableException;
                                },
                                2,
                                Duration.ofMillis(10),
                                2.0));
    }

    @Test
    void testExecuteWithRetryWithDefaultParametersSucceeds() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);

        // When
        final String result =
                RetryHelper.executeWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            return "success";
                        });

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteWithRetryWithSdkServiceExceptionRetries() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final SdkServiceException serviceException =
                SdkServiceException.builder()
                        .statusCode(503)
                        .message("Service unavailable")
                        .build();

        // When
        final String result =
                RetryHelper.executeWithRetry(
                        () -> {
                            final int attempt = attempts.incrementAndGet();
                            if (attempt < 2) {
                                throw serviceException;
                            }
                            return "success";
                        },
                        3,
                        Duration.ofMillis(10),
                        2.0);

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteWithRetryWithSdkClientExceptionRetries() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final SdkClientException clientException =
                SdkClientException.builder().message("Connection timeout").build();

        // When
        final String result =
                RetryHelper.executeWithRetry(
                        () -> {
                            final int attempt = attempts.incrementAndGet();
                            if (attempt < 2) {
                                throw clientException;
                            }
                            return "success";
                        },
                        3,
                        Duration.ofMillis(10),
                        2.0);

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteDynamoDbWithRetryWithSuccessfulOperationReturnsResult() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);

        // When
        final String result =
                RetryHelper.executeDynamoDbWithRetry(
                        () -> {
                            attempts.incrementAndGet();
                            return "success";
                        });

        // Then
        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteDynamoDbWithRetryWithThrottlingExceptionRetries() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final ProvisionedThroughputExceededException throttlingException =
                ProvisionedThroughputExceededException.builder().message("Throttled").build();

        // When
        final String result =
                RetryHelper.executeDynamoDbWithRetry(
                        () -> {
                            final int attempt = attempts.incrementAndGet();
                            if (attempt < 2) {
                                throw throttlingException;
                            }
                            return "success";
                        });

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void testExecuteDynamoDbWithRetryWithInternalServerErrorRetries() {
        // Given
        final AtomicInteger attempts = new AtomicInteger(0);
        final InternalServerErrorException serverError =
                InternalServerErrorException.builder().message("Internal error").build();

        // When
        final String result =
                RetryHelper.executeDynamoDbWithRetry(
                        () -> {
                            final int attempt = attempts.incrementAndGet();
                            if (attempt < 2) {
                                throw serverError;
                            }
                            return "success";
                        });

        // Then
        assertEquals("success", result);
        assertTrue(attempts.get() >= 2);
    }
}
