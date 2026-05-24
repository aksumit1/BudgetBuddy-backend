package com.budgetbuddy.service.subscription;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-user canonical merchant master-list. Maps every variant of a
 * merchant name the user has actually transacted with ("DJ BARRONS",
 * "D.J. Barrons", "DJ*BARRONS") to a single canonical display name.
 *
 * <p>Built lazily — first call for a user scans the user's transactions
 * + active subscriptions, groups variants by their normalised key
 * (lowercase + punctuation stripped), and picks the highest-frequency
 * raw spelling as the canonical display. Cached for 30 minutes per
 * user so repeated lookups (sparkline, subscription list, cancellation
 * recs) don't all re-scan transactions.
 *
 * <p>Consumers ({@link com.budgetbuddy.service.MerchantSpendTrendService},
 * fuzzy match in {@link com.budgetbuddy.service.SubscriptionInsightsService})
 * call {@link #canonicalFor(String, String)} to resolve a raw merchant
 * name to its canonical form before further matching. This turns
 * heuristic similarity tests into exact-string lookups for any merchant
 * the user has previously transacted with, which is the bulk of cases.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class MerchantCanonicalisationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MerchantCanonicalisationService.class);

    /** Same shape as SubscriptionInsightsService.normalizeMerchantName. */
    private static final Pattern PUNCT = Pattern.compile("[^a-z0-9]+");

    private static final long TTL_MS = 30L * 60 * 1000;
    private static final int MAX_USERS = 1_000;

    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MerchantCanonicalisationService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Resolve a raw merchant name to the user's canonical display for
     * that merchant. Returns the input unchanged when the user has no
     * prior transaction matching it.
     */
    public String canonicalFor(final String userId, final String rawMerchantName) {
        if (userId == null || rawMerchantName == null || rawMerchantName.isBlank()) {
            return rawMerchantName;
        }
        final Map<String, String> map = mapFor(userId);
        final String key = normalise(rawMerchantName);
        return map.getOrDefault(key, rawMerchantName);
    }

    /** Full canonical map for the user (normalisedKey → displayName). */
    public Map<String, String> mapFor(final String userId) {
        if (userId == null || userId.isEmpty()) return Map.of();
        final CacheEntry hit = cache.get(userId);
        final long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) return hit.map;
        ensureCacheCapacity();
        final Map<String, String> built = build(userId);
        cache.put(userId, new CacheEntry(now + TTL_MS, built));
        return built;
    }

    /** Invalidate so the next read rebuilds from current transactions. */
    public void invalidateUser(final String userId) {
        if (userId != null) cache.remove(userId);
    }

    private Map<String, String> build(final String userId) {
        // Bucket: normalisedKey → (rawSpelling → seenCount).
        final Map<String, Map<String, Integer>> buckets = new HashMap<>();
        try {
            for (final TransactionTable t : transactionRepository.findByUserId(userId, 0, 10_000)) {
                if (t == null || t.getMerchantName() == null) continue;
                if (t.getDeletedAt() != null) continue;
                track(buckets, t.getMerchantName());
            }
        } catch (Exception e) {
            LOGGER.debug("MerchantCanonicalisation: tx fetch failed for {}: {}", userId, e.getMessage());
        }
        try {
            for (final Subscription s : subscriptionService.getSubscriptions(userId)) {
                if (s == null || s.getMerchantName() == null) continue;
                if (!Boolean.TRUE.equals(s.getActive())) continue;
                // Subscription-confirmed names get +5 weight so the user-facing
                // subscription label wins over occasional transaction spellings.
                for (int i = 0; i < 5; i++) track(buckets, s.getMerchantName());
            }
        } catch (Exception e) {
            LOGGER.debug("MerchantCanonicalisation: sub fetch failed for {}: {}",
                    userId, e.getMessage());
        }

        final Map<String, String> out = new LinkedHashMap<>(buckets.size());
        for (final Map.Entry<String, Map<String, Integer>> e : buckets.entrySet()) {
            final List<Map.Entry<String, Integer>> bySeen = new ArrayList<>(e.getValue().entrySet());
            bySeen.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
            out.put(e.getKey(), bySeen.get(0).getKey());
        }
        return out;
    }

    private static void track(
            final Map<String, Map<String, Integer>> buckets, final String raw) {
        final String key = normalise(raw);
        if (key.isEmpty()) return;
        buckets.computeIfAbsent(key, k -> new HashMap<>())
                .merge(raw.trim(), 1, Integer::sum);
    }

    private static String normalise(final String raw) {
        if (raw == null) return "";
        return PUNCT.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private void ensureCacheCapacity() {
        if (cache.size() < MAX_USERS) return;
        final long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (cache.size() < MAX_USERS) return;
        // Evict the entry with the soonest expiry — same pattern the
        // budget / subscription summary caches use.
        cache.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.comparingLong(c -> c.expiresAt)))
                .ifPresent(e -> cache.remove(e.getKey(), e.getValue()));
    }

    private static final class CacheEntry {
        final long expiresAt;
        final Map<String, String> map;

        CacheEntry(final long expiresAt, final Map<String, String> map) {
            this.expiresAt = expiresAt;
            this.map = java.util.Collections.unmodifiableMap(map);
        }
    }

}
