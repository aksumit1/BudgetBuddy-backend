package com.budgetbuddy.service.correctness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Tests pinning the canonical money-math behaviour.
 *
 * <p>These tests are intentionally paranoid about edge cases that show up as user-visible drift:
 * 0.5-cent rounding, ratio on zero denominators, and scale consistency across operations. If one of
 * these tests ever fails it means the rounding contract has silently changed — which is exactly the
 * drift we built {@link FinancialMath} to prevent.
 */
class FinancialMathTest {

    @Test
    void roundingIsHalfUpSoHalfCentsGoUp() {
        // 0.005 → 0.01 under HALF_UP, 0.00 under HALF_EVEN. The user-visible
        // mental model is "a half-penny rounds up", so HALF_UP is correct.
        assertEquals(new BigDecimal("0.01"), FinancialMath.money(new BigDecimal("0.005")));
        assertEquals(new BigDecimal("0.02"), FinancialMath.money(new BigDecimal("0.015")));
        // Above/below the half mark behave identically in both modes.
        assertEquals(new BigDecimal("0.01"), FinancialMath.money(new BigDecimal("0.006")));
        assertEquals(new BigDecimal("0.00"), FinancialMath.money(new BigDecimal("0.004")));
    }

    @Test
    void moneyNormalisesScaleToTwoEvenWhenInputHasNoDecimals() {
        // A BigDecimal with scale 0 (e.g. from JSON parsing `"100"`) becomes
        // "100.00" — crucial for compareTo consistency and for display: we
        // always show two decimals regardless of input scale.
        assertEquals(new BigDecimal("100.00"), FinancialMath.money(new BigDecimal("100")));
        assertEquals(new BigDecimal("100.00"), FinancialMath.money(new BigDecimal("100.0")));
        assertEquals(new BigDecimal("100.00"), FinancialMath.money(new BigDecimal("100.00")));
    }

    @Test
    void moneyOfNullReturnsNull() {
        // Money fields are nullable (subscription row with no amount yet,
        // fee fields on non-credit accounts). Don't default to ZERO — that
        // would make a null-absent-field silently count as $0 in aggregates.
        assertNull(FinancialMath.money(null));
    }

    @Test
    void percentCrossesBudgetThresholdsExactly() {
        // The budget threshold evaluator checks spend >= 50/75/90/100%. At
        // exactly $500 spent on a $1000 limit the percent must be exactly
        // 50.0000, not 49.9999 (which would silently miss the alert).
        assertEquals(
                0,
                FinancialMath.percent(new BigDecimal("500.00"), new BigDecimal("1000.00"))
                        .compareTo(new BigDecimal("50.0000")));
        assertEquals(
                0,
                FinancialMath.percent(new BigDecimal("900.00"), new BigDecimal("1000.00"))
                        .compareTo(new BigDecimal("90.0000")));
        assertEquals(
                0,
                FinancialMath.percent(new BigDecimal("1000.00"), new BigDecimal("1000.00"))
                        .compareTo(new BigDecimal("100.0000")));
    }

    @Test
    void percentOverZeroOrNullDenominatorReturnsZeroNotException() {
        // Division by zero in this context means "no budget set" or "no
        // income" — callers expect ZERO so the % rendering reads "0%".
        // Throwing ArithmeticException would break the UI.
        assertEquals(
                0,
                FinancialMath.percent(new BigDecimal("100"), BigDecimal.ZERO)
                        .compareTo(BigDecimal.ZERO));
        assertEquals(
                0, FinancialMath.percent(new BigDecimal("100"), null).compareTo(BigDecimal.ZERO));
        assertEquals(
                0, FinancialMath.percent(null, new BigDecimal("1000")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void ratioPreservesFourDigitPrecision() {
        // Debt-to-income thresholds use 0.36 (36%). The ratio helper must
        // distinguish 0.3599 from 0.3600 or the warning triggers a cent off.
        assertEquals(
                0,
                FinancialMath.ratio(new BigDecimal("36"), new BigDecimal("100"))
                        .compareTo(new BigDecimal("0.3600")));
        assertEquals(
                0,
                FinancialMath.ratio(new BigDecimal("35.99"), new BigDecimal("100"))
                        .compareTo(new BigDecimal("0.3599")));
    }

    @Test
    void constantsPointToHalfUp() {
        // Load-bearing assertion — if someone flips ROUNDING globally the
        // ripple effect on every balance is catastrophic. This makes that
        // change fail at test time rather than in production.
        assertEquals(RoundingMode.HALF_UP, FinancialMath.ROUNDING);
        assertEquals(2, FinancialMath.MONEY_SCALE);
        assertEquals(4, FinancialMath.PERCENT_SCALE);
    }
}
