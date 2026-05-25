package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.budgetbuddy.service.TransactionAnomalyService.AnomalyType;
import com.budgetbuddy.service.TransactionAnomalyService.Severity;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the parts of {@link AiAnomalyExplainer} that don't require
 * network: the no-API-key short-circuit, null safety, and the cache key
 * that determines whether two anomalies collapse to one LLM call.
 *
 * <p>Real Anthropic round-trips are exercised via integration testing
 * in a separate environment — unit tests must not hit the network.
 */
class AiAnomalyExplainerTest {

    private AiAnomalyExplainer svc;

    @BeforeEach
    void setUp() {
        svc = new AiAnomalyExplainer();
        // Default state: no API key configured → all calls short-circuit.
        // @Value bindings don't run outside Spring, so the field is null.
    }

    @Test
    void explain_returnsNull_whenApiKeyIsNotConfigured() {
        // Without an API key, the LLM must not be invoked and the
        // method must return null so the caller falls back to the
        // deterministic reason. This is the production-safe default.
        final TransactionAnomaly a = anomaly("Costco", "groceries", "180");
        assertNull(svc.explain(a),
                "No API key → never hit the LLM, always return null");
    }

    @Test
    void explain_returnsNull_forNullAnomaly() {
        // Defensive: caller may pass null when no anomaly was found.
        assertNull(svc.explain(null));
    }

    @Test
    void explain_returnsNull_evenWithApiKey_whenLlmShortCircuits() throws Exception {
        // Set a fake API key but force the cache to "" (sentinel for
        // "LLM gave up on this one"). Verify the cache wins over an
        // expensive network call.
        setApiKey("fake-key-only-for-unit-test");
        final TransactionAnomaly a = anomaly("Starbucks", "dining", "8.50");
        primeCache(AiAnomalyExplainer.cacheKey(a), "");
        assertNull(svc.explain(a),
                "Cached empty-string sentinel must be treated as 'unavailable'");
    }

    @Test
    void explain_returnsCachedExplanation_withoutRecomputing() throws Exception {
        setApiKey("fake-key-only-for-unit-test");
        final TransactionAnomaly a = anomaly("Whole Foods", "groceries", "240");
        final String preset = "Higher-than-usual Whole Foods charge of $240 vs your typical $90.";
        primeCache(AiAnomalyExplainer.cacheKey(a), preset);
        assertEquals(preset, svc.explain(a),
                "Cache hit must return the cached explanation verbatim");
    }

    // ------------------------------------------------------------------
    // cacheKey — collapses look-alike anomalies into one LLM call
    // ------------------------------------------------------------------

    @Test
    void cacheKey_collapsesAmountsInSameTwentyDollarBucket() {
        // $180 and $185 both round to the same $200 bucket — share key.
        // (Same bucketing as AnomalyFeedbackService.fingerprintOf so a
        // dismissed anomaly's explanation also collapses correctly.)
        final TransactionAnomaly a1 = anomaly("Costco", "groceries", "180");
        final TransactionAnomaly a2 = anomaly("Costco", "groceries", "185");
        assertEquals(AiAnomalyExplainer.cacheKey(a1), AiAnomalyExplainer.cacheKey(a2));
    }

    @Test
    void cacheKey_differsAcrossSignificantlyDifferentAmounts() {
        // $50 and $500 must not share a key — one explanation per buck.
        final TransactionAnomaly a1 = anomaly("Costco", "groceries", "50");
        final TransactionAnomaly a2 = anomaly("Costco", "groceries", "500");
        assertNotEquals(AiAnomalyExplainer.cacheKey(a1), AiAnomalyExplainer.cacheKey(a2));
    }

    @Test
    void cacheKey_isCaseInsensitive() {
        final TransactionAnomaly a1 = anomaly("COSTCO", "GROCERIES", "100");
        final TransactionAnomaly a2 = anomaly("costco", "groceries", "100");
        assertEquals(AiAnomalyExplainer.cacheKey(a1), AiAnomalyExplainer.cacheKey(a2));
    }

    @Test
    void cacheKey_isStableForNullMerchantAndCategory() {
        // Defensive: production data has null merchant/category rows.
        // Cache key must still be computable so we don't NPE on the
        // hot path.
        final TransactionAnomaly a = anomaly(null, null, "0");
        final String key = AiAnomalyExplainer.cacheKey(a);
        assertEquals("||0", key);
    }

    // ------------------------------------------------------------------
    // test helpers
    // ------------------------------------------------------------------

    private static TransactionAnomaly anomaly(
            final String merchant, final String category, final String amount) {
        return new TransactionAnomaly(
                "tx-" + merchant,
                amount == null ? null : new BigDecimal(amount).negate(),
                merchant,
                merchant,
                "2026-01-15",
                category,
                AnomalyType.STATISTICAL_OUTLIER,
                Severity.MEDIUM,
                "Test reason");
    }

    private void setApiKey(final String key) throws Exception {
        final Field f = AiAnomalyExplainer.class.getDeclaredField("apiKey");
        f.setAccessible(true);
        f.set(svc, key);
    }

    @SuppressWarnings("unchecked")
    private void primeCache(final String key, final String value) throws Exception {
        final Field f = AiAnomalyExplainer.class.getDeclaredField("sessionCache");
        f.setAccessible(true);
        ((java.util.Map<String, String>) f.get(svc)).put(key, value);
    }
}
