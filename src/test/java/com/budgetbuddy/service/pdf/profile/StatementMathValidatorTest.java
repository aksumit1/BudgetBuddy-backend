package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.StatementMathValidator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatementMathValidatorTest {

    private final StatementMathValidator validator = new StatementMathValidator();

    @Test
    void validate_zero_starting_balance_passes() {
        // 0 - 0 + 100 = 100 ✓
        final Result r = validator.validate(
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                null, null, null, null);
        assertTrue(r.passed(), r.toString());
        assertEquals(0, new BigDecimal("100.00").compareTo(r.computed().orElseThrow()));
    }

    @Test
    void validate_payment_reduces_balance_passes() {
        // 500 - 500 + 1000 + 0 + 0 + 0 + 0 = 1000 ✓
        final Result r = validator.validate(
                new BigDecimal("500.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("1000.00"),
                null, null, null, null);
        assertTrue(r.passed());
    }

    @Test
    void validate_full_section_breakdown_passes_within_tolerance() {
        // previous 100 - payments 100 + purchases 50 + cash 10 + bt 5 + fees 3 + interest 2 = 70
        final Result r = validator.validate(
                new BigDecimal("100.00"),
                new BigDecimal("70.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("10.00"),
                new BigDecimal("5.00"),
                new BigDecimal("3.00"),
                new BigDecimal("2.00"));
        assertTrue(r.passed(), r.toString());
    }

    @Test
    void validate_off_by_two_dollars_fails() {
        // previous 0 - payments 0 + purchases 100 = 100 but newBalance says 102 → diff $2
        final Result r = validator.validate(
                new BigDecimal("0.00"),
                new BigDecimal("102.00"),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                null, null, null, null);
        assertFalse(r.passed(), r.toString());
        assertTrue(r.failed());
        assertEquals(0, new BigDecimal("2.00").compareTo(r.diff().orElseThrow()));
    }

    @Test
    void validate_within_default_tolerance_passes() {
        // $0.50 diff is below the $1 default tolerance — still passes.
        final Result r = validator.validate(
                new BigDecimal("0.00"),
                new BigDecimal("100.50"),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                null, null, null, null);
        assertTrue(r.passed(), r.toString());
    }

    @Test
    void validate_handles_signed_payments_via_absolute_value() {
        // Some issuers return paymentsAndCredits as negative; some as positive. The
        // validator uses .abs() internally so caller doesn't have to know.
        final Result r1 = validator.validate(
                new BigDecimal("500.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("-500.00"), // negative
                new BigDecimal("1000.00"),
                null, null, null, null);
        assertTrue(r1.passed(), r1.toString());

        final Result r2 = validator.validate(
                new BigDecimal("500.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"), // positive — same effect
                new BigDecimal("1000.00"),
                null, null, null, null);
        assertTrue(r2.passed(), r2.toString());
    }

    @Test
    void validate_missing_inputs_returnsSkipped() {
        final Result missingNewBalance = validator.validate(
                new BigDecimal("100"), null, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null);
        assertTrue(missingNewBalance.skipped());

        final Result missingPayments = validator.validate(
                new BigDecimal("100"), new BigDecimal("100"), null, BigDecimal.ZERO,
                null, null, null, null);
        assertTrue(missingPayments.skipped());
    }

    @Test
    void validate_randomized_balancesAlwaysReconcile() {
        final java.util.Random rng = new java.util.Random(0xCA5C_ADEDL);
        for (int i = 0; i < 1000; i++) {
            final BigDecimal prev = randomDollars(rng);
            final BigDecimal pay = prev.min(randomDollars(rng));
            final BigDecimal pur = randomDollars(rng);
            final BigDecimal cash = randomDollars(rng);
            final BigDecimal bt = randomDollars(rng);
            final BigDecimal fee = randomDollars(rng);
            final BigDecimal interest = randomDollars(rng);
            final BigDecimal newBal =
                    prev.subtract(pay).add(pur).add(cash).add(bt).add(fee).add(interest);
            final Result r = validator.validate(prev, newBal, pay, pur, cash, bt, fee, interest);
            assertTrue(r.passed(), "Iter " + i + ": " + r);
        }
    }

    private static BigDecimal randomDollars(final java.util.Random rng) {
        final int dollars = rng.nextInt(10_000);
        final int cents = rng.nextInt(100);
        return new BigDecimal(dollars + "." + String.format("%02d", cents));
    }
}
