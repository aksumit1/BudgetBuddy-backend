package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Validates the canonical credit-card statement identity:
 *
 * <pre>
 *   previousBalance
 *     - payments
 *     + purchases
 *     + cashAdvances
 *     + balanceTransfers
 *     + fees
 *     + interest
 *     = newBalance
 * </pre>
 *
 * <p>When all section totals plus the previous + new balance are extracted, the math
 * should reconcile to within a cent. A larger gap is almost always an extractor bug —
 * a section total caught a wrong line, a sign got dropped, or a value got truncated.
 *
 * <p>The validator is non-blocking: it emits a {@link Result} that callers can log or
 * surface, but never throws or rejects an import. This keeps the extraction pipeline
 * resilient (a math failure on one statement doesn't break the whole batch) while
 * still giving us automated visibility into extractor regressions.
 *
 * <p>Tolerance is $1.00 by default — generous enough to absorb rounding in disclosure-
 * level subtotal printing but tight enough to catch a sign flip or dropped row.
 */
public final class StatementMathValidator {

    /** Default tolerance for math reconciliation in dollars. */
    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("1.00");

    private final BigDecimal tolerance;

    public StatementMathValidator() {
        this(DEFAULT_TOLERANCE);
    }

    public StatementMathValidator(final BigDecimal tolerance) {
        this.tolerance = tolerance == null ? DEFAULT_TOLERANCE : tolerance.abs();
    }

    /**
     * Runs the math check. Returns {@link Result#skipped} when not enough inputs are
     * populated (avoids false negatives on partially-parsed statements), or a passing
     * / failing {@link Result} when all inputs are present.
     *
     * <p>Sign convention for inputs: payments and credits are PASSED IN AS POSITIVE
     * (absolute value of credits). The validator handles the sign internally so each
     * issuer's sign-handling convention doesn't bleed into here.
     */
    public Result validate(
            final BigDecimal previousBalance,
            final BigDecimal newBalance,
            final BigDecimal paymentsAndCredits,
            final BigDecimal purchasesTotal,
            final BigDecimal cashAdvancesTotal,
            final BigDecimal balanceTransfersTotal,
            final BigDecimal feesChargedTotal,
            final BigDecimal interestChargedTotal) {
        if (previousBalance == null || newBalance == null) {
            return Result.skipped("missing previousBalance or newBalance");
        }
        if (paymentsAndCredits == null || purchasesTotal == null) {
            return Result.skipped("missing payments or purchases — partial extraction");
        }
        final BigDecimal computed =
                previousBalance
                        .subtract(paymentsAndCredits.abs())
                        .add(purchasesTotal)
                        .add(nz(cashAdvancesTotal))
                        .add(nz(balanceTransfersTotal))
                        .add(nz(feesChargedTotal))
                        .add(nz(interestChargedTotal));
        final BigDecimal diff = newBalance.subtract(computed).abs();
        if (diff.compareTo(tolerance) <= 0) {
            return Result.pass(computed, diff);
        }
        return Result.fail(computed, diff, tolerance);
    }

    private static BigDecimal nz(final BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Outcome record. {@code passed=true} is the only "OK" state. */
    public static final class Result {
        public enum Status { PASS, FAIL, SKIPPED }

        private final Status status;
        private final BigDecimal computed;
        private final BigDecimal diff;
        private final BigDecimal tolerance;
        private final String reason;

        private Result(
                final Status status,
                final BigDecimal computed,
                final BigDecimal diff,
                final BigDecimal tolerance,
                final String reason) {
            this.status = status;
            this.computed = computed;
            this.diff = diff;
            this.tolerance = tolerance;
            this.reason = reason;
        }

        static Result pass(final BigDecimal computed, final BigDecimal diff) {
            return new Result(Status.PASS, computed, diff, null, null);
        }

        static Result fail(
                final BigDecimal computed, final BigDecimal diff, final BigDecimal tolerance) {
            return new Result(Status.FAIL, computed, diff, tolerance, null);
        }

        static Result skipped(final String reason) {
            return new Result(Status.SKIPPED, null, null, null, reason);
        }

        public boolean passed() { return status == Status.PASS; }
        public boolean failed() { return status == Status.FAIL; }
        public boolean skipped() { return status == Status.SKIPPED; }
        public Status status() { return status; }
        public Optional<BigDecimal> computed() { return Optional.ofNullable(computed); }
        public Optional<BigDecimal> diff() { return Optional.ofNullable(diff); }
        public Optional<BigDecimal> tolerance() { return Optional.ofNullable(tolerance); }
        public Optional<String> reason() { return Optional.ofNullable(reason); }

        @Override
        public String toString() {
            switch (status) {
                case PASS:
                    return String.format(
                            "PASS (computed=%s, diff=%s)",
                            computed.setScale(2, RoundingMode.HALF_UP),
                            diff.setScale(2, RoundingMode.HALF_UP));
                case FAIL:
                    return String.format(
                            "FAIL (computed=%s, diff=%s exceeds tolerance %s)",
                            computed.setScale(2, RoundingMode.HALF_UP),
                            diff.setScale(2, RoundingMode.HALF_UP),
                            tolerance.setScale(2, RoundingMode.HALF_UP));
                default:
                    return "SKIPPED (" + reason + ")";
            }
        }
    }
}
