package com.budgetbuddy.security.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registers the named buckets that {@link UserRateLimiter} enforces.
 * Defining them in one place (here) instead of scattered across call
 * sites means ops can see and tune every cap in one config block.
 *
 * <p>All values configurable via {@code app.ratelimit.*}; defaults
 * chosen to be generous enough to not trip normal use but tight
 * enough to bound DoS/runaway-client impact.
 */
@Component
public class RateLimitBuckets {

    /** PDF import — the heaviest write path. 5/minute / user. */
    public static final String PDF_IMPORT = "pdf-import";
    /** Transaction create/update bursts (manual entries, batch imports). */
    public static final String TRANSACTION_WRITE = "transaction-write";
    /** Account create/update. Rare in practice. */
    public static final String ACCOUNT_WRITE = "account-write";

    private final UserRateLimiter limiter;

    @Value("${app.ratelimit.pdf-import.max-per-window:5}")
    private int pdfMax;
    @Value("${app.ratelimit.pdf-import.window-seconds:60}")
    private int pdfWindow;
    @Value("${app.ratelimit.transaction-write.max-per-window:120}")
    private int txMax;
    @Value("${app.ratelimit.transaction-write.window-seconds:60}")
    private int txWindow;
    @Value("${app.ratelimit.account-write.max-per-window:20}")
    private int accountMax;
    @Value("${app.ratelimit.account-write.window-seconds:60}")
    private int accountWindow;

    public RateLimitBuckets(final UserRateLimiter limiter) {
        this.limiter = limiter;
    }

    @PostConstruct
    void register() {
        limiter.registerBucket(PDF_IMPORT, pdfMax, pdfWindow);
        limiter.registerBucket(TRANSACTION_WRITE, txMax, txWindow);
        limiter.registerBucket(ACCOUNT_WRITE, accountMax, accountWindow);
    }
}
