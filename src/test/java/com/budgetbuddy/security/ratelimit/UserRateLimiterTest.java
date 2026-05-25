package com.budgetbuddy.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the generalised rate limiter behaviour. Generic name +
 * generic bucket registration — same sliding-window semantics as the
 * ChatRateLimiter it replaced.
 */
class UserRateLimiterTest {

    private UserRateLimiter rl;

    @BeforeEach
    void setUp() {
        // Construct directly with in-process backend; bypass Spring's
        // @ConditionalOnProperty so tests don't need to set the
        // backend property.
        rl = new UserRateLimiter(new InProcessUserRateLimiterBackend());
        rl.registerBucket("test-bucket", 3, 60);
    }

    @Test
    void allows_upToCap_perUserPerBucket() {
        assertTrue(rl.tryAcquire("test-bucket", "u1"));
        assertTrue(rl.tryAcquire("test-bucket", "u1"));
        assertTrue(rl.tryAcquire("test-bucket", "u1"));
    }

    @Test
    void rejects_oneOverTheCap() {
        for (int i = 0; i < 3; i++) {
            rl.tryAcquire("test-bucket", "u1");
        }
        assertFalse(rl.tryAcquire("test-bucket", "u1"));
    }

    @Test
    void independentLimits_acrossUsers() {
        for (int i = 0; i < 3; i++) {
            rl.tryAcquire("test-bucket", "u1");
        }
        // u2 unaffected.
        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("test-bucket", "u2"));
        }
    }

    @Test
    void independentLimits_acrossBuckets() {
        rl.registerBucket("other-bucket", 3, 60);
        for (int i = 0; i < 3; i++) {
            rl.tryAcquire("test-bucket", "u1");
        }
        // Different bucket: full quota.
        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("other-bucket", "u1"));
        }
    }

    @Test
    void unknownBucket_failsOpen_logsWarning() {
        // Calling tryAcquire for an un-registered bucket should
        // ALLOW (not throw) but the limiter logs a warning so the
        // omission is visible.
        assertTrue(rl.tryAcquire("never-registered", "u1"));
    }

    @Test
    void nullUserId_isAllowed() {
        assertTrue(rl.tryAcquire("test-bucket", null));
        assertTrue(rl.tryAcquire("test-bucket", ""));
    }

    @Test
    void retryAfterSeconds_reflectsWindow() {
        rl.registerBucket("quick", 1, 7);
        assertTrue(rl.tryAcquire("quick", "u1"));
        assertFalse(rl.tryAcquire("quick", "u1"));
        // Retry-After should match the configured window.
        org.junit.jupiter.api.Assertions.assertEquals(7, rl.retryAfterSeconds("quick"));
    }
}
