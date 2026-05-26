package com.budgetbuddy.service.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data-driven merchant→category rules engine. The Java code is a thin
 * evaluator; the rules live in YAML and (eventually) a backing store.
 *
 * <h3>Conflict resolution — three tie-breakers, applied in order</h3>
 *
 * Authors don't manually juggle priority numbers. Resolution is automatic:
 *
 * <ol>
 *   <li><strong>Source priority</strong> — see {@link Source}. User
 *       overrides always beat curated rules; curated rules beat MCC
 *       defaults; MCC defaults beat public-DB guesses; public-DB beats
 *       ML. Lower {@code level} = wins.
 *   <li><strong>Specificity</strong> — within the same source level,
 *       the longer keyword wins. {@code "costco gas"} (10 chars) beats
 *       {@code "costco"} (6 chars) automatically, regardless of declared
 *       priority. This eliminates "did I remember to set priority 950"
 *       fragility.
 *   <li><strong>Declared priority</strong> — an explicit {@code priority}
 *       in the YAML is the final tie-break for the same source +
 *       specificity. Defaults to 0; manual nudges go here when needed.
 * </ol>
 *
 * <h3>Confidence</h3>
 *
 * Each rule may declare a {@code confidence} (0.0-1.0). When the engine
 * matches, it returns both the category and the rule's confidence so
 * downstream UI can show "we're 95% sure this is dining" or decline to
 * auto-apply low-confidence matches.
 *
 * <h3>Scaling beyond YAML</h3>
 *
 * For 10K+ rules, replace {@link #loadRules} with a database-backed
 * loader (DynamoDB or RDS) that streams rule rows into the same
 * {@link Rule} structure. The matcher logic doesn't change. Layers L3
 * (MCC) and L4 (public merchant DBs) plug in by tagging their rules
 * with {@link Source#MCC_DEFAULT} or {@link Source#PUBLIC_DB}.
 *
 * <h3>Thread safety</h3>
 *
 * Rules list is loaded once and never mutated. Concurrent {@link #match}
 * calls are safe without synchronisation.
 */
public final class MerchantCategoryRules {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantCategoryRules.class);

    private final List<Rule> compiledRules;

    public MerchantCategoryRules(final String classpathResource) {
        this.compiledRules =
                Collections.unmodifiableList(loadRules(classpathResource));
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "MerchantCategoryRules: {} rule(s) loaded from {}",
                    compiledRules.size(),
                    classpathResource);
        }
        warnOnRedundantKeywords();
    }

    /**
     * Scan the loaded rules for keywords that are subsumed by a
     * higher-priority rule's keyword — those lower-priority keywords are
     * dead code that will never fire. Warn (don't fail) so the operator
     * can clean them up at their leisure.
     *
     * <p>"Subsumed" means: there exists a higher-priority rule whose
     * keyword is a SUBSTRING of this rule's keyword. Example: if
     * priority-1000 has keyword "automatic payment - thank you" and a
     * priority-500 rule has keyword "automatic payment", the priority-500
     * keyword will never beat the priority-1000 keyword — the redundancy
     * check flags it.
     */
    private void warnOnRedundantKeywords() {
        if (!LOGGER.isWarnEnabled()) {
            return;
        }
        int dead = 0;
        for (int i = 0; i < compiledRules.size(); i++) {
            final Rule lower = compiledRules.get(i);
            for (final KeywordMatcher lowerKw : lower.matchers) {
                for (int j = 0; j < i; j++) {
                    final Rule higher = compiledRules.get(j);
                    if (higher.priority <= lower.priority) continue;
                    if (higher.source.ordinal() > lower.source.ordinal()) continue;
                    for (final KeywordMatcher higherKw : higher.matchers) {
                        // If the higher-priority keyword would match anywhere
                        // the lower keyword matches, the lower is dead code.
                        if (higherKw.keyword != null
                                && lowerKw.keyword != null
                                && lowerKw.keyword.contains(higherKw.keyword)
                                && higher.category.equals(lower.category)) {
                            dead++;
                            if (dead <= 3) {
                                LOGGER.warn(
                                        "Redundant rule keyword: '{}' (prio {}) is subsumed "
                                                + "by '{}' (prio {}) — same category '{}', "
                                                + "lower rule will never fire",
                                        lowerKw.keyword, lower.priority,
                                        higherKw.keyword, higher.priority,
                                        lower.category);
                            }
                        }
                    }
                }
            }
        }
        if (dead > 3) {
            LOGGER.warn("…and {} more redundant rule keyword(s)", dead - 3);
        }
    }

    /**
     * Evaluate rules and return the winning category (or null if no match).
     */
    public String match(final String descLower, final String normalizedDesc) {
        final MatchResult r = matchWithDetails(descLower, normalizedDesc);
        return r == null ? null : r.category;
    }

    /**
     * Evaluate rules and return the full match details (category, source,
     * confidence, and the rule that fired) — useful for debugging and UI
     * "why was this categorised this way?" displays.
     */
    public MatchResult matchWithDetails(final String descLower, final String normalizedDesc) {
        if (descLower == null && normalizedDesc == null) {
            return null;
        }
        Rule best = null;
        KeywordMatcher bestMatcher = null;
        for (final Rule rule : compiledRules) {
            for (final KeywordMatcher matcher : rule.matchers) {
                if (matcher.matches(descLower) || matcher.matches(normalizedDesc)) {
                    if (best == null || beatsCurrent(rule, matcher, best, bestMatcher)) {
                        best = rule;
                        bestMatcher = matcher;
                    }
                    // Don't break — we need to find the most specific match
                    // across all rules in the same source level.
                }
            }
        }
        if (best == null) {
            return null;
        }
        return new MatchResult(
                best.category, best.source, best.confidence, best.id, bestMatcher.keyword);
    }

    /**
     * Apply the three-tier tie-break:
     *   1. Source priority (lower level wins).
     *   2. Specificity (longer keyword wins).
     *   3. Declared priority (higher wins).
     */
    private static boolean beatsCurrent(
            final Rule candidate,
            final KeywordMatcher candidateMatcher,
            final Rule current,
            final KeywordMatcher currentMatcher) {
        // 1. Source priority
        if (candidate.source.level != current.source.level) {
            return candidate.source.level < current.source.level;
        }
        // 2. Specificity (longer keyword wins)
        final int candLen = candidateMatcher.keyword.length();
        final int currLen = currentMatcher.keyword.length();
        if (candLen != currLen) {
            return candLen > currLen;
        }
        // 3. Declared priority
        return candidate.priority > current.priority;
    }

    public int size() {
        return compiledRules.size();
    }

    // -------------------------------------------------------------------
    // Loader
    // -------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Rule> loadRules(final String classpathResource) {
        if (classpathResource == null || classpathResource.isBlank()) {
            return Collections.emptyList();
        }
        try (InputStream in =
                MerchantCategoryRules.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                return Collections.emptyList();
            }
            final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            final Map<String, Object> root =
                    yaml.readValue(
                            in,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<String, Object>>() {});
            final Object rules = root == null ? null : root.get("rules");
            if (!(rules instanceof List)) {
                return Collections.emptyList();
            }
            final List<Rule> out = new ArrayList<>();
            int order = 0;
            for (final Object raw : (List<Object>) rules) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                final Map<String, Object> spec = (Map<String, Object>) raw;
                final String category = stringOrNull(spec.get("category"));
                if (category == null) {
                    continue;
                }
                final String mode = stringOrNull(spec.get("mode"));
                final boolean wordBoundary = "word".equalsIgnoreCase(mode);
                final int priority = intOr(spec.get("priority"), 0);
                final double confidence = doubleOr(spec.get("confidence"), 0.85);
                final Source source = Source.fromString(stringOrNull(spec.get("source")));
                final String id = stringOrNull(spec.get("id"));
                final Object kws = spec.get("keywords");
                if (!(kws instanceof List)) {
                    continue;
                }
                final List<KeywordMatcher> matchers = new ArrayList<>();
                for (final Object kw : (List<Object>) kws) {
                    if (kw == null) {
                        continue;
                    }
                    final String k = kw.toString().toLowerCase(Locale.ROOT);
                    if (k.isEmpty()) {
                        continue;
                    }
                    matchers.add(KeywordMatcher.of(k, wordBoundary));
                }
                if (matchers.isEmpty()) {
                    continue;
                }
                out.add(
                        new Rule(
                                category.toLowerCase(Locale.ROOT),
                                source,
                                priority,
                                confidence,
                                id != null ? id : ("rule-" + order),
                                order++,
                                matchers));
            }
            // Pre-sort so iteration is deterministic. Real conflict
            // resolution happens in beatsCurrent during match — sorting
            // here just provides stable order.
            out.sort(
                    (a, b) -> {
                        final int bySource = Integer.compare(a.source.level, b.source.level);
                        if (bySource != 0) {
                            return bySource;
                        }
                        final int byPrio = Integer.compare(b.priority, a.priority);
                        return byPrio != 0 ? byPrio : Integer.compare(a.order, b.order);
                    });
            return out;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn(
                    "MerchantCategoryRules: failed to load \"{}\" — engine empty. Cause: {}",
                    classpathResource,
                    ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static String stringOrNull(final Object o) {
        return o == null ? null : o.toString();
    }

    private static int intOr(final Object o, final int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleOr(final Object o, final double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // -------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------

    /**
     * Source priority for a rule. Lower {@code level} wins. New layers
     * (database-backed L3/L4, ML L5) slot in by adding an enum value
     * with the appropriate level; no other code changes.
     */
    public enum Source {
        /** User correction in the app — the unambiguous override. */
        USER_OVERRIDE(0),
        /** Product / admin-set per-account or per-org rule. */
        ORG_OVERRIDE(10),
        /** Curated YAML rules in this repository (the current L2 layer). */
        CURATED(20),
        /** ISO 18245 MCC code mapping (when transaction has an MCC). */
        MCC_DEFAULT(30),
        /** Public merchant DB ingestion (Plaid, Google Places, etc.). */
        PUBLIC_DB(40),
        /** Statistical / ML fallback. */
        ML_GUESS(50);

        public final int level;

        Source(final int level) {
            this.level = level;
        }

        static Source fromString(final String s) {
            if (s == null || s.isBlank()) {
                return CURATED;
            }
            try {
                return Source.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CURATED;
            }
        }
    }

    /** Result of a successful match — includes provenance and confidence. */
    public static final class MatchResult {
        public final String category;
        public final Source source;
        public final double confidence;
        public final String ruleId;
        public final String matchedKeyword;

        MatchResult(
                final String category,
                final Source source,
                final double confidence,
                final String ruleId,
                final String matchedKeyword) {
            this.category = category;
            this.source = source;
            this.confidence = confidence;
            this.ruleId = ruleId;
            this.matchedKeyword = matchedKeyword;
        }

        @Override
        public String toString() {
            return String.format(
                    "%s (source=%s, confidence=%.2f, rule=%s, keyword=%s)",
                    category, source, confidence, ruleId, matchedKeyword);
        }
    }

    private static final class Rule {
        final String category;
        final Source source;
        final int priority;
        final double confidence;
        final String id;
        final int order;
        final List<KeywordMatcher> matchers;

        Rule(
                final String category,
                final Source source,
                final int priority,
                final double confidence,
                final String id,
                final int order,
                final List<KeywordMatcher> matchers) {
            this.category = category;
            this.source = source;
            this.priority = priority;
            this.confidence = confidence;
            this.id = id;
            this.order = order;
            this.matchers = matchers;
        }
    }

    abstract static class KeywordMatcher {
        final String keyword;

        KeywordMatcher(final String keyword) {
            this.keyword = keyword;
        }

        abstract boolean matches(String text);

        static KeywordMatcher of(final String keyword, final boolean wordBoundary) {
            return wordBoundary
                    ? new WordMatcher(keyword)
                    : new ContainsMatcher(keyword);
        }
    }

    private static final class ContainsMatcher extends KeywordMatcher {
        ContainsMatcher(final String keyword) {
            super(keyword);
        }

        @Override
        boolean matches(final String text) {
            return text != null && text.contains(keyword);
        }
    }

    private static final class WordMatcher extends KeywordMatcher {
        private final Pattern pattern;

        WordMatcher(final String keyword) {
            super(keyword);
            this.pattern =
                    Pattern.compile(
                            "(?:^|[^A-Za-z0-9])"
                                    + Pattern.quote(keyword)
                                    + "(?:$|[^A-Za-z0-9])",
                            Pattern.CASE_INSENSITIVE);
        }

        @Override
        boolean matches(final String text) {
            return text != null && pattern.matcher(text).find();
        }
    }
}
