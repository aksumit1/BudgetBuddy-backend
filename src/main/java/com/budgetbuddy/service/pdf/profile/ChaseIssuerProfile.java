package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chase (JPMorgan Chase Bank, N.A.) statement profile. Covers personal + business cards
 * across the major brands. Statement layout differs subtly by brand (Marriott Bonvoy
 * transfer-partner format, Amazon Visa cumulative balance, Freedom/Flex rotating bonus,
 * Sapphire travel-portal earnings) but the header always identifies as Chase.
 *
 * <p>Owns Chase-specific patterns directly. The complex brand-specific extractors
 * (Marriott Bonvoy transfer-partner points, Amazon Visa per-category multipliers,
 * Freedom rotating-quarter bonus) remain in StatementParsingUtilities for now because they
 * involve multi-pattern aggregation that's harder to isolate per-brand.
 *
 * @deprecated Migration target — superseded by {@code pdf-templates-v2/chase.yaml}.
 *     Card detection + statement-period + balance/total extraction are now in v2.
 *     Brand-specific reward / quarterly-bonus extraction still requires this
 *     class. Deletion gated on v2 schema gaining {@code points_block},
 *     {@code quarterly_bonus}, and {@code apr_table} rule types. See
 *     {@code docs/pdf-import-deprecation-map.md}.
 */
@Deprecated(since = "v2-migration-q1")
public final class ChaseIssuerProfile extends AbstractIssuerProfile {

