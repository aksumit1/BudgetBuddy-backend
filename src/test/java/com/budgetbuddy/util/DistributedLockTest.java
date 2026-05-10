package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Comprehensive tests for DistributedLock utility */
class DistributedLockTest {

    private static final String TEST_KEY = "test-key";
    private static final String SUCCESS = "success";

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    private DistributedLock distributedLock;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Should acquire lock successfully when Redis is available")
    void testAcquireLockSuccess() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        final DistributedLock.LockResult result = distributedLock.acquireLock(TEST_KEY);

        // Then
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
        verify(valueOperations).setIfAbsent(eq("lock:test-key"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Should fail to acquire lock when already locked")
    void testAcquireLockAlreadyLocked() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        final DistributedLock.LockResult result = distributedLock.acquireLock(TEST_KEY);

        // Then
        assertFalse(result.isAcquired());
        assertNull(result.getLockValue());
    }

    @Test
    @DisplayName("Should fall back to local lock when Redis is unavailable")
    void testAcquireLockRedisUnavailable() {
        // Given
        distributedLock = new DistributedLock(null);

        // When
        final DistributedLock.LockResult result = distributedLock.acquireLock(TEST_KEY);

        // Then
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("Should return false for null or empty lock key")
    void testAcquireLockInvalidKey() {
        // Given
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final DistributedLock.LockResult result1 = distributedLock.acquireLock(null);
        final DistributedLock.LockResult result2 = distributedLock.acquireLock("");

        // Then
        assertFalse(result1.isAcquired());
        assertFalse(result2.isAcquired());
    }

    @Test
    @DisplayName("Should acquire lock with custom timeout")
    void testAcquireLockCustomTimeout() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        final DistributedLock.LockResult result =
                distributedLock.acquireLock(TEST_KEY, Duration.ofSeconds(60));

        // Then
        assertTrue(result.isAcquired());
        verify(valueOperations)
                .setIfAbsent(eq("lock:test-key"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("Should release lock successfully")
    void testReleaseLockSuccess() {
        // Given
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final boolean released = distributedLock.releaseLock(TEST_KEY, "lock-value");

        // Then
        assertTrue(released);
        verify(redisTemplate)
                .execute(any(DefaultRedisScript.class), any(java.util.List.class), any());
    }

    @Test
    @DisplayName("Should fail to release lock with wrong value")
    void testReleaseLockWrongValue() {
        // Given
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenReturn(0L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final boolean released = distributedLock.releaseLock(TEST_KEY, "wrong-value");

        // Then
        assertFalse(released);
    }

    @Test
    @DisplayName("Should handle Redis failure during release gracefully")
    void testReleaseLockRedisFailure() {
        // Given
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenThrow(new RuntimeException("Redis down"));
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final boolean released = distributedLock.releaseLock(TEST_KEY, "lock-value");

        // Then - Should return true to allow operation to continue
        assertTrue(released);
    }

    @Test
    @DisplayName("Should skip release when Redis is unavailable")
    void testReleaseLockRedisUnavailable() {
        // Given
        distributedLock = new DistributedLock(null);

        // When
        final boolean released = distributedLock.releaseLock(TEST_KEY, "lock-value");

        // Then
        assertTrue(released);
        verify(redisTemplate, never())
                .execute(any(DefaultRedisScript.class), any(java.util.List.class), any());
    }

    @Test
    @DisplayName("Should return false for invalid lock key or value")
    void testReleaseLockInvalidInput() {
        // Given
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final boolean result1 = distributedLock.releaseLock(null, "value");
        final boolean result2 = distributedLock.releaseLock("key", null);
        final boolean result3 = distributedLock.releaseLock("", "value");

        // Then
        assertFalse(result1);
        assertFalse(result2);
        assertFalse(result3);
    }

    @Test
    @DisplayName("Should execute operation with lock")
    void testExecuteWithLockSuccess() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final String result =
                distributedLock.executeWithLock(TEST_KEY, Duration.ofSeconds(30), () -> SUCCESS);

        // Then
        assertEquals(SUCCESS, result);
        verify(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(redisTemplate)
                .execute(any(DefaultRedisScript.class), any(java.util.List.class), any());
    }

    @Test
    @DisplayName("Should throw exception when lock acquisition fails")
    void testExecuteWithLockLockAcquisitionFailed() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        distributedLock = new DistributedLock(redisTemplate);

        // When/Then
        assertThrows(
                DistributedLock.LockAcquisitionException.class,
                () -> {
                    distributedLock.executeWithLock(
                            TEST_KEY, Duration.ofSeconds(30), () -> SUCCESS);
                });
    }

    @Test
    @DisplayName("Should release lock even when operation throws exception")
    void testExecuteWithLockExceptionDuringOperation() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    distributedLock.executeWithLock(
                            TEST_KEY,
                            Duration.ofSeconds(30),
                            () -> {
                                throw new RuntimeException("Operation failed");
                            });
                });

        // Then - Lock should be released
        verify(redisTemplate)
                .execute(any(DefaultRedisScript.class), any(java.util.List.class), any());
    }

    @Test
    @DisplayName("Should return Optional.empty when lock not acquired")
    void testTryExecuteWithLockLockNotAcquired() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final Optional<String> result =
                distributedLock.tryExecuteWithLock(TEST_KEY, Duration.ofSeconds(30), () -> SUCCESS);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return result when lock acquired")
    void testTryExecuteWithLockSuccess() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(DefaultRedisScript.class), any(java.util.List.class), any()))
                .thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final Optional<String> result =
                distributedLock.tryExecuteWithLock(TEST_KEY, Duration.ofSeconds(30), () -> SUCCESS);

        // Then
        assertTrue(result.isPresent());
        assertEquals(SUCCESS, result.get());
    }

    @Test
    @DisplayName("Should fall back to local lock when Redis throws exception")
    void testAcquireLockRedisException() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection failed"));
        distributedLock = new DistributedLock(redisTemplate);

        // When
        final DistributedLock.LockResult result = distributedLock.acquireLock(TEST_KEY);

        // Then - Should fall back to local lock
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
    }
}
