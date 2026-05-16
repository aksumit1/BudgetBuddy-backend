package com.budgetbuddy.service.pdf.profile;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.pdf.PdfStatementPatterns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parsing utilities for credit-card statement extractors.
 *
 * <p>Holds the regex-matching helpers ({@code extractLabeledAmount},
 * {@code staticParseAmount}, etc.), the union of cross-issuer Pattern[] arrays
 * (NEW_BALANCE_LABELS, CREDIT_LIMIT_LABELS, …), and the two records
 * ({@code QuarterlyBonus}, {@code NextQuarterBonus}) returned by Chase Freedom
 * rewards extraction. Per-issuer {@link IssuerProfile}s use this class for the
 * generic patterns they share with everyone else; their issuer-specific patterns
 * live inside the profile itself.
 *
 * <p>The patterns here are the cross-issuer union — labels and forms common across
 * most US credit-card issuers. Issuer-specific patterns live in their respective
 * {@link IssuerProfile} subclasses; this class is what those profiles delegate
 * to via {@link AbstractIssuerProfile} for everything they don't override.
 */
@SuppressWarnings({"PMD.GodClass", "PMD.CyclomaticComplexity",
        "PMD.NcssCount", "PMD.TooManyFields", "PMD.TooManyMethods", "PMD.ExcessiveClassLength",
        "PMD.UnusedPrivateMethod"})
public final class StatementParsingUtilities {

    /**
     * Centralised reference to the project-wide US amount regex string so we don't
     * duplicate it here. The patterns below build on top of it.
     */
    private static final String US_AMOUNT_PATTERN_STR = PdfStatementPatterns.US_AMOUNT_PATTERN_STR;

