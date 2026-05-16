package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * American Express — charge cards (Platinum, Gold, Green) and credit cards (Blue Cash
 * Preferred / Everyday, Blue Business Cash, Delta SkyMiles, Hilton Honors, Marriott
 * Bonvoy Brilliant, Schwab Investor Card, Morgan Stanley Platinum). The shared
 * statement layout uses {@code Closing Date MM/DD/YY} headers, a multi-column Account
 * Summary, the {@code Pay Over Time Limit / Available Pay Over Time Limit} stacked
 * labels, and Membership Rewards points (Daily Cash on co-brands like Hilton, Delta).
 *
 * <p>Owns Amex-specific patterns directly. Cross-issuer patterns inherited via StatementParsingUtilities through the AbstractIssuerProfile delegation.
 */

public final class AmericanExpressIssuerProfile extends AbstractIssuerProfile {

    // Amex closing-date header (single-line, no colon variant common):
    //   "Closing Date 05/13/26"
    // Negative lookbehind for "opening/" + "opening " prevents this from matching
    // Chase's "Opening/Closing Date X - Y" combined form.
    private static final Pattern AMEX_CLOSING_DATE =
            Pattern.compile(
                    "(?i)(?<!opening/)(?<!opening\\s)\\bclosing\\s+date[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})\\b");

