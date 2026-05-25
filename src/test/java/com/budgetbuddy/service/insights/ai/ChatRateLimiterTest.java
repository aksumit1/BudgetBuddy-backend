package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ChatRateLimiter} behaviour at the documented defaults:
 * 5 messages per 60-second window per user, sliding.
 */
class ChatRateLimiterTest {

    private ChatRateLimiter rl;

    @BeforeEach
    void setUp() throws Exception {
        rl = new ChatRateLimiter();
        // @Value defaults aren't applied outside Spring; set explicitly.
        set("windowSeconds", 60);
        set("maxPerWindow", 5);
    }

    @Test
    void allows_upToMaxPerWindow() {
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("u1"),
                    "Acquire " + (i + 1) + "/5 should succeed");
        }
    }

    @Test
    void rejects_oneOverTheLimit() {
        for (int i = 0; i < 5; i++) {
            rl.tryAcquire("u1");
        }
        assertFalse(rl.tryAcquire("u1"),
                "6th acquire in the same window should be rejected");
    }

    @Test
    void independentLimits_perUser() {
        // u1 burns its window; u2 must still get full quota.
        for (int i = 0; i < 5; i++) {
            rl.tryAcquire("u1");
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("u2"),
                    "u2 should not be limited by u1's traffic");
        }
    }

    @Test
    void rollingWindow_evictsExpiredEntries() throws Exception {
        // Shrink window to 1s so the eviction is testable without a long sleep.
        set("windowSeconds", 1);
        set("maxPerWindow", 2);

        rl.tryAcquire("u1");
        rl.tryAcquire("u1");
        assertFalse(rl.tryAcquire("u1"), "third call within 1s must be rejected");

        Thread.sleep(1_100);
        assertTrue(rl.tryAcquire("u1"),
                "After window rolls off, a new acquire should succeed");
    }

    @Test
    void anonymousUser_isAllowed() {
        // Null/blank userId should not be rate-limited by this layer
        // (auth layer rejects upstream).
        assertTrue(rl.tryAcquire(null));
        assertTrue(rl.tryAcquire(""));
        assertTrue(rl.tryAcquire("   "));
    }

    private void set(final String name, final int value) throws Exception {
        final Field f = ChatRateLimiter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(rl, value);
    }
}
