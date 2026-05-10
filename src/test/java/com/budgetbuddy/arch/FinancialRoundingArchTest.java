package com.budgetbuddy.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Build-time guard that money math stays consistent across the backend.
 *
 * <p>Correctness in a finance app is mostly <em>not</em> about getting the formulas right — it's
 * about preventing drift once you do. One service picks {@code RoundingMode.HALF_EVEN} "just for
 * this one place" and six months later a budget screen and a goal screen disagree by a penny on the
 * same transaction. Users see the penny; trust is gone.
 *
 * <p>This suite fails the build if code outside {@link
 * com.budgetbuddy.service.correctness.FinancialMath} names a rounding mode other than {@code
 * HALF_UP} or constructs a {@link java.math.BigDecimal} from a raw {@code double} (which preserves
 * the IEEE-754 tail and produces values like {@code 1000.01000000000022...}).
 *
 * <p>Exceptions are deliberately narrow:
 *
 * <ul>
 *   <li>{@code FinancialMath} itself, where the canonical constants live.
 *   <li>Test classes — they set up fixtures and may use any mode.
 *   <li>Third-party math helpers are not present in the package scan.
 * </ul>
 */
class FinancialRoundingArchTest {

    private static final JavaClasses MAIN_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.budgetbuddy");

    @Test
    void bankersRoundingAndOtherExoticModesAreForbidden() {
        // The failure mode this prevents: a reasonable-looking service picks
        // {@code RoundingMode.HALF_EVEN} (banker's rounding, statistically
        // unbiased on repeated rounds) because it's "the fair one." Over
        // time a budget screen that uses HALF_UP and a goal screen that
        // uses HALF_EVEN disagree by a penny on the same $0.005 edge case.
        // Users see the penny, trust is gone.
        //
        // What's allowed here:
        //   - {@link java.math.RoundingMode#HALF_UP} — the canonical money
        //     mode, exposed via FinancialMath.ROUNDING. Anywhere.
        //   - {@link java.math.RoundingMode#UP} — integer ceilings only
        //     (e.g. months-to-payoff, round-up contributions). The audit
        //     sites are in PlannerController, GoalRoundUpService,
        //     SubscriptionsBudgetSeeder, FinancialGoalsRecommendationService,
        //     FinancialInsightsPredictionService. All six are scale-0 ops,
        //     not money math.
        //
        // What's forbidden (this test enforces):
        //   - HALF_EVEN, HALF_DOWN, DOWN, CEILING, FLOOR on any field,
        //     method, variable, or argument anywhere in the production tree.
        //
        // ArchUnit doesn't have a direct "uses this specific enum constant"
        // check, so we use a byte-code scan over access-to-field. HALF_EVEN
        // is the most common accidental culprit, so the message names it
        // explicitly to guide the fix.
        final ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("com.budgetbuddy..")
                        .and()
                        .haveSimpleNameNotContaining("FinancialMath")
                        .should(
                                new com.tngtech.archunit.lang.ArchCondition<
                                        com.tngtech.archunit.core.domain.JavaClass>(
                                        "not reference RoundingMode.HALF_EVEN / HALF_DOWN / DOWN / CEILING / FLOOR") {
                                    @Override
                                    public void check(
                                            final com.tngtech.archunit.core.domain.JavaClass item,
                                            final com.tngtech.archunit.lang.ConditionEvents
                                                    events) {
                                        item.getFieldAccessesFromSelf()
                                                .forEach(
                                                        access -> {
                                                            final String owner =
                                                                    access.getTarget()
                                                                            .getOwner()
                                                                            .getFullName();
                                                            final String name =
                                                                    access.getTarget().getName();
                                                            if ("java.math.RoundingMode"
                                                                    .equals(owner)
                                                                    && ("HALF_EVEN".equals(name)
                                                                    || "HALF_DOWN"
                                                                    .equals(name)
                                                                    || "DOWN".equals(name)
                                                                    || "CEILING"
                                                                    .equals(name)
                                                                    || "FLOOR"
                                                                    .equals(
                                                                            name))) {
                                                                events.add(
                                                                        com.tngtech.archunit.lang
                                                                                .SimpleConditionEvent
                                                                                .violated(
                                                                                        access,
                                                                                        access
                                                                                                .getDescription()
                                                                                                + " — use FinancialMath.ROUNDING (HALF_UP) for money math; "
                                                                                                + "use RoundingMode.UP only for integer ceilings at scale 0"));
                                                            }
                                                        });
                                    }
                                })
                        .because(
                                "Money rounding must be HALF_UP (FinancialMath.ROUNDING). "
                                        + "Banker's rounding (HALF_EVEN), DOWN, HALF_DOWN, CEILING, FLOOR "
                                        + "produce per-caller inconsistency — screens disagree by a penny, "
                                        + "trust in the app evaporates. RoundingMode.UP is allowed only for "
                                        + "integer-scale ceiling operations (months-to-payoff, round-up sweeps).")
                        .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    @Test
    void constructingBigDecimalFromDoubleIsForbidden() {
        // `new BigDecimal(double)` is genuinely lossy — it preserves the
        // IEEE-754 tail (e.g. `new BigDecimal(0.1)` → 0.1000000000000000055…).
        // Use `BigDecimal.valueOf(double)` (which goes through Double.toString
        // and produces the shortest round-trip decimal) or parse a string.
        //
        // This one we *can* enforce — there should be zero legitimate uses.
        final ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("com.budgetbuddy..")
                        .should()
                        .callConstructor(java.math.BigDecimal.class, double.class)
                        .because(
                                "new BigDecimal(double) preserves IEEE-754 rounding error. "
                                        + "Use BigDecimal.valueOf(double) or parse from a string.")
                        .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    @Test
    void bigDecimalDivideWithoutScaleOrContextIsForbidden() {
        // `BigDecimal.divide(BigDecimal)` throws ArithmeticException on any
        // non-terminating decimal expansion (e.g. 1/3). In production this
        // shows up as a 500 on a budget computation — the user's app crashes
        // out of a screen mid-render. The only safe forms are
        // {@code divide(BigDecimal, int, RoundingMode)} or
        // {@code divide(BigDecimal, MathContext)}. Enforce that at build time.
        final ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("com.budgetbuddy..")
                        .should()
                        .callMethod(
                                java.math.BigDecimal.class, "divide", java.math.BigDecimal.class)
                        .because(
                                "BigDecimal.divide(BigDecimal) throws on non-terminating decimals. "
                                        + "Use divide(BigDecimal, scale, RoundingMode) or divide(BigDecimal, MathContext).")
                        .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    private static void assertRuleDefined(final ArchRule rule) {
        if (rule == null) {
            throw new AssertionError("Rule should be defined");
        }
    }
}
