package com.budgetbuddy.security.ratelimit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic per-user sliding-window rate limiter façade. Holds the
 * bucket registry (window + cap per bucket name) and delegates the
 * actual counter math to a pluggable {@link UserRateLimiterBackend}:
 *
 * <ul>
 *   <li>{@link InProcessUserRateLimiterBackend} — default; single-pod
 *       correct.</li>
 *   <li>{@link RedisUserRateLimiterBackend} — multi-pod safe; toggle
 *       via {@code app.ratelimit.backend=redis}.</li>
 * </ul>
 *
 * <p>The limiter never throws — exceeding the cap returns
 * {@code false} from {@link #tryAcquire}; callers translate that into
 * a 429 with {@code Retry-After}. Unknown bucket name → fail-open
 * (with a WARN log) so a forgotten {@link #registerBucket} call
 * doesn't block traffic outright.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class UserRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserRateLimiter.class);

    private final ConcurrentMap<String, BucketConfig> buckets = new ConcurrentHashMap<>();
    private final UserRateLimiterBackend backend;

    public UserRateLimiter(final UserRateLimiterBackend backend) {
        this.backend = backend;
        LOGGER.info(
                "UserRateLimiter using backend: {}",
                backend.getClass().getSimpleName());
    }

    /**
     * Define (or redefine) a named bucket. Idempotent — re-registering
     * with new limits silently updates them. Call once at startup.
     *
     * @param bucket       short name (e.g. "pdf-import", "transactions-write")
     * @param maxPerWindow allowed requests per window
     * @param windowSeconds window length in seconds
     */
    public void registerBucket(
            final String bucket, final int maxPerWindow, final int windowSeconds) {
        buckets.put(bucket, new BucketConfig(maxPerWindow, windowSeconds));
    }

    /**
     * Try to consume one request token for {@code userId} in
     * {@code bucket}. Returns {@code true} when allowed, {@code false}
     * when the user has hit the bucket's cap.
     */
    public boolean tryAcquire(final String bucket, final String userId) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        final BucketConfig cfg = buckets.get(bucket);
        if (cfg == null) {
            LOGGER.warn(
                    "UserRateLimiter: bucket '{}' is not registered — request allowed by default."
                            + " Register the bucket at startup.",
                    bucket);
            return true;
        }
        return backend.tryAcquire(bucket, userId, cfg.maxPerWindow(), cfg.windowSeconds());
    }

    /**
     * @return Retry-After seconds for the named bucket. Conservative:
     *         returns the full window length.
     */
    public int retryAfterSeconds(final String bucket) {
        final BucketConfig cfg = buckets.get(bucket);
        return cfg == null ? 60 : cfg.windowSeconds();
    }

    /** Sealed bucket config — bucket name implicit (key of the map). */
    public record BucketConfig(int maxPerWindow, int windowSeconds) {}
}
