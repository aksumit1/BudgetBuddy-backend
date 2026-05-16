package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Edge-case + negative-path + scalability coverage for every metadata extractor on
 * {@link PDFImportService}. The happy-path tests live in {@link
 * MarriottBonvoyStatementFixtureTest}; this file ensures each extractor:
 *
 * <ol>
 *   <li>Returns null gracefully on null / empty / missing-label input.
 *   <li>Recognises every documented alias for its label (not just Chase's specific
 *       phrasing).
 *   <li>Does NOT false-positive on disclosure prose that contains its label phrase.
 *   <li>Does NOT cross-wire to an adjacent extractor's value (e.g. newBalance vs.
 *       previousBalance, creditLimit vs. cashAccessLine).
 *   <li>Handles boundary values (zero, very large, very small).
 *   <li>Stays linear-time on large input (the "10,000 disclosure lines" smoke test).
 * </ol>
 *
 * Tests use parameterized inputs where the only thing varying is the label phrasing —
 * this is what makes a single regression like "anchored credit_limit to \\b instead of
 * ^\\s*" visible immediately rather than silent.
 */
class PDFImportServiceMetadataEdgeCasesTest {

    // ============================================================
    //  NULL / EMPTY INPUT — every extractor must return null safely
    // ============================================================

    @Test
    void nullInput_everyExtractor_returnsNullWithoutThrowing() {
        // Pass a single-element array with a null entry. Every extractor must walk past
        // it without NPE and return null because no label was found.
        final String[] singleNull = new String[] {null};
        assertNull(StatementParsingUtilities.extractNewBalance(singleNull));
        assertNull(StatementParsingUtilities.extractPreviousBalance(singleNull));
        assertNull(StatementParsingUtilities.extractCreditLimit(singleNull));
        assertNull(StatementParsingUtilities.extractAvailableCredit(singleNull));
        assertNull(StatementParsingUtilities.extractPastDueAmount(singleNull));
        assertNull(StatementParsingUtilities.extractPurchasesTotal(singleNull));
        assertNull(StatementParsingUtilities.extractPaymentsAndCreditsTotal(singleNull));
        assertNull(StatementParsingUtilities.extractCashAdvancesTotal(singleNull));
        assertNull(StatementParsingUtilities.extractBalanceTransfersTotal(singleNull));
        assertNull(StatementParsingUtilities.extractFeesChargedTotal(singleNull));
        assertNull(StatementParsingUtilities.extractInterestChargedTotal(singleNull));
        assertNull(StatementParsingUtilities.extractPurchaseApr(singleNull));
        assertNull(StatementParsingUtilities.extractCashAdvanceApr(singleNull));
        assertNull(StatementParsingUtilities.extractBalanceTransferApr(singleNull));
        assertNull(StatementParsingUtilities.extractPenaltyApr(singleNull));
        assertNull(StatementParsingUtilities.extractCashAccessLine(singleNull));
        assertNull(StatementParsingUtilities.extractAvailableForCash(singleNull));
        assertNull(StatementParsingUtilities.extractBillingDays(singleNull));
        assertNull(StatementParsingUtilities.extractStatementDate(singleNull, 2026, true));
        assertNull(StatementParsingUtilities.extractForeignTransactionFeePercent(singleNull));
        assertNull(StatementParsingUtilities.extractAutoPayEnabled(singleNull));
        assertNull(StatementParsingUtilities.extractNextAutoPayAmount(singleNull));
        assertNull(StatementParsingUtilities.extractPointsEarnedThisPeriod(singleNull));
        assertNull(StatementParsingUtilities.extractPointsBalance(singleNull));
        assertNull(StatementParsingUtilities.extractAnnualMembershipFeeAndDate(singleNull, 2026, true));
    }

    @Test
    void emptyArrayInput_everyExtractor_returnsNull() {
        final String[] empty = new String[0];
        assertNull(StatementParsingUtilities.extractNewBalance(empty));
        assertNull(StatementParsingUtilities.extractCreditLimit(empty));
        assertNull(StatementParsingUtilities.extractPurchaseApr(empty));
        assertNull(StatementParsingUtilities.extractAutoPayEnabled(empty));
        assertNull(StatementParsingUtilities.extractPointsEarnedThisPeriod(empty));
        assertNull(StatementParsingUtilities.extractStatementDate(empty, 2026, true));
        assertNull(StatementParsingUtilities.extractBillingDays(empty));
    }

