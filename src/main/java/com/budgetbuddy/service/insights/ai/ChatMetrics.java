package com.budgetbuddy.service.insights.ai;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Micrometer-backed observability for AI chat turns. Lets ops:
 * <ul>
 *   <li>Track cost — input/output token counts per model/mode tag, so
 *       a runaway "summarisation"-mode loop is visible in dashboards
 *       before it shows up on the invoice.</li>
 *   <li>Track latency — p50/p95/p99 per model/mode.</li>
 *   <li>Catch failures — error counter tagged by reason.</li>
 *   <li>Catch abuse — turn counter tagged by mode + a per-user
 *       "rate-limit hit" counter.</li>
 * </ul>
 *
 * <p>Meters are created lazily per {@code (model, mode)} tag pair and
 * cached so we don't allocate per turn. Standard Micrometer tag
 * cardinality applies — keep tag values bounded.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — MeterRegistry shared by design")
@Service
public class ChatMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> turnCounterCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> inputTokenCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> outputTokenCache =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounterCache = new ConcurrentHashMap<>();
    private final Counter rateLimitedCounter;

    public ChatMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.rateLimitedCounter = Counter.builder("budgetbuddy.chat.rate_limited")
                .description("AI chat turns rejected by per-user rate limiter")
                .register(registry);
    }

    /** Record one successful turn — latency, tokens, count. */
    public void recordTurn(
            final String model,
            final String mode,
            final long latencyMillis,
            final int inputTokens,
            final int outputTokens) {
        final String key = model + "|" + mode;
        timerCache.computeIfAbsent(key, k -> Timer.builder("budgetbuddy.chat.turn.latency")
                        .tag("model", model)
                        .tag("mode", mode)
                        .description("Wall-clock latency of one AI chat turn (ms)")
                        .register(registry))
                .record(java.time.Duration.ofMillis(latencyMillis));
        turnCounterCache.computeIfAbsent(key, k -> Counter.builder("budgetbuddy.chat.turn.count")
                        .tag("model", model)
                        .tag("mode", mode)
                        .description("Successful AI chat turns")
                        .register(registry))
                .increment();
        if (inputTokens > 0) {
            inputTokenCache.computeIfAbsent(key, k -> DistributionSummary
                            .builder("budgetbuddy.chat.tokens.input")
                            .tag("model", model)
                            .tag("mode", mode)
                            .description("Input tokens per turn")
                            .register(registry))
                    .record(inputTokens);
        }
        if (outputTokens > 0) {
            outputTokenCache.computeIfAbsent(key, k -> DistributionSummary
                            .builder("budgetbuddy.chat.tokens.output")
                            .tag("model", model)
                            .tag("mode", mode)
                            .description("Output tokens per turn")
                            .register(registry))
                    .record(outputTokens);
        }
    }

    /** Record a failed turn, tagged by reason ("llm-timeout", "bad-json", etc.). */
    public void recordFailure(final String model, final String mode, final String reason) {
        final String key = model + "|" + mode + "|" + reason;
        errorCounterCache.computeIfAbsent(key, k -> Counter.builder("budgetbuddy.chat.turn.errors")
                        .tag("model", model)
                        .tag("mode", mode)
                        .tag("reason", reason)
                        .description("AI chat turn failures")
                        .register(registry))
                .increment();
    }

    /** Record a rate-limited request (the per-user limiter returned false). */
    public void recordRateLimited() {
        rateLimitedCounter.increment();
    }
}
