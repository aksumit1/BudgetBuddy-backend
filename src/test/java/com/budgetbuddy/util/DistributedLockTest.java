package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DistributedLock utility
 */
class DistributedLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DistributedLock distributedLock;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Should acquire lock successfully when Redis is available")
    void testAcquireLock_Success() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        DistributedLock.LockResult result = distributedLock.acquireLock("test-key");

        // Then
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
        verify(valueOperations).setIfAbsent(eq("lock:test-key"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Should fail to acquire lock when already locked")
    void testAcquireLock_AlreadyLocked() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        DistributedLock.LockResult result = distributedLock.acquireLock("test-key");

        // Then
        assertFalse(result.isAcquired());
        assertNull(result.getLockValue());
    }

    @Test
    @DisplayName("Should fall back to local lock when Redis is unavailable")
    void testAcquireLock_RedisUnavailable() {
        // Given
        distributedLock = new DistributedLock(null);

        // When
        DistributedLock.LockResult result = distributedLock.acquireLock("test-key");

        // Then
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("Should return false for null or empty lock key")
    void testAcquireLock_InvalidKey() {
        // Given
        distributedLock = new DistributedLock(redisTemplate);

        // When
        DistributedLock.LockResult result1 = distributedLock.acquireLock(null);
        DistributedLock.LockResult result2 = distributedLock.acquireLock("");

        // Then
        assertFalse(result1.isAcquired());
        assertFalse(result2.isAcquired());
    }

    @Test
    @DisplayName("Should acquire lock with custom timeout")
    void testAcquireLock_CustomTimeout() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        distributedLock = new DistributedLock(redisTemplate);

        // When
        DistributedLock.LockResult result = distributedLock.acquireLock("test-key", Duration.ofSeconds(60));

        // Then
        assertTrue(result.isAcquired());
        verify(valueOperations).setIfAbsent(eq("lock:test-key"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("Should release lock successfully")
    void testReleaseLock_Success() {
        // Given
        when(redisTemplate.execute(any(), any(), any())).thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        boolean released = distributedLock.releaseLock("test-key", "lock-value");

        // Then
        assertTrue(released);
        verify(redisTemplate).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Should fail to release lock with wrong value")
    void testReleaseLock_WrongValue() {
        // Given
        when(redisTemplate.execute(any(), any(), any())).thenReturn(0L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        boolean released = distributedLock.releaseLock("test-key", "wrong-value");

        // Then
        assertFalse(released);
    }

    @Test
    @DisplayName("Should handle Redis failure during release gracefully")
    void testReleaseLock_RedisFailure() {
        // Given
        when(redisTemplate.execute(any(), any(), any())).thenThrow(new RuntimeException("Redis down"));
        distributedLock = new DistributedLock(redisTemplate);

        // When
        boolean released = distributedLock.releaseLock("test-key", "lock-value");

        // Then - Should return true to allow operation to continue
        assertTrue(released);
    }

    @Test
    @DisplayName("Should skip release when Redis is unavailable")
    void testReleaseLock_RedisUnavailable() {
        // Given
        distributedLock = new DistributedLock(null);

        // When
        boolean released = distributedLock.releaseLock("test-key", "lock-value");

        // Then
        assertTrue(released);
        verify(redisTemplate, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Should return false for invalid lock key or value")
    void testReleaseLock_InvalidInput() {
        // Given
        distributedLock = new DistributedLock(redisTemplate);

        // When
        boolean result1 = distributedLock.releaseLock(null, "value");
        boolean result2 = distributedLock.releaseLock("key", null);
        boolean result3 = distributedLock.releaseLock("", "value");

        // Then
        assertFalse(result1);
        assertFalse(result2);
        assertFalse(result3);
    }

    @Test
    @DisplayName("Should execute operation with lock")
    void testExecuteWithLock_Success() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(), any(), any())).thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        String result = distributedLock.executeWithLock("test-key", Duration.ofSeconds(30), () -> "success");

        // Then
        assertEquals("success", result);
        verify(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(redisTemplate).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when lock acquisition fails")
    void testExecuteWithLock_LockAcquisitionFailed() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        distributedLock = new DistributedLock(redisTemplate);

        // When/Then
        assertThrows(DistributedLock.LockAcquisitionException.class, () -> {
            distributedLock.executeWithLock("test-key", Duration.ofSeconds(30), () -> "success");
        });
    }

    @Test
    @DisplayName("Should release lock even when operation throws exception")
    void testExecuteWithLock_ExceptionDuringOperation() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(), any(), any())).thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            distributedLock.executeWithLock("test-key", Duration.ofSeconds(30), () -> {
                throw new RuntimeException("Operation failed");
            });
        });

        // Then - Lock should be released
        verify(redisTemplate).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Should return Optional.empty when lock not acquired")
    void testTryExecuteWithLock_LockNotAcquired() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        Optional<String> result = distributedLock.tryExecuteWithLock("test-key", Duration.ofSeconds(30), () -> "success");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return result when lock acquired")
    void testTryExecuteWithLock_Success() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(), any(), any())).thenReturn(1L);
        distributedLock = new DistributedLock(redisTemplate);

        // When
        Optional<String> result = distributedLock.tryExecuteWithLock("test-key", Duration.ofSeconds(30), () -> "success");

        // Then
        assertTrue(result.isPresent());
        assertEquals("success", result.get());
    }

    @Test
    @DisplayName("Should fall back to local lock when Redis throws exception")
    void testAcquireLock_RedisException() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection failed"));
        distributedLock = new DistributedLock(redisTemplate);

        // When
        DistributedLock.LockResult result = distributedLock.acquireLock("test-key");

        // Then - Should fall back to local lock
        assertTrue(result.isAcquired());
        assertNotNull(result.getLockValue());
    }
}

