package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * U.S. Bank — Reflect (intro-APR), Cash+ (rotating category), Altitude (travel),
 * Smartly Visa Signature. The Reflect intro-APR card is the most common; its layout
 * has the distinctive "Open Date / Closing Date" header pair and {@code Days in
 * Billing Period N} trailing-number form.
 *
 * <p>Owns the patterns that uniquely identify a U.S. Bank statement layout. The
 * shared StatementParsingUtilities serves as the fallback for unknown issuers, but
 * for any statement detected as U.S. Bank, this profile is authoritative.
 */

public final class USBankIssuerProfile extends AbstractIssuerProfile {

    // USB-only labels for limit and available credit.
    private static final Pattern USB_REVOLVING_LINE_OF_CREDIT =
            Pattern.compile(
                    "(?i)^\\s*revolving\\s+line\\s+of\\s+credit[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");
    private static final Pattern USB_REVOLVING_LINE_AVAILABLE =
            Pattern.compile(
                    "(?i)^\\s*revolving\\s+line\\s+available[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // USB "Previous Balance + $X" — leading '+' decoration, value is positive.
    private static final Pattern USB_PREVIOUS_BALANCE_DECORATED =
            Pattern.compile(
                    "(?i)^\\s*previous\\s+balance\\s+\\+\\s+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // USB "Payments - $X" — value-position minus, not a sign on the dollar amount.
    private static final Pattern USB_PAYMENTS_DECORATED =
            Pattern.compile(
                    "(?i)^\\s*payments\\s+-\\s+\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // USB transaction-detail per-period total: "TOTAL THIS PERIOD $215.00".
    private static final Pattern USB_TOTAL_THIS_PERIOD =
            Pattern.compile(
                    "(?i)^\\s*total\\s+this\\s+period[\\s:]+"
                            + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // USB end-of-row APR shapes: "**PURCHASES $X $X $X N.NN% MM/YYYY".
    private static final Pattern USB_PURCHASE_APR =
            Pattern.compile(
                    "(?i)^\\s*\\*{0,2}\\s*purchases?\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");
    private static final Pattern USB_CASH_ADVANCE_APR =
            Pattern.compile(
                    "(?i)^\\s*\\*{0,2}\\s*(?:cash\\s+)?advances?\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");
    private static final Pattern USB_BALANCE_TRANSFER_APR =
            Pattern.compile(
                    "(?i)^\\s*\\*{0,2}\\s*balance\\s+transfers?\\b.*?(\\d{1,2}\\.\\d{1,4})\\s*%");

    // USB "Days in Billing Period 33" — number trails the label.
    private static final Pattern USB_BILLING_DAYS_TRAILING =
            Pattern.compile(
                    "(?i)\\bdays\\s+in\\s+billing\\s+(?:period|cycle)[\\s:]+(\\d{1,2})\\b");

    // USB header: "Open Date: 12/06/2025 Closing Date: 01/07/2026".
    private static final Pattern USB_OPEN_CLOSING_DATES =
            Pattern.compile(
                    "(?i)open\\s+date[\\s:]+([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                            + "\\s+closing\\s+date[\\s:]+"
                            + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    // USB AutoPay sentences (two equivalent phrasings):
    //   "An automatic payment of $213.00 will be deducted from your account on 02/02/26"
    //   "Your payment of $213.00 will be automatically deducted from your bank account..."
    private static final Pattern USB_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)(?:automatic\\s+payment\\s+of|payment\\s+of)\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"
                            + "\\s+(?:will\\s+be|is)\\s+(?:automatically\\s+)?deducted");

    // USB rewards rows.
    private static final Pattern USB_EARNED_THIS_STATEMENT =
            Pattern.compile(
                    "(?i)^\\s*earned\\s+this\\s+statement[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");
    private static final Pattern USB_REWARD_CENTER_BALANCE =
            Pattern.compile(
                    "(?i)^\\s*reward\\s+center\\s+balance[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)u\\.?s\\.?\\s*bank|usbank\\.com|"
                            + "u\\.s\\.\\s+bank\\s+(?:national\\s+association|cardmember\\s+service)");

    private static final Map<String, Pattern> BRANDS = new LinkedHashMap<>();

    static {
        BRANDS.put("smartly-visa-signature", Pattern.compile("(?i)smartly\\s+visa\\s+signature"));
        BRANDS.put("altitude-reserve", Pattern.compile("(?i)altitude\\s+reserve"));
        BRANDS.put("altitude-connect", Pattern.compile("(?i)altitude\\s+connect"));
        BRANDS.put("altitude-go", Pattern.compile("(?i)altitude\\s+go"));
        BRANDS.put("cash-plus", Pattern.compile("(?i)\\bcash\\+\\s+visa\\b"));
        BRANDS.put("reflect", Pattern.compile("(?i)\\breflect\\b"));
        BRANDS.put("shopper-cash-rewards", Pattern.compile("(?i)shopper\\s+cash\\s+rewards"));
    }

    public USBankIssuerProfile() {
        super("us-bank", "U.S. Bank", HEADER, BRANDS);
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchAmount(lines, USB_REVOLVING_LINE_OF_CREDIT);
        return direct != null ? direct : StatementParsingUtilities.extractCreditLimit(lines);
    }

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchAmount(lines, USB_REVOLVING_LINE_AVAILABLE);
        return direct != null ? direct : StatementParsingUtilities.extractAvailableCredit(lines);
    }

    @Override
    public BigDecimal extractPreviousBalance(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchAmount(lines, USB_PREVIOUS_BALANCE_DECORATED);
        return direct != null ? direct : StatementParsingUtilities.extractPreviousBalance(lines);
    }

    @Override
    public BigDecimal extractPaymentsAndCreditsTotal(
            final String[] lines, final ExtractionContext ctx) {
        BigDecimal direct = matchAmount(lines, USB_TOTAL_THIS_PERIOD);
        if (direct == null) {
            direct = matchAmount(lines, USB_PAYMENTS_DECORATED);
        }
        if (direct != null) {
            // USB prints these as positive (the "-" is value-position, not a sign).
            // Defer to the base's normalize-to-positive convention for consistency.
            return direct.abs();
        }
        return super.extractPaymentsAndCreditsTotal(lines, ctx);
    }

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, USB_PURCHASE_APR);
        return direct != null ? direct : StatementParsingUtilities.extractPurchaseApr(lines);
    }

