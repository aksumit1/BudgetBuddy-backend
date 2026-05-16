package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wells Fargo Bank, N.A. — covers Active Cash (cash-back), Autograph (points),
 * Reflect (intro-APR), and Bilt-branded cards. All share the {@code Statement Period
 * X to Y} header form and a "Rewards balance as of: DATE $X.XX" line for cash-back
 * variants. Points-based brands print a point integer instead.
 *
 * <p>This profile has begun migrating Wells-specific patterns OUT of the shared StatementParsingUtilities
 * in {@code PDFImportService} and INTO this class. Each override runs the Wells-only
 * regex first; only when it returns null does it fall back to the shared StatementParsingUtilities (which
 * still carries the pattern as a safety net during the transition). Once all profiles
 * are migrated, the corresponding entries in StatementParsingUtilities can be removed and the
 * generic fallback becomes truly minimal.
 *
 * @deprecated Migration target — superseded by {@code pdf-templates-v2/wells-fargo.yaml}.
 *     Card detection + period + balance/total extraction now in v2. APR table
 *     + Active Cash $-balance extraction still require this class. Deletion
 *     gated on v2 schema gaining {@code apr_table} + {@code cashback_balance}
 *     rule types. See {@code docs/pdf-import-deprecation-map.md}.
 */
@Deprecated(since = "v2-migration-q1")
public final class WellsFargoIssuerProfile extends AbstractIssuerProfile {

    // Wells-only label: "Total Available Credit $16,200". StatementParsingUtilities still
    // carries this pattern as a fallback for misclassified statements; once every
    // other profile is migrated and the generic fallback only handles unknown
    // issuers, we'll delete it from the shared utility.
    private static final Pattern WELLS_TOTAL_AVAILABLE_CREDIT =
            Pattern.compile(
                    "(?i)^\\s*total\\s+available\\s+credit[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Wells-only label: "Total Credit Limit $30,000".
    private static final Pattern WELLS_TOTAL_CREDIT_LIMIT =
            Pattern.compile(
                    "(?i)^\\s*total\\s+credit\\s+limit[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Wells per-period section total: "TOTAL PAYMENTS FOR THIS PERIOD $545.91".
    private static final Pattern WELLS_TOTAL_PAYMENTS_PERIOD =
            Pattern.compile(
                    "(?i)^\\s*total\\s+payments\\s+for\\s+this\\s+period[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Wells AutoPay sentence: "$X - $Y will be deducted from your account and
    // credited as your automatic payment on MM/DD/YY".
    private static final Pattern WELLS_AUTOPAY_RANGE =
            Pattern.compile(
                    "(?i)\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*[-–]\\s*"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s+will\\s+be\\s+deducted"
                            + "[^.]*?automatic\\s+payment");

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)wells\\s*fargo|wellsfargo\\.com|"
                            + "wells\\s+fargo\\s+(?:online|customer\\s+service|card\\s+services)");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        // Order matters: more-specific entries first so the generic ones (Autograph)
        // don't shadow the variants (Autograph Journey).
        BRANDS.put("active-cash", Pattern.compile("(?i)active\\s+cash"));
        BRANDS.put("autograph-journey", Pattern.compile("(?i)autograph\\s+journey"));
        BRANDS.put("autograph", Pattern.compile("(?i)\\bautograph\\b"));
        BRANDS.put("reflect", Pattern.compile("(?i)wells\\s+fargo\\s+reflect"));
        BRANDS.put("bilt", Pattern.compile("(?i)bilt\\s+world\\s+elite"));
        BRANDS.put("attune", Pattern.compile("(?i)\\battune\\b"));
    }

    public WellsFargoIssuerProfile() {
        super("wells-fargo", "Wells Fargo", HEADER, BRANDS);
    }

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchSingleAmount(lines, WELLS_TOTAL_AVAILABLE_CREDIT);
        return direct != null ? direct : StatementParsingUtilities.extractAvailableCredit(lines);
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchSingleAmount(lines, WELLS_TOTAL_CREDIT_LIMIT);
        return direct != null ? direct : StatementParsingUtilities.extractCreditLimit(lines);
    }

    @Override
    public BigDecimal extractPaymentsAndCreditsTotal(
            final String[] lines, final ExtractionContext ctx) {
        // Wells per-period form first; legacy fallback for older statement formats.
        final BigDecimal direct = matchSingleAmount(lines, WELLS_TOTAL_PAYMENTS_PERIOD);
        if (direct != null) {
            return direct;
        }
        // Inherit sign normalization from the base class for the fallback path.
        return super.extractPaymentsAndCreditsTotal(lines, ctx);
    }

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        // Wells AutoPay range sentence is conditional on enrollment — strong ON marker.
        if (WELLS_AUTOPAY_RANGE.matcher(joinLines(lines)).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        // Wells range form prefers the upper bound (group 2) — fixed-amount setups
        // have a degenerate range like "$50 - $50" so this still works.
        final Matcher m = WELLS_AUTOPAY_RANGE.matcher(joinLines(lines));
        if (m.find()) {
            try {
                return new BigDecimal(m.group(2).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    // ---- Helpers ----

    private static BigDecimal matchSingleAmount(final String[] lines, final Pattern pattern) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = pattern.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String joinLines(final String[] lines) {
        final StringBuilder sb = new StringBuilder();
        for (final String line : lines) {
            if (line != null) {
                sb.append(line).append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ");
    }
}
