package com.budgetbuddy.service.pdf.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-document scratchpad shared across all rules during one extraction.
 *
 * <p>Two motivations:
 *
 * <ol>
 *   <li><b>Avoid quadratic work.</b> The previous {@code applyLabelRule}
 *       called {@code text.split("\\r?\\n")} and {@code text.toLowerCase()}
 *       inside every rule evaluation. With 25 fields × multiple rules each
 *       × N PDFs, that's measurable overhead for no reason — the text is
 *       immutable. Splitting and lowering once per document and reusing
 *       across all rules is strictly cheaper.</li>
 *   <li><b>Field provenance.</b> When a field comes back null, the developer
 *       wants to know which rules were tried and how they failed. This
 *       context accumulates that as rules run; the evaluator surfaces it on
 *       {@code MetadataResult.provenance} for debugging.</li>
 * </ol>
 *
 * <p>Not thread-safe — one context per extraction call. The evaluator is
 * still stateless; per-call mutable state lives here.
 */
public final class ExtractionContext {

    public final String fullText;
    public final String[] lines;
    public final String[] linesLower;
    public final String fullTextLower;

    private final Map<String, RuleHit> provenance = new LinkedHashMap<>();

    public ExtractionContext(final String fullText) {
        this.fullText = fullText == null ? "" : fullText;
        this.lines = this.fullText.split("\\r?\\n");
        this.linesLower = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            this.linesLower[i] = lines[i].toLowerCase(Locale.ROOT);
        }
        this.fullTextLower = this.fullText.toLowerCase(Locale.ROOT);
    }

    /**
     * Record which rule fired (or attempted) for a field. Called by the
     * evaluator's helpers as they iterate through rule lists. The latest hit
     * wins per field; rules tried and missed are recorded only if no later
     * rule succeeds.
     */
    public void recordHit(final String field, final RuleHit hit) {
        if (hit == null) return;
        provenance.put(field, hit);
    }

    public Map<String, RuleHit> getProvenance() {
        return Collections.unmodifiableMap(provenance);
    }

    /**
     * One row of provenance: which rule index within a field's rule list
     * matched, what kind of rule it was, and what raw substring was captured.
     * Carries enough context that a debugger reading
     * {@code metadata.provenance.get("purchase_apr")} can trace back to the
     * exact YAML rule.
     */
    public static final class RuleHit {
        public final int ruleIndex;
        public final String ruleKind;   // "label" | "pattern" | "stacked" | "missed"
        public final String captured;   // raw captured text (or null when missed)

        public RuleHit(final int ruleIndex, final String ruleKind, final String captured) {
            this.ruleIndex = ruleIndex;
            this.ruleKind = ruleKind;
            this.captured = captured;
        }

        public static RuleHit hit(final int ruleIndex, final String kind, final String captured) {
            return new RuleHit(ruleIndex, kind, captured);
        }

        public static RuleHit missed(final int ruleCount) {
            return new RuleHit(ruleCount, "missed", null);
        }

        @Override public String toString() {
            return ruleKind + "[" + ruleIndex + "]"
                    + (captured == null ? "" : "=" + captured);
        }
    }
}
