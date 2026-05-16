package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citi / Citibank, N.A. — Double Cash, Costco Anywhere Visa, Premier, Custom Cash,
 * Rewards+, AAdvantage. Statement layout is consistent across products:
 * {@code Billing Period: MM/DD/YY-MM/DD/YY} header + sign-attached section totals
 * + multi-line ThankYou Points balance. Costco is special because it's cash-back
 * (dollars, not points) and uses an annual-certificate redemption model.
 *
 * <p>Owns Citi-specific patterns directly. Cross-issuer patterns inherited via StatementParsingUtilities through the AbstractIssuerProfile delegation.
 */

public final class CitiIssuerProfile extends AbstractIssuerProfile {

    // Citi-only: bare "Payments -$X" (sign tightly attached to $ — distinguishes from
    // Chase's "Payment, Credits -$X" which has the comma separator).
    private static final Pattern CITI_PAYMENTS_BARE =
            Pattern.compile(
                    "(?i)^\\s*payments\\s+\\-?\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Citi prints "Payments -$X" and "Credits -$X" on SEPARATE lines. The
    // combined total used for math reconciliation is Payments + Credits, not
    // Payments alone — a missing Credits row leaves the statement identity
    // off by exactly the Credits amount.
    private static final Pattern CITI_CREDITS_BARE =
            Pattern.compile(
                    "(?i)^\\s*credits\\s+\\-?\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Citi Costco available credit: "Available Credit Limit $X" (other Citi cards print
    // "Available credit $X" which the shared StatementParsingUtilities pattern handles).
    private static final Pattern CITI_AVAILABLE_CREDIT_LIMIT =
            Pattern.compile(
                    "(?i)^\\s*available\\s+credit\\s+limit[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Citi billing-period header: "Billing Period: 03/04/26-04/02/26".
    private static final Pattern CITI_BILLING_PERIOD =
            Pattern.compile(
                    "(?i)billing\\s+period[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                            + "\\s*(?:to|through|-|–)\\s*"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    // Citi APR sub-labels under PURCHASES / ADVANCES section headers.
    private static final Pattern CITI_STANDARD_PURCH_APR =
            Pattern.compile(
                    "(?i)^\\s*standard\\s+purch\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");
    private static final Pattern CITI_STANDARD_ADV_APR =
            Pattern.compile(
                    "(?i)^\\s*standard\\s+adv\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");
    private static final Pattern CITI_STANDARD_BAL_TRANS_APR =
            Pattern.compile(
                    "(?i)^\\s*standard\\s+bal\\s+trans\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");

    // Citi billing-days inline: "Interest charge calculation Days in billing cycle: 30".
    private static final Pattern CITI_DAYS_IN_BILLING_CYCLE =
            Pattern.compile(
                    "(?i)\\bdays\\s+in\\s+billing\\s+(?:period|cycle)[\\s:]+(\\d{1,2})\\b");

    // Citi AutoPay: "Your next AutoPay payment of $X will be deducted...".
    private static final Pattern CITI_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)next\\s+autopay\\s+payment\\s+(?:for|of)\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Citi ThankYou Points balance — multi-line label + value (current OR previous line).
    private static final Pattern CITI_TY_POINTS_BARE_LABEL =
            Pattern.compile(
                    "(?i)^\\s*total\\s+(?:available\\s+)?thankyou\\s*(?:®)?\\s*points"
                            + "(?:\\s+balance)?[\\s:]*$");
    private static final Pattern CITI_INTEGER_ON_LINE =
            Pattern.compile("^\\s*(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    // Citi Costco cash-back label (single-line and wrapped variants).
    private static final Pattern CITI_COSTCO_CASH_BACK_INLINE =
            Pattern.compile(
                    "(?i)^\\s*total\\s+(?:costco\\s+)?cash\\s+back\\s+rewards?\\s+balance"
                            + "(?:\\s+year\\s+to\\s+date)?[\\s:]+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");
    private static final Pattern CITI_COSTCO_CASH_BACK_BARE_LABEL =
            Pattern.compile(
                    "(?i)^\\s*total\\s+(?:costco\\s+)?cash\\s+back\\s+rewards?\\s+balance\\s*$");

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)citicards\\.com|citi(?:bank|cards)?|"
                            + "©\\s*\\d{4}\\s*citibank|citi\\s+(?:double|premier|custom|rewards\\+)|"
                            + "costco\\s+anywhere\\s+visa\\s+by\\s+citi");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        BRANDS.put("double-cash", Pattern.compile("(?i)citi\\s+double\\s+cash"));
        BRANDS.put("costco-anywhere-visa", Pattern.compile("(?i)costco\\s+anywhere\\s+visa"));
        BRANDS.put("premier", Pattern.compile("(?i)citi\\s+premier"));
        BRANDS.put("custom-cash", Pattern.compile("(?i)citi\\s+custom\\s+cash"));
        BRANDS.put("rewards-plus", Pattern.compile("(?i)citi\\s+rewards\\+"));
        BRANDS.put("aadvantage", Pattern.compile("(?i)aadvantage"));
        BRANDS.put("diamond-preferred", Pattern.compile("(?i)citi\\s+diamond\\s+preferred"));
        BRANDS.put("simplicity", Pattern.compile("(?i)citi\\s+simplicity"));
    }

    public CitiIssuerProfile() {
        super("citi", "Citi", HEADER, BRANDS);
    }

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal costcoForm = matchAmount(lines, CITI_AVAILABLE_CREDIT_LIMIT);
        return costcoForm != null ? costcoForm : StatementParsingUtilities.extractAvailableCredit(lines);
    }

