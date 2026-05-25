package com.budgetbuddy.security.ratelimit;

/**
 * Backend strategy contract for {@link UserRateLimiter}. Two
 * implementations ship:
 * <ul>
 *   <li>In-process — fast, no extra infra; correct only for single-pod
 *       deployments. The default for dev/staging.</li>
 *   <li>Redis-backed — sliding window counters in Redis;
 *       correct across pods. Toggled via
 *       {@code app.ratelimit.backend=redis} in production.</li>
 * </ul>
 *
 * <p>Same contract as {@link UserRateLimiter#tryAcquire} but
 * parameterised by bucket + window/cap so a backend can be shared
 * across buckets.
 */
public interface UserRateLimiterBackend {

    /**
     * Try to consume one token for {@code userId} in {@code bucket}.
     *
     * @param bucket       bucket name (e.g. "pdf-import")
     * @param userId       caller identity
     * @param maxPerWindow allowed requests per window
     * @param windowSeconds window length in seconds
     * @return true when allowed (and recorded), false when over cap
     */
    boolean tryAcquire(
            String bucket, String userId, int maxPerWindow, int windowSeconds);
}