    // Chase: "Opening/Closing Date 05/13/26 - 06/12/26" — single label with two
    // dates. The END of the range is Chase's statement-close date. This was the
    // pattern that caused the AMEX "Closing Date X" regression: without
    // routing through this profile first, the bare AMEX regex would grab the
    // START date of Chase's combined form. Per-issuer dispatch eliminates that
    // class of bug.
    private static final Pattern CHASE_OPENING_CLOSING_RANGE =
            Pattern.compile(
                    "(?i)opening\\s*[/]\\s*closing\\s+date[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                            + "\\s*(?:to|through|-|–)\\s*"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    // Chase "Credit Access Line $X" — its name for credit limit (distinct from
    // "Credit Limit" used by mainstream issuers).
    private static final Pattern CHASE_CREDIT_ACCESS_LINE =
            Pattern.compile(
                    "(?i)^\\s*credit\\s+access\\s+line[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Chase AutoPay disclosure: "Your next AutoPay payment for $X will be deducted...".
    private static final Pattern CHASE_NEXT_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)next\\s+autopay\\s+payment\\s+(?:for|of)\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Chase explicit ON markers.
    private static final Pattern CHASE_AUTOPAY_ON =
            Pattern.compile("(?i)\\bautopay\\s+is\\s+on\\b");
    private static final Pattern CHASE_AUTOPAY_OFF =
            Pattern.compile("(?i)\\bautopay\\s+is\\s+off\\b");

    // ---- Chase brand-specific reward patterns ----
    // Marriott Bonvoy transfer-partner: "Total points transferred to Marriott Bonvoy NN,NNN".
    // The partner name is variable (Marriott, Hyatt, United) so we accept any A-Z partner.
    private static final Pattern CHASE_TRANSFER_PARTNER_POINTS =
            Pattern.compile(
                    "(?i)total\\s+points\\s+transferred\\s+to\\s+[a-z][a-z\\s]*?\\s+"
                            + "(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    // Amazon Visa per-category rewards line: "+ N% back on/at CATEGORY NN" (NN = points
    // earned this cycle for that category, which we sum across all matched lines to get
    // pointsEarnedThisPeriod).
    private static final Pattern AMAZON_PER_CATEGORY_EARNED =
            Pattern.compile(
                    "(?i)(?:^|\\s)\\+\\s+(\\d{1,2}(?:\\.\\d{1,2})?)%\\s+back\\s+(?:on|at)\\s+"
                            + "([^0-9\\n]+?)\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

    // Freedom base rewards line: "+ N% (M Pts)/$1 earned on CATEGORY NN"
    private static final Pattern FREEDOM_BASE_EARNED =
            Pattern.compile(
                    "(?i)(?:^|\\s)\\+?\\s*(\\d{1,2})%\\s*\\(\\d{1,2}\\s*Pts?\\)/\\$1\\s+"
                            + "(?:addl\\.?\\s+)?(?:earned\\s+)?on\\s+(.+?)\\s+"
                            + "(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

    // Freedom rotating bonus line: "+ Bonus from NQ N% cat: CATEGORY NN"
    private static final Pattern FREEDOM_BONUS_EARNED =
            Pattern.compile(
                    "(?i)(?:^|\\s)\\+\\s+Bonus\\s+from\\s+([1-4]Q)\\s+(\\d{1,2})%\\s+cat:\\s+"
                            + "(.+?)\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

    // Amazon Visa "Previous points balance NN,NNN" — explicit prior-cycle row.
    private static final Pattern AMAZON_PREVIOUS_POINTS_BALANCE =
            Pattern.compile(
                    "(?i)previous\\s+points\\s+balance[\\s:]+"
                            + "(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    // Amazon Visa cumulative balance: "Total points available for redemption NN,NNN"
    private static final Pattern AMAZON_TOTAL_POINTS_AVAILABLE =
            Pattern.compile(
                    "(?is)total\\s+points\\s+available\\s+for\\b.{0,400}?"
                            + "\\b(?:redemption|redeeming|use)\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)chase\\.com|\\bchase\\s+(?:bank|ink|sapphire|freedom|business)|"
                            + "marriott\\s+bonvoy\\s+(?:bold|boundless)|amazon\\s+prime\\s+visa|"
                            + "chase\\s+(?:united|hyatt|southwest|world\\s+of\\s+hyatt|disney|aer\\s+lingus)");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        BRANDS.put("marriott-bonvoy", Pattern.compile("(?i)marriott\\s+bonvoy"));
        BRANDS.put("amazon-prime-visa", Pattern.compile("(?i)amazon\\s+prime\\s+visa|prime\\s+visa"));
        BRANDS.put("freedom-flex", Pattern.compile("(?i)freedom\\s+flex"));
        BRANDS.put("freedom-unlimited", Pattern.compile("(?i)freedom\\s+unlimited"));
        BRANDS.put("freedom", Pattern.compile("(?i)\\bchase\\s+freedom\\b"));
        BRANDS.put("sapphire-reserve", Pattern.compile("(?i)sapphire\\s+reserve"));
        BRANDS.put("sapphire-preferred", Pattern.compile("(?i)sapphire\\s+preferred"));
        BRANDS.put("ink-business", Pattern.compile("(?i)ink\\s+business"));
        BRANDS.put("united-explorer", Pattern.compile("(?i)united\\s+(?:explorer|club|quest|gateway)"));
        BRANDS.put("southwest", Pattern.compile("(?i)southwest\\s+(?:plus|premier|priority|performance)"));
        BRANDS.put("hyatt", Pattern.compile("(?i)world\\s+of\\s+hyatt"));
    }

    public ChaseIssuerProfile() {
        super("chase", "Chase", HEADER, BRANDS);
    }

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = CHASE_OPENING_CLOSING_RANGE.matcher(line);
            if (m.find()) {
                // The END of the range is Chase's statement-close date.
                return parseSlashDate(m.group(2), ctx.inferredYear(), ctx.usLocale());
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = CHASE_CREDIT_ACCESS_LINE.matcher(line.trim());
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

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        boolean sawOn = false;
        boolean sawOff = false;
        final String joined = joinLines(lines);
        if (CHASE_AUTOPAY_ON.matcher(joined).find()) {
            sawOn = true;
        }
        if (CHASE_AUTOPAY_OFF.matcher(joined).find()) {
            sawOff = true;
        }
        if (sawOn || sawOff) {
            return sawOn;
        }
        // "Your next AutoPay payment for $X" is also an implicit on-marker (Chase
        // only prints this row when AutoPay is enrolled).
        if (CHASE_NEXT_AUTOPAY_AMOUNT.matcher(joined).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = CHASE_NEXT_AUTOPAY_AMOUNT.matcher(joinLines(lines));
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    /**
     * Chase rewards landscape:
     *
     * <ul>
     *   <li><b>Marriott Bonvoy</b>: transfer-partner format — points move OUT of the card
     *       to the partner account on issue, so {@code pointsBalance} stays null and
     *       {@code pointsEarnedThisPeriod} comes from the "Total points transferred to
     *       Marriott Bonvoy NN,NNN" row.
     *   <li><b>Amazon Prime Visa</b>: cumulative balance — accrues on the card. Each
     *       category prints a row like "+ 5% back on Amazon.com purchases NN" where NN
     *       is the points earned this cycle. We SUM those for earnedThisPeriod, and the
     *       balance comes from "Total points available for redemption NN,NNN".
     *   <li><b>Freedom / Freedom Flex / Freedom Unlimited</b>: base 1x earning ("+ 1%
     *       (1 Pt)/$1 earned on all purchases NN") plus optional rotating-quarter
     *       bonus ("+ Bonus from 1Q 5% cat: Grocery Stores NN"). Both earning lines
     *       contribute to pointsEarnedThisPeriod.
     * </ul>
     *
     * <p>Returns null when no Chase-style rewards row is present — caller falls
     * through to the shared StatementParsingUtilities for unknown formats.
     */
    @Override
    public Long extractPointsEarnedThisPeriod(
            final String[] lines, final ExtractionContext ctx) {
        // Pass 1: Marriott-style transfer-partner — single row, single value.
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = CHASE_TRANSFER_PARTNER_POINTS.matcher(line);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        // Pass 2: SUM of Amazon per-category rows + Freedom base + Freedom bonus.
        long total = 0;
        boolean foundAny = false;
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher amz = AMAZON_PER_CATEGORY_EARNED.matcher(line);
            if (amz.find()) {
                try {
                    total += Long.parseLong(amz.group(3).replace(",", ""));
                    foundAny = true;
                    continue;
                } catch (NumberFormatException ignored) {
                    // skip this row, keep summing
                }
            }
            final Matcher base = FREEDOM_BASE_EARNED.matcher(line);
            if (base.find()) {
                try {
                    total += Long.parseLong(base.group(3).replace(",", ""));
                    foundAny = true;
                    continue;
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            final Matcher bonus = FREEDOM_BONUS_EARNED.matcher(line);
            if (bonus.find()) {
                try {
                    total += Long.parseLong(bonus.group(4).replace(",", ""));
                    foundAny = true;
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        if (foundAny) {
            return total;
        }
        return StatementParsingUtilities.extractPointsEarnedThisPeriod(lines);
    }

    @Override
    public Long extractPointsBalance(final String[] lines, final ExtractionContext ctx) {
        // Amazon Visa redemption-pool form first — strongest signal.
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = AMAZON_TOTAL_POINTS_AVAILABLE.matcher(line);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        // Marriott Bonvoy: balance lives at the partner — null is correct for transfer-
        // partner statements. Defer to StatementParsingUtilities for any other Chase brand we haven't yet
        // classified here.
        return StatementParsingUtilities.extractPointsBalance(lines);
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
