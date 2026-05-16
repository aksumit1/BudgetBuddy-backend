package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple Card (issued by Goldman Sachs Bank USA). Distinctive minimalist statement
 * layout that diverges from every other issuer we support:
 *
 * <ul>
 *   <li><b>Total Balance</b> instead of "New Balance".
 *   <li><b>Statement Date</b> on its own line (like Chase) — no Open/Close range, no
 *       Billing Period.
 *   <li><b>Daily Cash earned this month</b> as a dollar value — Apple Card has no
 *       points, just Daily Cash (1%/2%/3% rebates as cash back). Maps to the same
 *       {@code cashBackBalance} field as Wells Fargo Active Cash and Citi Costco.
 *   <li><b>No foreign-transaction fee, no annual fee</b> — both correctly extract as
 *       null since the disclosure text doesn't print those rows.
 *   <li><b>AutoPay phrasing</b>: "Your payment will be deducted on MM/DD" with a
 *       separate "AutoPay payment amount: $X" line.
 *   <li><b>Member since</b> footer instead of card-ending-in line.
 * </ul>
 */

public final class AppleCardIssuerProfile extends AbstractIssuerProfile {

    private static final Pattern HEADER =
            Pattern.compile(
                    "(?i)apple\\s+card|goldman\\s+sachs\\s+bank|card\\.apple\\.com");

    public AppleCardIssuerProfile() {
        super("apple-card", "Apple Card", HEADER, Collections.emptyMap());
    }

    // Apple Card: "Total Balance $X" — the canonical balance row.
    private static final Pattern APPLE_TOTAL_BALANCE =
            Pattern.compile(
                    "(?i)^\\s*total\\s+balance[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractNewBalance(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = APPLE_TOTAL_BALANCE.matcher(line.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractNewBalance(lines);
    }

    // Apple Card: "Daily Cash earned this month $X.XX" + "Daily Cash balance $X.XX".
    // The "balance" line is the cumulative redeemable amount (mapped to cashBackBalance).
    private static final Pattern APPLE_DAILY_CASH_BALANCE =
            Pattern.compile(
                    "(?i)daily\\s+cash\\s+balance[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public BigDecimal extractCashBackBalance(
            final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = APPLE_DAILY_CASH_BALANCE.matcher(line);
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

    // Apple Card has no foreign-tx fee, no annual fee, no penalty APR. The standard
    // extractors correctly return null because those phrases don't appear in the
    // statement — no override needed.

    // Apple Card AutoPay row: "AutoPay payment amount $X.XX" on its own.
    private static final Pattern APPLE_AUTOPAY_AMOUNT =
            Pattern.compile(
                    "(?i)autopay\\s+payment\\s+amount[\\s:]+"
                            + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            if (APPLE_AUTOPAY_AMOUNT.matcher(line).find()) {
                return Boolean.TRUE;
            }
        }
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(
            final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = APPLE_AUTOPAY_AMOUNT.matcher(line);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    // Apple Card APR row: "Variable APR for purchases X.XX%" — different label.
    private static final Pattern APPLE_VARIABLE_APR =
            Pattern.compile(
                    "(?i)variable\\s+apr\\s+for\\s+purchases[\\s:]+(\\d{1,2}\\.\\d{1,4})\\s*%");

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = APPLE_VARIABLE_APR.matcher(line);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return StatementParsingUtilities.extractPurchaseApr(lines);
    }

    // Apple Card statement-date label: "Statement Date MM/DD/YY" (no colon variant).
    private static final Pattern APPLE_STATEMENT_DATE =
            Pattern.compile(
                    "(?i)^\\s*statement\\s+date\\s+"
                            + "([\\d]{1,2}/[\\d]{1,2}/[\\d]{2,4})");

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = APPLE_STATEMENT_DATE.matcher(line.trim());
            if (m.find()) {
                final String raw = m.group(1);
                final String[] parts = raw.split("/");
                try {
                    int y = Integer.parseInt(parts[2]);
                    if (y < 100) {
                        y += 2000;
                    }
                    return LocalDate.of(
                            y, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                } catch (NumberFormatException | java.time.DateTimeException ignored) {
                    // fall through
                }
            }
        }
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }
}