    @Override
    public BigDecimal extractCashAdvanceApr(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, USB_CASH_ADVANCE_APR);
        return direct != null ? direct : StatementParsingUtilities.extractCashAdvanceApr(lines);
    }

    @Override
    public BigDecimal extractBalanceTransferApr(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal direct = matchPercent(lines, USB_BALANCE_TRANSFER_APR);
        return direct != null ? direct : StatementParsingUtilities.extractBalanceTransferApr(lines);
    }

    @Override
    public Integer extractBillingDays(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = USB_BILLING_DAYS_TRAILING.matcher(line);
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
            final Matcher m = USB_OPEN_CLOSING_DATES.matcher(line);
            if (m.find()) {
                // Closing date is group 2 — the end of the period, USB's statement date.
                return parseSlashDate(m.group(2), ctx.inferredYear(), ctx.usLocale());
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        if (USB_AUTOPAY_AMOUNT.matcher(joinLines(lines)).find()) {
            return Boolean.TRUE;
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        final Matcher m = USB_AUTOPAY_AMOUNT.matcher(joinLines(lines));
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
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = USB_REWARD_CENTER_BALANCE.matcher(line);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractPointsBalance(lines);
    }

    @Override
    public Long extractPointsEarnedThisPeriod(
            final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = USB_EARNED_THIS_STATEMENT.matcher(line);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractPointsEarnedThisPeriod(lines);
    }

    // ---- Helpers (kept local to USB; same shape as WellsFargoIssuerProfile) ----

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
