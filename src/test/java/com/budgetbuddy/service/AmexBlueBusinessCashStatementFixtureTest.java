package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.AmericanExpressIssuerProfile;
import com.budgetbuddy.service.pdf.profile.IssuerProfile;
import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Fixture for an American Express Blue Business Cash credit-card statement.
 * Distinct from {@link AmexPlatinumStatementFixtureTest} because Blue
 * Business Cash uses a different layout:
 *
 * <ul>
 *   <li><b>"Account Summary"</b> stacked block with 5 labels (Previous Balance,
 *       Payments/Credits, New Charges, Fees, Interest Charged) followed by 5
 *       signed-dollar values. Charge cards (Platinum) use a different 6-label
 *       "Account Total" block — see {@link AmexPlatinumStatementFixtureTest}.
 *   <li><b>"Credit Limit"</b> + <b>"Amount Above the Credit Limit"</b> stacked
 *       labels (vs. "Pay Over Time Limit" on charge cards). The first value
 *       below the pair is the credit limit.
 *   <li><b>"monthly AutoPay payment"</b> wording — the older Platinum disclosure
 *       just said "payment of $X" but Blue Business added "monthly AutoPay"
 *       between "your" and "payment of". The Amex profile's autopay regex
 *       accepts both forms.
 *   <li><b>Account-ending suffix line</b>: "AGARWAL SUMIT KUMAR 1-21002 -$28.36"
 *       summary rows for credit blocks. The "1-21002" shape mimics a 1/21 date
 *       to the DATE_PATTERN matcher and gets filtered by AMEX_ACCOUNT_SUFFIX_
 *       PATTERN at the orchestrator level.
 * </ul>
 *
 * <p>Values are synthetic; no real PII.
 */
class AmexBlueBusinessCashStatementFixtureTest {