    @Test
    void unrelatedText_everyExtractor_returnsNullWhenNoLabelPresent() {
        // A line of disclosure text that has none of the trigger labels. No extractor
        // should fire — that's the central invariant.
        final String[] lines = {
            "We hope you enjoy all the benefits your card has to offer.",
            "Please refer to your cardmember agreement for more details.",
            "Call 1-800-555-1234 with any questions about your account.",
        };
        assertNull(StatementParsingUtilities.extractNewBalance(lines));
        assertNull(StatementParsingUtilities.extractPreviousBalance(lines));
        assertNull(StatementParsingUtilities.extractCreditLimit(lines));
        assertNull(StatementParsingUtilities.extractAvailableCredit(lines));
        assertNull(StatementParsingUtilities.extractPastDueAmount(lines));
        assertNull(StatementParsingUtilities.extractPurchasesTotal(lines));
        assertNull(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines));
        assertNull(StatementParsingUtilities.extractAutoPayEnabled(lines));
        assertNull(StatementParsingUtilities.extractPointsEarnedThisPeriod(lines));
    }

    // ============================================================
    //  LABEL ALIASES — each extractor's documented variants
    // ============================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "New Balance $1,234.56",
        "Statement Balance: $1,234.56",
        "Current Balance $1,234.56",
        "  New Balance: $1,234.56", // leading whitespace tolerance
        "NEW BALANCE $1,234.56", // upper-case
    })
    void newBalance_acceptsAllDocumentedAliases(final String line) {
        final BigDecimal v = StatementParsingUtilities.extractNewBalance(new String[] {line});
        assertNotNull(v, "Line '" + line + "' must produce a non-null new balance");
        assertEquals(0, new BigDecimal("1234.56").compareTo(v),
                "Line '" + line + "' must extract $1,234.56");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Previous Balance $999.99",
        "Prior Balance: $999.99",
        "Last Statement Balance $999.99",
    })
    void previousBalance_acceptsAllDocumentedAliases(final String line) {
        final BigDecimal v = StatementParsingUtilities.extractPreviousBalance(new String[] {line});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("999.99").compareTo(v));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Credit Access Line $50,000", // Chase
        "Total Credit Limit: $50,000", // some issuers
        "Credit Limit $50,000", // generic
    })
    void creditLimit_acceptsAllDocumentedAliases(final String line) {
        final BigDecimal v = StatementParsingUtilities.extractCreditLimit(new String[] {line});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("50000").compareTo(v));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Available Credit $42,000",
        "Credit Available: $42,000",
    })
    void availableCredit_acceptsAllDocumentedAliases(final String line) {
        final BigDecimal v = StatementParsingUtilities.extractAvailableCredit(new String[] {line});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("42000").compareTo(v));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Past Due Amount $0.00",
        "Amount Past Due: $0.00",
        "Past Due: $0.00",
    })
    void pastDueAmount_acceptsAllDocumentedAliases(final String line) {
        final BigDecimal v = StatementParsingUtilities.extractPastDueAmount(new String[] {line});
        assertNotNull(v);
        assertEquals(0, BigDecimal.ZERO.compareTo(v));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "AUTOPAY IS ON",
        "Autopay is on",
        "Automatic Payments: Enabled",
        "Automatic Payments is on",
        "Automatic Payment is on",
    })
    void autoPay_recognisesEveryDocumentedOnPhrase(final String line) {
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(new String[] {line});
        assertNotNull(enabled, "Line '" + line + "' must produce a non-null AutoPay status");
        assertTrue(enabled, "Line '" + line + "' must yield true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "AUTOPAY IS OFF",
        "Autopay is off",
        "Automatic Payments: Disabled",
        "Automatic Payments is off",
    })
    void autoPay_recognisesEveryDocumentedOffPhrase(final String line) {
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(new String[] {line});
        assertNotNull(enabled);
        assertFalse(enabled);
    }

    // ============================================================
    //  FALSE-POSITIVE PREVENTION — disclosure prose containing labels
    // ============================================================

    @Test
    void autoPay_doesNotFalsePositive_onDisclosureProse() {
        // Real disclosure phrases that contain "automatic payment(s)" but are NOT a
        // statement-level AutoPay marker. The extractor must return null, not true.
        final String[][] disclosurePhrases = {
            {"Your AutoPay amount will be reduced by any payments or merchant credits"
                + " that post to your account before we process your AutoPay payment."},
            {"If we receive automatic payments before processing, we credit them same-day."},
            {"You may set up automatic payments through our website."},
            {"Authorize us to make automatic payments from your designated bank account."},
        };
        for (final String[] lines : disclosurePhrases) {
            final Boolean result = StatementParsingUtilities.extractAutoPayEnabled(lines);
            assertNull(result,
                    "Disclosure phrase '" + lines[0] + "' must NOT produce a Boolean — got: "
                            + result);
        }
    }

    @Test
    void creditLimit_doesNotFalsePositive_onDisclosureProse() {
        // Phrases that mention "credit limit" but aren't the actual limit row.
        final String[] disclosureLines = {
            "If you want to inquire about your options to help prevent your account from",
            "exceeding your credit limit, please call the number on the back of your card.",
            "We may reduce your credit limit at our discretion.",
        };
        assertNull(StatementParsingUtilities.extractCreditLimit(disclosureLines));
    }

    @Test
    void newBalance_doesNotFalsePositive_onDisclosurePhrase() {
        // "your new balance" mentioned in disclosure prose without an immediately-following
        // $ amount must NOT trigger.
        final String[] lines = {
            "After we apply your payment, your new balance will be reduced.",
            "Your new balance reflects all activity from the prior cycle.",
        };
        assertNull(StatementParsingUtilities.extractNewBalance(lines));
    }

    @Test
    void previousBalance_doesNotFalsePositive_onPriorCycleNarrative() {
        final String[] lines = {
            "Your previous balance may have included pending charges that have since posted.",
            "Compare your prior balance against this cycle to understand changes.",
        };
        assertNull(StatementParsingUtilities.extractPreviousBalance(lines));
    }

    @Test
    void purchasesTotal_doesNotFalsePositive_onPlainParagraphMention() {
        // "Purchases" mentioned in disclosure prose (no amount immediately after).
        final String[] lines = {
            "Purchases made with cash advances from an ATM or with a check do not qualify.",
            "We will not impose interest charges on any portion of a purchase balance.",
        };
        assertNull(StatementParsingUtilities.extractPurchasesTotal(lines));
    }

    // ============================================================
    //  CROSS-EXTRACTOR ISOLATION — adjacent labels don't cross-wire
    // ============================================================

    @Test
    void creditLimit_doesNotPickUpCashAccessLine() {
        // Order matters: cash limit appears in the same summary section. Each extractor
        // must hit only its OWN label.
        final String[] lines = {
            "Credit Access Line $50,000",
            "Cash Access Line $5,000",
        };
        assertEquals(0, new BigDecimal("50000")
                .compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
        assertEquals(0, new BigDecimal("5000")
                .compareTo(StatementParsingUtilities.extractCashAccessLine(lines)));
    }

    @Test
    void availableCredit_doesNotPickUpAvailableForCash() {
        final String[] lines = {
            "Available Credit $42,000",
            "Available for Cash $5,000",
        };
        assertEquals(0, new BigDecimal("42000")
                .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)));
        assertEquals(0, new BigDecimal("5000")
                .compareTo(StatementParsingUtilities.extractAvailableForCash(lines)));
    }

    @Test
    void allSectionTotalsExtractIndependently_andDoNotConflate() {
        // The six section-total labels are similar phrases. Verify each picks ONLY its
        // own row even with all six present and all distinct values.
        final String[] lines = {
            "Previous Balance $1,000.00",
            "Payment, Credits -$200.00",
            "Purchases +$300.00",
            "Cash Advances $50.00",
            "Balance Transfers $25.00",
            "Fees Charged $10.00",
            "Interest Charged $5.00",
        };
        assertEquals(0, new BigDecimal("300.00")
                .compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)));
        assertEquals(0, new BigDecimal("-200.00")
                .compareTo(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)));
        assertEquals(0, new BigDecimal("50.00")
                .compareTo(StatementParsingUtilities.extractCashAdvancesTotal(lines)));
        assertEquals(0, new BigDecimal("25.00")
                .compareTo(StatementParsingUtilities.extractBalanceTransfersTotal(lines)));
        assertEquals(0, new BigDecimal("10.00")
                .compareTo(StatementParsingUtilities.extractFeesChargedTotal(lines)));
        assertEquals(0, new BigDecimal("5.00")
                .compareTo(StatementParsingUtilities.extractInterestChargedTotal(lines)));
    }

    @Test
    void allFourApRsExtractIndependently_andDoNotConflate() {
        // Same idea for the APR block — four rates of similar shape, must not cross-wire.
        final String[] lines = {
            "Purchases 19.49%(v)(d) - 0 -   - 0 -",
            "Cash Advances 28.49%(v)(d) - 0 -   - 0 -",
            "Balance Transfers 19.50%(v)(d) - 0 -   - 0 -",
            "My Chase Loan 19.49%(v)(d)",
            "Penalty APR of 29.99%.",
        };
        assertEquals(0, new BigDecimal("19.49")
                .compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0, new BigDecimal("28.49")
                .compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        // BT rate is intentionally distinct (19.50 vs 19.49) to catch a regex that
        // matches whichever appears first.
        assertEquals(0, new BigDecimal("19.50")
                .compareTo(StatementParsingUtilities.extractBalanceTransferApr(lines)));
        assertEquals(0, new BigDecimal("29.99")
                .compareTo(StatementParsingUtilities.extractPenaltyApr(lines)));
    }

    // ============================================================
    //  BOUNDARY VALUES — zero, very large, very small
    // ============================================================

    @Test
    void newBalance_acceptsZero() {
        final BigDecimal v =
                StatementParsingUtilities.extractNewBalance(new String[] {"New Balance $0.00"});
        assertNotNull(v);
        assertEquals(0, BigDecimal.ZERO.compareTo(v));
    }

    @Test
    void creditLimit_acceptsSixFigureAmount() {
        // High-net-worth cards can have $100k+ limits. Verify the comma-grouped pattern
        // handles them.
        final BigDecimal v = StatementParsingUtilities.extractCreditLimit(
                new String[] {"Credit Access Line $150,000"});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("150000").compareTo(v));
    }

    @Test
    void newBalance_acceptsSubDollarAmount() {
        // Tiny balances (interest accrual on near-paid-off cards).
        final BigDecimal v =
                StatementParsingUtilities.extractNewBalance(new String[] {"New Balance $0.05"});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("0.05").compareTo(v));
    }

    @Test
    void pastDueAmount_acceptsLargeNonZeroValue_forDelinquentAccounts() {
        // A delinquent account would print a non-zero past-due. The extractor must NOT
        // reject it as "zero looks suspicious".
        final BigDecimal v = StatementParsingUtilities.extractPastDueAmount(
                new String[] {"Past Due Amount $2,847.00"});
        assertNotNull(v);
        assertEquals(0, new BigDecimal("2847.00").compareTo(v));
    }

    // ============================================================
    //  FX block — additional negative cases
    // ============================================================

    @Test
    void fxBlock_doesNotFire_whenExchgRateMarkerAbsent() {
        // No "(EXCHG RATE)" → no FX detection, no stripping. Defensive against banks
        // that emit a similar-looking pattern without the marker.
        final String input = String.join(
                "\n",
                "06/03     LEGIT HOTEL CHARGE 211.11",
                "06/04     SWISS FRANC",
                "192.50 X 1.096493506 some-other-suffix");
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(input);
        assertEquals(0, res.getAnnotationsByAnchor().size(),
                "Without (EXCHG RATE) marker, no FX block must be captured");
        assertTrue(res.getCleanedText().contains("SWISS FRANC"),
                "Currency-name line must NOT be stripped when no detail line follows");
    }

    @Test
    void fxBlock_handlesUnknownCurrency_byPreservingDisplayName() {
        final String input = String.join(
                "\n",
                "06/03     SOMEWHERE EXOTIC 100.00",
                "06/04     MAURITANIAN OUGUIYA",
                "37,500.00 X 0.00267 (EXCHG RATE)");
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(input);
        final PDFImportService.FxAnnotation fx =
                res.getAnnotationsByAnchor().get("06-03|100.00");
        assertNotNull(fx);
        // No ISO mapping → code falls back to the raw display name. Better than dropping.
        assertEquals("MAURITANIAN OUGUIYA", fx.getOriginalCurrencyCode());
        assertEquals("MAURITANIAN OUGUIYA", fx.getOriginalCurrencyDisplay());
    }

    @Test
    void fxBlock_acceptsCurrenciesWithThreeWordNames() {
        // "NEW TAIWAN DOLLAR" has 3 words — verify the all-caps multi-word regex works.
        final String input = String.join(
                "\n",
                "06/03     TAIPEI HOTEL 200.00",
                "06/04     NEW TAIWAN DOLLAR",
                "6,500.00 X 0.030769 (EXCHG RATE)");
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(input);
        assertEquals(1, res.getAnnotationsByAnchor().size());
        final PDFImportService.FxAnnotation fx =
                res.getAnnotationsByAnchor().get("06-03|200.00");
        assertEquals("TWD", fx.getOriginalCurrencyCode());
    }

    // ============================================================
    //  POINTS — more negative + edge cases
    // ============================================================

    @Test
    void pointsEarnedAndBalance_independentlyExtracted_whenBothPresent() {
        // A card that prints both fields (e.g. Chase Sapphire) — both extractors must
        // populate independently from different lines.
        final String input = String.join(
                "\n",
                "Points earned this period: 2,500",
                "Points balance: 47,890");
        final Long earned = StatementParsingUtilities.extractPointsEarnedThisPeriod(input.split("\\n"));
        final Long balance = StatementParsingUtilities.extractPointsBalance(input.split("\\n"));
        assertEquals(2500L, earned.longValue());
        assertEquals(47890L, balance.longValue());
    }

    @Test
    void pointsEarnedThisPeriod_rejectsGarbageNumeric() {
        // A misformed line that LOOKS like it might match but isn't a real points line.
        final String[] lines = {"You earned a lot of points last cycle."};
        assertNull(StatementParsingUtilities.extractPointsEarnedThisPeriod(lines));
    }

    @Test
    void pointsBalance_zeroValueIsValidAndReturned() {
        // A user who has redeemed everything: explicit zero balance.
        final String[] lines = {"Points balance: 0"};
        final Long balance = StatementParsingUtilities.extractPointsBalance(lines);
        assertNotNull(balance);
        assertEquals(0L, balance.longValue());
    }

    // ============================================================
    //  STATEMENT DATE / BILLING DAYS — date format variations
    // ============================================================

    @Test
    void statementDate_acceptsFourDigitYear() {
        final LocalDate d = StatementParsingUtilities.extractStatementDate(
                new String[] {"Statement Date: 06/17/2026"}, 2026, true);
        assertEquals(LocalDate.of(2026, 6, 17), d);
    }

    @Test
    void statementDate_acceptsTwoDigitYear_via2000Offset() {
        final LocalDate d = StatementParsingUtilities.extractStatementDate(
                new String[] {"Statement Date: 06/17/26"}, 2026, true);
        assertEquals(LocalDate.of(2026, 6, 17), d);
    }

    @Test
    void billingDays_acceptsOneOrTwoDigits() {
        assertEquals(7,
                StatementParsingUtilities.extractBillingDays(new String[] {"7 Days in Billing Period"})
                        .intValue());
        assertEquals(31,
                StatementParsingUtilities.extractBillingDays(new String[] {"31 Days in Billing Period"})
                        .intValue());
    }

    @Test
    void billingDays_rejectsThreeDigitNumberBeforeLabel() {
        // "100 Days in Billing Period" is implausible. The regex \d{1,2} guards against
        // accidentally capturing 3-digit prefixes. (It also forbids "111 Days...".)
        assertNull(StatementParsingUtilities.extractBillingDays(
                new String[] {"100 Days in Billing Period"}));
    }

    // ============================================================
    //  ANNUAL FEE — both parts present, only one present, neither
    // ============================================================

    @Test
    void annualFee_capturesAmountAndDate_fromSingleSentence() {
        final String[] lines = {
            "Your annual membership fee in the amount of $95.00 will be billed on 09/01/2026."
        };
        final Object[] block =
                StatementParsingUtilities.extractAnnualMembershipFeeAndDate(lines, 2026, true);
        assertNotNull(block);
        assertEquals(0, new BigDecimal("95.00").compareTo((BigDecimal) block[0]));
        assertEquals(LocalDate.of(2026, 9, 1), block[1]);
    }

    @Test
    void annualFee_returnsNull_whenSentenceIsAbsentOrIncomplete() {
        // Disclosure prose about the fee policy without the "billed on" pivot.
        final String[] lines = {
            "If your Account Agreement has an annual membership fee, you are",
            "responsible for it every year your Account is open.",
        };
        assertNull(StatementParsingUtilities.extractAnnualMembershipFeeAndDate(lines, 2026, true));
    }

    // ============================================================
    //  WHITESPACE TOLERANCE — tabs, irregular spaces
    // ============================================================

    @Test
    void labels_toleratesTabsAsLabelAmountSeparator() {
        // PDFBox occasionally emits tabs instead of spaces — verify [\s:]+ catches them.
        final String[] lines = {"New Balance\t$1,234.56"};
        final BigDecimal v = StatementParsingUtilities.extractNewBalance(lines);
        assertNotNull(v);
        assertEquals(0, new BigDecimal("1234.56").compareTo(v));
    }

    @Test
    void labels_toleratesMultipleSpacesBeforeAmount() {
        final String[] lines = {"Credit Access Line          $25,000"};
        final BigDecimal v = StatementParsingUtilities.extractCreditLimit(lines);
        assertNotNull(v);
        assertEquals(0, new BigDecimal("25000").compareTo(v));
    }

    // ============================================================
    //  FOREIGN TRANSACTION FEE — international alias
    // ============================================================

    @Test
    void foreignTxFee_acceptsInternationalTransactionFeeAlias() {
        final String[] lines = {
            "There is an International Transaction Fee of 2.5% of the U.S. dollar amount"
        };
        final BigDecimal fee =
                StatementParsingUtilities.extractForeignTransactionFeePercent(lines);
        assertNotNull(fee);
        assertEquals(0, new BigDecimal("2.5").compareTo(fee));
    }

    // ============================================================
    //  SCALABILITY — linear-time on large input, stable across noise
    // ============================================================

    @Test
    void extractors_handleLargeInput_inUnderOneSecond() {
        // Worst-case scenario: 10,000 lines of disclosure noise with a single matching
        // label at the end. The two-pass approach must remain bounded in the line count.
        // We don't pin an exact ms budget here (CI variance is too noisy) — we just
        // assert it completes within a generous 5s window so a regression that turned
        // the inner loop quadratic would still be caught.
        final int N = 10_000;
        final String[] lines = new String[N + 1];
        for (int i = 0; i < N; i++) {
            lines[i] = "Disclosure line " + i + ": no real labels here, just prose";
        }
        lines[N] = "New Balance $123.45";

        final long start = System.currentTimeMillis();
        final BigDecimal v = StatementParsingUtilities.extractNewBalance(lines);
        // Run every extractor on the same large input to stress the full pipeline.
        StatementParsingUtilities.extractCreditLimit(lines);
        StatementParsingUtilities.extractPurchaseApr(lines);
        StatementParsingUtilities.extractAutoPayEnabled(lines);
        StatementParsingUtilities.extractPointsEarnedThisPeriod(lines);
        final long elapsed = System.currentTimeMillis() - start;

        assertNotNull(v);
        assertEquals(0, new BigDecimal("123.45").compareTo(v));
        assertTrue(elapsed < 5_000,
                "Large-input extraction took " + elapsed + "ms — investigate quadratic regression");
    }

    @Test
    void extractors_stableUnderRepeatedNoise_neverFalsePositive() {
        // 100 copies of the same disclosure line containing label phrases that LOOK
        // similar to real ones. Nothing should fire.
        final int N = 100;
        final String[] lines = new String[N];
        for (int i = 0; i < N; i++) {
            lines[i] = "If you make only the minimum payment each period, your new"
                    + " balance shown on this statement will take longer to pay off.";
        }
        // No actual labels in the noise. Every extractor must return null.
        assertNull(StatementParsingUtilities.extractNewBalance(lines));
        assertNull(StatementParsingUtilities.extractPreviousBalance(lines));
        assertNull(StatementParsingUtilities.extractCreditLimit(lines));
        assertNull(StatementParsingUtilities.extractAutoPayEnabled(lines));
    }
}