    // Amex disclosure restatement (single-line, with colon):
    //   "Pay Over Time Limit: $35,000.00"
    private static final Pattern AMEX_POT_LIMIT_INLINE =
            Pattern.compile(
                    "(?i)^\\s*pay\\s+over\\s+time\\s+limit[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Stacked labels (front-page Amex layout):
    //   "Pay Over Time Limit"
    //   "Available Pay Over Time Limit"
    //   "$35,000.00"
    //   "$34,021.51"
    private static final Pattern AMEX_POT_LIMIT_LABEL =
            Pattern.compile("(?i)^\\s*pay\\s+over\\s+time\\s+limit\\s*$");
    private static final Pattern AMEX_AVAILABLE_POT_LABEL =
            Pattern.compile("(?i)^\\s*available\\s+pay\\s+over\\s+time\\s+limit\\s*$");
    private static final Pattern AMEX_DOLLAR_LINE =
            Pattern.compile("^\\s*\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$");

    // Amex AutoPay disclosure sentence (conditional on enrollment):
    //   "We will debit your bank account for your payment of $978.49 on 05/28/26"
    private static final Pattern AMEX_AUTOPAY_DEBIT =
            Pattern.compile(
                    "(?i)debit\\s+your\\s+bank\\s+account\\s+for\\s+your\\s+payment\\s+of\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Membership Rewards points multi-line block:
    //   "Membership Rewards® Points"
    //   "Available and Pending as of MM/DD/YY"
    //   "           89,096  "
    private static final Pattern AMEX_MR_POINTS_LABEL =
            Pattern.compile("(?i)^\\s*membership\\s+rewards\\s*(?:®)?\\s*points\\s*$");
    private static final Pattern AMEX_INTEGER_ON_LINE =
            Pattern.compile("^\\s*(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)american\\s+express|americanexpress\\.com|\\bamex\\b|"
                            + "make\\s+check\\s+payable\\s+to\\s+american\\s+express");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        BRANDS.put("morgan-stanley-platinum",
                Pattern.compile("(?i)morgan\\s+stanley\\s+platinum"));
        BRANDS.put("platinum", Pattern.compile("(?i)\\bplatinum\\s+card\\b"));
        BRANDS.put("gold", Pattern.compile("(?i)american\\s+express\\s+gold|\\bamex\\s+gold\\b"));
        BRANDS.put("green", Pattern.compile("(?i)american\\s+express\\s+green"));
        BRANDS.put("blue-cash-preferred", Pattern.compile("(?i)blue\\s+cash\\s+preferred"));
        BRANDS.put("blue-cash-everyday", Pattern.compile("(?i)blue\\s+cash\\s+everyday"));
        BRANDS.put("blue-business-cash", Pattern.compile("(?i)blue\\s+business\\s+cash"));
        BRANDS.put("blue-business-plus", Pattern.compile("(?i)blue\\s+business\\s+plus"));
        BRANDS.put("delta-platinum", Pattern.compile("(?i)delta\\s+(?:skymiles\\s+)?platinum"));
        BRANDS.put("delta-gold", Pattern.compile("(?i)delta\\s+(?:skymiles\\s+)?gold"));
        BRANDS.put("delta-reserve", Pattern.compile("(?i)delta\\s+(?:skymiles\\s+)?reserve"));
        BRANDS.put("hilton-aspire", Pattern.compile("(?i)hilton\\s+honors\\s+aspire"));
        BRANDS.put("hilton-surpass", Pattern.compile("(?i)hilton\\s+honors\\s+surpass"));
        BRANDS.put("marriott-bonvoy-brilliant",
                Pattern.compile("(?i)marriott\\s+bonvoy\\s+brilliant"));
    }

    public AmericanExpressIssuerProfile() {
        super("amex", "American Express", HEADER, BRANDS);
    }

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = AMEX_CLOSING_DATE.matcher(line);
            if (m.find()) {
                return parseSlashDate(m.group(1), ctx.inferredYear(), ctx.usLocale());
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        // Try the disclosure restatement first (single-line, unambiguous).
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = AMEX_POT_LIMIT_INLINE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        // Fall back to the stacked-label layout — value follows the label across lines.
        final BigDecimal stackedLimit = extractStackedPotValue(lines, /*pickIndex=*/0);
        if (stackedLimit != null) {
            return stackedLimit;
        }
        return StatementParsingUtilities.extractCreditLimit(lines);
    }

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        // Stacked-label layout assigns the SECOND value to the SECOND label.
        final BigDecimal stackedAvailable = extractStackedPotValue(lines, /*pickIndex=*/1);
        if (stackedAvailable != null) {
            return stackedAvailable;
        }
        return StatementParsingUtilities.extractAvailableCredit(lines);
    }

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        if (AMEX_AUTOPAY_DEBIT.matcher(joinLines(lines)).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = AMEX_AUTOPAY_DEBIT.matcher(joinLines(lines));
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    @Override
    public Long extractPointsBalance(final String[] lines, final ExtractionContext ctx) {
        // Three-line block: heading, "Available and Pending as of MM/DD/YY", integer.
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) {
                continue;
            }
            if (!AMEX_MR_POINTS_LABEL.matcher(lines[i]).find()) {
                continue;
            }
            int scanned = 0;
            for (int j = i + 1; j < lines.length && scanned < 5; j++) {
                final String next = lines[j];
                if (next == null || next.isBlank()) {
                    continue;
                }
                scanned++;
                final Matcher m = AMEX_INTEGER_ON_LINE.matcher(next);
                if (m.find()) {
                    try {
                        return Long.parseLong(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return StatementParsingUtilities.extractPointsBalance(lines);
    }

    /**
     * Walks the stacked-label layout and returns either the first or second value
     * (label1=POT Limit / value1, label2=Available POT Limit / value2). Returns null
     * when the stack isn't present so callers can fall back gracefully.
     */
    private static BigDecimal extractStackedPotValue(final String[] lines, final int pickIndex) {
        for (int i = 0; i + 3 < lines.length; i++) {
            if (lines[i] == null || lines[i + 1] == null) {
                continue;
            }
            if (!AMEX_POT_LIMIT_LABEL.matcher(lines[i]).find()) {
                continue;
            }
            if (!AMEX_AVAILABLE_POT_LABEL.matcher(lines[i + 1]).find()) {
                continue;
            }
            final java.util.List<BigDecimal> values = new java.util.ArrayList<>(2);
            for (int j = i + 2; j < lines.length && values.size() < 2 && j < i + 8; j++) {
                final String next = lines[j];
                if (next == null || next.isBlank()) {
                    continue;
                }
                final Matcher m = AMEX_DOLLAR_LINE.matcher(next.trim());
                if (m.find()) {
                    try {
                        values.add(new BigDecimal(m.group(1).replace(",", "")));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
            if (values.size() == 2 && pickIndex >= 0 && pickIndex < 2) {
                return values.get(pickIndex);
            }
        }
        return null;
    }

    // ---- Helpers ----

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
