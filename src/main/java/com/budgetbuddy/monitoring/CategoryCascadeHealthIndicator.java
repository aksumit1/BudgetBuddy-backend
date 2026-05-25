package com.budgetbuddy.monitoring;

import com.budgetbuddy.service.category.ChainedLocationLookup;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surface the category cascade's runtime state on the actuator health
 * endpoint so we can spot leaks, exhaustion, or dead layers without
 * waiting for a manual audit. Reports:
 *
 * <ul>
 *   <li>L5 rules count (from {@link MerchantCategoryRules})
 *   <li>L6 chain — total lookups served + how many hit the per-request
 *       budget cap (high count = upstream APIs are slow / unreachable)
 * </ul>
 *
 * <p>Always reports UP — these counters describe healthy operating
 * range, not a hard failure. Set thresholds via Prometheus alerts on
 * the underlying metrics instead.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Component
public class CategoryCascadeHealthIndicator implements HealthIndicator {

    private final ChainedLocationLookup chain;

    public CategoryCascadeHealthIndicator(final ChainedLocationLookup chain) {
        this.chain = chain;
    }

    @Override
    public Health health() {
        final Map<String, Object> details = new LinkedHashMap<>();
        final long total = chain.getTotalLookups();
        final long exceeded = chain.getBudgetExceededCount();
        details.put("l6_total_lookups", total);
        details.put("l6_budget_exceeded", exceeded);
        if (total > 0) {
            details.put(
                    "l6_budget_exceeded_pct",
                    Math.round(100.0 * exceeded / total * 10) / 10.0);
        }
        // l5 rule count is logged at startup ("MerchantCategoryRules: N rule(s)
        // loaded from category-rules-v2.yaml") — operationally that's
        // sufficient since the count only changes on deploy.
        return Health.up().withDetails(details).build();
    }
}
