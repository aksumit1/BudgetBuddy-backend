package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * End-to-end fixture coverage for a Chase Marriott Bonvoy Premier statement. The fixture
 * below mirrors the LAYOUT and LABEL phrasing Chase prints, but every transaction, balance,
 * date, account-number, and personal name is synthetic. DO NOT replace fixture values with
 * data from a real statement.
 *
 * <p>This test asserts three layers at once so a regression in any of them surfaces here
 * rather than days later in production:
 *
 * <ol>
 *   <li><b>FX strip + capture</b> — every {@code (EXCHG RATE)} block produces an {@link
 *       PDFImportService.FxAnnotation} anchored to its parent USD purchase.
 *   <li><b>Statement summary extraction</b> — newBalance, previousBalance, creditLimit,
 *       availableCredit, pastDueAmount each pick up their respective Chase-labeled value.
 *   <li><b>Account detection</b> — the statement is recognised as Chase Marriott Bonvoy
 *       Premier with the synthetic last 4.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MarriottBonvoyStatementFixtureTest {

    @Mock private AccountRepository accountRepository;
    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        // Empty existing-accounts list — so detection runs purely off PDF content.
        when(accountRepository.findByUserId(anyString())).thenReturn(Collections.emptyList());
        accountDetectionService =
                new AccountDetectionService(accountRepository, new BalanceExtractor());
    }

    /**
     * Synthetic statement text that reproduces the Chase Marriott Bonvoy Premier layout
     * (headers, label phrasing, FX-block format) WITHOUT any real account data. The numbers
     * below are chosen so that:
     *   - newBalance != previousBalance (catches cross-wiring),
     *   - creditLimit != cashAccessLine (catches label-collision),
     *   - the FX block uses CHF / EUR (covers the currency-name lookup path).
     */
    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Manage your account online: Mobile:  Download the",
                    "Chase Mobile app today® ",
                    "ACCOUNT SUMMARY",
                    "YOUR ACCOUNT MESSAGES",
                    "AUTOPAY IS ON",
                    "Payment Due Date: 07/14/26",
                    "New Balance: $999.99",
                    "Minimum Payment Due: $25.00",
                    "Account Number:  XXXX XXXX XXXX 4242",
                    "New Balance $999.99",
                    "Past Due Amount $0.00",
                    "Balance over the Credit Access Line $0.00",
                    "Previous Balance $2,500.00",
                    "Payment, Credits -$2,500.00",
                    "Purchases +$999.99",
                    "Cash Advances $0.00",
                    "Balance Transfers $0.00",
                    "Fees Charged $0.00",
                    "Interest Charged $0.00",
                    "Opening/Closing Date 05/18/26 - 06/17/26",
                    "Credit Access Line $50,000",
                    "Available Credit $49,000",
                    "Cash Access Line $7,500",
                    "Available for Cash $7,500",
                    "+ 5X Points on Marriott Bonvoy purchases 4,500",
                    "Total points transferred to Marriott Bonvoy 4,500",
                    "Thank you for using your Marriott Bonvoy® Premier Credit Card.",
                    "MARRIOTT BONVOY PREMIER",
                    "SYNTHETIC USER NAME",
                    "ACCOUNT ACTIVITY",
                    "PAYMENTS AND OTHER CREDITS",
                    "06/14     AUTOMATIC PAYMENT - THANK YOU -2,500.00",
                    "PURCHASE",
                    "06/03     SYNTHETIC HOTEL ZURICH 211.11",
                    "06/04    SWISS FRANC             ",
                    "192.50 X 1.096493506 (EXCHG RATE) ",
                    "06/15     FAKE BISTRO MUNICH 88.50",
                    "06/16    EURO             ",
                    "78.45 X 1.127979605 (EXCHG RATE) ",
                    "Statement Date: 06/17/26",
                    "31 Days in Billing Period",
                    "Penalty APR of 29.99%.",
                    "Purchases 19.49%(v)(d) - 0 -   - 0 -",
                    "Cash Advances 28.49%(v)(d) - 0 -   - 0 -",
                    "Balance Transfers 19.49%(v)(d) - 0 -   - 0 -",
                    "My Chase Loan 19.49%(v)(d) - 0 -   - 0 -",
                    "Your annual membership fee in the amount of $99.00 will be billed on"
                            + " 07/01/2026. There is a transaction fee for each balance transfer.",
                    "There is a foreign transaction fee of 3% of the U.S. dollar amount"
                            + " of any foreign transaction for some accounts.",
                    "Your next AutoPay payment for $999.99 will be deducted from your Pay From"
                            + " account and credited on your due date.");

    // ---------- FX block: strip + capture ----------

    @Test
    void chaseMarriottBonvoyFixture_stripsBothFxBlocks_capturesBothAnchors() {
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(STATEMENT_FIXTURE);

        assertTrue(!res.getCleanedText().contains("EXCHG RATE"),
                "FX detail lines must be stripped");
        assertTrue(!res.getCleanedText().contains("SWISS FRANC"),
                "FX header lines must be stripped");

        assertEquals(2, res.getAnnotationsByAnchor().size());
        final PDFImportService.FxAnnotation zurich =
                res.getAnnotationsByAnchor().get("06-03|211.11");
        assertNotNull(zurich, "06-03 Zurich block must anchor to the $211.11 parent");
        assertEquals("CHF", zurich.getOriginalCurrencyCode());
        assertEquals(0, new BigDecimal("192.50").compareTo(zurich.getOriginalAmount()));

        final PDFImportService.FxAnnotation munich =
                res.getAnnotationsByAnchor().get("06-15|88.50");
        assertNotNull(munich, "06-15 Munich block must anchor to the $88.50 parent");
        assertEquals("EUR", munich.getOriginalCurrencyCode());
    }

    // ---------- statement-summary metadata ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsAllStatementSummaryFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        final BigDecimal newBalance = StatementParsingUtilities.extractNewBalance(lines);
        assertNotNull(newBalance, "New Balance must be extracted");
        assertEquals(0, new BigDecimal("999.99").compareTo(newBalance));

        final BigDecimal previousBalance = StatementParsingUtilities.extractPreviousBalance(lines);
        assertNotNull(previousBalance, "Previous Balance must be extracted");
        assertEquals(0, new BigDecimal("2500.00").compareTo(previousBalance));

        final BigDecimal creditLimit = StatementParsingUtilities.extractCreditLimit(lines);
        assertNotNull(creditLimit,
                "Credit Limit must be extracted (Chase labels it 'Credit Access Line')");
        assertEquals(0, new BigDecimal("50000").compareTo(creditLimit));

        final BigDecimal availableCredit = StatementParsingUtilities.extractAvailableCredit(lines);
        assertNotNull(availableCredit, "Available Credit must be extracted");
        assertEquals(0, new BigDecimal("49000").compareTo(availableCredit));

        final BigDecimal pastDue = StatementParsingUtilities.extractPastDueAmount(lines);
        assertNotNull(pastDue, "Past Due Amount must be extracted (even when $0.00)");
        assertEquals(0, BigDecimal.ZERO.compareTo(pastDue));
    }

    @Test
    void chaseMarriottBonvoyFixture_creditLimitDoesNotConfuseWithCashAccessLine() {
        // The statement has BOTH "Credit Access Line $50,000" (the actual credit limit)
        // and "Cash Access Line $7,500" (the cash-advance sub-limit). Our extractor must
        // pick the credit limit, not the cash limit — a label-collision bug here would
        // silently understate every Chase user's credit headroom by ~7x.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal creditLimit = StatementParsingUtilities.extractCreditLimit(lines);
        assertEquals(0, new BigDecimal("50000").compareTo(creditLimit));
    }

    // ---------- Account Detection ----------

    @Test
    void chaseMarriottBonvoyFixture_detectsChaseInstitution() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-marriott-bonvoy-statement.pdf");
        assertNotNull(detected, "Detection must produce a result for a Chase statement");
        assertNotNull(detected.getInstitutionName(),
                "Institution name must be detected from PDF content");
        final String institution = detected.getInstitutionName().toLowerCase(java.util.Locale.ROOT);
        assertTrue(
                institution.contains("chase") || institution.contains("marriott"),
                "Expected Chase / Marriott institution; got: " + detected.getInstitutionName());
    }

    @Test
    void chaseMarriottBonvoyFixture_detectsCreditCardAccountType() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-marriott-bonvoy-statement.pdf");
        assertNotNull(detected);
        assertNotNull(detected.getAccountType(),
                "Account type must be detected (credit / credit-card)");
        final String type = detected.getAccountType().toLowerCase(java.util.Locale.ROOT);
        assertTrue(
                type.contains("credit") || type.equals("creditcard"),
                "Expected credit-card account type; got: " + detected.getAccountType());
    }

    @Test
    void chaseMarriottBonvoyFixture_detectsAccountNumberLastFour() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-marriott-bonvoy-statement.pdf");
        assertNotNull(detected);
        // The masked "XXXX XXXX XXXX 4242" must collapse to the trailing four digits.
        final String accountNumber = detected.getAccountNumber();
        assertNotNull(accountNumber,
                "Last 4 of the account number must be detected from the masked label");
        assertTrue(
                accountNumber.endsWith("4242"),
                "Expected detected account number to end with 4242; got: " + accountNumber);
    }

    // ---------- section totals ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsAllSectionTotals() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        assertEquals(0,
                new BigDecimal("999.99").compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)),
                "Purchases total");
        assertEquals(0,
                new BigDecimal("-2500.00").compareTo(
                        StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)),
                "Payments+Credits total (negative on credit cards)");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractCashAdvancesTotal(lines)),
                "Cash Advances total");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractBalanceTransfersTotal(lines)),
                "Balance Transfers total");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractFeesChargedTotal(lines)),
                "Fees Charged total");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractInterestChargedTotal(lines)),
                "Interest Charged total");
    }

    // ---------- APR rates ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsAllAprRates() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        assertEquals(0,
                new BigDecimal("19.49").compareTo(StatementParsingUtilities.extractPurchaseApr(lines)),
                "Purchase APR");
        assertEquals(0,
                new BigDecimal("28.49").compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)),
                "Cash Advance APR");
        assertEquals(0,
                new BigDecimal("19.49").compareTo(
                        StatementParsingUtilities.extractBalanceTransferApr(lines)),
                "Balance Transfer APR");
        assertEquals(0,
                new BigDecimal("29.99").compareTo(StatementParsingUtilities.extractPenaltyApr(lines)),
                "Penalty APR");
    }

    // ---------- annual membership fee ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsAnnualMembershipFeeAndDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Object[] feeBlock =
                StatementParsingUtilities.extractAnnualMembershipFeeAndDate(lines, 2026, true);

        assertNotNull(feeBlock, "Annual fee sentence must produce a {fee, date} pair");
        assertEquals(0, new BigDecimal("99.00").compareTo((BigDecimal) feeBlock[0]),
                "Annual fee amount");
        assertEquals(java.time.LocalDate.of(2026, 7, 1), feeBlock[1],
                "Annual fee billing date");
    }

    // ---------- cash limits + billing days + statement date ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsCashLimits() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("7500").compareTo(StatementParsingUtilities.extractCashAccessLine(lines)),
                "Cash Access Line");
        assertEquals(0,
                new BigDecimal("7500").compareTo(StatementParsingUtilities.extractAvailableForCash(lines)),
                "Available for Cash");
    }

    @Test
    void chaseMarriottBonvoyFixture_extractsBillingDays() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Integer billingDays = StatementParsingUtilities.extractBillingDays(lines);
        assertNotNull(billingDays);
        assertEquals(31, billingDays.intValue());
    }

    @Test
    void chaseMarriottBonvoyFixture_extractsStatementDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final java.time.LocalDate statementDate =
                StatementParsingUtilities.extractStatementDate(lines, 2026, true);
        assertNotNull(statementDate, "Statement date must be extracted from header");
        assertEquals(java.time.LocalDate.of(2026, 6, 17), statementDate);
    }

    // ---------- AutoPay + scheduled amount ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsAutoPayOnAndNextAmount() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(lines);
        assertNotNull(enabled, "AutoPay status must be detected from 'AUTOPAY IS ON'");
        assertTrue(enabled, "AutoPay must be ON for this fixture");

        final BigDecimal next = StatementParsingUtilities.extractNextAutoPayAmount(lines);
        assertNotNull(next, "Next AutoPay amount must be parsed from 'next AutoPay payment for $X'");
        assertEquals(0, new BigDecimal("999.99").compareTo(next));
    }

    @Test
    void autoPay_detectsOffWhenStatementSaysSo() {
        final String input = String.join(
                "\n",
                "New Balance $100.00",
                "AUTOPAY IS OFF",
                "Payment Due Date: 07/14/26");
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(input.split("\\n"));
        assertNotNull(enabled);
        assertFalse(enabled, "AUTOPAY IS OFF must produce a false (not null)");
    }

    @Test
    void autoPay_returnsNullWhenStatementDoesNotMentionAutoPay() {
        final String input = String.join(
                "\n",
                "New Balance $100.00",
                "Payment Due Date: 07/14/26");
        assertEquals(null, StatementParsingUtilities.extractAutoPayEnabled(input.split("\\n")));
        assertEquals(null, StatementParsingUtilities.extractNextAutoPayAmount(input.split("\\n")));
    }

    @Test
    void autoPay_acceptsAutomaticPaymentsPhrasingFromOtherIssuers() {
        // Some issuers use "Automatic Payments: Enabled" instead of Chase's "AUTOPAY IS ON".
        final String input = "Automatic Payments: Enabled";
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(input.split("\\n"));
        assertNotNull(enabled);
        assertTrue(enabled);
    }

    // ---------- points: earned vs balance ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsPointsEarnedThisPeriod() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Long earned = StatementParsingUtilities.extractPointsEarnedThisPeriod(lines);
        assertNotNull(earned,
                "Marriott Bonvoy 'Total points transferred to' phrase must yield earned-this-period");
        assertEquals(4500L, earned.longValue(),
                "Expected the synthetic 4,500 points from the transfer-to-Marriott line");
    }

    @Test
    void chaseMarriottBonvoyFixture_pointsBalanceIsNull_forTransferPartnerCards() {
        // Chase Marriott Bonvoy never prints a cumulative balance — the balance lives at
        // Marriott. Confirm we return null rather than mistakenly returning the earned
        // figure (which would conflate two distinct concepts).
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Long balance = StatementParsingUtilities.extractPointsBalance(lines);
        assertEquals(null, balance,
                "Marriott Bonvoy statements have no points balance — should return null");
    }

    @Test
    void pointsBalance_extractedFromGenericPointsBalanceLine() {
        // Synthetic statement from a non-transfer-partner card that prints both an
        // earned figure and a balance. Both should populate independently.
        final String input = String.join(
                "\n",
                "Points earned this period: 1,250",
                "Points balance: 47,830");

        final Long earned = StatementParsingUtilities.extractPointsEarnedThisPeriod(input.split("\\n"));
        final Long balance = StatementParsingUtilities.extractPointsBalance(input.split("\\n"));
        assertEquals(1250L, earned.longValue());
        assertEquals(47830L, balance.longValue());
    }

    @Test
    void pointsBalance_extractedFromAvailableForRedemptionPhrase() {
        // Citi-style "Total points available for redemption: NN,NNN" phrasing.
        final String input = "Total points available for redemption: 12,400";
        final Long balance = StatementParsingUtilities.extractPointsBalance(input.split("\\n"));
        assertEquals(12400L, balance.longValue());
    }

    @Test
    void pointsEarned_recoveredAcrossLineBreak_whenChaseSplitsPhrase() {
        // PDFBox-on-Chase quirk #1: "Total points transferred to" lands on one line and
        // "Marriott Bonvoy NN,NNN" lands on the next, so a single-line regex misses it.
        // The fallback joins lines before retrying, which must recover the value.
        final String input = String.join(
                "\n",
                "POINTS",
                "Total points transferred to",
                "Marriott Bonvoy 6,464");
        final Long earned = StatementParsingUtilities.extractPointsEarnedThisPeriod(input.split("\\n"));
        assertNotNull(earned, "Multi-line phrasing must still produce a value");
        assertEquals(6464L, earned.longValue());
    }

    @Test
    void pointsEarned_recoveredWhenPdfBoxGluesValueToFollowingDate() {
        // PDFBox-on-Chase quirk #2: "Marriott Bonvoy 6,464" sits next to a date column
        // "04/14/26" and PDFBox extracts them as one token: "Marriott Bonvoy 6,46404/14/26".
        // The fallback inserts a synthetic space anywhere a date pattern abuts a digit,
        // so the number boundary is recoverable.
        final String input = String.join(
                "\n",
                "POINTS",
                "Total points transferred to",
                "Marriott Bonvoy 6,46404/14/26");
        final Long earned = StatementParsingUtilities.extractPointsEarnedThisPeriod(input.split("\\n"));
        assertNotNull(earned, "Glued-date quirk must not swallow the points value");
        assertEquals(6464L, earned.longValue(),
                "Must capture the comma-grouped points value, not the digit-prefix or"
                        + " the concatenated number");
    }

    // ---------- foreign transaction fee ----------

    @Test
    void chaseMarriottBonvoyFixture_extractsForeignTransactionFeePercent() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal fee = StatementParsingUtilities.extractForeignTransactionFeePercent(lines);
        assertNotNull(fee, "Foreign-transaction fee % must be extracted from the disclosure");
        assertEquals(0, new BigDecimal("3").compareTo(fee));
    }

    // ---------- false-positive guards (label / sub-label collisions) ----------

    @Test
    void creditLimit_doesNotMatchPrecedingPhrase_evenWhenAmountIsNonZero() {
        // Production-grade adversarial: the disclosure line "Balance over the Credit Access
        // Line $1,500.00" appears BEFORE the real "Credit Access Line $50,000". An older
        // version of the extractor anchored on \b instead of ^\s* and would have matched
        // "credit access line" inside the longer disclosure phrase, returning $1,500
        // instead of the actual credit limit. The fix anchors to ^\s*.
        final String input =
                String.join(
                        "\n",
                        "Balance over the Credit Access Line $1,500.00",
                        "Credit Access Line $50,000",
                        "Available Credit $48,500");

        final BigDecimal creditLimit = StatementParsingUtilities.extractCreditLimit(input.split("\\n"));
        assertEquals(0,
                new BigDecimal("50000").compareTo(creditLimit),
                "Credit limit must NOT pick up the embedded label in the disclosure line");
    }

    @Test
    void newBalance_doesNotMatchEmbeddedLabelInDisclosure() {
        // Defense: a disclosure paragraph that mentions "your new balance" must NOT win
        // over the actual "New Balance $X" row on the statement.
        final String input =
                String.join(
                        "\n",
                        "If your new balance is more than $50.00 you may opt in to automatic",
                        "payments.",
                        "New Balance $750.00");

        final BigDecimal newBalance = StatementParsingUtilities.extractNewBalance(input.split("\\n"));
        assertEquals(0, new BigDecimal("750.00").compareTo(newBalance));
    }

    @Test
    void paymentsAndCreditsTotal_preservesNegativeSign_evenInParensFormat() {
        // Some issuers print negatives as parens: "Payment, Credits ($1,500.00)". The
        // extractor must NOT silently drop the sign when stripping the parens — that
        // would falsely show as a positive payment, which inverts net-balance math.
        final String input = "Payment, Credits ($1,500.00)";
        final BigDecimal total =
                StatementParsingUtilities.extractPaymentsAndCreditsTotal(new String[] {input});
        assertNotNull(total);
        assertEquals(0, new BigDecimal("-1500.00").compareTo(total),
                "Parens-form must yield a negative — got " + total);
    }

    @Test
    void fxDetailPattern_rejectsMalformedRateLines() {
        // The tightened FX_DETAIL_PATTERN requires a sensibly-shaped rate (e.g. 1.123 or
        // 0.0107) — pathological inputs like "1.2.3 X 4.5.6" should NOT match. Verify by
        // feeding such a line; stripAndCaptureFxAnnotations must NOT add an anchor for it.
        final String input =
                String.join(
                        "\n",
                        "06/03     SYNTHETIC HOTEL X 100.00",
                        "06/04     SWISS FRANC",
                        "100.0.0 X 1.0.0 (EXCHG RATE)");

        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(input);
        // Garbage rate → no anchor recorded.
        assertEquals(0, res.getAnnotationsByAnchor().size(),
                "Malformed rate must not produce an FX anchor");
        // And the SWISS FRANC header line stays because the detail didn't match.
        assertTrue(res.getCleanedText().contains("SWISS FRANC"),
                "Header must survive when paired detail is malformed");
    }

    @Test
    void chaseMarriottBonvoyFixture_newBalanceDoesNotConfuseWithPreviousBalance() {
        // Both "New Balance" and "Previous Balance" appear on the statement. The extractors
        // must NOT cross-wire — the New Balance regex must reject the "Previous Balance"
        // line and vice versa.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal newBalance = StatementParsingUtilities.extractNewBalance(lines);
        final BigDecimal previousBalance = StatementParsingUtilities.extractPreviousBalance(lines);
        assertEquals(0, new BigDecimal("999.99").compareTo(newBalance));
        assertEquals(0, new BigDecimal("2500.00").compareTo(previousBalance));
        // And they must be distinct values — sanity check against a regex that grabbed the
        // same line for both.
        assertTrue(newBalance.compareTo(previousBalance) != 0,
                "New and previous balance must be distinct values");
    }
}
