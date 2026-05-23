package com.budgetbuddy.monitoring;

import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surface subscription-detection telemetry on /actuator/health so
 * regressions are visible without grepping logs.
 *
 * <p>Today reports:
 *   <ul>
 *     <li>{@code subscriptions.deletes.total} — total user deletions
 *         (vote of "false positive" against the detector)
 *     <li>{@code subscriptions.deletes.byMerchant[X]} — top 10
 *         merchants by deletion count
 *   </ul>
 *
 * <p>Always UP — these counters describe operating range, not failures.
 * Alert on them via Prometheus rules: e.g.
 * {@code subscriptions.deletes.total} growing &gt;100/hour means the
 * detector is mis-classifying something popular and users are cleaning
 * up after it.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Component
public class SubscriptionDetectionHealthIndicator implements HealthIndicator {

    private final SubscriptionService subscriptionService;

    public SubscriptionDetectionHealthIndicator(final SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public Health health() {
        final Map<String, Object> details = new LinkedHashMap<>();
        try {
            details.putAll(subscriptionService.getDetectionTelemetry());
        } catch (RuntimeException ex) {
            details.put("error", ex.getMessage());
        }
        return Health.up().withDetails(details).build();
    }
}
