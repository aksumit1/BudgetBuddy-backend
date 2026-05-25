package com.budgetbuddy.service.insights.ai;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-user sliding-window rate limit for AI chat. Keeps both LLM cost
 * (each turn is a billable Anthropic call) and accidental retry loops
 * in check without blocking legitimate burst usage.
 *
 * <p>Default: 5 messages per 60-second window per user. Configurable
 * via {@code app.insights.chat.rate-limit.*}.
 *
 * <p>Sliding window means: a user who sends 5 messages at second 0 can
 * send another at second 60 (not before); a user spread across 10
 * seconds is fine. Strict fixed windows would let a user double-spend
 * around the boundary (5 at :55, 5 at :00); sliding avoids that.
 *
 * <p>In-process, single-replica safe. Multi-replica deployments will
 * see 5×N effective limit until we wire to Redis. That's a known
 * follow-up — for the typical single-pod dev/staging it's fine, and
 * production deployments can either accept the higher ceiling or add
 * the Redis-backed limiter.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Stateless service — no external mutables")
@Service
public class ChatRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRateLimiter.class);
    private static final int CACHE_CAP = 10_000;

    @Value("${app.insights.chat.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${app.insights.chat.rate-limit.max-per-window:5}")
    private int maxPerWindow;

    /**
     * Per-user deque of recent turn timestamps (epoch millis). The
     * deque is mutated under its own lock by {@code synchronized}
     * blocks below — concurrent users hit different keys, so the
     * outer ConcurrentHashMap takes the multi-thread brunt.
     */
    private final ConcurrentMap<String, Deque<Long>> recentByUser = new ConcurrentHashMap<>();

    /**
     * @return true if the call is allowed (and recorded); false if
     *         the user has exceeded their window.
     */
    public boolean tryAcquire(final String userId) {
        if (userId == null || userId.isBlank()) {
            return true; // anonymous — let auth layer reject upstream
        }
        evictIfOversized();
        final long now = System.currentTimeMillis();
        final long windowStart = now - windowSeconds * 1_000L;
        final Deque<Long> deque = recentByUser.computeIfAbsent(
                userId, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            // Drop entries that fell out of the window.
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPerWindow) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "ChatRateLimiter: user {} exceeded {}/{}s window",
                            userId, maxPerWindow, windowSeconds);
                }
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /**
     * @return seconds the caller should wait before retrying. Always
     *         the conservative {@code windowSeconds}; returning the
     *         exact wait would require exposing the oldest stamp,
     *         which leaks info to the client without benefit.
     */
    public int retryAfterSeconds() {
        return windowSeconds;
    }

    /**
     * Prevent unbounded growth under attack. When the cache exceeds
     * the cap, drop the user with the smallest recent activity first
     * — these are stale anyway (their window has long since rolled
     * off).
     */
    private void evictIfOversized() {
        if (recentByUser.size() < CACHE_CAP) {
            return;
        }
        final long windowStart = System.currentTimeMillis() - windowSeconds * 1_000L;
        recentByUser.entrySet().removeIf(e -> {
            final Deque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst() < windowStart) {
                    d.pollFirst();
                }
                return d.isEmpty();
            }
        });
    }
}
