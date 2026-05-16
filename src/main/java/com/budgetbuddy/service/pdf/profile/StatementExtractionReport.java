package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-extraction diagnostic snapshot. Captures exactly what the parser saw, which
 * profile fired, which fields landed vs. didn't, and the math-reconciliation outcome.
 *
 * <h2>Why this exists</h2>
 *
 * <p>When a real-world PDF parses badly, the engineer triaging the failure needs to
 * answer four questions fast:
 *
 * <ol>
 *   <li>Did we even detect the issuer correctly?
 *   <li>Which specific extractors returned null? (A "purchases total missing" failure is
 *       very different from "AutoPay status missing".)
 *   <li>Does the canonical statement identity (prev - pay + purchases + ... = new)
 *       reconcile? If not, by how much?
 *   <li>Is the failure data-shaped (a brand-new layout) or pattern-shaped (a regex
 *       regression)?
 * </ol>
 *
 * <p>The report is built incrementally as extractors run and emitted at the end. Tests
 * and {@link SyntheticFixtureGenerator} read it to make decisions; production logging
 * surfaces it for observability.
 *
 * <h2>Health classification</h2>
 *
 * <p>{@link #healthBand} buckets the report into FAIL / DEGRADED / OK / EXCELLENT based
 * on the fraction of core fields extracted plus math reconciliation. The bands let
 * runtime telemetry alert on regressions without having to inspect every field.
 */
public final class StatementExtractionReport {

    /** Health classification. */
    public enum HealthBand {
        FAIL,        // < 30% core fields OR math diff > $10
        DEGRADED,    // 30-69% OR math diff $1-$10
        OK,          // 70-89% core, math within tolerance
        EXCELLENT,   // ≥ 90% core, math reconciles to the cent
    }

    private final Instant generatedAt;
    private final String issuerId;
    private final String brand;
    private final String fileName;
    private final int rawLineCount;
    private final int rawCharCount;
    private final Map<String, Object> extractedFields;
    private final List<String> missingFields;
    private final StatementMathValidator.Result mathResult;

    private StatementExtractionReport(final Builder b) {
        this.generatedAt = b.generatedAt == null ? Instant.now() : b.generatedAt;
        this.issuerId = b.issuerId;
        this.brand = b.brand;
        this.fileName = b.fileName;
        this.rawLineCount = b.rawLineCount;
        this.rawCharCount = b.rawCharCount;
        this.extractedFields = Collections.unmodifiableMap(
                new LinkedHashMap<>(b.extractedFields));
        this.missingFields = Collections.unmodifiableList(new ArrayList<>(b.missingFields));
        this.mathResult = b.mathResult;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Instant generatedAt() { return generatedAt; }
    public String issuerId() { return issuerId; }
    public String brand() { return brand; }
    public String fileName() { return fileName; }
    public int rawLineCount() { return rawLineCount; }
    public int rawCharCount() { return rawCharCount; }
    public Map<String, Object> extractedFields() { return extractedFields; }
    public List<String> missingFields() { return missingFields; }
    public StatementMathValidator.Result mathResult() { return mathResult; }

    /** Fraction of core fields that were extracted (0.0–1.0). */
    public double populationRatio() {
        final int total = extractedFields.size() + missingFields.size();
        if (total == 0) {
            return 0.0;
        }
        return (double) extractedFields.size() / total;
    }

    public HealthBand healthBand() {
        final double pop = populationRatio();
        final boolean mathPass = mathResult != null && mathResult.passed();
        final BigDecimal mathDiff =
                mathResult == null ? null : mathResult.diff().orElse(null);
        final boolean bigMathGap =
                mathDiff != null && mathDiff.compareTo(new BigDecimal("10.00")) > 0;
        if (pop < 0.30 || bigMathGap) {
            return HealthBand.FAIL;
        }
        if (pop < 0.70 || !mathPass && mathResult != null && !mathResult.skipped()) {
            return HealthBand.DEGRADED;
        }
        if (pop < 0.90) {
            return HealthBand.OK;
        }
        return HealthBand.EXCELLENT;
    }

    /** One-line summary suitable for log lines and dashboards. */
    public String summary() {
        return String.format(
                "issuer=%s brand=%s pop=%.0f%% (%d/%d) math=%s band=%s file=%s",
                issuerId,
                brand == null ? "—" : brand,
                populationRatio() * 100,
                extractedFields.size(),
                extractedFields.size() + missingFields.size(),
                mathResult == null ? "no-data" : mathResult.status(),
                healthBand(),
                fileName == null ? "—" : fileName);
    }

    /** Multi-line dump with missing-field list — printed by {@link #toString()}. */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("StatementExtractionReport:\n");
        sb.append("  ").append(summary()).append('\n');
        sb.append("  rawLines=").append(rawLineCount)
                .append(" rawChars=").append(rawCharCount).append('\n');
        if (!missingFields.isEmpty()) {
            sb.append("  missingFields=").append(missingFields).append('\n');
        }
        if (mathResult != null) {
            sb.append("  math=").append(mathResult.toString()).append('\n');
        }
        return sb.toString();
    }

    public static final class Builder {
        private Instant generatedAt;
        private String issuerId;
        private String brand;
        private String fileName;
        private int rawLineCount;
        private int rawCharCount;
        private final Map<String, Object> extractedFields = new LinkedHashMap<>();
        private final List<String> missingFields = new ArrayList<>();
        private StatementMathValidator.Result mathResult;

        public Builder issuerId(final String v) { this.issuerId = v; return this; }
        public Builder brand(final String v) { this.brand = v; return this; }
        public Builder fileName(final String v) { this.fileName = v; return this; }
        public Builder rawLineCount(final int v) { this.rawLineCount = v; return this; }
        public Builder rawCharCount(final int v) { this.rawCharCount = v; return this; }
        public Builder generatedAt(final Instant v) { this.generatedAt = v; return this; }
        public Builder mathResult(final StatementMathValidator.Result v) {
            this.mathResult = v; return this;
        }

        /**
         * Record one field's extraction outcome. Non-null values land in {@code
         * extractedFields}; nulls in {@code missingFields}. Idempotent — a second call
         * with the same name overwrites.
         */
        public Builder record(final String name, final Object value) {
            if (value == null) {
                if (!missingFields.contains(name)) {
                    missingFields.add(name);
                }
                extractedFields.remove(name);
            } else {
                extractedFields.put(name, value);
                missingFields.remove(name);
            }
            return this;
        }

        public StatementExtractionReport build() {
            return new StatementExtractionReport(this);
        }
    }
}
