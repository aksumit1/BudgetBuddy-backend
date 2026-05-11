package com.budgetbuddy.observability;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralised cost guardrail for DynamoDB scan operations.
 *
 * <p>Several scheduled jobs ({@code WeeklyDigestService}, {@code NetWorthSnapshotService}, {@code
 * TransactionRepository.findByUserIdCapped} fallback) drive table scans without any upstream
 * throttling. If a cron malfunctions and fires hourly instead of weekly, or if a GSI gets
 * accidentally removed and every fallback path engages at once, on-demand DynamoDB costs can
 * balloon into the thousands of dollars per hour with no in-process safety net.
 *
 * <p>This component caps the number of concurrent scans application-wide via a {@link Semaphore}
 * and surfaces a simple counter that ops can scrape via the actuator. The permit count is
 * intentionally low (default 3) because every scan touches the whole table; legitimate concurrent
 * demand should still fit because scheduled jobs run at staggered times and disaster fallbacks are
 * by definition rare.
 *
 * <p>Callers wrap their scan with {@link #acquire()} and {@link #release()} (or use the {@link
 * #runWithPermit(java.util.function.Supplier)} sugar). When the permit pool is exhausted, {@code
 * acquire()} blocks up to {@link #ACQUIRE_TIMEOUT_MS} and then returns false; callers should fall
 * through to a degraded code path rather than block a Tomcat worker indefinitely.
 */
@Component
public class ScanRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanRateLimiter.class);

    /** How long {@link #acquire()} blocks before giving up. */
    private static final long ACQUIRE_TIMEOUT_MS = 5_000L;

    @Value("${app.dynamodb.scan-max-concurrent:3}")
    private int maxConcurrent;

    private volatile Semaphore permits;
    private final AtomicLong totalAcquired = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    private Semaphore permitsOrInit() {
        Semaphore s = permits;
        if (s == null) {
            synchronized (this) {
                if (permits == null) {
                    permits = new Semaphore(Math.max(1, maxConcurrent), /* fair */ true);
                }
                s = permits;
            }
        }
        return s;
    }

    /**
     * Try to acquire a permit. Returns true on success — caller must then release in finally.
     * Returns false if no permit became available within {@link #ACQUIRE_TIMEOUT_MS}; caller should
     * log + skip the scan rather than block longer.
     */
    public boolean acquire() {
        try {
            final boolean ok =
                    permitsOrInit()
                            .tryAcquire(
                                    ACQUIRE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ok) {
                totalAcquired.incrementAndGet();
            } else {
                totalRejected.incrementAndGet();
                LOGGER.warn(
                        "ScanRateLimiter exhausted (max={}). Caller should skip the scan.",
                        maxConcurrent);
            }
            return ok;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            totalRejected.incrementAndGet();
            return false;
        }
    }

    public void release() {
        permitsOrInit().release();
    }

    /**
     * Convenience: run {@code work} while holding a permit. Returns the result, or {@code
     * defaultIfRejected} when the limiter denied entry — caller decides what fallback means for
     * their use case (often "return empty list and log").
     */
    public <T> T runWithPermit(
            final java.util.function.Supplier<T> work, final T defaultIfRejected) {
        if (!acquire()) {
            return defaultIfRejected;
        }
        try {
            return work.get();
        } finally {
            release();
        }
    }

    /** Counter exposed for actuator / Prometheus scrape. */
    public long totalAcquired() {
        return totalAcquired.get();
    }

    /** Counter exposed for actuator / Prometheus scrape. */
    public long totalRejected() {
        return totalRejected.get();
    }
}
