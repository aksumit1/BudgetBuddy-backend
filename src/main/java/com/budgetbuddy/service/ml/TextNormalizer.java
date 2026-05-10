package com.budgetbuddy.service.ml;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight semantic enrichment for merchant text.
 *
 * <p>This is a deliberate stepping stone toward a proper embedding-based semantic layer (DistilBERT
 * / sentence-transformers). Until a model is wired in, we approximate semantic matching by:
 *
 * <p>1. <b>Stemming</b> — collapse English inflections so "groceries", "grocer", "grocery store"
 * share a root. 2. <b>Synonym expansion</b> — project a small set of cross-synonym pairs (e.g.
 * {restaurant, eatery, cafe, diner, bistro}) into a shared canonical token so either side of a
 * matching pair hits the same category cluster. 3. <b>Merchant-noise stripping</b> — remove
 * payment-processor prefixes (SQ*, TST*, PAYPAL *, PP*), transaction-rail boilerplate (POS DEBIT,
 * ACH DEBIT, DIRECT DEP), and trailing location/state noise.
 *
 * <p>The service is intentionally deterministic and cheap — it runs on every transaction during
 * categorization.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class TextNormalizer {

    private static final Set<String> STOP_WORDS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            java.util.Arrays.asList(
                                    "the", "a", "an", "of", "at", "in", "on", "for", "to", "from",
                                    "and", "or", "with", "by", "via", "inc", "llc", "corp", "ltd",
                                    "co")));

    private static final Set<String> MERCHANT_NOISE_PREFIXES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            java.util.Arrays.asList(
                                    "sq*",
                                    "tst*",
                                    "pp*",
                                    "paypal *",
                                    "paypal*",
                                    "sp *",
                                    "sp*",
                                    "venmo *",
                                    "venmo*",
                                    "cash app*",
                                    "cashapp*",
                                    "square *",
                                    "square*",
                                    "stripe *",
                                    "stripe*",
                                    "goog*",
                                    "google *",
                                    "apl*",
                                    "apple.com")));

    private static final Set<String> TRANSACTION_RAIL_NOISE =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            java.util.Arrays.asList(
                                    "pos debit",
                                    "pos credit",
                                    "debit card purchase",
                                    "debit purchase",
                                    "card purchase",
                                    "ach debit",
                                    "ach credit",
                                    "direct dep",
                                    "direct deposit",
                                    "online transfer",
                                    "electronic payment",
                                    "bill pay",
                                    "billpay",
                                    "recurring payment",
                                    "mobile deposit",
                                    "web pmt",
                                    "web payment",
                                    "preauthorized",
                                    "preauth")));

    /**
     * Canonical synonyms — members of each Set are collapsed into the first element (key). Keep
     * this list small, well-curated, and conservative. When in doubt, omit: a wrong synonym creates
     * false positives that are very hard to debug.
     */
    private static final Map<String, String> SYNONYM_TO_CANONICAL;

    static {
        final Map<String, String> m = new HashMap<>();
        addSynonyms(
                m,
                "restaurant",
                "eatery",
                "cafe",
                "diner",
                "bistro",
                "brasserie",
                "kitchen",
                "grill",
                "taverna");
        addSynonyms(
                m,
                "grocery",
                "grocer",
                "market",
                "supermarket",
                "hypermarket",
                "bodega",
                "foodmart");
        addSynonyms(m, "pharmacy", "drugstore", "apothecary", "chemist");
        addSynonyms(m, "fuel", "gas", "petrol", "gasoline", "diesel");
        addSynonyms(m, "transport", "transportation", "transit", "commute", "rideshare");
        addSynonyms(m, "subscription", "membership", "recurring");
        addSynonyms(
                m,
                "utility",
                "utilities",
                "internet",
                "broadband",
                "electric",
                "electricity",
                "water",
                "sewer",
                "trash",
                "garbage");
        addSynonyms(
                m,
                "healthcare",
                "medical",
                "health",
                "clinic",
                "hospital",
                "doctor",
                "physician",
                "dental",
                "dentist");
        addSynonyms(
                m,
                "entertainment",
                "cinema",
                "movies",
                "theater",
                "theatre",
                "concert",
                "streaming");
        addSynonyms(m, "salary", "payroll", "wages", "compensation", "direct");
        addSynonyms(m, "transfer", "xfer", "wire", "zelle", "venmo", "cashapp", "paypal");
        addSynonyms(m, "insurance", "premium", "policy", "underwriting");
        addSynonyms(m, "education", "tuition", "school", "university", "college", "course");
        SYNONYM_TO_CANONICAL = Collections.unmodifiableMap(m);
    }

    private static void addSynonyms(
            final Map<String, String> target, final String canonical, final String... variants) {
        target.put(canonical, canonical);
        for (final String variant : variants) {
            target.put(variant, canonical);
        }
    }

    private TextNormalizer() {}

    /**
     * Strip payment-processor prefixes, transaction-rail boilerplate, ZIP codes, state
     * abbreviations, and trailing store numbers from raw merchant text.
     */
    public static String cleanMerchantText(final String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) {
            return s;
        }

        for (final String prefix : MERCHANT_NOISE_PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
            }
        }

        for (final String noise : TRANSACTION_RAIL_NOISE) {
            if (s.startsWith(noise + " ")) {
                s = s.substring(noise.length()).trim();
            }
            // Also handle mid-string appearances.
            s = s.replace(" " + noise + " ", " ");
        }

        // Drop store numbers, long numeric IDs, ZIPs, state abbrevs, phone numbers.
        s = s.replaceAll("#\\s*\\d+", " ");
        s = s.replaceAll("\\b\\d{4,}\\b", " ");
        s = s.replaceAll("\\b\\d{5}(-\\d{4})?\\b", " ");
        s =
                s.replaceAll(
                        "\\b(al|ak|az|ar|ca|co|ct|de|fl|ga|hi|id|il|in|ia|ks|ky|la|me|md|ma|mi|mn|ms|mo|mt|ne|nv|nh|nj|nm|ny|nc|nd|oh|ok|or|pa|ri|sc|sd|tn|tx|ut|vt|va|wa|wv|wi|wy|dc)\\b",
                        " ");
        s = s.replaceAll("\\+?1?[-.\\s]?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}", " ");

        // Collapse whitespace.
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * Very small English suffix stripper — intentionally not a full Porter stemmer. We only
     * collapse high-frequency inflections likely to show up in merchant text; aggressive stemming
     * causes more harm than good for proper nouns.
     */
    public static String stem(final String token) {
        if (token == null || token.length() <= 3) {
            return token;
        }
        final String t = token;
        if (t.endsWith("ies") && t.length() > 4) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.endsWith("ing") && t.length() > 5) {
            return t.substring(0, t.length() - 3);
        }
        if (t.endsWith("ers") && t.length() > 5) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("es") && t.length() > 4) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("ed") && t.length() > 4) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("er") && t.length() > 4) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("s") && !t.endsWith("ss") && t.length() > 3) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * Expand a token set with its canonical synonyms and stems. The returned set is a superset of
     * the input: original tokens are preserved so exact-match paths keep working.
     */
    public static Set<String> expandWithSynonyms(final Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> expanded = new HashSet<>(tokens.size() * 2);
        for (final String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            final String lower = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(lower)) {
                continue;
            }
            expanded.add(lower);
            final String stemmed = stem(lower);
            if (!stemmed.equals(lower)) {
                expanded.add(stemmed);
            }
            final String canonical = SYNONYM_TO_CANONICAL.get(lower);
            if (canonical != null) {
                expanded.add(canonical);
            }
            final String canonicalStem = SYNONYM_TO_CANONICAL.get(stemmed);
            if (canonicalStem != null) {
                expanded.add(canonicalStem);
            }
        }
        return expanded;
    }
}
