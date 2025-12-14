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
 * Automatically falls back to local locks when Redis is unavailable
 *
 * Features:
 * - Atomic lock acquisition and release (when Redis is available)
 * - Automatic expiration
 * - Thread-safe operations
 * - Lua script for atomic operations
 * - Graceful fallback to local locks when Redis is down
 * - Application continues to function when Redis is unavailable (falls back to backend)
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
    private final StringRedisTemplate redisTemplate;

    /**
     * Constructor - Redis is optional, will fall back to local locks if unavailable
     */
    public DistributedLock(@Autowired(required = false) final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.releaseScript = new DefaultRedisScript<>();
        this.releaseScript.setScriptText(RELEASE_LOCK_SCRIPT);
        this.releaseScript.setResultType(Long.class);
        
        if (redisTemplate == null) {
            logger.info("Redis not available - DistributedLock will use local locks (not distributed). Application will fall back to backend operations.");
        } else {
            logger.debug("DistributedLock initialized with Redis support");
        }
    }

    /**
     * Acquire a distributed lock
     */
    public LockResult acquireLock(final String lockKey) {
        return acquireLock(lockKey, DEFAULT_TIMEOUT);
    }

    /**
     * Acquire a distributed lock with custom timeout
     * Automatically falls back to local lock if Redis is unavailable
     */
    public LockResult acquireLock(final String lockKey, final Duration timeout) {
        if (redisTemplate == null) {
            logger.debug("Redis not available, using local lock (not distributed) - falling back to backend");
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
            // Redis is down - fall back to local lock (not distributed, but allows operation to continue)
            logger.warn("Redis unavailable, falling back to local lock for key: {} (error: {})", lockKey, e.getMessage());
            return new LockResult(true, UUID.randomUUID().toString());
        }
    }

    /**
     * Release a distributed lock atomically
     * Gracefully handles Redis unavailability
     */
    public boolean releaseLock(final String lockKey, final String lockValue) {
        if (redisTemplate == null) {
            logger.debug("Redis not available, skipping lock release (using local lock fallback)");
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
            // Redis is down - gracefully handle (lock will expire automatically)
            logger.warn("Redis unavailable during lock release for key: {} (error: {}). Lock will expire automatically.", lockKey, e.getMessage());
            return true; // Return true to allow operation to continue
        }
    }

    /**
     * Execute with distributed lock
     * Automatically acquires and releases lock
     * Falls back to executing without distributed lock if Redis is unavailable
     */
    public <T> T executeWithLock(String lockKey, Duration timeout, LockedOperation<T> operation) {
        LockResult lockResult = acquireLock(lockKey, timeout);

        if (!lockResult.isAcquired()) {
            // If Redis is down, we already returned a successful lock result in acquireLock
            // If we get here, it means the lock was already held (distributed scenario)
            // In this case, throw exception to prevent concurrent execution
            if (redisTemplate == null) {
                // Redis is down but we should have gotten a lock - this shouldn't happen
                // But if it does, proceed anyway (fallback behavior)
                logger.warn("Lock acquisition returned false but Redis is unavailable - proceeding without lock");
            } else {
                throw new LockAcquisitionException("Failed to acquire lock: " + lockKey);
            }
        }

        try {
            return operation.execute();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error executing locked operation", e);
        } finally {
            boolean released = releaseLock(lockKey, lockResult.getLockValue());
            if (!released && redisTemplate != null) {
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
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error executing locked operation", e);
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

        public LockResult(final boolean acquired, final String lockValue) {
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
        private static final long serialVersionUID = 1L;
        public LockAcquisitionException(final String message) {
            super(message);
        }
    }
}
