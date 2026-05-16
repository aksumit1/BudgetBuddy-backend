package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catch-all profile that claims any statement none of the issuer-specific profiles
 * matched.
 *
 * <p>Owns its OWN minimal set of issuer-agnostic patterns — the most common label
 * phrasings shared across US credit-card statements ("New Balance", "Credit Limit",
 * "Available Credit", "Statement Date", "Payment Due Date" etc.). Issuer-specific
 * label phrasings (Wells "Total Available Credit", USB "Revolving Line of Credit",
 * BoA "Total Credit Line", Amex "Pay Over Time Limit", etc.) belong in the per-issuer
 * profiles, NOT here.
 *
 * <p>Migration history: this profile previously delegated everything to a
 * static extractor union in {@code PDFImportService}. That coupled it to ~1400 lines
 * of utility code and made it impossible to evolve independently. Now the fallback
 * owns only what's genuinely cross-issuer — when a new issuer-specific phrasing
 * appears, the right answer is to add a new {@link IssuerProfile}, not to bloat the
 * fallback.
 */
public final class GenericFallbackProfile implements IssuerProfile {

    // Universal credit-card statement labels. Issuer-specific variants live in their
    // own profiles — keep these strictly to the labels every major US issuer uses.

    private static final Pattern[] NEW_BALANCE = {
        Pattern.compile("(?i)^\\s*new\\s+balance[\\s:]+"
                + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"),
        Pattern.compile("(?i)^\\s*statement\\s+balance[\\s:]+"
                + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"),
        Pattern.compile("(?i)^\\s*current\\s+balance[\\s:]+"
                + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"),
    };

    private static final Pattern[] PREVIOUS_BALANCE = {
        Pattern.compile("(?i)^\\s*previous\\s+balance[\\s:]+"
                + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"),
        Pattern.compile("(?i)^\\s*prior\\s+balance[\\s:]+"
                + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)"),
    };

    private static final Pattern CREDIT_LIMIT =
            Pattern.compile("(?i)^\\s*credit\\s+limit[\\s:]+"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern AVAILABLE_CREDIT =
            Pattern.compile("(?i)^\\s*available\\s+credit[\\s:]+"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern PAST_DUE =
            Pattern.compile("(?i)^\\s*past\\s+due(?:\\s+amount)?[\\s:]+"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern PURCHASES_TOTAL =
            Pattern.compile("(?i)^\\s*purchases[\\s:]+\\+?"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern FEES_TOTAL =
            Pattern.compile("(?i)^\\s*fees\\s+charged[\\s:]+\\+?"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern INTEREST_TOTAL =
            Pattern.compile("(?i)^\\s*interest\\s+charged[\\s:]+\\+?"
                    + "\\$?([\\d]+(?:,\\d{3})*(?:\\.\\d{1,2})?)");

    private static final Pattern PURCHASE_APR =
            Pattern.compile(
                    "(?i)^\\s*purchases?\\s+(\\d{1,2}\\.\\d{1,4})\\s*%");

    private static final Pattern PAYMENT_DUE_DATE_PATTERN =
            Pattern.compile(
                    "(?i)statement\\s+date[\\s:]+([\\d]{1,2}[/-][\\d]{1,2}[/-][\\d]{2,4})");

    private static final Pattern BILLING_DAYS_PATTERN =
            Pattern.compile("(?i)\\b(\\d{1,2})\\s+days\\s+in\\s+billing\\s+period\\b");

    private static final Pattern AUTOPAY_ON_PATTERN =
            Pattern.compile(
                    "(?i)\\b(?:autopay\\s+is\\s+on|automatic\\s+payments?\\s+is\\s+on)\\b");

    private static final Pattern AUTOPAY_OFF_PATTERN =
            Pattern.compile(
                    "(?i)\\b(?:autopay\\s+is\\s+off|automatic\\s+payments?\\s+is\\s+off)\\b");

    @Override
    public String issuerId() {
        return "generic";
    }

    @Override
    public String displayName() {
        return "Generic";
    }

    @Override
    public boolean matches(final String headerText) {
        // Always matches — this is the tail of the registry chain.
        return true;
    }

    @Override
    public String detectBrand(final String headerText) {
        return null;
    }

    @Override
    public BigDecimal extractNewBalance(final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, NEW_BALANCE);
    }

    @Override
    public BigDecimal extractPreviousBalance(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, PREVIOUS_BALANCE);
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {CREDIT_LIMIT});
    }

    @Override
    public BigDecimal extractAvailableCredit(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {AVAILABLE_CREDIT});
    }

    @Override
    public BigDecimal extractPastDueAmount(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {PAST_DUE});
    }

    @Override
    public BigDecimal extractPurchasesTotal(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {PURCHASES_TOTAL});
    }

    @Override
    public BigDecimal extractFeesChargedTotal(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {FEES_TOTAL});
    }

    @Override
    public BigDecimal extractInterestChargedTotal(
            final String[] lines, final ExtractionContext ctx) {
        return firstAmount(lines, new Pattern[] {INTEREST_TOTAL});
    }

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = PURCHASE_APR.matcher(line.trim());
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

    @Override
    public Integer extractBillingDays(final String[] lines, final ExtractionContext ctx) {
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
        }
        return null;
    }

    @Override
    public LocalDate extractStatementDate(
            final String[] lines, final ExtractionContext ctx) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final Matcher m = PAYMENT_DUE_DATE_PATTERN.matcher(line);
            if (m.find()) {
                return parseDate(m.group(1), ctx.inferredYear(), ctx.usLocale());
            }
        }
        return null;
    }

    @Override
    public Boolean extractAutoPayEnabled(
            final String[] lines, final ExtractionContext ctx) {
        boolean sawOn = false;
        boolean sawOff = false;
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            if (AUTOPAY_ON_PATTERN.matcher(line).find()) {
                sawOn = true;
            } else if (AUTOPAY_OFF_PATTERN.matcher(line).find()) {
                sawOff = true;
            }
        }
        if (!sawOn && !sawOff) {
            return null;
        }
        return sawOn;
    }

    // Other extractors (cash-back, points balance, AutoPay amount, etc.) intentionally
    // return null at the generic level — there's no portable "Total Cashback Bonus" /
    // "Membership Rewards" / etc. label across all issuers, so attempting a fallback
    // here would just be incorrect. Per-issuer profiles own those fields.

    private static BigDecimal firstAmount(final String[] lines, final Pattern[] patterns) {
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final String trimmed = line.trim();
            for (final Pattern p : patterns) {
                final Matcher m = p.matcher(trimmed);
                if (m.find()) {
                    try {
                        return new BigDecimal(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static LocalDate parseDate(
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

    /** Empty brand map — generic profile has no notion of a brand. */
    @SuppressWarnings("unused")
    private static final java.util.Map<String, Pattern> NO_BRANDS = Collections.emptyMap();
}
