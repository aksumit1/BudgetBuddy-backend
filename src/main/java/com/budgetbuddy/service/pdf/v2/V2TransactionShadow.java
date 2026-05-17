package com.budgetbuddy.service.pdf.v2;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shadow-mode runner for the v2 transaction extractor.
 *
 * <p>When a matched v2 template declares {@code transactions:}, this runs
 * {@link TransactionExtractor} on the same input and compares the result
 * against the legacy-extracted transaction list. Output is logged at INFO
 * (match) or WARN (delta) — production behavior is unchanged.
 *
 * <p>This is the observability layer for the v2 transaction migration:
 * before flipping a per-issuer cutover from legacy to v2, we want a streak
 * of "shadow matched legacy" log entries across the corpus. Once each
 * issuer's shapes have proven equivalence, the cutover is a one-line policy
 * change.
 *
 * <p>Templates without {@code transactions:} declared are no-ops — shadow
 * mode is opt-in per YAML.
 */
public final class V2TransactionShadow {

    private static final Logger LOGGER = LoggerFactory.getLogger(V2TransactionShadow.class);
    private static final TransactionExtractor EXTRACTOR = new TransactionExtractor();
    /** Tolerance for the v2-vs-legacy sum comparison. Half a cent. */
    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.005");

    public static final class Comparison {
        public final int legacyCount;
        public final int v2Count;
        public final BigDecimal legacySum;
        public final BigDecimal v2Sum;
        public final boolean countsMatch;
        public final boolean sumsMatch;

        public Comparison(final int legacyCount, final int v2Count,
                final BigDecimal legacySum, final BigDecimal v2Sum) {
            this.legacyCount = legacyCount;
            this.v2Count = v2Count;
            this.legacySum = legacySum;
            this.v2Sum = v2Sum;
            this.countsMatch = legacyCount == v2Count;
            this.sumsMatch = legacySum.subtract(v2Sum).abs().compareTo(SUM_TOLERANCE) <= 0;
        }

        public boolean parity() {
            return countsMatch && sumsMatch;
        }
    }

    private V2TransactionShadow() { }

    /**
     * Run the shadow comparison. Returns a {@link Comparison} when v2 ran,
     * {@code null} when the template doesn't declare {@code transactions:}
     * (the common case during gradual migration). Caller is free to ignore
     * the result — the side effect is the log.
     */
    public static Comparison run(
            final PdfTemplateV2 template,
            final String fullText,
            final List<BigDecimal> legacyAmounts,
            final String labelForLog) {
        if (template == null || template.getTransactions().isEmpty()) return null;
        if (fullText == null) return null;
        final List<TransactionExtractor.ExtractedTransaction> v2 =
                EXTRACTOR.extract(template, fullText);
        final BigDecimal legacySum = sum(legacyAmounts);
        final BigDecimal v2Sum = sumExtracted(v2);
        final int legacyCount = legacyAmounts == null ? 0 : legacyAmounts.size();
        final Comparison c = new Comparison(legacyCount, v2.size(), legacySum, v2Sum);
        if (c.parity()) {
            LOGGER.info(
                    "v2 tx shadow PARITY: template={} file={} count={} sum={}",
                    template.getId(), labelForLog, c.v2Count, c.v2Sum);
        } else {
            // Dump both legacy amounts and v2's matched amounts (descriptions
            // truncated) on DELTA so the YAML author can spot which
            // transactions v2 missed or over-matched without re-running the
            // parser locally.
            final StringBuilder legacyDebug = new StringBuilder(128);
            if (legacyAmounts != null) {
                for (int i = 0; i < legacyAmounts.size(); i++) {
                    if (i > 0) legacyDebug.append(", ");
                    legacyDebug.append(legacyAmounts.get(i));
                }
            }
            final StringBuilder v2Debug = new StringBuilder(256);
            for (int i = 0; i < v2.size(); i++) {
                if (i > 0) v2Debug.append(", ");
                v2Debug.append('[').append(v2.get(i).amount).append(' ');
                final String d = v2.get(i).description == null
                        ? "" : v2.get(i).description;
                v2Debug.append(d.length() > 30 ? d.substring(0, 30) + "..." : d).append(']');
            }
            LOGGER.warn(
                    "v2 tx shadow DELTA: template={} file={} "
                            + "legacy(count={}, sum={}, amounts=[{}]) "
                            + "v2(count={}, sum={}, matches=[{}]) "
                            + "Δcount={} Δsum={}",
                    template.getId(), labelForLog,
                    c.legacyCount, c.legacySum, legacyDebug,
                    c.v2Count, c.v2Sum, v2Debug,
                    c.v2Count - c.legacyCount, c.v2Sum.subtract(c.legacySum));
        }
        return c;
    }

    private static BigDecimal sum(final List<BigDecimal> amounts) {
        BigDecimal s = BigDecimal.ZERO;
        if (amounts == null) return s;
        for (final BigDecimal a : amounts) {
            if (a != null) s = s.add(a.abs());
        }
        return s;
    }

    private static BigDecimal sumExtracted(
            final List<TransactionExtractor.ExtractedTransaction> txs) {
        BigDecimal s = BigDecimal.ZERO;
        for (final TransactionExtractor.ExtractedTransaction t : txs) {
            if (t.amount != null) s = s.add(t.amount.abs());
        }
        return s;
    }
}