    private final AmericanExpressIssuerProfile profile = new AmericanExpressIssuerProfile();
    private final IssuerProfile.ExtractionContext ctx =
            new IssuerProfile.ExtractionContext(2026, true);

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "American Express Blue Business Cash°",
                    "TEST CARDHOLDER",
                    "Closing Date 02/16/26",
                    "Account Ending 1-21002",
                    "Payment Due Date",
                    "03/13/26",
                    "New Balance",
                    "$26,182.25",
                    "AutoPay Amount",
                    "$1,432.00",
                    "We will  debit your bank account for your monthly AutoPay payment of "
                            + "$1,432.00 on 03/03/26.",
                    "New Balance $26,182.25",
                    "Minimum Payment Due $1,432.00",
                    "Payment Due Date 03/13/26",
                    "Account Summary",
                    "Previous Balance",
                    "Payments/Credits",
                    "New Charges",
                    "Fees",
                    "Interest Charged",
                    "$26,145.56",
                    "-$1,424.36",
                    "+$1,461.05",
                    "+$0.00",
                    "+$0.00",
                    "Credit Limit",
                    "Amount Above the Credit Limit",
                    "$25,000.00",
                    "$1,182.25",
                    "Days in Billing Period: 28",
                    "p. 1/9");

    private String[] lines() {
        return STATEMENT_FIXTURE.split("\\n");
    }

    @Test
    void blueBusinessCash_extractsClosingDateAndDueDate() {
        assertEquals(LocalDate.of(2026, 2, 16),
                StatementParsingUtilities.extractStatementDate(lines(), 2026, true));
    }

    @Test
    void blueBusinessCash_stackedAccountSummary_extractsAllFiveValues() {
        final String[] l = lines();
        assertEquals(0, new BigDecimal("26145.56")
                .compareTo(profile.extractPreviousBalance(l, ctx)),
                "previousBalance = Account Summary index 0");
        assertEquals(0, new BigDecimal("1461.05")
                .compareTo(profile.extractPurchasesTotal(l, ctx)),
                "purchasesTotal = Account Summary index 2 (New Charges)");
        // Payments/Credits is COMBINED ($1,424.36 = $1,396 payment + $28.36 credit).
        // The Amex override prefers the stacked value over the bare "Payments -$X"
        // inline form so cashback credits aren't dropped.
        assertEquals(0, new BigDecimal("1424.36")
                .compareTo(profile.extractPaymentsAndCreditsTotal(l, ctx)),
                "paymentsAndCreditsTotal = abs(Account Summary index 1)");
        assertEquals(0, BigDecimal.ZERO.compareTo(profile.extractFeesChargedTotal(l, ctx)));
        assertEquals(0, BigDecimal.ZERO.compareTo(profile.extractInterestChargedTotal(l, ctx)));
    }

    @Test
    void blueBusinessCash_stackedCreditLimit_extractsFirstValueOnly() {
        // "Credit Limit / Amount Above the Credit Limit / $25,000.00 / $1,182.25"
        // — only the first value is the credit limit.
        assertEquals(0, new BigDecimal("25000.00")
                .compareTo(profile.extractCreditLimit(lines(), ctx)));
    }

    @Test
    void blueBusinessCash_minimumPaymentDue_picksInlineFormFromPreStitchText() throws Exception {
        // The stacked layout has "New Balance / Minimum Payment Due / $26,182.25 / $1,432.00".
        // Without the pre-stitch fix, regex would greedily match
        // "Minimum Payment Due $26,182.25" from the stitched mega-line — picking
        // the wrong value. The orchestrator now uses pre-stitch text for metadata,
        // and the line "Minimum Payment Due $1,432.00" is the correct hit.
        // extractMinimumPaymentDue is private on PDFImportService — invoke via reflection.
        final java.lang.reflect.Method m =
                PDFImportService.class.getDeclaredMethod(
                        "extractMinimumPaymentDue", String[].class);
        m.setAccessible(true);
        final PDFImportService svc =
                new PDFImportService(
                        org.mockito.Mockito.mock(AccountDetectionService.class),
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        new EnhancedPatternMatcher(),
                        null);
        final BigDecimal result = (BigDecimal) m.invoke(svc, (Object) lines());
        assertEquals(0, new BigDecimal("1432.00").compareTo(result));
    }

    @Test
    void blueBusinessCash_autoPayEnabled_recognizesMonthlyAutoPayWording() {
        // Older Platinum disclosure said "payment of $X". Blue Business added
        // "monthly AutoPay" between "your" and "payment of". The AMEX_AUTOPAY_DEBIT
        // regex was widened to accept both.
        assertEquals(Boolean.TRUE, profile.extractAutoPayEnabled(lines(), ctx));
        assertEquals(0, new BigDecimal("1432.00")
                .compareTo(profile.extractNextAutoPayAmount(lines(), ctx)));
    }

    @Test
    void blueBusinessCash_statementMath_balances() {
        // previousBalance - paymentsAndCredits + newCharges + fees + interest = newBalance
        // 26145.56 - 1424.36 + 1461.05 + 0 + 0 = 26182.25
        final String[] l = lines();
        final BigDecimal computed = profile.extractPreviousBalance(l, ctx)
                .subtract(profile.extractPaymentsAndCreditsTotal(l, ctx))
                .add(profile.extractPurchasesTotal(l, ctx))
                .add(profile.extractFeesChargedTotal(l, ctx))
                .add(profile.extractInterestChargedTotal(l, ctx));
        assertEquals(0, new BigDecimal("26182.25").compareTo(computed),
                "Account Summary math must balance to NewBalance ($26,182.25)");
    }

    @Test
    void blueBusinessCash_purchaseAprFallback_whenNoPayOverTime() {
        // Blue Business Cash doesn't have a "Pay Over Time" row — the Amex
        // override's first branch returns null and the shared utility's
        // "Purchases 19.49%" pattern handles it. Add a dedicated APR row to
        // verify the fallback wires up correctly.
        final String[] withApr = (STATEMENT_FIXTURE + "\nPurchases 19.49%(v)(d)").split("\\n");
        assertEquals(0, new BigDecimal("19.49")
                .compareTo(profile.extractPurchaseApr(withApr, ctx)));
    }

    @Test
    void blueBusinessCash_payOverTimeApr_isPreferredWhenPresent() {
        // Verify the new "Pay Over Time" purchase-APR extractor works even
        // when the legacy "Purchases X%" line is also present. Pay Over Time
        // wins because it's checked first.
        final String[] both =
                (STATEMENT_FIXTURE + "\nPay Over Time 12/30/2022 19.49% (v) $0.00 $0.00\n"
                        + "Purchases 22.00%(v)(d)").split("\\n");
        assertEquals(0, new BigDecimal("19.49")
                .compareTo(profile.extractPurchaseApr(both, ctx)),
                "Pay Over Time APR must take precedence over Purchases APR for Amex");
    }

    @Test
    void blueBusinessCash_pointsBalance_isNullForCashBackCard() {
        // Cash-back card — no Membership Rewards points printed. After the
        // RewardExtractor tightening (MULTI_LINE_REWARD_LINE1 no longer matches
        // bare "total"/"available"), this returns null instead of a random
        // dollar amount.
        assertNull(profile.extractPointsBalance(lines(), ctx),
                "Blue Business Cash doesn't print Membership Rewards points");
    }
}
