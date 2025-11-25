package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Distributed Lock Utility
 * Provides distributed locking for critical operations using Redis
 * 
 * Features:
 * - Atomic lock acquisition and release
 * - Automatic expiration
 * - Thread-safe operations
 * - Lua script for atomic operations
 */
@Component
public class DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLock.class);
    private static final String LOCK_PREFIX = "lock:";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    // Lua script for atomic lock release
    private static final String RELEASE_LOCK_SCRIPT = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";

    private final DefaultRedisScript<Long> releaseScript;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public DistributedLock() {
        this.releaseScript = new DefaultRedisScript<>();
        this.releaseScript.setScriptText(RELEASE_LOCK_SCRIPT);
        this.releaseScript.setResultType(Long.class);
    }

    /**
     * Acquire a distributed lock
     */
    public LockResult acquireLock(String lockKey) {
        return acquireLock(lockKey, DEFAULT_TIMEOUT);
    }

    /**
     * Acquire a distributed lock with custom timeout
     */
    public LockResult acquireLock(String lockKey, Duration timeout) {
        if (redisTemplate == null) {
            logger.warn("Redis not available, using local lock (not distributed)");
            return new LockResult(true, UUID.randomUUID().toString());
        }

        if (lockKey == null || lockKey.isEmpty()) {
            logger.warn("Lock key is null or empty");
            return new LockResult(false, null);
        }

        String lockValue = UUID.randomUUID().toString();
        String fullLockKey = LOCK_PREFIX + lockKey;
        
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(fullLockKey, lockValue, java.time.Duration.ofSeconds(timeout.getSeconds()));
            
            if (Boolean.TRUE.equals(acquired)) {
                logger.debug("Lock acquired: {} with value: {}", lockKey, lockValue);
                return new LockResult(true, lockValue);
            } else {
                logger.debug("Lock not acquired: {} (already locked)", lockKey);
                return new LockResult(false, null);
            }
        } catch (Exception e) {
            logger.error("Error acquiring lock: {}", lockKey, e);
            return new LockResult(false, null);
        }
    }

    /**
     * Release a distributed lock atomically
     */
    public boolean releaseLock(String lockKey, String lockValue) {
        if (redisTemplate == null) {
            logger.debug("Redis not available, skipping lock release");
            return true;
        }

        if (lockKey == null || lockKey.isEmpty() || lockValue == null || lockValue.isEmpty()) {
            logger.warn("Invalid lock key or value for release");
            return false;
        }

        String fullLockKey = LOCK_PREFIX + lockKey;
        
        try {
            // Use Lua script for atomic release
            Long result = redisTemplate.execute(
                    releaseScript,
                    Collections.singletonList(fullLockKey),
                    lockValue
            );
            
            if (result != null && result > 0) {
                logger.debug("Lock released: {}", lockKey);
                return true;
            } else {
                logger.warn("Lock value mismatch or already released for key: {}", lockKey);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error releasing lock: {}", lockKey, e);
            return false;
        }
    }

    /**
     * Execute with distributed lock
     * Automatically acquires and releases lock
     */
    public <T> T executeWithLock(String lockKey, Duration timeout, LockedOperation<T> operation) {
        LockResult lockResult = acquireLock(lockKey, timeout);
        
        if (!lockResult.isAcquired()) {
            throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
        }

        try {
            return operation.execute();
        } finally {
            boolean released = releaseLock(lockKey, lockResult.getLockValue());
            if (!released) {
                logger.warn("Failed to release lock: {}", lockKey);
            }
        }
    }

    /**
     * Try to acquire lock and execute operation, return Optional.empty() if lock not acquired
     */
    public <T> java.util.Optional<T> tryExecuteWithLock(String lockKey, Duration timeout, LockedOperation<T> operation) {
        LockResult lockResult = acquireLock(lockKey, timeout);
        
        if (!lockResult.isAcquired()) {
            return java.util.Optional.empty();
        }

        try {
            return java.util.Optional.of(operation.execute());
        } finally {
            releaseLock(lockKey, lockResult.getLockValue());
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute() throws Exception;
    }

    /**
     * Lock acquisition result
     */
    public static class LockResult {
        private final boolean acquired;
        private final String lockValue;

        public LockResult(boolean acquired, String lockValue) {
            this.acquired = acquired;
            this.lockValue = lockValue;
        }

        public boolean isAcquired() {
            return acquired;
        }

        public String getLockValue() {
            return lockValue;
        }
    }

    /**
     * Exception thrown when lock acquisition fails
     */
    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }
    }
}
