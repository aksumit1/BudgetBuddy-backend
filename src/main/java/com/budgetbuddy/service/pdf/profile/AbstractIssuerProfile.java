package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Base class that lets concrete profiles override only what's different for their
 * issuer while inheriting shared cross-issuer extractor behavior for everything else.
 *
 * <p>Every extractor method defaults to calling {@link StatementParsingUtilities} —
 * the shared utility that owns the union of generic patterns common across issuers.
 * Per-issuer overrides supplant that default with issuer-specific logic; anything
 * not overridden inherits the shared behavior automatically.
 *
 * <p>The subclass contract is small:
 *
 * <ul>
 *   <li>{@link #issuerId()} — stable lowercase identifier.
 *   <li>{@link #displayName()} — what to show in logs / analytics.
 *   <li>{@link #matches} — header-text matcher; usually a single pre-compiled
 *       {@link Pattern} captured in a constant.
 *   <li>{@link #detectBrand} — optional, returns the product family within the issuer.
 * </ul>
 *
 * <p>Subclasses are package-friendly to write — see {@link GenericFallbackProfile}
 * for a minimal example.
 *
 * <p><b>Deprecation note:</b> {@link StatementParsingUtilities}'s
 * {@code extractCreditLimit}, {@code extractAvailableCredit},
 * {@code extractStatementDate(String[], Integer, boolean)} and
 * {@code extractNextAutoPayAmount} are marked {@code @Deprecated}. Their
 * migration target is per-issuer overrides on this class — the deprecation
 * is the migration tracker, not a bug. This base class intentionally
 * routes through them so issuers that haven't yet been ported to
 * issuer-specific logic still parse correctly. Suppress the warning at
 * the class level so the build stays quiet without losing the signal.
 */
@SuppressWarnings("deprecation")
public abstract class AbstractIssuerProfile implements IssuerProfile {

    private final String issuerId;
    private final String displayName;
    private final Pattern headerPattern;
    private final Map<String, Pattern> brandPatterns;

    /**
     * @param issuerId stable short id used in logs / metrics.
     * @param displayName human-readable name.
     * @param headerPattern regex that, if found in the first ~30 lines, identifies this
     *     issuer. Combine specific issuer terms so generic phrasing ("bank") doesn't
     *     false-positive.
     * @param brandPatterns brand-name → pattern. First match wins.
     */
    protected AbstractIssuerProfile(
            final String issuerId,
            final String displayName,
            final Pattern headerPattern,
            final Map<String, Pattern> brandPatterns) {
        this.issuerId = issuerId;
        this.displayName = displayName;
        this.headerPattern = headerPattern;
        this.brandPatterns = brandPatterns;
    }

    @Override
    public final String issuerId() {
        return issuerId;
    }

    @Override
    public final String displayName() {
        return displayName;
    }

    @Override
    public boolean matches(final String headerText) {
        return IssuerProfile.headerContains(headerText, headerPattern);
    }

    @Override
    public String detectBrand(final String headerText) {
        if (brandPatterns == null || brandPatterns.isEmpty() || headerText == null) {
            return null;
        }
        for (final Map.Entry<String, Pattern> e : brandPatterns.entrySet()) {
            if (e.getValue().matcher(headerText).find()) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---- Default delegations to shared parsing utilities ----
    //
    // Each method routes to StatementParsingUtilities for the cross-issuer pattern
    // union. Concrete profiles override individual methods to apply issuer-specific
    // logic — anything not overridden inherits the shared behavior automatically.

    @Override
    public BigDecimal extractNewBalance(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractNewBalance(lines);
    }

    @Override
    public BigDecimal extractPreviousBalance(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPreviousBalance(lines);
    }

    @Override
    public BigDecimal extractCreditLimit(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCreditLimit(lines);
    }

    @Override
    public BigDecimal extractAvailableCredit(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractAvailableCredit(lines);
    }

    @Override
    public BigDecimal extractPastDueAmount(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPastDueAmount(lines);
    }

    @Override
    public BigDecimal extractPurchasesTotal(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPurchasesTotal(lines);
    }

    @Override
    public BigDecimal extractPaymentsAndCreditsTotal(
            final String[] lines, final ExtractionContext ctx) {
        // Normalize sign across issuers. The raw value extracted from the statement
        // varies by issuer convention:
        //   - Citi / Chase Amazon Visa print "Payments -$3,110.20" → extractor returns -3110.20
        //   - Wells Fargo / U.S. Bank print "TOTAL PAYMENTS FOR THIS PERIOD $545.91" → +545.91
        // Downstream consumers (insights, math validator, iOS) want a single convention.
        // We expose POSITIVE (absolute value, "amount of credit applied this cycle") because
        // semantically that's what the field represents — payments REDUCE the balance and
        // the sign is implied by the field name. Per-issuer profile overrides can opt out
        // by NOT calling super() if they need the raw sign.
        final BigDecimal raw = StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines);
        return raw == null ? null : raw.abs();
    }

    @Override
    public BigDecimal extractFeesChargedTotal(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractFeesChargedTotal(lines);
    }

    @Override
    public BigDecimal extractInterestChargedTotal(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractInterestChargedTotal(lines);
    }

    @Override
    public BigDecimal extractPurchaseApr(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPurchaseApr(lines);
    }

    @Override
    public BigDecimal extractCashAdvanceApr(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCashAdvanceApr(lines);
    }

    @Override
    public BigDecimal extractBalanceTransferApr(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractBalanceTransferApr(lines);
    }

    @Override
    public BigDecimal extractPenaltyApr(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPenaltyApr(lines);
    }

    @Override
    public Integer extractBillingDays(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractBillingDays(lines);
    }

    @Override
    public LocalDate extractStatementDate(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractStatementDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public Boolean extractAutoPayEnabled(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractAutoPayEnabled(lines);
    }

    @Override
    public BigDecimal extractNextAutoPayAmount(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractNextAutoPayAmount(lines);
    }

    @Override
    public Long extractPointsBalance(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPointsBalance(lines);
    }

    @Override
    public Long extractPointsEarnedThisPeriod(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPointsEarnedThisPeriod(lines);
    }

    @Override
    public BigDecimal extractCashBackBalance(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCashBackBalance(lines);
    }

    @Override
    public BigDecimal extractYtdFeesCharged(final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractYtdFeesCharged(lines);
    }

    @Override
    public BigDecimal extractYtdInterestCharged(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractYtdInterestCharged(lines);
    }

    // ---- Delegations for less-common extractors ----

    @Override
    public BigDecimal extractCashAdvancesTotal(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCashAdvancesTotal(lines);
    }

    @Override
    public BigDecimal extractBalanceTransfersTotal(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractBalanceTransfersTotal(lines);
    }

    @Override
    public BigDecimal extractCashAccessLine(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCashAccessLine(lines);
    }

    @Override
    public BigDecimal extractAvailableForCash(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractAvailableForCash(lines);
    }

    @Override
    public BigDecimal extractForeignTransactionFeePercent(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractForeignTransactionFeePercent(lines);
    }

    @Override
    public Long extractPreviousPointsBalance(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractPreviousPointsBalance(lines);
    }

    @Override
    public Object[] extractAnnualMembershipFeeAndDate(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractAnnualMembershipFeeAndDate(
                lines, ctx.inferredYear(), ctx.usLocale());
    }

    @Override
    public java.util.Map<String, java.math.BigDecimal> extractRewardMultipliersFromPdf(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractRewardMultipliersFromPdf(lines);
    }

    @Override
    public StatementParsingUtilities.QuarterlyBonus extractCurrentQuarterBonus(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractCurrentQuarterBonus(lines);
    }

    @Override
    public StatementParsingUtilities.NextQuarterBonus extractNextQuarterBonus(
            final String[] lines, final ExtractionContext ctx) {
        return StatementParsingUtilities.extractNextQuarterBonus(
                lines, ctx.inferredYear(), ctx.usLocale());
    }
}