    @Override
    public BigDecimal extractPaymentsAndCreditsTotal(
            final String[] lines, final ExtractionContext ctx) {
        // Citi prints Payments and Credits as TWO separate lines, both with
        // negative sign attached:
        //   "Payments -$1,670.03"
        //   "Credits -$38.51"
        // The math identity uses the SUM. Pre-fix, this method returned only
        // the Payments value, so the validator failed by exactly the Credits
        // amount. We now scan for both, sum them, and normalize to positive.
        BigDecimal payments = null;
        BigDecimal credits = null;
        for (final String line : lines) {
            if (line == null) continue;
            final String trimmed = line.trim();
            if (payments == null) {
                final Matcher m = CITI_PAYMENTS_BARE.matcher(trimmed);
                if (m.find()) {
                    try {
                        payments = new BigDecimal(m.group(1).replace(",", "")).abs();
                    } catch (NumberFormatException ignored) {
                        // skip and keep scanning
                    }
                }
            }
            if (credits == null) {
                final Matcher m = CITI_CREDITS_BARE.matcher(trimmed);
                if (m.find()) {
                    try {
                        credits = new BigDecimal(m.group(1).replace(",", "")).abs();
                    } catch (NumberFormatException ignored) {
                        // skip and keep scanning
                    }
                }
            }
        }
        if (payments != null && credits != null) {
            return payments.add(credits);
        }
        if (payments != null) {
            return payments;
        }
        if (credits != null) {
            return credits;
        }
        return super.extractPaymentsAndCreditsTotal(lines, ctx);
    }

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, CITI_STANDARD_PURCH_APR);
        return direct != null ? direct : StatementParsingUtilities.extractPurchaseApr(lines);
    }

    @Override
    public BigDecimal extractCashAdvanceApr(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, CITI_STANDARD_ADV_APR);
        return direct != null ? direct : StatementParsingUtilities.extractCashAdvanceApr(lines);
    }

    @Override
    public BigDecimal extractBalanceTransferApr(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, CITI_STANDARD_BAL_TRANS_APR);
        return direct != null ? direct : StatementParsingUtilities.extractBalanceTransferApr(lines);
    }

    @Override
    public Integer extractBillingDays(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = CITI_DAYS_IN_BILLING_CYCLE.matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractBillingDays(lines);
    }

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = CITI_BILLING_PERIOD.matcher(line);
            if (m.find()) {
                // The END of the billing period is Citi's statement-close date.
                return parseSlashDate(m.group(2), ctx.inferredYear(), ctx.usLocale());
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        // "next AutoPay payment of $X" is conditional on enrollment — Citi doesn't
        // print it unless AutoPay is configured.
        if (CITI_AUTOPAY_AMOUNT.matcher(joinLines(lines)).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = CITI_AUTOPAY_AMOUNT.matcher(joinLines(lines));
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
        // Multi-line "Total ThankYou Points Balance:" \n "21,371" form.
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) {
                continue;
            }
            if (!CITI_TY_POINTS_BARE_LABEL.matcher(lines[i]).find()) {
                continue;
            }
            int scanned = 0;
            for (int j = i + 1; j < lines.length && scanned < 5; j++) {
                final String next = lines[j];
                if (next == null || next.isBlank()) {
                    continue;
                }
                scanned++;
                final Matcher mm = CITI_INTEGER_ON_LINE.matcher(next);
                if (mm.find()) {
                    try {
                        return Long.parseLong(mm.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return StatementParsingUtilities.extractPointsBalance(lines);
    }

    @Override
    public BigDecimal extractCashBackBalance(
            final String[] lines, final ExtractionContext ctx) {
        // Single-line form first.
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) {
                continue;
            }
            final Matcher m = CITI_COSTCO_CASH_BACK_INLINE.matcher(lines[i]);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            // Multi-line wrap: bare label first line, " Year to Date :  $X" next line.
            if (CITI_COSTCO_CASH_BACK_BARE_LABEL.matcher(lines[i]).find()
                    && i + 1 < lines.length && lines[i + 1] != null) {
                final String merged = lines[i] + " " + lines[i + 1].trim();
                final Matcher mm = CITI_COSTCO_CASH_BACK_INLINE.matcher(merged);
                if (mm.find()) {
                    try {
                        return new BigDecimal(mm.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return StatementParsingUtilities.extractCashBackBalance(lines);
    }

    // ---- Helpers ----

    private static BigDecimal matchAmount(final String[] lines, final Pattern pattern) {
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

    private static BigDecimal matchPercent(final String[] lines, final Pattern pattern) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = pattern.matcher(line);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1));
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
