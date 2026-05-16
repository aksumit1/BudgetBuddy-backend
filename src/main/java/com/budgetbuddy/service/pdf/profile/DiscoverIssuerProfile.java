package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discover Financial Services — Discover it (rotating 5% category), Discover it Miles,
 * Discover it Cash Back, Discover it Chrome, Discover it Student, Discover it Secured,
 * Discover it Business, NHL Discover it. The statement layout is consistent across
 * brands; only the rewards-section vocabulary changes (Cashback Bonus / Miles).
 *
 * <p>Distinctive Discover phrasings handled by this profile:
 *
 * <ul>
 *   <li><b>"Statement Closing Date: MM/DD/YYYY"</b> — same form as BoA but with a
 *       different surrounding header. We accept either label.
 *   <li><b>"Cashback Bonus +$0.06 10/06 STARBUCKS"</b> — Discover prints rewards as a
 *       prefix on individual transaction rows (per-tx earned). The existing YAML
 *       template at {@code pdf-templates/discover.yaml} handles those transaction lines
 *       directly. This profile handles the SUMMARY-level cashback values.
 *   <li><b>"Total Cashback Bonus YTD: $X.XX"</b> — total cashback earned in the
 *       calendar year. Maps to {@code cashBackBalance} (the cumulative redeemable
 *       amount, even though it's technically "earned YTD").
 *   <li><b>"Available Credit: $X" / "Credit Line: $X"</b> — Discover's wording. The
 *       shared StatementParsingUtilities already handles "Available Credit"; we add the "Credit Line" form.
 *   <li><b>"This Month's APR" + "X.XX%" </b> on its own row — different from other
 *       issuers' APR table format. We extract via a Discover-specific pattern.
 *   <li><b>AutoPay disclosure</b>: "Your AutoPay amount of $X will be deducted on
 *       MM/DD/YYYY" — Discover-specific phrasing.
 *   <li><b>"Cashback Match"</b> first-year promotion — detected as a brand flag so
 *       the iOS app can show a "matching active" badge. Tracked via brand detection.
 * </ul>
 *
 * <p>Migration note: every override here is scoped to this class. If a Discover
 * statement layout ever evolves, the only place it changes is this file — the
 * fragility lessons from earlier in the codebase (the {@code CLOSING_DATE_PATTERN}
 * regression that broke Chase, the AutoPay false-positive on disclosure prose) are
 * exactly what per-issuer scoping protects against.
 */

public final class DiscoverIssuerProfile extends AbstractIssuerProfile {

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)\\bdiscover\\b\\s*(?:bank|card|it|financial|services)"
                            + "|discover\\.com|"
                            + "discover\\s+(?:credit\\s+card|cardmember\\s+services)");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        // Most-specific first so "Discover it Miles" isn't shadowed by "Discover it".
        BRANDS.put("discover-it-miles", Pattern.compile("(?i)discover\\s+it\\s+miles"));
        BRANDS.put("discover-it-cash-back", Pattern.compile("(?i)discover\\s+it\\s+cash\\s+back"));
        BRANDS.put("discover-it-chrome", Pattern.compile("(?i)discover\\s+it\\s+chrome"));
        BRANDS.put("discover-it-student", Pattern.compile("(?i)discover\\s+it\\s+student"));
        BRANDS.put("discover-it-secured", Pattern.compile("(?i)discover\\s+it\\s+secured"));
        BRANDS.put("discover-it-business", Pattern.compile("(?i)discover\\s+it\\s+business"));
        BRANDS.put("nhl-discover-it", Pattern.compile("(?i)nhl\\s+discover\\s+it"));
        BRANDS.put("discover-it", Pattern.compile("(?i)discover\\s+it\\b"));
    }

    public DiscoverIssuerProfile() {
        super("discover", "Discover", HEADER, BRANDS);
    }

    // ---- Discover-specific overrides ----

    // Discover prints "Credit Line: $X,XXX" — additional label not in StatementParsingUtilities' CREDIT_LIMIT_LABELS.
    private static final Pattern DISCOVER_CREDIT_LINE =
            Pattern.compile(
                    "(?i)^\\s*credit\\s+line[\\s:]+\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = DISCOVER_CREDIT_LINE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractCreditLimit(lines);
    }

    /**
     * Discover cash-back: "Total Cashback Bonus: $X.XX" (current redeemable) or
     * "Total Cashback Bonus YTD: $X.XX" (year-to-date earned). We prefer the bare
     * "Total Cashback Bonus" form when both are present — that's the redeemable
     * balance the cardmember can actually spend.
     */
    private static final Pattern DISCOVER_CASHBACK_BALANCE =
            Pattern.compile(
                    "(?i)^\\s*total\\s+cashback\\s+bonus(?:\\s+ytd)?[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractCashBackBalance(
            final String[] lines, final ExtractionContext ctx) {
        // Pass 1: bare "Total Cashback Bonus: $X" (no YTD). Wins over the YTD form.
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final String trimmed = line.trim();
            final Matcher m = DISCOVER_CASHBACK_BALANCE.matcher(trimmed);
            if (m.find()) {
                if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("ytd")) {
                    continue;
                }
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        // Pass 2: fall back to the YTD form if no current-balance row is present.
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = DISCOVER_CASHBACK_BALANCE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractCashBackBalance(lines);
    }

    /**
     * Discover prints "Statement Closing Date: MM/DD/YYYY" (BoA-compatible) or
     * "Closing Date: MM/DD/YYYY" depending on layout era. Both are handled — we
     * use StatementParsingUtilities' CLOSING_DATE_PATTERN as a fallback because Discover
     * doesn't have the Chase "Opening/Closing Date X - Y" form (so no risk of the
     * regression that motivated the Chase profile's negative-lookbehind hack).
     */
    private static final Pattern DISCOVER_STATEMENT_CLOSING =
            Pattern.compile(
                    "(?i)(?:statement\\s+)?closing\\s+date[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = DISCOVER_STATEMENT_CLOSING.matcher(line);
            if (m.find()) {
                final LocalDate parsed =
                        parseSlashDate(m.group(1), ctx.inferredYear(), ctx.usLocale());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    /**
     * Discover AutoPay disclosure: "Your AutoPay amount of $X.XX will be deducted on
     * MM/DD/YYYY." Conditional on enrollment so it's a reliable ON marker.
     */
    private static final Pattern DISCOVER_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)autopay\\s+(?:amount|payment)\\s+of\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"
                            + "\\s+will\\s+be\\s+deducted");

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        if (DISCOVER_AUTOPAY_AMOUNT.matcher(joinLines(lines)).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = DISCOVER_AUTOPAY_AMOUNT.matcher(joinLines(lines));
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    // ---- Helpers (kept local) ----

    private static String joinLines(final String[] lines) {
        final StringBuilder sb = new StringBuilder();
        for (final String line : lines) {
            if (line != null) {
                sb.append(line).append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static LocalDate parseSlashDate(
            final String raw, final Integer inferredYear, final boolean usLocale) {
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split("[/-]");
        try {
            if (parts.length == 3) {
                int y = Integer.parseInt(parts[2]);
                if (y < 100) {
                    y += 2000;
                }
                final int m = usLocale ? Integer.parseInt(parts[0]) : Integer.parseInt(parts[1]);
                final int d = usLocale ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0]);
                return LocalDate.of(y, m, d);
            }
            if (parts.length == 2 && inferredYear != null) {
                final int m = usLocale ? Integer.parseInt(parts[0]) : Integer.parseInt(parts[1]);
                final int d = usLocale ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0]);
                return LocalDate.of(inferredYear, m, d);
            }
        } catch (NumberFormatException | java.time.DateTimeException ignored) {
            // fall through
        }
        return null;
    }
}
