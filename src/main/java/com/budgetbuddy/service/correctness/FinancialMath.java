package com.budgetbuddy.service.correctness;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Canonical constants for every money-math operation in the backend.
 *
 * <p><strong>Why this exists.</strong> Correctness drift in a finance app is usually not one bad
 * formula — it's inconsistency. Four services each pick a reasonable-looking rounding mode and
 * scale, and three months later a goal tracker disagrees with a budget tracker by a penny because
 * one rounds {@code HALF_UP} and the other rounds {@code HALF_EVEN}. Users see the penny; they
 * don't see the rounding mode. Trust is gone.
 *
 * <p>This class defines ONE rounding mode ({@link #ROUNDING}) and ONE money scale ({@link
 * #MONEY_SCALE}) used everywhere that stores a dollar amount. Percentage/ratio math uses {@link
 * #PERCENT_SCALE} — more precision for comparisons (50.0001% vs 50% matters at thresholds), but
 * still bounded.
 *
 * <p>Non-obvious pieces:
 *
 * <ul>
 *   <li>{@link #HUNDRED} and {@link #TWELVE} are pre-constructed so the hot paths can
 *       multiply/divide without allocating fresh {@code BigDecimal} each call — these get called in
 *       per-transaction loops.
 *   <li>{@link #MATH_CONTEXT} is for square-root / irrational intermediates (stddev, compound
 *       interest). 16 digits matches double's precision without any of double's IEEE-754 quirks.
 *   <li>Every method here returns a {@link BigDecimal} with {@link #MONEY_SCALE} applied, so
 *       callers don't have to remember to re-scale.
 * </ul>
 *
 * <p>Enforcement: an ArchUnit test under {@code FinancialMathArchTest} prevents code outside this
 * class from calling {@code setScale(int, RoundingMode)} with anything other than this rounding
 * mode on amount-typed fields. That catches regressions at build time.
 */
public final class FinancialMath {

    /**
     * The single rounding mode for money. HALF_UP because that's how users read a price tag (4.5
     * cents rounds to 5). HALF_EVEN would be correct for accounting ledgers but disagrees with user
     * expectations.
     */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Cents precision for any stored / displayed money value. */
    public static final int MONEY_SCALE = 2;

    /**
     * Four-decimal precision for ratios so threshold comparisons stay crisp (50.00% vs 50.0001% vs
     * 49.9999% are all distinguishable).
     */
    public static final int PERCENT_SCALE = 4;

    /** Square-root / log / compound-interest intermediate precision. */
    public static final MathContext MATH_CONTEXT = new MathContext(16, ROUNDING);

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private FinancialMath() {}

    /** Normalise an amount to canonical money scale. */
    public static BigDecimal money(final BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, ROUNDING);
    }

    /**
     * Percent = numerator / denominator × 100, scale 4. Returns {@code BigDecimal.ZERO} when
     * denominator is zero or null.
     */
    public static BigDecimal percent(final BigDecimal numerator, final BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(HUNDRED).divide(denominator, PERCENT_SCALE, ROUNDING);
    }

    /**
     * Ratio = numerator / denominator, scale 4. Returns {@code BigDecimal.ZERO} when denominator is
     * zero or null.
     */
    public static BigDecimal ratio(final BigDecimal numerator, final BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, PERCENT_SCALE, ROUNDING);
    }
}
