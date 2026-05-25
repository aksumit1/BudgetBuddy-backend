package com.budgetbuddy.security.ratelimit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed sliding-window rate limiter. Correct across pods: every
 * replica increments the same key, so the per-user cap is global, not
 * per-pod. Use in production deployments with more than one app
 * replica.
 *
 * <p>Algorithm — fixed-window counter, not true sliding-window-log:
 * <ol>
 *   <li>Compute a bucketed key: {@code ratelimit:<bucket>:<userId>:<windowEpoch>}
 *       where {@code windowEpoch = floor(nowSeconds / windowSeconds)}.</li>
 *   <li>{@code INCR} the key.</li>
 *   <li>If the result is 1 (we just created it), set TTL to the window
 *       length so old buckets evict themselves.</li>
 *   <li>If the counter is &gt; cap, return false.</li>
 * </ol>
 *
 * <p>Trade-off: this is fixed-window, so a user can burst 2× cap
 * around window boundaries. For the size limits we set
 * (5/min, 120/min) this is fine — the absolute worst-case attacker
 * still can't exceed 2× cap. True sliding-window-log uses sorted sets
 * and is heavier; not needed at these thresholds.
 *
 * <p>When Redis is unreachable, fails OPEN (returns true) — we'd
 * rather a brief Redis outage be invisible than reject all user
 * traffic. WARN logs make the degradation visible to ops.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
@ConditionalOnProperty(
        name = "app.ratelimit.backend",
        havingValue = "redis")
public class RedisUserRateLimiterBackend implements UserRateLimiterBackend {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisUserRateLimiterBackend.class);
    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;

    public RedisUserRateLimiterBackend(final StringRedisTemplate redis) {
        this.redis = redis;
        LOGGER.info("Redis-backed rate limiter active — multi-pod safe.");
    }

    @Override
    public boolean tryAcquire(
            final String bucket, final String userId,
            final int maxPerWindow, final int windowSeconds) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        final long nowSec = System.currentTimeMillis() / 1_000L;
        final long windowEpoch = nowSec / windowSeconds;
        final String key = KEY_PREFIX + bucket + ":" + userId + ":" + windowEpoch;
        try {
            final Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count <= maxPerWindow;
        } catch (final RuntimeException e) {
            LOGGER.warn(
                    "RedisUserRateLimiterBackend: Redis unavailable for bucket {} user {} — "
                            + "failing open (counter not enforced). Cause: {}",
                    bucket, userId, e.getMessage());
            return true;
        }
    }
}
