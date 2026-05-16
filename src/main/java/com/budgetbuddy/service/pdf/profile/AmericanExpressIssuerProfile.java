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
 *
 * @deprecated Migration target — superseded by {@code pdf-templates-v2/american-express.yaml}.
 *     Card detection + balance/total extraction now in v2. The stacked
 *     Account Summary / Account Total label-then-value blocks, Membership
 *     Rewards multi-line points, and Pay Over Time Limit stacked layout
 *     still require this class. Deletion gated on v2 schema gaining
 *     {@code stacked_label_block} + {@code points_block} rule types. See
 *     {@code docs/pdf-import-deprecation-map.md}.
 */
@Deprecated(since = "v2-migration-q1")
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

    // Amex AutoPay disclosure sentence. Variants observed across cards:
    //   "We will debit your bank account for your payment of $978.49 on 05/28/26"
    //   "We will debit your bank account for your monthly AutoPay payment of $1,432.00 on 03/03/26"
    private static final Pattern AMEX_AUTOPAY_DEBIT =
            Pattern.compile(
                    "(?i)debit\\s+your\\s+bank\\s+account\\s+for\\s+your\\s+"
                            + "(?:monthly\\s+autopay\\s+)?"
                            + "payment\\s+of\\s+\\$"
                            + "([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    // Stacked Account Summary block (Blue Cash / Blue Business / Platinum, etc.):
    //   "Account Summary"
    //   "Previous Balance"
    //   "Payments/Credits"
    //   "New Charges"
    //   "Fees"
    //   "Interest Charged"
    //   "$26,145.56"
    //   "-$1,424.36"
    //   "+$1,461.05"
    //   "+$0.00"
    //   "+$0.00"
    // Each value carries an optional sign prefix; we accept "+", "-", or nothing.
    private static final Pattern AMEX_ACCT_SUMMARY_HEADER =
            Pattern.compile("(?i)^\\s*account\\s+summary\\s*$");
    private static final Pattern AMEX_PREV_BAL_LABEL =
            Pattern.compile("(?i)^\\s*previous\\s+balance\\s*$");
    private static final Pattern AMEX_PAYMENTS_CREDITS_LABEL =
            Pattern.compile("(?i)^\\s*payments\\s*[/\\\\]\\s*credits\\s*$");
    private static final Pattern AMEX_NEW_CHARGES_LABEL =
            Pattern.compile("(?i)^\\s*new\\s+charges\\s*$");
    private static final Pattern AMEX_SIGNED_DOLLAR_LINE =
            Pattern.compile("^\\s*([+-])?\\$([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*$");

    // Charge-card (Platinum / Gold / Green) "Account Total" block. Distinct
    // from Blue Cash's "Account Summary" — the Platinum statement first prints
    // a "Pay In Full" sub-section (all zeros for charge-card holders), then a
    // "Pay Over Time and/or Cash Advance" sub-section, then the consolidated
    // "Account Total" block which is what we want. The Account Total has 6
    // category labels (Previous Balance, Payments/Credits, New Charges, New
    // Cash Advances, Fees, Interest Charged) followed by 6 signed-dollar
    // values; then a separate New Balance / Minimum Payment Due pair.
    private static final Pattern AMEX_ACCT_TOTAL_HEADER =
            Pattern.compile("(?i)^\\s*account\\s+total\\s*$");
    private static final Pattern AMEX_NEW_CASH_ADVANCES_LABEL =
            Pattern.compile("(?i)^\\s*new\\s+cash\\s+advances\\s*$");
    private static final Pattern AMEX_FEES_LABEL =
            Pattern.compile("(?i)^\\s*fees\\s*$");
    private static final Pattern AMEX_INTEREST_CHARGED_LABEL =
            Pattern.compile("(?i)^\\s*interest\\s+charged\\s*$");

    // Charge-card "Pay Over Time" APR row — semantic equivalent of
    // "Purchases" APR on a credit card. Same shape as Wells Fargo /
    // Chase: "<label> <date>? <rate>% (v) <balance> <interest>".
    private static final Pattern AMEX_PAY_OVER_TIME_APR =
            Pattern.compile(
                    "(?i)^\\s*pay\\s+over\\s+time\\b.*?(\\d+\\.\\d{1,3})\\s*%");

    // Stacked Credit Limit / second-label block. Amex prints the SECOND label
    // in one of two forms depending on whether the account is over-limit:
    //   over-limit:    "Credit Limit / Amount Above the Credit Limit"
    //   within-limit:  "Credit Limit / Available Credit"
    // Followed by two $-values: $25,000.00 / $X. First value = credit limit.
    private static final Pattern AMEX_CREDIT_LIMIT_LABEL =
            Pattern.compile("(?i)^\\s*credit\\s+limit\\s*$");
    private static final Pattern AMEX_SECOND_LIMIT_LABEL =
            Pattern.compile(
                    "(?i)^\\s*(?:amount\\s+above\\s+the\\s+credit\\s+limit"
                            + "|available\\s+credit)\\s*$");

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
        // Pay Over Time stacked layout (charge cards).
        final BigDecimal stackedLimit = extractStackedPotValue(lines, /*pickIndex=*/0);
        if (stackedLimit != null) {
            return stackedLimit;
        }
        // Credit-card stacked layout: "Credit Limit" / "Amount Above the Credit Limit"
        // labels followed by two $-values. First value is the credit limit.
        final BigDecimal cardLimit = extractStackedCreditLimit(lines);
        if (cardLimit != null) {
            return cardLimit;
        }
        return StatementParsingUtilities.extractCreditLimit(lines);
    }

    @Override
    public BigDecimal extractNewBalance(final String[] lines, final ExtractionContext ctx) {
        // Stacked Account Summary has "New Charges" but the new balance is printed
        // inline on the front page as "New Balance $X". The shared utility already
        // handles that — only override here if we ever need an Amex-specific tweak.
        return StatementParsingUtilities.extractNewBalance(lines);
    }

    @Override
    public BigDecimal extractPreviousBalance(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal inline = StatementParsingUtilities.extractPreviousBalance(lines);
        if (inline != null) {
            return inline;
        }
        // Charge-card "Account Total" block takes precedence — its values are the
        // consolidated totals across Pay In Full + Pay Over Time sub-sections.
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null) {
            return totalVals[0];
        }
        // Credit-card stacked Account Summary fallback (Blue Cash, etc.).
        final BigDecimal[] vals = extractStackedAccountSummaryValues(lines);
        return vals == null ? null : vals[0];
    }

    @Override
    public BigDecimal extractPurchasesTotal(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal inline = StatementParsingUtilities.extractPurchasesTotal(lines);
        if (inline != null) {
            return inline;
        }
        // Charge-card "Account Total" index 2 = "New Charges".
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null) {
            return totalVals[2];
        }
        // Credit-card "Account Summary" index 2 = "New Charges".
        final BigDecimal[] vals = extractStackedAccountSummaryValues(lines);
        return vals == null ? null : vals[2];
    }

    @Override
    public BigDecimal extractPaymentsAndCreditsTotal(
            final String[] lines, final ExtractionContext ctx) {
        // Prefer Account Total (charge cards) first, then Account Summary
        // (credit cards). Both produce the combined "Payments/Credits" sum;
        // the shared extractor would otherwise pick the "Payments -$X" sub-
        // total alone and miss e.g. a separate cashback refund row.
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null && totalVals[1] != null) {
            return totalVals[1].abs();
        }
        final BigDecimal[] vals = extractStackedAccountSummaryValues(lines);
        if (vals != null && vals[1] != null) {
            return vals[1].abs();
        }
        return super.extractPaymentsAndCreditsTotal(lines, ctx);
    }

    @Override
    public BigDecimal extractCashAdvancesTotal(final String[] lines, final ExtractionContext ctx) {
        // Charge-card "Account Total" splits cash advances into their own
        // category at index 3. Credit-card "Account Summary" doesn't break it
        // out — fall through to the shared utility for those.
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null) {
            return totalVals[3];
        }
        return StatementParsingUtilities.extractCashAdvancesTotal(lines);
    }

    @Override
    public BigDecimal extractFeesChargedTotal(final String[] lines, final ExtractionContext ctx) {
        final BigDecimal inline = StatementParsingUtilities.extractFeesChargedTotal(lines);
        if (inline != null) {
            return inline;
        }
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null) {
            return totalVals[4];
        }
        final BigDecimal[] vals = extractStackedAccountSummaryValues(lines);
        return vals == null ? null : vals[3];
    }

    @Override
    public BigDecimal extractInterestChargedTotal(
            final String[] lines, final ExtractionContext ctx) {
        final BigDecimal inline = StatementParsingUtilities.extractInterestChargedTotal(lines);
        if (inline != null) {
            return inline;
        }
        final BigDecimal[] totalVals = extractStackedAccountTotalValues(lines);
        if (totalVals != null) {
            return totalVals[5];
        }
        final BigDecimal[] vals = extractStackedAccountSummaryValues(lines);
        return vals == null ? null : vals[4];
    }

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        // Charge cards (Platinum / Gold / Green) print the purchase APR
        // equivalent as "Pay Over Time <date> NN.NN% (v)". Try that first.
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = AMEX_PAY_OVER_TIME_APR.matcher(line.trim());
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

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        // Charge cards: stacked Pay-Over-Time block, second value = available.
        final BigDecimal stackedAvailable = extractStackedPotValue(lines, /*pickIndex=*/1);
        if (stackedAvailable != null) {
            return stackedAvailable;
        }
        // Credit cards: when the account is within limit, the second label is
        // "Available Credit" (not "Amount Above the Credit Limit"). The second
        // value is the available credit. When the account is over-limit the
        // second value is "amount above" (positive) — not the available credit,
        // so we must NOT return it. Distinguish by checking the second label.
        if (hasAvailableCreditAsSecondLabel(lines)) {
            final BigDecimal[] vals = extractStackedCreditLimitValues(lines);
            if (vals != null) {
                return vals[1];
            }
        }
        return StatementParsingUtilities.extractAvailableCredit(lines);
    }

    /** True when the second label below "Credit Limit" is "Available Credit". */
    private static boolean hasAvailableCreditAsSecondLabel(final String[] lines) {
        if (lines == null) {
            return false;
        }
        final java.util.regex.Pattern available =
                java.util.regex.Pattern.compile("(?i)^\\s*available\\s+credit\\s*$");
        for (int i = 0; i + 1 < lines.length; i++) {
            if (lines[i] == null || !AMEX_CREDIT_LIMIT_LABEL.matcher(lines[i]).find()) {
                continue;
            }
            if (lines[i + 1] != null && available.matcher(lines[i + 1]).find()) {
                return true;
            }
        }
        return false;
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
     * Scans the stacked Account Summary block and returns the 5 values in label order:
     *   [0] Previous Balance
     *   [1] Payments/Credits  (negative, kept as-is so callers can read the sign)
     *   [2] New Charges
     *   [3] Fees
     *   [4] Interest Charged
     * Returns null when the block isn't present. The label stack must appear in the
     * exact order Amex prints it; the value stack follows immediately after with
     * one $-value per non-blank line.
     */
    private static BigDecimal[] extractStackedAccountSummaryValues(final String[] lines) {
        if (lines == null) {
            return null;
        }
        // Find the label block: "Account Summary" then "Previous Balance",
        // "Payments/Credits", "New Charges".
        for (int i = 0; i + 8 < lines.length; i++) {
            if (lines[i] == null
                    || !AMEX_ACCT_SUMMARY_HEADER.matcher(lines[i]).find()) {
                continue;
            }
            int j = i + 1;
            // Skip blank lines between header and labels.
            while (j < lines.length && (lines[j] == null || lines[j].isBlank())) {
                j++;
            }
            if (j >= lines.length
                    || !AMEX_PREV_BAL_LABEL.matcher(lines[j]).find()) {
                continue;
            }
            // Expect 4 more label lines.
            if (j + 4 >= lines.length
                    || !AMEX_PAYMENTS_CREDITS_LABEL.matcher(lines[j + 1]).find()
                    || !AMEX_NEW_CHARGES_LABEL.matcher(lines[j + 2]).find()) {
                continue;
            }
            // Now find the next 5 $-values (skip blanks).
            final BigDecimal[] result = new BigDecimal[5];
            int filled = 0;
            for (int k = j + 5; k < lines.length && filled < 5 && k < j + 25; k++) {
                final String s = lines[k];
                if (s == null || s.isBlank()) {
                    continue;
                }
                final Matcher m = AMEX_SIGNED_DOLLAR_LINE.matcher(s.trim());
                if (!m.find()) {
                    // The value stack must be contiguous; break if we hit prose.
                    break;
                }
                try {
                    BigDecimal v = new BigDecimal(m.group(2).replace(",", ""));
                    if ("-".equals(m.group(1))) {
                        v = v.negate();
                    }
                    result[filled++] = v;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (filled == 5) {
                return result;
            }
        }
        return null;
    }

    /**
     * Scans the charge-card "Account Total" block (Platinum / Gold / Green) and
     * returns 6 category values in label order:
     *   [0] Previous Balance
     *   [1] Payments/Credits  (negative)
     *   [2] New Charges
     *   [3] New Cash Advances
     *   [4] Fees
     *   [5] Interest Charged
     * Distinct from the 5-value "Account Summary" block used on credit cards
     * (Blue Cash, etc.) — charge cards split out cash advances into its own
     * category. The label stack must appear in the exact order Amex prints it.
     * Returns null when the block isn't present so callers fall back to the
     * simpler Account Summary extractor.
     */
    private static BigDecimal[] extractStackedAccountTotalValues(final String[] lines) {
        if (lines == null) {
            return null;
        }
        for (int i = 0; i + 12 < lines.length; i++) {
            if (lines[i] == null
                    || !AMEX_ACCT_TOTAL_HEADER.matcher(lines[i]).find()) {
                continue;
            }
            int j = i + 1;
            while (j < lines.length && (lines[j] == null || lines[j].isBlank())) {
                j++;
            }
            if (j + 5 >= lines.length
                    || !AMEX_PREV_BAL_LABEL.matcher(lines[j]).find()
                    || !AMEX_PAYMENTS_CREDITS_LABEL.matcher(lines[j + 1]).find()
                    || !AMEX_NEW_CHARGES_LABEL.matcher(lines[j + 2]).find()
                    || !AMEX_NEW_CASH_ADVANCES_LABEL.matcher(lines[j + 3]).find()
                    || !AMEX_FEES_LABEL.matcher(lines[j + 4]).find()
                    || !AMEX_INTEREST_CHARGED_LABEL.matcher(lines[j + 5]).find()) {
                continue;
            }
            // Now scan forward for 6 $-values, skipping blank lines.
            final BigDecimal[] result = new BigDecimal[6];
            int filled = 0;
            for (int k = j + 6; k < lines.length && filled < 6 && k < j + 25; k++) {
                final String s = lines[k];
                if (s == null || s.isBlank()) {
                    continue;
                }
                final Matcher m = AMEX_SIGNED_DOLLAR_LINE.matcher(s.trim());
                if (!m.find()) {
                    break;
                }
                try {
                    BigDecimal v = new BigDecimal(m.group(2).replace(",", ""));
                    if ("-".equals(m.group(1))) {
                        v = v.negate();
                    }
                    result[filled++] = v;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (filled == 6) {
                return result;
            }
        }
        return null;
    }

    /**
     * Scans the stacked Credit Limit block and returns the first $-value. The
     * second label is "Amount Above the Credit Limit" when the account is
     * over-limit and "Available Credit" otherwise. Returns null when the
     * block isn't present.
     */
    private static BigDecimal extractStackedCreditLimit(final String[] lines) {
        final BigDecimal[] vals = extractStackedCreditLimitValues(lines);
        return vals == null ? null : vals[0];
    }

    /**
     * Scans the stacked Credit Limit block and returns both values:
     *   [0] Credit Limit (always present)
     *   [1] Amount-above OR Available Credit (per second label)
     * Returns null when the block isn't present.
     */
    private static BigDecimal[] extractStackedCreditLimitValues(final String[] lines) {
        if (lines == null) {
            return null;
        }
        for (int i = 0; i + 3 < lines.length; i++) {
            if (lines[i] == null
                    || !AMEX_CREDIT_LIMIT_LABEL.matcher(lines[i]).find()) {
                continue;
            }
            if (lines[i + 1] == null
                    || !AMEX_SECOND_LIMIT_LABEL.matcher(lines[i + 1]).find()) {
                continue;
            }
            final BigDecimal[] result = new BigDecimal[2];
            int filled = 0;
            for (int j = i + 2; j < lines.length && filled < 2 && j < i + 10; j++) {
                final String s = lines[j];
                if (s == null || s.isBlank()) {
                    continue;
                }
                final Matcher m = AMEX_DOLLAR_LINE.matcher(s.trim());
                if (!m.find()) {
                    break;
                }
                try {
                    result[filled++] = new BigDecimal(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if (filled == 2) {
                return result;
            }
        }
        return null;
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
