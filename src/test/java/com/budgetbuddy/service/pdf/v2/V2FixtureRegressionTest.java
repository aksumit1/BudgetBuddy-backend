package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * v2-side coverage for the synthetic fixtures previously asserted only against
 * the legacy IssuerProfile chain (CitiDoubleCashStatementFixtureTest,
 * AmexBlueBusinessCashStatementFixtureTest, AmexPlatinumStatementFixtureTest).
 *
 * <p>The legacy fixture tests stay until the IssuerProfile classes themselves
 * are deleted; this test pins the same business values on the v2 path so a
 * YAML regression — wrong rule order, missing label, broken regex — fails CI.
 * Without it, "all tests pass" would mean only that the legacy code still
 * works; the v2 chain that the production parser actually runs first could
 * silently rot.
 */
class V2FixtureRegressionTest {

    private static final PdfTemplateV2Evaluator EVAL = new PdfTemplateV2Evaluator();

    private static PdfTemplateV2 load(final String resourcePath) throws Exception {
        try (InputStream is = V2FixtureRegressionTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "YAML resource missing on classpath: " + resourcePath);
            return new ObjectMapper(new YAMLFactory()).readValue(is, PdfTemplateV2.class);
        }
    }

    // ============================================================
    // Citi Double Cash fixture — formerly only exercised by
    // CitiDoubleCashStatementFixtureTest against StatementParsingUtilities.
    // ============================================================

    private static final String CITI_DOUBLE_CASH_FIXTURE = String.join(
            "\n",
            "TEST CARDHOLDER",
            "Citi Double Cash Card",
            "Member Since 2015. Account number ending in: 1111",
            "Billing Period: 03/04/26-04/02/26",
            "New balance as of 04/02/26:  $1,038.02",
            "Minimum payment due:  $41.00",
            "Payment due date:  04/28/26",
            "Total Available  ThankYou  Points:",
            "21,371  as of 03/31/26",
            "Your next AutoPay payment of $1,038.02 will be",
            " deducted from your bank account on 04/28/2026.",
            "Account Summary",
            "Previous Balance  $3,110.20",
            "Payments -$3,110.20",
            "Credits -$0.00",
            "Purchases  +$1,038.02",
            "Cash advances  +$0.00",
            "Fees  +$0.00",
            "Interest  +$0.00",
            "New balance  $1,038.02",
            "Credit Limit  $18,500.00",
            "Available Credit  $17,461.00",
            "Total fees charged in this billing period  $0.00",
            "Total interest charged in this billing period  $0.00",
            "2026 totals year-to-date",
            "Total fees charged in 2026  $0.00",
            "Total interest charged in 2026  $0.00",
            "Days in billing cycle: 30",
            " PURCHASES",
            " Standard Purch 19.49% (V) $0.00 (D) $0.00",
            " ADVANCES",
            " Standard Adv 29.74% (V) $0.00 (D) $0.00",
            "Total ThankYou Points Balance",
            "21,371",
            "ThankYou Points Earned:  1,038");

    @Test
    void v2_citi_coreSummary() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, CITI_DOUBLE_CASH_FIXTURE);
        assertNotNull(m, "evaluator must return result");
        assertEquals(0, new BigDecimal("1038.02").compareTo(m.newBalance));
        assertEquals(0, new BigDecimal("3110.20").compareTo(m.previousBalance));
        assertEquals(0, new BigDecimal("1038.02").compareTo(m.purchasesTotal));
        // payments_total_sum: true — sums "Payments -$X" + "Credits -$X" + "Adjustments".
        // Both rows are $3110.20 + $0.00 → 3110.20.
        assertEquals(0, new BigDecimal("3110.20").compareTo(m.paymentsAndCreditsTotal));
        assertEquals(0, new BigDecimal("18500.00").compareTo(m.creditLimit));
        assertEquals(0, new BigDecimal("17461.00").compareTo(m.availableCredit));
        assertEquals(0, new BigDecimal("41.00").compareTo(m.minimumPaymentDue));
    }

    @Test
    void v2_citi_aprBlock_standardPurchAndAdvLabels() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, CITI_DOUBLE_CASH_FIXTURE);
        assertEquals(0, new BigDecimal("19.49").compareTo(m.purchaseApr),
                "Citi 'Standard Purch 19.49%' must map to purchase APR");
        assertEquals(0, new BigDecimal("29.74").compareTo(m.cashAdvanceApr),
                "Citi 'Standard Adv 29.74%' must map to cash-advance APR");
    }

    @Test
    void v2_citi_pointsBalanceMultiLine() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, CITI_DOUBLE_CASH_FIXTURE);
        assertEquals(Long.valueOf(21_371L), m.pointsBalance,
                "Bare 'Total ThankYou Points Balance' label with value on next line");
        assertEquals(Long.valueOf(1_038L), m.pointsEarned);
    }

    @Test
    void v2_citi_autopaySentenceAnchor() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, CITI_DOUBLE_CASH_FIXTURE);
        assertEquals(Boolean.TRUE, m.autopayEnabled,
                "Citi 'Your next AutoPay payment of $X' only prints when enrolled");
        assertEquals(0, new BigDecimal("1038.02").compareTo(m.nextAutopayAmount));
    }

    @Test
    void v2_citi_ytdTotalsAndBillingDays() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, CITI_DOUBLE_CASH_FIXTURE);
        assertEquals(0, BigDecimal.ZERO.compareTo(m.ytdFees));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.ytdInterest));
        assertEquals(Integer.valueOf(30), m.billingDays);
    }

    // ============================================================
    // Amex Blue Business Cash fixture — Account Summary 5-label stack +
    // Credit Limit / Amount Above stack + monthly-AutoPay disclosure.
    // ============================================================

    private static final String AMEX_BLUE_BUSINESS_FIXTURE = String.join(
            "\n",
            "American Express Blue Business Cash",
            "TEST CARDHOLDER",
            "Closing Date 02/16/26",
            "Account Ending 1-21002",
            "Payment Due Date 03/13/26",
            "We will  debit your bank account for your monthly AutoPay payment of "
                    + "$1,432.00 on 03/03/26.",
            "New Balance $26,182.25",
            "Minimum Payment Due $1,432.00",
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
            "Days in Billing Period: 28");

    @Test
    void v2_amexBlueBusiness_stackedAccountSummary() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_BLUE_BUSINESS_FIXTURE);
        assertEquals(0, new BigDecimal("26145.56").compareTo(m.previousBalance),
                "previousBalance = Account Summary stacked index 0");
        // New Charges is at stacked index 2 — purchases.
        assertEquals(0, new BigDecimal("1461.05").compareTo(m.purchasesTotal),
                "purchasesTotal = Account Summary stacked index 2 (New Charges)");
        // Payments/Credits combined; v2 returns the signed value (-1424.36).
        assertEquals(0, new BigDecimal("-1424.36").compareTo(m.paymentsAndCreditsTotal),
                "paymentsAndCreditsTotal = Account Summary stacked index 1");
        assertEquals(0, BigDecimal.ZERO.compareTo(m.feesTotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.interestTotal));
    }

    @Test
    void v2_amexBlueBusiness_creditLimitOverLimitVariant_picksFirstValueOnly() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_BLUE_BUSINESS_FIXTURE);
        assertEquals(0, new BigDecimal("25000.00").compareTo(m.creditLimit),
                "Credit Limit / Amount Above stacked layout: first $-value is credit limit");
        // CRITICAL: $1,182.25 is amount-above, NOT availableCredit.
        assertNull(m.availableCredit,
                "Over-limit stacked layout must NOT yield $1,182.25 as availableCredit");
    }

    @Test
    void v2_amexBlueBusiness_autopayDisclosure_acceptsExtraSpaceAndMonthlyWording() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_BLUE_BUSINESS_FIXTURE);
        assertEquals(Boolean.TRUE, m.autopayEnabled,
                "Blue Business AutoPay disclosure has two spaces in 'We will  debit' and "
                        + "'monthly AutoPay' qualifier — both variants must match");
        assertEquals(0, new BigDecimal("1432.00").compareTo(m.nextAutopayAmount));
    }

    @Test
    void v2_amexBlueBusiness_pointsBalance_isNullForCashbackCard() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_BLUE_BUSINESS_FIXTURE);
        assertNull(m.pointsBalance,
                "Cashback cards don't print Membership Rewards points — must stay null");
    }

    // ============================================================
    // Amex Platinum fixture — 6-label Account Total stack + Pay Over Time
    // stacked credit limit + 3-line Membership Rewards points block.
    // ============================================================

    private static final String AMEX_PLATINUM_FIXTURE = String.join(
            "\n",
            "Morgan Stanley Platinum Card",
            "TEST CARDHOLDER",
            "Closing Date 05/13/26",
            "Account Ending 8-12345",
            "Payment Due Date 06/07/26",
            "We will debit your bank account for your payment of $978.49 on 05/28/26.",
            "New Balance $978.49",
            "Minimum Payment Due $40.00",
            "Membership Rewards Points",
            "Available and Pending as of 04/30/26",
            "           89,096  ",
            "Account Total",
            "Previous Balance",
            "Payments/Credits",
            "New Charges",
            "New Cash Advances",
            "Fees",
            "Interest Charged",
            "$2,581.31",
            "-$2,628.73",
            "+$1,025.91",
            "+$0.00",
            "+$0.00",
            "+$0.00",
            "Pay Over Time Limit",
            "Available Pay Over Time Limit",
            "$35,000.00",
            "$34,021.51",
            "Days in Billing Period: 30");

    @Test
    void v2_amexPlatinum_accountTotal_sixLabelStack() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        assertEquals(0, new BigDecimal("2581.31").compareTo(m.previousBalance));
        assertEquals(0, new BigDecimal("1025.91").compareTo(m.purchasesTotal),
                "purchasesTotal = Account Total stacked index 2 (New Charges)");
        assertEquals(0, new BigDecimal("-2628.73").compareTo(m.paymentsAndCreditsTotal),
                "paymentsAndCreditsTotal = Account Total stacked index 1");
        assertEquals(0, BigDecimal.ZERO.compareTo(m.feesTotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.interestTotal));
    }

    @Test
    void v2_amexPlatinum_payOverTimeStackedCreditLimit() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        assertEquals(0, new BigDecimal("35000.00").compareTo(m.creditLimit),
                "Pay Over Time Limit / Available Pay Over Time Limit: first $-value = credit limit");
        assertEquals(0, new BigDecimal("34021.51").compareTo(m.availableCredit),
                "Available Pay Over Time Limit stacked: second $-value = available credit");
    }

    @Test
    void v2_amexPlatinum_membershipRewardsPoints_threeLineBlock() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        assertEquals(Long.valueOf(89_096L), m.pointsBalance,
                "3-line layout: 'Membership Rewards Points' / 'Available and Pending as of MM/DD/YY' / N");
    }

    @Test
    void v2_amexPlatinum_autopayDisclosure() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        assertEquals(Boolean.TRUE, m.autopayEnabled);
        assertEquals(0, new BigDecimal("978.49").compareTo(m.nextAutopayAmount));
    }

    @Test
    void v2_amexPlatinum_billingDays() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        assertEquals(Integer.valueOf(30), m.billingDays);
    }

    // ============================================================
    // Sanity: the math identity that 42-PDF audit verifies must also hold for
    // the synthetic Amex Platinum fixture when computed from v2 outputs alone.
    // prev - paymentsAndCredits + purchases + ... = newBalance
    // 2581.31 - 2628.73 + 1025.91 + 0 + 0 + 0 = 978.49 ✓
    // ============================================================
    // ============================================================
    // Sweep regressions on v2 — same root causes the legacy
    // SweepLearningsRegressionTest pins, now asserted via v2.
    // ============================================================

    @Test
    void v2_citi_paymentsAndCreditsSum_acrossSeparateRows() throws Exception {
        // Real bug: Citi prints "Payments -$X" and "Credits -$X" as two rows;
        // paymentsAndCreditsTotal must SUM both. v2 expresses this via
        // payments_total_sum: true. Pre-fix, math identity failed by exactly
        // the Credits amount.
        final String fixture = String.join("\n",
                "Account Summary",
                "Previous Balance  $1,708.54",
                "Payments -$1,670.03",
                "Credits -$38.51",
                "Purchases  +$5,103.68",
                "Fees  +$0.00",
                "Interest  +$0.00",
                "New balance  $5,103.68");
        final PdfTemplateV2 t = load("/pdf-templates-v2/citi.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, fixture);
        assertEquals(0, new BigDecimal("1708.54").compareTo(m.paymentsAndCreditsTotal),
                "payments_total_sum must add Payments (1670.03) + Credits (38.51)");
    }

    @Test
    void v2_usb_interestSignWithSpace_extracted() throws Exception {
        // Real bug: US Bank prints "Interest Charged + $79.99" — sign separated
        // from $ by whitespace. v2 must accept either "+ $79.99" or "+$79.99".
        final String fixture = String.join("\n",
                "Account Summary",
                "Previous Balance + $20,574.32",
                "Payments + $20,574.32",
                "Purchases $0.00",
                "Interest Charged + $79.99",
                "New Balance $79.99");
        final PdfTemplateV2 t = load("/pdf-templates-v2/us-bank.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, fixture);
        assertEquals(0, new BigDecimal("79.99").compareTo(m.interestTotal),
                "v2 must extract '+ $79.99' (sign-with-space) interest total");
    }

    @Test
    void v2_amexPlatinum_mathIdentity() throws Exception {
        final PdfTemplateV2 t = load("/pdf-templates-v2/american-express.yaml");
        final PdfTemplateV2Evaluator.MetadataResult m = EVAL.evaluateMetadata(t, AMEX_PLATINUM_FIXTURE);
        final BigDecimal computed = m.previousBalance
                .add(m.paymentsAndCreditsTotal)
                .add(m.purchasesTotal)
                .add(m.feesTotal)
                .add(m.interestTotal);
        assertEquals(0, new BigDecimal("978.49").compareTo(computed),
                "v2 Account Total math identity must reconcile to NewBalance");
        assertTrue(m.previousBalance.signum() > 0, "previousBalance > 0");
    }
}
