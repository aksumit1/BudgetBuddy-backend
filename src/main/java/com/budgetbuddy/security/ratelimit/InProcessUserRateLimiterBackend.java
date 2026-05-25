package com.budgetbuddy.security.ratelimit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Single-process sliding-window backend. Default backend when
 * {@code app.ratelimit.backend} is unset or {@code in-process}.
 *
 * <p>Suitable for dev/staging and single-pod production. Multi-pod
 * deployments will see Nx the effective per-user cap because each pod
 * tracks its own counters — wire {@link RedisUserRateLimiterBackend}
 * via {@code app.ratelimit.backend=redis} for those.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Stateless service — no external mutables")
@Service
@ConditionalOnProperty(
        name = "app.ratelimit.backend",
        havingValue = "in-process",
        matchIfMissing = true)
public class InProcessUserRateLimiterBackend implements UserRateLimiterBackend {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InProcessUserRateLimiterBackend.class);
    private static final int CACHE_CAP = 10_000;

    private final ConcurrentMap<String, ConcurrentMap<String, Deque<Long>>> hits =
            new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(
            final String bucket, final String userId,
            final int maxPerWindow, final int windowSeconds) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        evictIfOversized(windowSeconds);
        final long now = System.currentTimeMillis();
        final long windowStart = now - windowSeconds * 1_000L;
        final Deque<Long> deque = hits
                .computeIfAbsent(bucket, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPerWindow) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    private void evictIfOversized(final int windowSeconds) {
        for (final var e : hits.entrySet()) {
            final var bucketCounts = e.getValue();
            if (bucketCounts.size() < CACHE_CAP) {
                continue;
            }
            final long windowStart = System.currentTimeMillis() - windowSeconds * 1_000L;
            bucketCounts.entrySet().removeIf(entry -> {
                final Deque<Long> d = entry.getValue();
                synchronized (d) {
                    while (!d.isEmpty() && d.peekFirst() < windowStart) {
                        d.pollFirst();
                    }
                    return d.isEmpty();
                }
            });
        }
        if (hits.size() > CACHE_CAP && LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "InProcessUserRateLimiterBackend: bucket map exceeded {} entries — "
                            + "consider switching to the Redis backend.",
                    CACHE_CAP);
        }
    }
}
