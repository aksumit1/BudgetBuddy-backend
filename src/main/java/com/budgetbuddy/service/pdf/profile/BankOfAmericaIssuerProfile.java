package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank of America, N.A. — Customized Cash Rewards, Travel Rewards, Premium Rewards,
 * Unlimited Cash Rewards. Distinctive elements of the BoA statement layout that this
 * profile handles:
 *
 * <ul>
 *   <li><b>Total Credit Line</b> / <b>Total Credit Line Available</b> instead of
 *       "Credit Limit" / "Available Credit".
 *   <li><b>Statement Closing Date: MM/DD/YYYY</b> in the header (no "Statement Date"
 *       label like Chase, no "Closing Date" alone like USB).
 *   <li><b>Total Reward Cash Balance: $X.XX</b> for cash-back balance.
 *   <li><b>"Your monthly payment of $X will automatically debit your account on
 *       MM/DD"</b> AutoPay disclosure.
 *   <li><b>Choose Your 3% Category</b> earning row on Customized Cash Rewards.
 *   <li>Section totals printed with {@code Payments and Other Credits} label (existing
 *       pattern matches it already, so we inherit).
 * </ul>
 *
 * <p>This profile is the canonical example of a NEW issuer added with the new
 * architecture: only fields that differ from the inherited generic behavior are
 * overridden here. Detection runs on header text; extraction overrides supplant the
 * shared StatementParsingUtilities patterns for THIS issuer only.
 */

public final class BankOfAmericaIssuerProfile extends AbstractIssuerProfile {

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)bank\\s+of\\s+america|bankofamerica\\.com|\\bbofa\\b|"
                            + "bank\\s+of\\s+america\\s+customer\\s+service");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        // Order matters: "Premium Rewards Elite" must precede "Premium Rewards" so the
        // generic prefix doesn't shadow the variant.
        BRANDS.put("customized-cash-rewards",
                Pattern.compile("(?i)customized\\s+cash\\s+rewards"));
        BRANDS.put("unlimited-cash-rewards",
                Pattern.compile("(?i)unlimited\\s+cash\\s+rewards"));
        BRANDS.put("travel-rewards", Pattern.compile("(?i)travel\\s+rewards"));
        BRANDS.put("premium-rewards-elite",
                Pattern.compile("(?i)premium\\s+rewards\\s+elite"));
        BRANDS.put("premium-rewards", Pattern.compile("(?i)premium\\s+rewards"));
        BRANDS.put("alaska-airlines-visa", Pattern.compile("(?i)alaska\\s+airlines"));
        BRANDS.put("amtrak-guest-rewards", Pattern.compile("(?i)amtrak\\s+guest\\s+rewards"));
    }

    public BankOfAmericaIssuerProfile() {
        super("boa", "Bank of America", HEADER, BRANDS);
    }

    // ---- Issuer-specific overrides ----

    /** BoA prints "Total Credit Line $X" (the shared StatementParsingUtilities pattern set doesn't have this label). */
    private static final Pattern BOA_CREDIT_LINE =
            Pattern.compile(
                    "(?i)^\\s*total\\s+credit\\s+line[\\s:]+\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = BOA_CREDIT_LINE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        // Fall back to the shared StatementParsingUtilities (covers "Credit Limit" etc. if BoA changes wording).
        return StatementParsingUtilities.extractCreditLimit(lines);
    }

    /** BoA's available-credit row is "Total Credit Line Available $X". */
    private static final Pattern BOA_CREDIT_LINE_AVAILABLE =
            Pattern.compile(
                    "(?i)^\\s*total\\s+credit\\s+line\\s+available[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = BOA_CREDIT_LINE_AVAILABLE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractAvailableCredit(lines);
    }

    /** BoA: "Statement Closing Date: MM/DD/YYYY" header. */
    private static final Pattern BOA_STATEMENT_CLOSING =
            Pattern.compile(
                    "(?i)statement\\s+closing\\s+date[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = BOA_STATEMENT_CLOSING.matcher(line);
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

    /** BoA cash-back: "Total Reward Cash Balance: $X.XX". */
    private static final Pattern BOA_REWARD_CASH =
            Pattern.compile(
                    "(?i)total\\s+reward\\s+cash\\s+balance[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractCashBackBalance(
            final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = BOA_REWARD_CASH.matcher(line);
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
     * BoA AutoPay disclosure: "Your monthly payment of $X will automatically debit
     * your account on MM/DD" — the on-marker is enrollment-conditional.
     */
    private static final Pattern BOA_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)monthly\\s+payment\\s+of\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"
                            + "\\s+will\\s+automatically\\s+debit");

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        final String joined = joinLines(lines);
        if (BOA_AUTOPAY_AMOUNT.matcher(joined).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = BOA_AUTOPAY_AMOUNT.matcher(joinLines(lines));
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    // ---- Helpers (kept local so they don't leak into other profiles) ----

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
