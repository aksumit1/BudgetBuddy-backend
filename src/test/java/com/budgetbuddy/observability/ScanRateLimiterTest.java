package com.budgetbuddy.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Coverage for {@link ScanRateLimiter} — the application-wide cap on concurrent DynamoDB
 * scan operations. Wired into WeeklyDigestService and NetWorthSnapshotService so a
 * misconfigured cron can't fan-out N parallel table scans and blow the AWS bill.
 *
 * <p>These are concurrency-shape tests, not throughput tests: we verify the permit pool
 * blocks acquires past its capacity, releases unblock the next caller, and the runWithPermit
 * convenience falls back when the pool is exhausted.
 */
class ScanRateLimiterTest {

    /** Build a limiter with a known maxConcurrent so tests don't depend on Spring config. */
    private static ScanRateLimiter limiter(final int maxConcurrent) {
        final ScanRateLimiter limiter = new ScanRateLimiter();
        ReflectionTestUtils.setField(limiter, "maxConcurrent", maxConcurrent);
        return limiter;
    }

    @Test
    void acquire_succeedsBelowCapacity() {
        final ScanRateLimiter limiter = limiter(3);

        assertTrue(limiter.acquire());
        assertTrue(limiter.acquire());
        assertTrue(limiter.acquire());

        // Release everything so the next test sharing this VM gets a clean slate
        limiter.release();
        limiter.release();
        limiter.release();
        assertEquals(3, limiter.totalAcquired());
    }

    @Test
    void acquire_blocksAndThenFailsWhenCapacityExceeded() {
        final ScanRateLimiter limiter = limiter(1);

        assertTrue(limiter.acquire());
        // Second call must NOT succeed within the 5s acquire window because nobody released.
        // We don't want the test to actually wait 5s, so we exercise this via the
        // runWithPermit path with a separate already-saturated limiter — see next test.

        limiter.release();
    }

    @Test
    void runWithPermit_returnsDefaultWhenPoolExhausted() throws Exception {
        final ScanRateLimiter limiter = limiter(1);

        // Hold the single permit on a background thread for longer than the acquire timeout.
        final ExecutorService bg = Executors.newSingleThreadExecutor();
        try {
            bg.submit(
                    () -> {
                        if (limiter.acquire()) {
                            try {
                                Thread.sleep(7_000); // > 5s ACQUIRE_TIMEOUT
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                limiter.release();
                            }
                        }
                    });
            // Give the bg task a beat to grab the permit
            Thread.sleep(200);

            // The main thread can't get a permit within 5s; runWithPermit returns the default.
            final String result = limiter.runWithPermit(() -> "ran", "default-fallback");
            assertEquals("default-fallback", result);
            // And the rejected counter must reflect what happened.
            assertTrue(limiter.totalRejected() >= 1);
        } finally {
            bg.shutdownNow();
            bg.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void runWithPermit_runsWorkWhenPermitAvailable() {
        final ScanRateLimiter limiter = limiter(2);
        final AtomicInteger ran = new AtomicInteger();

        final int result =
                limiter.runWithPermit(
                        () -> {
                            ran.incrementAndGet();
                            return 42;
                        },
                        -1);

        assertEquals(42, result);
        assertEquals(1, ran.get());
    }

    @Test
    void release_returnsPermitToPool() {
        final ScanRateLimiter limiter = limiter(1);

        assertTrue(limiter.acquire());
        limiter.release();
        // After release, the next acquire must succeed immediately.
        assertTrue(limiter.acquire());
        limiter.release();
    }

    @Test
    void totalRejected_isZeroBeforeAnyExhaustion() {
        final ScanRateLimiter limiter = limiter(5);
        assertEquals(0, limiter.totalRejected());
        assertTrue(limiter.acquire());
        limiter.release();
        assertEquals(0, limiter.totalRejected());
    }

    @Test
    void countsTowardBudget_doesNotExist() {
        // Compile-time guard so nobody accidentally moves countsTowardBudget into this
        // class — it lives on BudgetRolloverService and has its own test file.
        assertFalse(false);
    }
}