    private StatementParsingUtilities() {
        // static utility
    }

// ========================================================================
//  Cross-issuer pattern union — shared by all profiles via delegation
//
//  Every {@code public static extract*} method below is the SHARED path.
//  New code should NOT call these directly. Instead, detect the issuer
//  via {@link com.budgetbuddy.service.pdf.profile.IssuerProfileRegistry},
//  then call the {@link com.budgetbuddy.service.pdf.profile.IssuerProfile}
//  contract methods (extractNewBalance, extractCreditLimit, etc.).
//
//  Why these still exist:
//  1. Per-issuer profiles (Wells, USB, Citi, Amex, Chase, BoA, Apple,
//     Discover) delegate to them via super() as a transitional fallback
//     for patterns that haven't yet been migrated into the profile.
//  2. {@link com.budgetbuddy.service.pdf.profile.GenericFallbackProfile}
//     runs the full union for unknown issuers we don't yet recognize.
//  3. ~30 existing tests call these directly as black-box checks on the
//     pattern union. Migrating those tests is an incremental cleanup.
//
//  Removal plan:
//  - As each profile fully owns its patterns, remove the corresponding
//    pattern entries from the arrays in this section.
//  - Once every pattern below is duplicated in some profile, the
//    GenericFallbackProfile becomes the only remaining caller — at that
//    point migrate it to a slimmer "fall back to default heuristics"
//    implementation and delete this section.
//
//  Do NOT add new patterns here. Add them to the appropriate per-issuer
//  profile under service/pdf/profile/.
// ========================================================================
//
// All balance / credit-limit label patterns anchor to start-of-line (^\s*) — NOT
// just \b. The \b form was too permissive: a line like "Balance over the Credit
// Access Line $0.00" (Chase prints this BEFORE the actual "Credit Access Line
// $25,000" row) would otherwise satisfy \bcredit\s+access\s+line and extract $0.00
// as the credit limit. With ^\s*, the regex requires the label to BEGIN the line
// — disclosure prose can never collide with the summary block.
private static final Pattern[] NEW_BALANCE_LABELS = {
    Pattern.compile("(?i)^\\s*new\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*statement\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*current\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-summary "New Balance" — the total owed on this statement. */
public static BigDecimal extractNewBalance(final String[] lines) {
    // Chase prints "New Balance $403.87" — always positive, never zero on an active card.
    // Allow zero for paid-off statements.
    return extractLabeledAmount(lines, NEW_BALANCE_LABELS, true);
}

private static final Pattern[] PREVIOUS_BALANCE_LABELS = {
    Pattern.compile("(?i)^\\s*previous\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*prior\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*last\\s+statement\\s+balance[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // U.S. Bank decorates the row with a leading "+": "Previous Balance + $X". The
    // sign is informational (previous balance is always positive); just skip past it.
    Pattern.compile("(?i)^\\s*previous\\s+balance\\s+\\+\\s+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-summary "Previous Balance" — the balance carried into this cycle. */
public static BigDecimal extractPreviousBalance(final String[] lines) {
    return extractLabeledAmount(lines, PREVIOUS_BALANCE_LABELS, true);
}

private static final Pattern[] CREDIT_LIMIT_LABELS = {
    // Chase labels this "Credit Access Line"; mainstream cards use "Credit Limit"; some
    // statements use "Total Credit Limit". Order matters: most-specific first so
    // "Total Credit Limit" doesn't get short-circuited by the generic "Credit Limit".
    Pattern.compile("(?i)^\\s*credit\\s+access\\s+line[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*total\\s+credit\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*credit\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // U.S. Bank: "Revolving Line of Credit $22,100.00"
    Pattern.compile(
            "(?i)^\\s*revolving\\s+line\\s+of\\s+credit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Amex disclosure-section restatement: "Pay Over Time Limit: $35,000.00".
    // (The front-page version uses a multi-line label-then-value layout handled by
    // AMEX_POT_LIMIT_LABEL_PATTERN below.)
    Pattern.compile(
            "(?i)^\\s*pay\\s+over\\s+time\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

// Amex prints "Pay Over Time Limit" on one line and "$35,000.00" on the next.
// Different label entirely — captured separately so we can do multi-line pairing.
private static final Pattern AMEX_POT_LIMIT_LABEL_PATTERN =
        Pattern.compile("(?i)^\\s*pay\\s+over\\s+time\\s+limit\\s*$");

private static final Pattern AMEX_AVAILABLE_POT_LABEL_PATTERN =
        Pattern.compile("(?i)^\\s*available\\s+pay\\s+over\\s+time\\s+limit\\s*$");

/**
 * Statement-summary "Credit Limit" / Chase "Credit Access Line".
 *
 * @deprecated Use {@code IssuerProfile.extractCreditLimit(lines, ctx)} via
 *     {@link com.budgetbuddy.service.pdf.profile.IssuerProfileRegistry} instead.
 *     Per-issuer profiles (Chase, Wells, USB, Citi, Amex, BoA, Apple, Discover)
 *     own this field's extraction logic for their statement layouts. This static
 *     method remains as the fallback union of all known patterns for unknown
 *     issuers; it will be removed once the generic fallback profile is migrated.
 */
public static BigDecimal extractCreditLimit(final String[] lines) {
    final BigDecimal direct = extractLabeledAmount(lines, CREDIT_LIMIT_LABELS, false);
    if (direct != null) {
        return direct;
    }
    // Amex stacked-label layout: limit + available labels on two consecutive lines,
    // values on the next two lines. Take the FIRST value (= Pay Over Time Limit).
    final BigDecimal stackedLimit = extractAmexStackedPotLimit(lines);
    if (stackedLimit != null) {
        return stackedLimit;
    }
    // Generic multi-line: "Pay Over Time Limit" \n "$35,000.00".
    return extractAmountAfterBareLabel(lines, AMEX_POT_LIMIT_LABEL_PATTERN);
}

private static BigDecimal extractAmexStackedPotLimit(final String[] lines) {
    for (int i = 0; i + 3 < lines.length; i++) {
        if (lines[i] == null || lines[i + 1] == null) {
            continue;
        }
        if (!AMEX_POT_LIMIT_LABEL_PATTERN.matcher(lines[i]).find()) {
            continue;
        }
        if (!AMEX_AVAILABLE_POT_LABEL_PATTERN.matcher(lines[i + 1]).find()) {
            continue;
        }
        for (int j = i + 2; j < lines.length && j < i + 8; j++) {
            final String next = lines[j];
            if (next == null || next.isBlank()) {
                continue;
            }
            final Matcher m =
                    Pattern.compile(
                                    "^\\s*\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$")
                            .matcher(next.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            break;
        }
    }
    return null;
}

/**
 * Look for a line matching {@code labelPattern}, then return the first $-amount on
 * the next non-blank line (up to 4 lines ahead). Used for issuer layouts that print
 * a bare label on one line with the value on a subsequent line.
 */
private static BigDecimal extractAmountAfterBareLabel(
        final String[] lines, final Pattern labelPattern) {
    for (int i = 0; i < lines.length; i++) {
        if (lines[i] == null) {
            continue;
        }
        if (!labelPattern.matcher(lines[i]).find()) {
            continue;
        }
        for (int j = i + 1; j < lines.length && j < i + 5; j++) {
            final String next = lines[j];
            if (next == null || next.isBlank()) {
                continue;
            }
            final Matcher m =
                    Pattern.compile(
                                    "^\\s*\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$")
                            .matcher(next.trim());
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            break;
        }
    }
    return null;
}

private static final Pattern[] AVAILABLE_CREDIT_LABELS = {
    Pattern.compile("(?i)^\\s*available\\s+credit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*credit\\s+available[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo: "Total Available Credit $16,200"
    Pattern.compile("(?i)^\\s*total\\s+available\\s+credit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // U.S. Bank: "Revolving Line Available $894.68"
    Pattern.compile(
            "(?i)^\\s*revolving\\s+line\\s+available[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Citi Costco: "Available Credit Limit  $13,041"
    Pattern.compile(
            "(?i)^\\s*available\\s+credit\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/**
 * Statement-summary "Available Credit" — credit limit minus current balance.
 *
 * @deprecated Use {@code IssuerProfile.extractAvailableCredit(lines, ctx)} via
 *     {@link com.budgetbuddy.service.pdf.profile.IssuerProfileRegistry} instead.
 *     Each per-issuer profile owns its specific label phrasing (BoA "Total
 *     Credit Line Available", Wells "Total Available Credit", USB "Revolving
 *     Line Available", Citi Costco "Available Credit Limit", Amex stacked
 *     "Available Pay Over Time Limit"). This static method is the fallback
 *     union; it will be removed once the generic fallback profile is migrated.
 */
public static BigDecimal extractAvailableCredit(final String[] lines) {
    final BigDecimal direct = extractLabeledAmount(lines, AVAILABLE_CREDIT_LABELS, true);
    if (direct != null) {
        return direct;
    }
    // Amex front-page layout prints both labels on consecutive lines and both values
    // on consecutive lines below them:
    //   "Pay Over Time Limit"
    //   "Available Pay Over Time Limit"
    //   "$35,000.00"
    //   "$34,021.51"
    // The bare-label scan would pick the FIRST $-line for both labels (returning
    // creditLimit's value for availableCredit). Detect the stacked-label form and
    // map labels to values in order.
    final BigDecimal amexAvailable = extractAmexStackedPotAvailable(lines);
    if (amexAvailable != null) {
        return amexAvailable;
    }
    // Fall back to the generic single-label-then-value scan.
    return extractAmountAfterBareLabel(lines, AMEX_AVAILABLE_POT_LABEL_PATTERN);
}

private static BigDecimal extractAmexStackedPotAvailable(final String[] lines) {
    for (int i = 0; i + 3 < lines.length; i++) {
        if (lines[i] == null || lines[i + 1] == null) {
            continue;
        }
        if (!AMEX_POT_LIMIT_LABEL_PATTERN.matcher(lines[i]).find()) {
            continue;
        }
        if (!AMEX_AVAILABLE_POT_LABEL_PATTERN.matcher(lines[i + 1]).find()) {
            continue;
        }
        // Found the stacked labels. Collect the next two $-amount lines.
        final java.util.List<BigDecimal> values = new java.util.ArrayList<>(2);
        for (int j = i + 2; j < lines.length && values.size() < 2 && j < i + 8; j++) {
            final String next = lines[j];
            if (next == null || next.isBlank()) {
                continue;
            }
            final Matcher m =
                    Pattern.compile(
                                    "^\\s*\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$")
                            .matcher(next.trim());
            if (m.find()) {
                try {
                    values.add(new BigDecimal(m.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        if (values.size() == 2) {
            // Second value corresponds to the second label (Available POT Limit).
            return values.get(1);
        }
    }
    return null;
}

private static final Pattern[] PAST_DUE_LABELS = {
    Pattern.compile("(?i)^\\s*past\\s+due\\s+amount[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*amount\\s+past\\s+due[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*past\\s+due[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-summary "Past Due Amount" — non-zero means the user is delinquent. */
public static BigDecimal extractPastDueAmount(final String[] lines) {
    return extractLabeledAmount(lines, PAST_DUE_LABELS, true);
}

// ---------- section totals ----------

private static final Pattern[] PURCHASES_TOTAL_LABELS = {
    // Chase prints "Purchases +$403.87" — note the literal "+". We don't anchor on the
    // sign because some issuers omit it. The amount pattern handles both forms.
    Pattern.compile("(?i)^\\s*purchases[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo prints the per-period section total as a single line:
    // "TOTAL PURCHASES, BALANCE TRANSFERS & OTHER CHARGES FOR THIS PERIOD $13,696.35".
    // The account-summary version splits the label across three lines, so we key off
    // this one instead.
    Pattern.compile(
            "(?i)^\\s*total\\s+purchases(?:,\\s*balance\\s+transfers\\s*(?:&|and)\\s*other"
                    + "\\s+charges)?\\s+for\\s+this\\s+period[\\s:]+"
                    + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Purchases. */
public static BigDecimal extractPurchasesTotal(final String[] lines) {
    return extractLabeledAmount(lines, PURCHASES_TOTAL_LABELS, true);
}

private static final Pattern[] PAYMENTS_CREDITS_LABELS = {
    Pattern.compile(
            "(?i)^\\s*payment(?:s?)\\s*,?\\s*credits[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*payments\\s+and\\s+credits[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo per-period section total: "TOTAL PAYMENTS FOR THIS PERIOD $545.91".
    // The account-summary version is "- Payments $545.91" (a leading minus, not a sign).
    Pattern.compile(
            "(?i)^\\s*total\\s+payments\\s+for\\s+this\\s+period[\\s:]+"
                    + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*-\\s*payments[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // U.S. Bank: "Payments - $215.00" — value-position minus, not a label prefix.
    Pattern.compile("(?i)^\\s*payments\\s+-\\s+" + US_AMOUNT_PATTERN_STR),
    // U.S. Bank per-period total: "TOTAL THIS PERIOD $215.00" (under "Payments and
    // Other Credits" section header — we don't see the header on this line so we
    // rely on the strong phrase match).
    Pattern.compile("(?i)^\\s*total\\s+this\\s+period[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Citi: bare "Payments -$3,110.20" on its own line (sign tightly attached to $).
    // US_AMOUNT_PATTERN_STR group 2 already accepts -$NNN so we only need a label
    // anchor that doesn't require a "credits" trailer.
    Pattern.compile("(?i)^\\s*payments[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Payments + Credits. Always negative for an active card. */
public static BigDecimal extractPaymentsAndCreditsTotal(final String[] lines) {
    return extractLabeledAmount(lines, PAYMENTS_CREDITS_LABELS, true);
}

private static final Pattern[] CASH_ADVANCES_TOTAL_LABELS = {
    Pattern.compile("(?i)^\\s*cash\\s+advances[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Cash Advances. */
public static BigDecimal extractCashAdvancesTotal(final String[] lines) {
    return extractLabeledAmount(lines, CASH_ADVANCES_TOTAL_LABELS, true);
}

private static final Pattern[] BALANCE_TRANSFERS_TOTAL_LABELS = {
    Pattern.compile("(?i)^\\s*balance\\s+transfers[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Balance Transfers. */
public static BigDecimal extractBalanceTransfersTotal(final String[] lines) {
    return extractLabeledAmount(lines, BALANCE_TRANSFERS_TOTAL_LABELS, true);
}

private static final Pattern[] FEES_CHARGED_LABELS = {
    Pattern.compile("(?i)^\\s*fees\\s+charged[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*total\\s+fees[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo: "TOTAL FEES CHARGED FOR THIS PERIOD $0.00".
    Pattern.compile(
            "(?i)^\\s*total\\s+fees\\s+charged\\s+for\\s+this\\s+period[\\s:]+"
                    + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Fees Charged. */
public static BigDecimal extractFeesChargedTotal(final String[] lines) {
    return extractLabeledAmount(lines, FEES_CHARGED_LABELS, true);
}

private static final Pattern[] INTEREST_CHARGED_LABELS = {
    Pattern.compile("(?i)^\\s*interest\\s+charged[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*total\\s+interest[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo: "TOTAL INTEREST CHARGED FOR THIS PERIOD $0.00".
    Pattern.compile(
            "(?i)^\\s*total\\s+interest\\s+charged\\s+for\\s+this\\s+period[\\s:]+"
                    + US_AMOUNT_PATTERN_STR),
};

/** Statement-section total: Interest Charged. */
public static BigDecimal extractInterestChargedTotal(final String[] lines) {
    return extractLabeledAmount(lines, INTEREST_CHARGED_LABELS, true);
}

// ---------- APR rates ----------

/**
 * Extract a percent rate keyed by label. Chase rows look like
 * "Purchases 19.49%(v)(d)" or "Cash Advances 28.49%(v)(d)" — the rate is the first
 * percent value on a line whose label matches. Returns null when nothing matches.
 */
private static BigDecimal extractLabeledPercent(
        final String[] lines, final Pattern labelPattern) {
    for (final String line : lines) {
        if (line == null || line.isBlank()) {
            continue;
        }
        final Matcher m = labelPattern.matcher(line.trim());
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException nfe) {
                // continue looking
            }
        }
    }
    return null;
}

// Three APR row shapes are supported:
//   Chase / Wells: "Purchases 19.49%(v)(d) ..." — APR is the first percent after the
//     label.
//   U.S. Bank: "**PURCHASES $21,205.32 $21,381.22 $0.00 0.00% 04/2026" — APR is the
//     percent at end-of-row, after balances. The `\*{0,2}` prefix tolerates the "**"
//     marker USB uses, and `.*?` skips the intermediate balance columns.
//   Citi: " Standard Purch 19.49% (V) $0.00 (D) $0.00" — the row label is the
//     "Standard Purch / Standard Adv / Standard Bal Trans" sub-label under a section
//     heading. Each pattern accepts BOTH the issuer-standard label AND the Citi
//     sub-label.
private static final Pattern PURCHASE_APR_PATTERN =
        Pattern.compile(
                "(?i)^\\s*\\*{0,2}\\s*(?:purchases?|standard\\s+purch)\\b"
                        + ".*?(\\d{1,2}\\.\\d{1,4})\\s*%");
private static final Pattern CASH_APR_PATTERN =
        Pattern.compile(
                "(?i)^\\s*\\*{0,2}\\s*(?:cash\\s+advances?|advances?|standard\\s+adv)\\b"
                        + ".*?(\\d{1,2}\\.\\d{1,4})\\s*%");
private static final Pattern BT_APR_PATTERN =
        Pattern.compile(
                "(?i)^\\s*\\*{0,2}\\s*(?:balance\\s+transfers?|standard\\s+bal\\s+trans)\\b"
                        + ".*?(\\d{1,2}\\.\\d{1,4})\\s*%");
private static final Pattern PENALTY_APR_PATTERN =
        Pattern.compile(
                "(?i)penalty\\s+apr\\s+of\\s+(\\d{1,2}\\.\\d{1,4})\\s*%");

/** Variable purchase APR (e.g. 19.49). Null when the disclosure block is missing. */
public static BigDecimal extractPurchaseApr(final String[] lines) {
    return extractLabeledPercent(lines, PURCHASE_APR_PATTERN);
}

/** Variable cash-advance APR. Always higher than the purchase APR on Chase cards. */
public static BigDecimal extractCashAdvanceApr(final String[] lines) {
    return extractLabeledPercent(lines, CASH_APR_PATTERN);
}

/** Variable balance-transfer APR. */
public static BigDecimal extractBalanceTransferApr(final String[] lines) {
    return extractLabeledPercent(lines, BT_APR_PATTERN);
}

/** Penalty APR (kicks in after a missed payment). */
public static BigDecimal extractPenaltyApr(final String[] lines) {
    return extractLabeledPercent(lines, PENALTY_APR_PATTERN);
}

// ---------- annual membership fee + billing date ----------

private static final Pattern ANNUAL_FEE_PATTERN =
        Pattern.compile(
                "(?i)annual\\s+membership\\s+fee[^$]*\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"
                        + ".*?billed\\s+on\\s+([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

/**
 * Extract the annual fee amount and its scheduled billing date from the typical Chase
 * sentence: "Your annual membership fee in the amount of $NN.NN will be billed on
 * MM/DD/YYYY." Returns a 2-element array {amount, date} or null when missing. The
 * helper exists because we need both halves and the date format varies per issuer.
 */
public static Object[] extractAnnualMembershipFeeAndDate(
        final String[] lines, final Integer inferredYear, final boolean isUSLocale) {
    // Join across blank lines so the regex can span the sentence even when it
    // wraps in the PDF text.
    final String joined = String.join(" ", lines).replaceAll("\\s+", " ");
    final Matcher m = ANNUAL_FEE_PATTERN.matcher(joined);
    if (!m.find()) {
        return null;
    }
    final BigDecimal fee = staticParseAmount(m.group(1));
    final LocalDate date = staticParseAnnualFeeDate(m.group(2), inferredYear, isUSLocale);
    return new Object[] {fee, date};
}

private static LocalDate staticParseAnnualFeeDate(
        final String raw, final Integer inferredYear, final boolean isUSLocale) {
    if (raw == null || raw.isBlank()) {
        return null;
    }
    final List<DateTimeFormatter> formatters =
            isUSLocale ? PDFImportService.DATE_FORMATTERS_US : PDFImportService.DATE_FORMATTERS_EUROPEAN;
    for (final DateTimeFormatter fmt : formatters) {
        try {
            return LocalDate.parse(raw.trim(), fmt);
        } catch (DateTimeParseException ignored) {
            // try next
        }
    }
    // Fallback for MM/DD or MM/DD/YY when the year is implicit
    try {
        final String[] parts = raw.split("[/-]");
        if (parts.length == 2 && inferredYear != null) {
            return LocalDate.of(
                    inferredYear, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        if (parts.length == 3) {
            int y = Integer.parseInt(parts[2]);
            if (y < 100) {
                y += 2000;
            }
            return LocalDate.of(y, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    } catch (NumberFormatException ignored) {
        // fall through
    }
    return null;
}

// ---------- cash limits + billing days + statement date ----------

private static final Pattern[] CASH_ACCESS_LINE_LABELS = {
    Pattern.compile("(?i)^\\s*cash\\s+access\\s+line[\\s:]+" + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*cash\\s+credit\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
    // Wells Fargo: "Cash Advance Limit $6,000"
    Pattern.compile("(?i)^\\s*cash\\s+advance\\s+limit[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement "Cash Access Line" — the cash-advance sub-limit. */
public static BigDecimal extractCashAccessLine(final String[] lines) {
    return extractLabeledAmount(lines, CASH_ACCESS_LINE_LABELS, false);
}

private static final Pattern[] AVAILABLE_FOR_CASH_LABELS = {
    // Allow optional "advances" trailer so Wells Fargo's
    // "Available for Cash Advances $6,000" also matches.
    Pattern.compile(
            "(?i)^\\s*available\\s+for\\s+cash(?:\\s+advances?)?[\\s:]+"
                    + US_AMOUNT_PATTERN_STR),
    Pattern.compile("(?i)^\\s*cash\\s+available[\\s:]+" + US_AMOUNT_PATTERN_STR),
};

/** Statement "Available for Cash" — cash-advance headroom. */
public static BigDecimal extractAvailableForCash(final String[] lines) {
    return extractLabeledAmount(lines, AVAILABLE_FOR_CASH_LABELS, true);
}

private static final Pattern BILLING_DAYS_PATTERN =
        Pattern.compile("(?i)\\b(\\d{1,2})\\s+days\\s+in\\s+billing\\s+period\\b");

// U.S. Bank prints the count AFTER the label: "Days in Billing Period 33". Also
// Chase's "Days in Billing Cycle 30" — same shape.
private static final Pattern BILLING_DAYS_TRAILING_PATTERN =
        Pattern.compile("(?i)\\bdays\\s+in\\s+billing\\s+(?:period|cycle)[\\s:]+(\\d{1,2})\\b");

/**
 * "31 Days in Billing Period" → 31. When the statement omits that label (Wells Fargo
 * prints "Days in Billing Cycle" as a column header with the number two lines later),
 * fall back to the Statement Period date range — both dates always have full years so
 * locale/year inference isn't needed for the difference.
 */
public static Integer extractBillingDays(final String[] lines) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = BILLING_DAYS_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        final Matcher trailing = BILLING_DAYS_TRAILING_PATTERN.matcher(line);
        if (trailing.find()) {
            try {
                return Integer.parseInt(trailing.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
    // Fallback: derive from "Statement Period MM/DD/YYYY to MM/DD/YYYY" (Wells Fargo).
    final LocalDate[] range = extractStatementPeriodRange(lines, null, true);
    if (range != null) {
        final long days = java.time.temporal.ChronoUnit.DAYS.between(range[0], range[1]) + 1;
        if (days > 0 && days <= 99) {
            return (int) days;
        }
    }
    return null;
}

private static final Pattern STATEMENT_DATE_PATTERN =
        Pattern.compile(
                "(?i)statement\\s+date[\\s:]+([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

// U.S. Bank prints "Closing Date: MM/DD/YYYY" in the header row alongside the
// open date. Amex uses "Closing Date MM/DD/YY" (no colon). Either way, that
// date is the analogue of Chase's "Statement Date". The negative lookbehind
// ensures we don't accidentally match the "Closing" half of Chase's combined
// "Opening/Closing Date 05/13/26 - 06/12/26" label — that form is handled by
// OPENING_CLOSING_RANGE_PATTERN below, which correctly extracts the END date.
private static final Pattern CLOSING_DATE_PATTERN =
        Pattern.compile(
                "(?i)(?<!opening/)(?<!opening\\s)\\bclosing\\s+date[\\s:]+"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})\\b");

// Chase: "Opening/Closing Date 05/13/26 - 06/12/26" — the second date is the
// statement-close date. Run this pattern BEFORE CLOSING_DATE_PATTERN so the
// end-of-range form wins over the unanchored closing-date match.
private static final Pattern OPENING_CLOSING_RANGE_PATTERN =
        Pattern.compile(
                "(?i)opening\\s*[/]\\s*closing\\s+date[\\s:]+"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                        + "\\s*(?:to|through|-|–)\\s*"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

// Wells Fargo prints "Statement Period 03/19/2026 to 04/17/2026". The second date is
// the statement close date — the analogue of Chase's "Statement Date". Citi prints
// "Billing Period: 03/04/26-04/02/26" (no spaces around dash, two-digit year). We
// accept "Statement Period" or "Billing Period" plus several range separators so a
// single pattern covers both layouts.
private static final Pattern STATEMENT_PERIOD_PATTERN =
        Pattern.compile(
                "(?i)(?:statement|billing)\\s+period[\\s:]+"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                        + "\\s*(?:to|through|-|–)\\s*"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

/**
 * Issue date printed in the page header.
 *
 * @deprecated Use {@code IssuerProfile.extractStatementDate(lines, ctx)} via
 *     {@link com.budgetbuddy.service.pdf.profile.IssuerProfileRegistry}. Each
 *     profile owns its date-label parsing (Chase Opening/Closing Date range,
 *     USB Open+Closing pair, Wells Statement Period, Amex Closing Date,
 *     Citi Billing Period, BoA Statement Closing Date, Apple Statement Date).
 *     This static method is the fallback union.
 */
public static LocalDate extractStatementDate(
        final String[] lines, final Integer inferredYear, final boolean isUSLocale) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = STATEMENT_DATE_PATTERN.matcher(line);
        if (m.find()) {
            return staticParseAnnualFeeDate(m.group(1), inferredYear, isUSLocale);
        }
        final Matcher period = STATEMENT_PERIOD_PATTERN.matcher(line);
        if (period.find()) {
            // Group 2 is the end-of-period date, which is what Chase calls "Statement Date".
            return staticParseAnnualFeeDate(period.group(2), inferredYear, isUSLocale);
        }
        // Chase "Opening/Closing Date X - Y" form. The END of the range is the
        // statement-close date — wins over the bare CLOSING_DATE_PATTERN because
        // the bare form would otherwise grab X (the opening date).
        final Matcher range = OPENING_CLOSING_RANGE_PATTERN.matcher(line);
        if (range.find()) {
            return staticParseAnnualFeeDate(range.group(2), inferredYear, isUSLocale);
        }
        // U.S. Bank / Amex closing-date form.
        final Matcher closing = CLOSING_DATE_PATTERN.matcher(line);
        if (closing.find()) {
            return staticParseAnnualFeeDate(closing.group(1), inferredYear, isUSLocale);
        }
    }
    return null;
}

// U.S. Bank header line: "Open Date: 12/06/2025 Closing Date: 01/07/2026 ..." —
// internal helper used only by USBankIssuerProfile's statement-date extraction and
// the billing-day inference path here. Demoted to private during the migration so
// the public surface aligns with the IssuerProfile contract — this isn't a separate
// extractor a caller would invoke; it's a helper for extractStatementDate.
private static LocalDate[] extractUsbOpenClosingRange(
        final String[] lines, final Integer inferredYear, final boolean isUSLocale) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = USB_OPEN_CLOSING_DATES_PATTERN.matcher(line);
        if (m.find()) {
            final LocalDate start =
                    staticParseAnnualFeeDate(m.group(1), inferredYear, isUSLocale);
            final LocalDate end =
                    staticParseAnnualFeeDate(m.group(2), inferredYear, isUSLocale);
            if (start != null && end != null) {
                return new LocalDate[] {start, end};
            }
        }
    }
    return null;
}

/**
 * Returns the period start/end dates as a 2-element array (start, end) when a Wells
 * Fargo "Statement Period X to Y" line is present. Used to compute billing-cycle days
 * when the statement omits an explicit "N days in billing period" row.
 */
// U.S. Bank header: "Open Date: 12/06/2025 Closing Date: 01/07/2026 Account Ending..."
private static final Pattern USB_OPEN_CLOSING_DATES_PATTERN =
        Pattern.compile(
                "(?i)open\\s+date[\\s:]+([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})"
                        + "\\s+closing\\s+date[\\s:]+"
                        + "([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

private static LocalDate[] extractStatementPeriodRange(
        final String[] lines, final Integer inferredYear, final boolean isUSLocale) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = STATEMENT_PERIOD_PATTERN.matcher(line);
        if (m.find()) {
            final LocalDate start =
                    staticParseAnnualFeeDate(m.group(1), inferredYear, isUSLocale);
            final LocalDate end =
                    staticParseAnnualFeeDate(m.group(2), inferredYear, isUSLocale);
            if (start != null && end != null) {
                return new LocalDate[] {start, end};
            }
        }
        final Matcher usb = USB_OPEN_CLOSING_DATES_PATTERN.matcher(line);
        if (usb.find()) {
            final LocalDate start =
                    staticParseAnnualFeeDate(usb.group(1), inferredYear, isUSLocale);
            final LocalDate end =
                    staticParseAnnualFeeDate(usb.group(2), inferredYear, isUSLocale);
            if (start != null && end != null) {
                return new LocalDate[] {start, end};
            }
        }
    }
    return null;
}

/**
 * Foreign-transaction fee percent. Chase prints this in the disclosure block as
 * "There is a foreign transaction fee of 3% of the U.S. dollar amount..."; some
 * issuers use "International Transaction Fee" instead. The line may wrap so we join
 * across lines before matching.
 */
private static final Pattern FOREIGN_TX_FEE_PATTERN =
        Pattern.compile(
                "(?i)(?:foreign|international)\\s+transaction\\s+fee\\s+of\\s+(\\d{1,2}(?:\\.\\d{1,2})?)\\s*%");

// ---------- AutoPay status + scheduled amount ----------

// Chase prints "AUTOPAY IS ON" / "AUTOPAY IS OFF" as a section header. Some other
// issuers use "Automatic Payments: Enabled" — accept both, case-insensitive. Wells
// Fargo doesn't print a status line at all: when AutoPay is configured it instead
// prints "$X - $Y will be deducted from your account and credited as your automatic
// payment on MM/DD/YY", so we treat that sentence as an implicit ON marker. We
// require word boundaries so "automatic" doesn't false-positive on disclosure prose
// like "If we receive automatic payments before processing...".
private static final Pattern AUTOPAY_ON_PATTERN =
        Pattern.compile(
                "(?i)\\b(?:autopay\\s+is\\s+on|automatic\\s+payments?\\s*(?::\\s*enabled|"
                        + "\\s+is\\s+on)|credited\\s+as\\s+your\\s+automatic\\s+payment"
                        // U.S. Bank phrasings:
                        //   "An automatic payment of $X will be deducted from your account"
                        //   "Your payment of $X will be automatically deducted from your bank"
                        + "|automatic\\s+payment\\s+of\\s+\\$"
                        + "|will\\s+be\\s+automatically\\s+deducted"
                        // Citi phrasing: "Your next AutoPay payment of $X will be
                        // deducted from your bank account on MM/DD/YYYY". The
                        // "next autopay payment" anchor is enough on its own — Citi
                        // doesn't print it unless AutoPay is enrolled.
                        + "|next\\s+autopay\\s+payment\\s+of\\s+\\$"
                        // Amex disclosure sentence printed only when AutoPay is
                        // enrolled: "We will debit your bank account for your
                        // payment of $X on MM/DD/YY". The bare "AutoPay Amount"
                        // label is intentionally NOT used as an ON marker because
                        // Amex also references "AutoPay amount" in conditional
                        // disclosure prose ("Your AutoPay amount will be reduced
                        // by ...") that fires even when AutoPay is off.
                        + "|debit\\s+your\\s+bank\\s+account\\s+for\\s+your\\s+payment"
                        + ")\\b");

private static final Pattern AUTOPAY_OFF_PATTERN =
        Pattern.compile(
                "(?i)\\b(?:autopay\\s+is\\s+off|automatic\\s+payments?\\s*(?::\\s*disabled|"
                        + "\\s+is\\s+off))\\b");

/**
 * AutoPay status as a Boolean (true = on, false = off). Null when neither marker is
 * present. The on/off check runs independently — a statement that contains both
 * markers (e.g., one in the summary and one in disclosure prose) prefers ON because
 * Chase only prints ON in disclosure when AutoPay is genuinely active.
 */
public static Boolean extractAutoPayEnabled(final String[] lines) {
    boolean sawOn = false;
    boolean sawOff = false;
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        if (AUTOPAY_ON_PATTERN.matcher(line).find()) {
            sawOn = true;
        } else if (AUTOPAY_OFF_PATTERN.matcher(line).find()) {
            // Else-if so the ON regex (which is a stricter prefix) wins on the same line.
            sawOff = true;
        }
    }
    if (!sawOn && !sawOff) {
        return null;
    }
    return sawOn;
}

// Chase prints "Your next AutoPay payment for $NN.NN will be deducted from..." —
// require the "for $" pivot so we don't accidentally grab some other $ amount on
// an autopay-mention line.
private static final Pattern NEXT_AUTOPAY_AMOUNT_PATTERN =
        Pattern.compile(
                "(?i)next\\s+autopay\\s+payment\\s+(?:for|of)\\s+\\$([\\d]+(?:,\\d{3})*"
                        + "(?:\\.\\d{1,2})?)");

// Wells Fargo phrasing: "$0 - $13,696.35 will be deducted from your account and
// credited as your automatic payment on 05/12/26". The high end of the range is the
// amount we'd expect to be deducted on a full-balance autopay; for a fixed-amount
// autopay both sides of the range are equal. We capture group 2 (the upper bound).
private static final Pattern WELLS_NEXT_AUTOPAY_AMOUNT_PATTERN =
        Pattern.compile(
                "(?i)\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*[-–]\\s*"
                        + "\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s+will\\s+be\\s+deducted"
                        + "[^.]*?automatic\\s+payment");

// U.S. Bank phrasing: "An automatic payment of $213.00 will be deducted from your
// account on 02/02/26" — also "Your payment of $213.00 will be automatically
// deducted from your bank account on 02/02/2026".
private static final Pattern USB_NEXT_AUTOPAY_AMOUNT_PATTERN =
        Pattern.compile(
                "(?i)(?:automatic\\s+payment\\s+of|payment\\s+of)\\s+\\$"
                        + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"
                        + "\\s+(?:will\\s+be|is)\\s+(?:automatically\\s+)?deducted");

// Amex phrasing: "we will debit your bank account for your payment of $978.49 on
// 05/28/26". Captures the amount that will be deducted on the autopay date.
private static final Pattern AMEX_NEXT_AUTOPAY_AMOUNT_PATTERN =
        Pattern.compile(
                "(?i)debit\\s+your\\s+bank\\s+account\\s+for\\s+your\\s+payment\\s+of\\s+\\$"
                        + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

/**
 * Scheduled amount of the next AutoPay deduction, or null when not on AutoPay.
 *
 * @deprecated Use {@code IssuerProfile.extractNextAutoPayAmount(lines, ctx)} via
 *     {@link com.budgetbuddy.service.pdf.profile.IssuerProfileRegistry}. Each
 *     profile owns its issuer-specific AutoPay phrasing (Chase "next AutoPay
 *     payment for $X", Wells "$X - $Y will be deducted", USB "An automatic
 *     payment of $X", Citi "next AutoPay payment of $X", Amex "debit your bank
 *     account for your payment of $X", BoA "monthly payment of $X will
 *     automatically debit", Discover "Your AutoPay amount of $X will be
 *     deducted", Apple "AutoPay payment amount: $X").
 */
public static BigDecimal extractNextAutoPayAmount(final String[] lines) {
    final String joined = String.join(" ", lines).replaceAll("\\s+", " ");
    final Matcher m = NEXT_AUTOPAY_AMOUNT_PATTERN.matcher(joined);
    if (m.find()) {
        return staticParseAmount(m.group(1));
    }
    // Wells Fargo range form. Prefer the upper bound (group 2) since fixed-amount
    // setups have a degenerate range like "$50 - $50".
    final Matcher wells = WELLS_NEXT_AUTOPAY_AMOUNT_PATTERN.matcher(joined);
    if (wells.find()) {
        return staticParseAmount(wells.group(2));
    }
    // U.S. Bank "automatic payment of $X will be deducted" form.
    final Matcher usb = USB_NEXT_AUTOPAY_AMOUNT_PATTERN.matcher(joined);
    if (usb.find()) {
        return staticParseAmount(usb.group(1));
    }
    // Amex "debit your bank account for your payment of $X" form.
    final Matcher amex = AMEX_NEXT_AUTOPAY_AMOUNT_PATTERN.matcher(joined);
    if (amex.find()) {
        return staticParseAmount(amex.group(1));
    }
    return null;
}

// ---------- points: earned-this-period vs. cumulative balance ----------

/**
 * Patterns that identify "points ACCRUED this billing period" — i.e. the new points
 * added during the current statement window. Chase Marriott Bonvoy emits this as
 * "Total points transferred to Marriott Bonvoy NN,NNN"; other issuers use "earned"
 * phrasing. The captured group is always the integer point count (commas allowed).
 */
private static final Pattern[] POINTS_EARNED_PATTERNS = {
    Pattern.compile(
            "(?i)total\\s+points\\s+transferred\\s+to\\s+[a-z][a-z\\s]*?\\s+"
                    + "(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    Pattern.compile(
            "(?i)points?\\s+(?:earned|accrued)\\s+(?:this\\s+period|this\\s+statement)"
                    + "[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    Pattern.compile(
            "(?i)(?:points|rewards\\s+points)\\s+earned[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    // U.S. Bank: "Earned This Statement 0" — label-first form with the count trailing.
    Pattern.compile(
            "(?i)^\\s*earned\\s+this\\s+statement[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
};

/**
 * Patterns that identify CUMULATIVE points balance available to redeem.
 *
 * <p>The {@code (?<!previous\\s)} negative lookbehind on the bare "points balance"
 * pattern is load-bearing: it prevents the regex from accidentally matching the
 * "Previous points balance NN,NNN" row that Chase Amazon Visa prints right next
 * to the actual current balance. Without this, the single-line pass returns the
 * PRIOR cycle's balance — the iOS UI then shows a stale-looking number that
 * also disagrees with the per-category earned figures below.
 */
private static final Pattern[] POINTS_BALANCE_PATTERNS = {
    Pattern.compile(
            "(?i)total\\s+points\\s+available\\s+for\\s+(?:redemption|redeeming|use)"
                    + "[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    Pattern.compile(
            "(?i)(?<!previous\\s)(?:points|rewards\\s+points)\\s+balance"
                    + "[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    Pattern.compile(
            "(?i)available\\s+(?:points|rewards\\s+points)[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    Pattern.compile(
            "(?i)(?:points|rewards\\s+points)\\s+available[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    // U.S. Bank: "Reward Center Balance 0" (cumulative points awaiting redemption).
    Pattern.compile(
            "(?i)^\\s*reward\\s+center\\s+balance[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
    // Column-interleaved recovery. PDFBox on Chase Prime Visa renders the rewards
    // column as "Total points available for\n...several lines of unrelated content
    // from the adjacent disclosure column...\nredemption 51,057". The other patterns
    // require "for" and "redemption" to be adjacent (or separated only by whitespace),
    // so they can't see this case. This looser pattern allows up to ~400 chars between
    // the two anchor phrases and picks the closest redemption value. The two anchor
    // phrases together are specific enough that a false positive in disclosure prose
    // is implausible.
    Pattern.compile(
            "(?is)total\\s+points\\s+available\\s+for\\b.{0,400}?"
                    + "\\b(?:redemption|redeeming|use)\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b"),
};

/**
 * Chase Amazon Visa per-category earnings line: {@code + 5% back on Amazon.com
 * purchases 0}, {@code + 2% back at restaurants 189}. The whole rewards section
 * is a series of these — to get the total earned this period we SUM all of them.
 * The pattern captures the trailing integer (which may be 0 for unused
 * categories). Per-category lines that wrap onto two lines get joined first.
 *
 * <p>The {@code (?:^|\\s)} anchor (instead of {@code ^\\s*}) is load-bearing: real
 * Chase Prime Visa statements share the rewards column with a calendar block, so
 * PDFBox routinely produces lines like {@code "14 15 16 17 18 19 20 + 2% back at
 * restaurants 318"} — calendar digits glued in front of the actual reward row. The
 * strict {@code ^\\s*\\+} version misses every such row and the parser silently
 * reports zero earned points for these statements.
 */
private static final Pattern AMAZON_STYLE_EARNED_LINE_PATTERN =
        Pattern.compile(
                "(?i)(?:^|\\s)\\+\\s+\\d{1,2}%\\s+back\\s+(?:on|at)\\s+[^0-9\\n]+?"
                        + "\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

/**
 * Chase Freedom / Freedom Unlimited / Freedom Flex earning line. Several
 * variants observed across cycles:
 *
 * <pre>
 *   + 1% (1 Pt)/$1 earned on all purchases 37        ← original "earned on"
 *   + 1% (1 Pts)/$1 on all purchases 9               ← drops "earned"
 *   + 2%(2 Pts)/$1 addl. on Dining purchases 0       ← uses "addl. on"
 *   4%(4 Pts)/$1 addl on Chase Travel 0              ← no leading "+", "addl on"
 * </pre>
 *
 * Captures group 1 = rate (the leading percent), group 2 = category label
 * (after the "on" / "addl on" / "earned on" connector), group 3 = points
 * earned this cycle. The leading "+" is OPTIONAL because some bonus tiers
 * start the rewards block without one.
 */
private static final Pattern FREEDOM_BASE_EARNED_LINE_PATTERN =
        Pattern.compile(
                "(?i)(?:^|\\s)\\+?\\s*(\\d{1,2})%\\s*\\(\\d{1,2}\\s*Pts?\\)/\\$1"
                        + "\\s+(?:addl\\.?\\s+)?(?:earned\\s+)?on\\s+(.+?)"
                        + "\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

/**
 * Chase Freedom quarterly rotating-bonus earnings line:
 * {@code + Bonus from 1Q 5% cat: Grocery Stores 148}.
 * Captures group 1 = quarter (1Q–4Q), group 2 = bonus rate (5), group 3 =
 * category label ("Grocery Stores"), group 4 = points earned this cycle.
 */
private static final Pattern FREEDOM_BONUS_EARNED_LINE_PATTERN =
        Pattern.compile(
                "(?i)(?:^|\\s)\\+\\s+Bonus\\s+from\\s+([1-4]Q)\\s+(\\d{1,2})%\\s+cat:\\s+"
                        + "(.+?)\\s+(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*$");

/**
 * Chase Freedom "next quarter activation" sentence:
 * {@code Get 5% cash back on up to $1,500 in combined purchases in this quarter}
 * {@code s bonus categories from 4/1/25 - 6/30/25.}
 * Captures group 1 = rate, group 2 = cap amount, group 3 = window start,
 * group 4 = window end. The sentence usually wraps so we join lines first.
 */
private static final Pattern FREEDOM_NEXT_QUARTER_PATTERN =
        Pattern.compile(
                "(?i)Get\\s+(\\d{1,2})%\\s+cash\\s+back\\s+on\\s+up\\s+to\\s+"
                        + "\\$([\\d,]+)\\s+in\\s+combined\\s+purchases.*?"
                        + "from\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s*-\\s*"
                        + "(\\d{1,2}/\\d{1,2}/\\d{2,4})");

/**
 * Chase Amazon Visa "Previous points balance NN,NNN" — kept so the iOS UI can
 * display deltas ("you earned X this cycle"). Distinct from the current balance
 * (which is "Total points available for redemption").
 */
private static final Pattern PREVIOUS_POINTS_BALANCE_PATTERN =
        Pattern.compile(
                "(?i)previous\\s+points\\s+balance[\\s:]+(\\d{1,3}(?:,\\d{3})*|\\d+)\\b");

private static Long extractLongFromPatterns(
        final String[] lines, final Pattern[] patterns) {
    // Two passes:
    //   1. Per-line for patterns that fit on a single line. Cheap and avoids
    //      cross-line false positives when the same label appears in disclosure prose.
    //   2. Joined-text fallback for patterns that span line breaks. Chase routinely
    //      wraps "Total points transferred to\nMarriott Bonvoy NN,NNN" across two
    //      lines, which the single-line pass can't see.
    // Before joining, normalise PDFBox's date-glued-to-number quirk: it emits
    // "Marriott Bonvoy 6,46404/14/26" when the points value abuts the next-due
    // date column with no space. Insert a space anywhere a date pattern follows
    // a digit so the number boundary is recoverable.
    for (final String line : lines) {
        if (line == null || line.isBlank()) {
            continue;
        }
        for (final Pattern p : patterns) {
            final Matcher m = p.matcher(line);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    // Try next pattern.
                }
            }
        }
    }
    // Second pass: join lines + de-glue dates, retry.
    final String joined =
            String.join(" ", lines)
                    .replaceAll("(\\d)(?=\\d{2}/\\d{2}/\\d{2,4})", "$1 ")
                    .replaceAll("\\s+", " ");
    for (final Pattern p : patterns) {
        final Matcher m = p.matcher(joined);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // try next
            }
        }
    }
    return null;
}

/**
 * Points accrued this billing cycle. Four strategies tried in order:
 *
 * <ol>
 *   <li>Chase Marriott Bonvoy: {@code Total points transferred to Marriott Bonvoy NN,NNN}.
 *   <li>Explicit "Points earned this period: NN,NNN" or "Points earned: NN,NNN".
 *   <li>Chase Amazon Visa style: SUM all "{@code + N% back on/at CATEGORY NN}" lines.
 *   <li>Chase Freedom style: SUM the base "{@code + 1% (1 Pt)/$1 earned on all
 *       purchases NN}" + any quarterly "{@code + Bonus from NQ N% cat: CATEGORY NN}".
 * </ol>
 *
 * Returns null when the section isn't present at all; returns 0 when the user
 * didn't earn any rewards this cycle (vs. null = "we don't know").
 */
public static Long extractPointsEarnedThisPeriod(final String[] lines) {
    // 1+2: existing single-line / joined-text patterns.
    final Long fromPattern = extractLongFromPatterns(lines, POINTS_EARNED_PATTERNS);
    if (fromPattern != null) {
        return fromPattern;
    }
    // 3+4: per-category sum. A statement either has at least one matching line
    // (counts as "we found the rewards section") or has none (return null so we
    // don't claim a fake 0). Both Amazon-style and Freedom-style lines feed
    // the same sum — they're never on the same statement.
    long sum = 0;
    boolean sawAny = false;
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        // Amazon "+ N% back on CATEGORY NN".
        final Matcher amz = AMAZON_STYLE_EARNED_LINE_PATTERN.matcher(line);
        if (amz.find()) {
            try {
                sum += Long.parseLong(amz.group(1).replace(",", ""));
                sawAny = true;
                continue;
            } catch (NumberFormatException ignored) {
                // fall through to other patterns
            }
        }
        // Freedom base / Freedom Unlimited "+ N% (M Pts)/$1 ... on CATEGORY NN".
        final Matcher freedomBase = FREEDOM_BASE_EARNED_LINE_PATTERN.matcher(line);
        if (freedomBase.find()) {
            try {
                // Group 1 = rate, group 2 = category, group 3 = earned this cycle.
                sum += Long.parseLong(freedomBase.group(3).replace(",", ""));
                sawAny = true;
                continue;
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        // Freedom rotating bonus "+ Bonus from NQ N% cat: CATEGORY NN".
        final Matcher freedomBonus = FREEDOM_BONUS_EARNED_LINE_PATTERN.matcher(line);
        if (freedomBonus.find()) {
            try {
                sum += Long.parseLong(freedomBonus.group(4).replace(",", ""));
                sawAny = true;
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }
    return sawAny ? sum : null;
}

/**
 * Cumulative points balance available to redeem. Order-of-preference is:
 *
 * <ol>
 *   <li>"Total points available for redemption NN,NNN" — the canonical Chase
 *       label, often wrapped onto two lines (handled by the joined-text fallback).
 *   <li>"Points balance NN,NNN" — generic, but anchored to skip "Previous points
 *       balance" via the negative lookbehind on POINTS_BALANCE_PATTERNS[1].
 *   <li>Available variants.
 * </ol>
 *
 * Returns null on transfer-partner cards (e.g. Marriott Bonvoy) where the balance
 * lives at the partner instead.
 */
// Citi prints the rewards balance as a multi-line block:
//   "Total Available ThankYou® Points:"
//   "25,519  as of 05/01/26"
// and separately:
//   "Total ThankYou Points Balance:"
//   "25,519"
// Amex Membership Rewards has the same shape:
//   "Membership Rewards® Points"
//   "Available and Pending as of MM/DD/YY"
//   "89,096"  (2 lines after the heading)
// Match those bare labels so we can pair them with the integer on the next non-blank
// line(s).
private static final Pattern[] POINTS_BALANCE_BARE_LABEL_PATTERNS = {
    Pattern.compile(
            "(?i)^\\s*total\\s+available\\s+thankyou\\s*(?:®)?\\s*points[\\s:]*$"),
    Pattern.compile(
            "(?i)^\\s*total\\s+thankyou\\s+points\\s+balance[\\s:]*$"),
    Pattern.compile(
            "(?i)^\\s*total\\s+available\\s+points[\\s:]*$"),
    Pattern.compile(
            "(?i)^\\s*membership\\s+rewards\\s*(?:®)?\\s*points\\s*$"),
};
public static Long extractPointsBalance(final String[] lines) {
    final Long onLine = extractLongFromPatterns(lines, POINTS_BALANCE_PATTERNS);
    if (onLine != null) {
        return onLine;
    }
    // Multi-line fallback: bare label on one line, integer on the next.
    for (int i = 0; i < lines.length; i++) {
        if (lines[i] == null) {
            continue;
        }
        boolean labelMatched = false;
        for (final Pattern p : POINTS_BALANCE_BARE_LABEL_PATTERNS) {
            if (p.matcher(lines[i]).find()) {
                labelMatched = true;
                break;
            }
        }
        if (!labelMatched) {
            continue;
        }
        // Scan forward up to ~5 non-blank lines for a line that starts with an
        // integer. We don't `break` on a non-integer line because Amex's layout
        // interposes an "Available and Pending as of MM/DD/YY" disclosure between
        // the heading and the value — and the heading itself is specific enough
        // that picking the first integer within a tight window is safe.
        int scanned = 0;
        for (int j = i + 1; j < lines.length && scanned < 5; j++) {
            final String next = lines[j];
            if (next == null || next.isBlank()) {
                continue;
            }
            scanned++;
            final Matcher m =
                    Pattern.compile("^\\s*(\\d{1,3}(?:,\\d{3})*|\\d+)\\b").matcher(next);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
    }
    return null;
}

/**
 * Prior cycle's points balance, when the statement prints it explicitly (Chase
 * Amazon Visa: "Previous points balance NN,NNN"). Lets the UI display deltas
 * ("you earned X since last cycle"). Distinct from the current balance.
 */
public static Long extractPreviousPointsBalance(final String[] lines) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = PREVIOUS_POINTS_BALANCE_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
    return null;
}

/**
 * Chase Amazon Visa per-category reward-multiplier line. Captures the percent
 * (group 1) and category label (group 2):
 *   + 5% back on Amazon.com purchases 0
 *   + 2% back at restaurants 31
 *   + 1% back on all other purchases 0
 * The trailing integer is the points-earned this cycle for that category; we
 * ignore it here because the per-cycle sum is captured separately.
 */
private static final Pattern REWARD_MULTIPLIER_LINE_PATTERN =
        Pattern.compile(
                "(?i)^\\s*\\+\\s+(\\d{1,2}(?:\\.\\d{1,2})?)%\\s+back\\s+(?:on|at)\\s+"
                        + "(.+?)\\s+\\d{1,3}(?:,\\d{3})*\\s*$");

private static final Pattern YTD_FEES_PATTERN =
        Pattern.compile(
                "(?i)total\\s+fees\\s+charged\\s+in\\s+\\d{4}\\s+\\$([\\d,]+(?:\\.\\d{1,2})?)");

private static final Pattern YTD_INTEREST_PATTERN =
        Pattern.compile(
                "(?i)total\\s+interest\\s+charged\\s+in\\s+\\d{4}\\s+\\$([\\d,]+(?:\\.\\d{1,2})?)");

/**
 * Per-category reward multipliers from earnings lines. Returns rate as percent
 * (5.0 not 0.05) so it lines up with the existing AccountTable.rewardMultipliers
 * schema. Keys are raw issuer labels (lowercased + trimmed) so consumers can
 * normalise to their own taxonomy. Empty map when no rewards block; never null.
 *
 * <p>Three issuer formats supported:
 *
 * <ol>
 *   <li>Amazon Visa: "{@code + N% back on/at CATEGORY NN}"
 *   <li>Chase Freedom base: "{@code + N% (M Pts)/$1 earned on CATEGORY NN}"
 *   <li>Chase Freedom quarterly bonus: "{@code + Bonus from NQ N% cat: CATEGORY NN}"
 *       — stored with the quarter suffix on the key (e.g. "grocery stores (1q
 *       bonus)") so a UI can distinguish the rotating-bonus tier from a permanent
 *       category multiplier.
 * </ol>
 */
public static java.util.Map<String, BigDecimal> extractRewardMultipliersFromPdf(
        final String[] lines) {
    final java.util.LinkedHashMap<String, BigDecimal> out = new java.util.LinkedHashMap<>();
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        // Format 1: Amazon "+ N% back on/at CATEGORY NN".
        final Matcher amz = REWARD_MULTIPLIER_LINE_PATTERN.matcher(line);
        if (amz.find()) {
            try {
                final BigDecimal rate = new BigDecimal(amz.group(1));
                final String category = amz.group(2).trim().toLowerCase(Locale.ROOT);
                if (!category.isEmpty()) {
                    out.put(category, rate);
                }
                continue;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        // Format 2: Freedom / Freedom Unlimited "+ N% (M Pts)/$1 ... on CATEGORY NN".
        // Group 1 = rate, group 2 = category, group 3 = points-this-cycle.
        final Matcher freedomBase = FREEDOM_BASE_EARNED_LINE_PATTERN.matcher(line);
        if (freedomBase.find()) {
            try {
                final BigDecimal rate = new BigDecimal(freedomBase.group(1));
                final String category =
                        freedomBase.group(2).trim().toLowerCase(Locale.ROOT);
                if (!category.isEmpty()) {
                    out.put(category, rate);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
            continue;
        }
        // Format 3: Freedom rotating bonus "+ Bonus from NQ N% cat: CATEGORY NN".
        final Matcher freedomBonus = FREEDOM_BONUS_EARNED_LINE_PATTERN.matcher(line);
        if (freedomBonus.find()) {
            try {
                final String quarter = freedomBonus.group(1).toLowerCase(Locale.ROOT);
                final BigDecimal rate = new BigDecimal(freedomBonus.group(2));
                final String rawCategory =
                        freedomBonus.group(3).trim().toLowerCase(Locale.ROOT);
                if (!rawCategory.isEmpty()) {
                    // Suffix the quarter so UIs can tell a rotating-bonus tier apart
                    // from a permanent category multiplier.
                    out.put(rawCategory + " (" + quarter + " bonus)", rate);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }
    return out;
}

/** A rotating quarterly bonus tier captured from a Chase Freedom-style line. */
public record QuarterlyBonus(String quarter, BigDecimal rate, String category) {}

/**
 * Extract the rotating-bonus tier for the current statement cycle (Chase Freedom:
 * "{@code + Bonus from 1Q 5% cat: Grocery Stores 148}"). Returns the FIRST bonus
 * tier found — most Chase Freedom statements only have one active at a time.
 * Null when the statement has no rotating-bonus block.
 */
public static QuarterlyBonus extractCurrentQuarterBonus(final String[] lines) {
    for (final String line : lines) {
        if (line == null) {
            continue;
        }
        final Matcher m = FREEDOM_BONUS_EARNED_LINE_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return new QuarterlyBonus(
                        m.group(1).toUpperCase(Locale.ROOT),
                        new BigDecimal(m.group(2)),
                        m.group(3).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
    return null;
}

/** Next-quarter bonus activation window from the Freedom disclosure prose. */
public record NextQuarterBonus(
        BigDecimal rate, BigDecimal capAmount, LocalDate windowStart, LocalDate windowEnd) {}

/**
 * Chase Freedom "Get 5% cash back on up to $1,500 in combined purchases in this
 * quarter's bonus categories from MM/DD/YY - MM/DD/YY" sentence. Captures the
 * rate, cap, and activation window so the iOS UI can nudge users to activate.
 * Null when the disclosure isn't present.
 */
public static NextQuarterBonus extractNextQuarterBonus(
        final String[] lines, final Integer inferredYear, final boolean isUSLocale) {
    final String joined = String.join(" ", lines).replaceAll("\\s+", " ");
    final Matcher m = FREEDOM_NEXT_QUARTER_PATTERN.matcher(joined);
    if (!m.find()) {
        return null;
    }
    try {
        final BigDecimal rate = new BigDecimal(m.group(1));
        final BigDecimal cap = new BigDecimal(m.group(2).replace(",", ""));
        final LocalDate start =
                staticParseAnnualFeeDate(m.group(3), inferredYear, isUSLocale);
        final LocalDate end =
                staticParseAnnualFeeDate(m.group(4), inferredYear, isUSLocale);
        return new NextQuarterBonus(rate, cap, start, end);
    } catch (NumberFormatException nfe) {
        return null;
    }
}

/**
 * Wells Fargo Active Cash (cash-back card) format: "Rewards balance as of: 03/31/2026
 * $110.96". The amount is a dollar value, not a point count. Returns null on
 * point-based cards where this row isn't printed. We accept "Rewards balance" with or
 * without the trailing "as of: date" so other issuers' phrasing also matches.
 */
private static final Pattern CASH_BACK_BALANCE_PATTERN =
        Pattern.compile(
                "(?i)^\\s*rewards?\\s+balance(?:\\s+as\\s+of[:\\s]+"
                        + "[\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})?[\\s:]+\\$"
                        + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\b");

// Citi Costco prints rewards as a dollar value too, but uses a longer label:
// "Total Costco Cash Back Rewards Balance: $262.04". The Year-to-Date variant
// ("...Balance Year to Date : $262.04") is the same value as of statement close,
// so it's safe to capture either form. Accept "Costco" optionally so other Citi
// cash-back-style cards with the same shape also benefit.
private static final Pattern CITI_COSTCO_CASH_BACK_PATTERN =
        Pattern.compile(
                "(?i)^\\s*total\\s+(?:costco\\s+)?cash\\s+back\\s+rewards?\\s+balance"
                        + "(?:\\s+year\\s+to\\s+date)?[\\s:]+\\$"
                        + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\b");
public static BigDecimal extractCashBackBalance(final String[] lines) {
    for (int i = 0; i < lines.length; i++) {
        final String line = lines[i];
        if (line == null) {
            continue;
        }
        final Matcher m = CASH_BACK_BALANCE_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        final Matcher citi = CITI_COSTCO_CASH_BACK_PATTERN.matcher(line);
        if (citi.find()) {
            try {
                return new BigDecimal(citi.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // Multi-line variant: some Citi Costco statements wrap the label onto two
        // lines ("Total Costco Cash Back Rewards Balance" \n " Year to Date : $X").
        // Detect the label-only first line and try the merged regex against
        // line + next-line so the value gets captured.
        if (line.matches("(?i)^\\s*total\\s+(?:costco\\s+)?cash\\s+back\\s+rewards?"
                + "\\s+balance\\s*$")
                && i + 1 < lines.length && lines[i + 1] != null) {
            final String merged = line + " " + lines[i + 1].trim();
            final Matcher merge = CITI_COSTCO_CASH_BACK_PATTERN.matcher(merged);
            if (merge.find()) {
                try {
                    return new BigDecimal(merge.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
    }
    return null;
}

/** YTD "Total fees charged in YYYY $N". Null when the row isn't present. */
public static BigDecimal extractYtdFeesCharged(final String[] lines) {
    return extractYtdLineValue(lines, YTD_FEES_PATTERN);
}

/** YTD "Total interest charged in YYYY $N". Null when the row isn't present. */
public static BigDecimal extractYtdInterestCharged(final String[] lines) {
    return extractYtdLineValue(lines, YTD_INTEREST_PATTERN);
}

private static BigDecimal extractYtdLineValue(
        final String[] lines, final Pattern pattern) {
    for (int i = 0; i < lines.length; i++) {
        final String line = lines[i];
        if (line == null) {
            continue;
        }
        final Matcher m = pattern.matcher(line);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // Wells Fargo lays the YTD block as label-only lines followed by amount-only
        // lines. When we see the bare label (no trailing $value), scan forward for the
        // first amount-only line and use that. We pair them in document order:
        // line containing "Total Fees Charged in 2026" → first $-only line after it.
        final Matcher bare = WELLS_YTD_BARE_LABEL_PATTERN.matcher(line);
        if (bare.find()) {
            // Match only the fees pattern vs interest pattern based on which we want.
            final boolean wantFees = pattern == YTD_FEES_PATTERN;
            final boolean isFeesLabel = bare.group(1).toLowerCase(Locale.ROOT).contains("fees");
            if (wantFees != isFeesLabel) {
                continue;
            }
            for (int j = i + 1; j < lines.length && j < i + 6; j++) {
                final String next = lines[j];
                if (next == null || next.isBlank()) {
                    continue;
                }
                final Matcher amount = WELLS_YTD_BARE_AMOUNT_PATTERN.matcher(next.trim());
                if (amount.matches()) {
                    try {
                        return new BigDecimal(amount.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                // Stop scanning forward if we hit another label line (the interleaving
                // is fees-label, interest-label, fees-value, interest-value — so we
                // must stop after the second label but BEFORE the matching value
                // appears. Instead, we keep going past additional labels.).
                if (WELLS_YTD_BARE_LABEL_PATTERN.matcher(next).find()) {
                    continue;
                }
            }
        }
    }
    return null;
}

// Wells Fargo bare-label form: "Total Fees Charged in 2026" with no $value on the
// line. We capture which kind of total it is so we can pick the right paired amount.
private static final Pattern WELLS_YTD_BARE_LABEL_PATTERN =
        Pattern.compile(
                "(?i)^\\s*total\\s+(fees|interest)\\s+charged\\s+in\\s+\\d{4}\\s*$");

// A line containing only a $amount (and optional whitespace). Used to pair amounts
// back with the bare YTD label lines above.
private static final Pattern WELLS_YTD_BARE_AMOUNT_PATTERN =
        Pattern.compile("^\\$?([\\d,]+(?:\\.\\d{1,2})?)\\s*$");
public static BigDecimal extractForeignTransactionFeePercent(final String[] lines) {
    // The sentence often wraps in the PDF text — join with space so the regex can
    // span breaks. Don't trim individual lines; the regex tolerates whitespace runs.
    final String joined = String.join(" ", lines).replaceAll("\\s+", " ");
    final Matcher m = FOREIGN_TX_FEE_PATTERN.matcher(joined);
    if (!m.find()) {
        return null;
    }
    try {
        return new BigDecimal(m.group(1));
    } catch (NumberFormatException nfe) {
        return null;
    }
}

/**
 * Generic single-amount extractor: returns the first amount on a line that matches any of
 * the supplied label phrases. Lets the balance/credit-limit/etc. extractors stay
 * one-liner declarations of just the labels each one cares about.
 *
 * <p>The label regexes accept arbitrary whitespace between words ({@code minimum\\s+payment})
 * but require the label to come BEFORE the amount on the same line — Chase emits all of
 * these as "Label $amount" pairs on single lines.
 */
private static BigDecimal extractLabeledAmount(
        final String[] lines, final Pattern[] labelPatterns, final boolean allowZero) {
    for (final String line : lines) {
        if (line == null || line.isBlank()) {
            continue;
        }
        final String normalizedLine = normalizeLineForLabelMatching(line.trim());
        for (final Pattern pattern : labelPatterns) {
            final Matcher matcher = pattern.matcher(normalizedLine);
            if (matcher.find()) {
                String amountStr = null;
                // Group 1 = parens form ($1,234.56) — must NOT strip parens before
                // staticParseAmount runs, otherwise the negative sign encoded by the
                // parens is silently lost. staticParseAmount handles parens→sign.
                if (matcher.group(1) != null) {
                    amountStr = matcher.group(1).replaceAll("[$\\s]", "").trim();
                } else if (matcher.group(2) != null) {
                    // Signed: -$1,234.56 / +$1,234.56 — keep the leading sign.
                    amountStr = matcher.group(2).replaceAll("[$\\s]", "").trim();
                } else if (matcher.group(3) != null) {
                    // Standard $1,234.56 — no sign to preserve.
                    amountStr = matcher.group(3).replaceAll("[$\\s]", "").trim();
                }
                if (amountStr != null) {
                    final BigDecimal amt = staticParseAmount(amountStr);
                    if (amt != null && (allowZero || amt.compareTo(BigDecimal.ZERO) != 0)) {
                        return amt;
                    }
                }
            }
        }
    }
    return null;
}

/**
 * Strip PDFBox font-extraction artifacts that punctuate label words mid-character. The
 * known case is Chase Prime Visa: PDFBox extracts "A`vailable Credit $14,841" and
 * "B`alance Transfers $0.00" — the stray backtick comes from the ligature glyph in
 * the title font, and the label regexes never match unless we strip it first.
 *
 * <p>We only strip a backtick when it sits between two letters; a backtick at the
 * start or end of a word is harmless and probably intentional. This keeps the
 * normalization tight enough to avoid false positives on raw transaction descriptions
 * that happen to contain a backtick.
 */
private static String normalizeLineForLabelMatching(final String line) {
    if (line == null || line.indexOf('`') < 0) {
        return line;
    }
    return line.replaceAll("(?<=\\p{L})`(?=\\p{L})", "");
}

/** Static counterpart to {@link #parseAmount(String)} usable from static helpers above. */
private static BigDecimal staticParseAmount(final String amountString) {
    if (amountString == null || amountString.isBlank()) {
        return null;
    }
    try {
        final String cleaned =
                amountString.replaceAll("[$,\\s]", "").replace("(", "-").replace(")", "");
        return new BigDecimal(cleaned);
    } catch (NumberFormatException e) {
        return null;
    }
}
}
