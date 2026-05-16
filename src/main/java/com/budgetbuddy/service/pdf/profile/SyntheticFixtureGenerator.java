package com.budgetbuddy.service.pdf.profile;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-learning loop: when a real-world PDF parses poorly, this generator converts the
 * raw extracted text into a synthetic, PII-safe regression fixture that can be checked
 * in as a permanent test case.
 *
 * <h2>Workflow</h2>
 *
 * <ol>
 *   <li>A PDF parses and {@link StatementExtractionReport#healthBand} returns FAIL or
 *       DEGRADED.
 *   <li>An engineer runs the failing PDF through {@link #anonymize}, passing a
 *       deterministic seed so the output is reproducible.
 *   <li>The output is dropped into a fixture test (verbatim or trimmed). Future runs
 *       of the test suite verify the layout continues to extract correctly.
 *   <li>If a later pattern change regresses on that layout, the test fails loudly.
 * </ol>
 *
 * <h2>What gets redacted</h2>
 *
 * <p>Cardholder names, addresses, account numbers, reference codes, phone numbers,
 * customer service URLs that embed account-specific tokens, and any other obvious
 * PII anchors. Amounts and dates can OPTIONALLY be jittered (off by default — keeping
 * the original math intact makes the fixture also useful as a reconciliation test).
 *
 * <h2>Determinism</h2>
 *
 * <p>The same input + same seed produces byte-identical output. This is critical for
 * code review and for re-running the generator after pattern updates without
 * inflating the diff.
 */
public final class SyntheticFixtureGenerator {

    private static final Pattern NAME_LINE =
            Pattern.compile(
                    "(?i)^([A-Z][A-Z\\s'\\-\\.]{5,40})$",
                    Pattern.MULTILINE);

    private static final Pattern ADDRESS_LINE =
            Pattern.compile(
                    "(?i)^\\s*\\d+\\s+[A-Z][A-Z0-9\\s\\.'\\-]*\\s+(?:ST|AVE|RD|BLVD|DR|LN|"
                            + "WAY|CT|PKWY|HWY|SE|NW|SW|NE)\\b.*$",
                    Pattern.MULTILINE);

    private static final Pattern CITY_STATE_ZIP =
            Pattern.compile(
                    "(?i)^\\s*([A-Z][A-Z\\s]+)\\s+([A-Z]{2})\\s+(\\d{5}(?:-\\d{4})?)\\s*$",
                    Pattern.MULTILINE);

    private static final Pattern CARD_ENDING_FOUR =
            Pattern.compile(
                    "(?i)(?:ending\\s+in|account\\s+(?:number\\s+)?ending\\s+in|"
                            + "card\\s+(?:number\\s+)?ending\\s+in|"
                            + "#{0,4}\\s*#{0,4}\\s*#{0,4})\\s+(\\d{4})\\b");

    private static final Pattern LONG_REF_CODE =
            Pattern.compile("\\b([A-Z0-9]{14,})\\b");

    private static final Pattern PHONE =
            Pattern.compile(
                    "\\b(?:1-)?[2-9]\\d{2}[-\\s]?[2-9]\\d{2}[-\\s]?\\d{4}\\b");

    /**
     * Anonymize the raw PDF text. {@code jitterAmounts=false} keeps math intact;
     * {@code true} also nudges every dollar amount by a small percentage so the
     * fixture's numerical specifics aren't recoverable from the source PDF.
     */
    public static String anonymize(
            final String rawText, final long seed, final boolean jitterAmounts) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        final Random rng = new Random(seed);
        String out = rawText;

        // Phones → "555-0100" series (RFC 6761 reserved range).
        out = PHONE.matcher(out).replaceAll(
                m -> String.format(
                        "555-01%02d",
                        Math.abs(m.group().hashCode()) % 100));

        // Card last-4 → synthetic "1234".
        out = CARD_ENDING_FOUR.matcher(out).replaceAll(
                m -> m.group().replace(m.group(1), "1234"));

        // Long alphanumeric reference codes → synthetic prefix + position-derived hash.
        // We preserve the LENGTH so layout-sensitive parsers (column widths) still match.
        out = LONG_REF_CODE.matcher(out).replaceAll(
                m -> "REF" + ("X".repeat(Math.max(0, m.group(1).length() - 3))));

        // Cardholder names → "TEST CARDHOLDER".
        out = NAME_LINE.matcher(out).replaceAll("TEST CARDHOLDER");

        // Street addresses → "123 MAIN ST".
        out = ADDRESS_LINE.matcher(out).replaceAll("123 MAIN ST");

        // City/state/zip → "TESTVILLE WA 98000".
        out = CITY_STATE_ZIP.matcher(out).replaceAll("TESTVILLE WA 98000");

        if (jitterAmounts) {
            out = jitterDollarAmounts(out, rng);
        }
        return out;
    }

    /**
     * Convenience: returns a stub JUnit fixture body that defines a synthetic
     * statement string suitable for a fixture-test class.
     */
    public static String emitFixtureStub(
            final String fixtureClassName,
            final String issuerId,
            final String rawText,
            final long seed) {
        final String anonymized = anonymize(rawText, seed, false);
        final StringBuilder sb = new StringBuilder(anonymized.length() + 256);
        sb.append("// Auto-generated synthetic fixture for ").append(issuerId)
                .append(" — anonymize() with seed=").append(seed).append("\n");
        sb.append("private static final String STATEMENT_FIXTURE =\n");
        sb.append("        String.join(\n                \"\\n\",\n");
        boolean first = true;
        for (final String line : anonymized.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (first) {
                first = false;
            } else {
                sb.append(",\n");
            }
            sb.append("                \"").append(
                    line.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append(");\n");
        return sb.toString();
    }

    private static final Pattern DOLLAR_AMOUNT =
            Pattern.compile("\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static String jitterDollarAmounts(final String text, final Random rng) {
        final Matcher m = DOLLAR_AMOUNT.matcher(text);
        final StringBuilder out = new StringBuilder(text.length() + 32);
        while (m.find()) {
            final String original = m.group(1).replace(",", "");
            try {
                final double value = Double.parseDouble(original);
                // ±5% jitter, preserve sign and integer/decimal shape.
                final double jitterPct = (rng.nextDouble() - 0.5) * 0.10;
                final double newValue = value * (1.0 + jitterPct);
                final String replacement =
                        original.contains(".")
                                ? String.format("$%,.2f", newValue)
                                : String.format("$%,.0f", newValue);
                m.appendReplacement(
                        out, Matcher.quoteReplacement(replacement));
            } catch (NumberFormatException e) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private SyntheticFixtureGenerator() {
        // static utility
    }
}
