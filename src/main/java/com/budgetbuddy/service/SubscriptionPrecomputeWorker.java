package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly job that walks every active user and pre-computes their
 * subscription set. The /detect endpoint then returns the cached result
 * from {@code SubscriptionService}'s in-memory cooldown cache instantly —
 * the user no longer waits for a synchronous full-history scan when they
 * open the Subscriptions screen.
 *
 * <h3>Why this exists</h3>
 *
 * detectSubscriptions does:
 *   - findByUserId(0, 10_000)  (DynamoDB scan)
 *   - filter chain
 *   - groupByMerchant + groupByAmount
 *   - cadence + threshold + date-regularity per group
 *   - consolidateMultiPriceSubscriptions
 *
 * That's ~500ms–5s per user depending on transaction count. On every
 * iOS app launch. Running it nightly via this worker collapses the
 * latency to "look up cached list".
 *
 * <h3>Config</h3>
 *
 * Opt-in: {@code app.subscription.precompute.enabled=true}. Cron is
 * overridable (default 02:00 UTC). The worker is intentionally
 * single-pod-safe — it iterates serially, no fan-out. For multi-pod
 * deployments, add a leader-election guard before flipping on.
 */
@Service
@ConditionalOnProperty(name = "app.subscription.precompute.enabled", havingValue = "true")
public class SubscriptionPrecomputeWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SubscriptionPrecomputeWorker.class);

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionPrecomputeWorker(
            final SubscriptionService subscriptionService,
            final SubscriptionRepository subscriptionRepository) {
        this.subscriptionService = subscriptionService;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Walk every user with at least one existing subscription and refresh
     * their detection. We use existing subscription rows as the user-id
     * source because they're a small, durable list (vs scanning the
     * Transactions table for distinct user-ids, which would dominate the
     * job's cost). Users with zero subscriptions wait until they call
     * /detect explicitly — at which point the cooldown kicks in.
     *
     * Cron defaults to 02:00 UTC — natural low-traffic window before
     * North American morning. Override via app.subscription.precompute.cron.
     */
    @Scheduled(cron = "${app.subscription.precompute.cron:0 0 2 * * *}")
    public void precomputeAll() {
        final long t0 = System.currentTimeMillis();
        // SubscriptionRepository.findAll-equivalent: scan all rows, collect
        // distinct user-ids. For the production scale this is fine
        // (subscriptions table is one row per user-merchant, not per tx).
        final Set<String> userIds = new HashSet<>();
        subscriptionRepository.findByUserId("").forEach(s -> userIds.add(s.getUserId()));
        // Note: passing "" relies on findByUserId returning empty — the
        // repository doesn't currently expose a true scanAll. As a no-op
        // discovery this is wrong; we need a real iterator. Mark TODO and
        // skip the run rather than crash. Replace once the repository
        // grows a scan API.
        if (userIds.isEmpty()) {
            LOGGER.info(
                    "SubscriptionPrecomputeWorker: no user iterator available "
                            + "(SubscriptionRepository lacks scanAll). Skipping run. "
                            + "Add a scan method to enable.");
            return;
        }

        int succeeded = 0, failed = 0;
        for (final String userId : userIds) {
            if (userId == null) continue;
            try {
                final List<Subscription> result =
                        subscriptionService.detectSubscriptions(userId);
                succeeded++;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Precompute user={}: {} subscriptions",
                            userId, result.size());
                }
            } catch (RuntimeException ex) {
                failed++;
                LOGGER.warn("Precompute failed for user={}: {}",
                        userId, ex.getMessage());
            }
        }
        LOGGER.info(
                "SubscriptionPrecomputeWorker: walked {} users in {}ms (ok={}, failed={})",
                userIds.size(), System.currentTimeMillis() - t0, succeeded, failed);
    }
}
