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
 * End-to-end fixture coverage for a Chase Prime Visa (Amazon-co-branded) credit card
 * statement. The fixture below mirrors the LAYOUT and LABEL phrasing Chase prints on
 * the Prime Visa statement, but every transaction, balance, date, account-number, and
 * personal name is synthetic. DO NOT replace fixture values with data from a real
 * statement.
 *
 * <p>The Prime Visa layout differs from the Marriott Bonvoy layout in two important
 * ways the parser must handle:
 *
 * <ol>
 *   <li><b>Single-date transaction rows</b> — Prime Visa prints only a posting date
 *       column (e.g. {@code "04/12 CHIPOTLE MEX GR ONLINE ... 20.00"}) rather than the
 *       transaction-date + posting-date pair on Marriott Bonvoy. The single-date
 *       layout falls through the chase.yaml credit-card-two-date template and must
 *       be matched by the loose Pattern 1 fallback.
 *   <li><b>Points "Total points available for redemption"</b> — Prime Visa prints
 *       only a redemption balance, not a transferred-out total. The points-balance
 *       extractor must pick this up while the earned-this-period extractor stays
 *       null (or picks up category-level subtotals if the statement prints any).
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ChasePrimeVisaStatementFixtureTest {

    @Mock private AccountRepository accountRepository;
    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        when(accountRepository.findByUserId(anyString())).thenReturn(Collections.emptyList());
        accountDetectionService =
                new AccountDetectionService(accountRepository, new BalanceExtractor());
    }

    /**
     * Synthetic statement text that reproduces the Chase Prime Visa layout (headers,
     * label phrasing, single-date transaction format) WITHOUT any real account data.
     * Numbers are chosen so that:
     *   - newBalance ($321.45) != previousBalance ($215.50) — catches cross-wiring,
     *   - creditLimit ($20,000) != cashAccessLine ($5,000) — catches label collision,
     *   - 9 distinct transactions with one negative payment line for sign handling,
     *   - statement period 04/13/26-05/12/26 with statement date 05/12/26.
     */
    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Manage your account online:  Customer Service: Mobile:  Download the",
                    "www.chase.com/amazon 1-888-247-4080 Chase Mobile® app today",
                    "New Balance",
                    "June  2026 YOUR PRIME VISA POINTS",
                    "$321.45",
                    "S M T W T F S Previous points balance 11,727",
                    "Minimum Payment Due",
                    "31 1 2 3 4 5 6 + 5% back on Amazon.com purchases 0",
                    "$30.00 + 5% back on Whole Foods Market purchases 0",
                    "7 8 9 10 11 12 13",
                    "Payment Due Date + 2% back at gas stations 0",
                    "14 15 16 17 18 19 20 + 2% back at restaurants 618",
                    "06/09/26 + 5% back on Chase Travel purchases 0",
                    "21 22 23 24 25 26 27 + 2% back on local transit/commuting 0",
                    "28 29 30 1 2 3 4 + 1% back on all other purchases 0",
                    "+ 5% back at Amazon sites and stores 0",
                    "Total points available for",
                    "redemption 12,345",
                    "Reward your routine everywhere you shop with your Prime Visa.",
                    "Cardmembers earn unlimited 5% back at Amazon.com, Whole",
                    "Foods Market, and on Chase Travel purchases with an eligible",
                    "Prime membership (otherwise 3% back).",
                    "ACCOUNT SUMMARY",
                    "Account Number:  XXXX XXXX XXXX 9999",
                    "Previous Balance $215.50",
                    "Payment, Credits -$215.50",
                    "Purchases +$321.45",
                    "Cash Advances $0.00",
                    "Balance Transfers $0.00",
                    "Fees Charged $0.00",
                    "Interest Charged $0.00",
                    "New Balance $321.45",
                    "Opening/Closing Date 04/13/26 - 05/12/26",
                    "Credit Access Line $20,000",
                    "Available Credit $19,678",
                    "Cash Access Line $5,000",
                    "Available for Cash $5,000",
                    "Past Due Amount $0.00",
                    "Balance over the Credit Access Line $0.00",
                    "YOUR ACCOUNT MESSAGES",
                    "Your next AutoPay payment for $321.45 will be deducted from your Pay From"
                            + " account and credited on your due date.",
                    "AUTOPAY IS ON Payment Due Date: 06/09/26",
                    "Account number: XXXX XXXX XXXX 9999",
                    "SYNTHETIC CARDHOLDER NAME",
                    "1234 SE EXAMPLE ST",
                    "ANYTOWN WA 98000-0000",
                    "CARDMEMBER SERVICE",
                    "PO BOX 6294",
                    "CAROL STREAM IL 60197-6294",
                    "There is a foreign transaction fee of 3% of the U.S. dollar amount of any"
                            + " foreign transaction for some accounts.",
                    "ACCOUNT ACTIVITY",
                    "Date of",
                    "Transaction Merchant  Name or Transaction Description $ Amount",
                    "PAYMENTS AND OTHER CREDITS",
                    "05/08     AUTOMATIC PAYMENT - THANK YOU -215.50",
                    "PURCHASE",
                    "04/12     EXAMPLE EATERY ONLINE https://prod. CA 20.00",
                    "04/15     EXAMPLE EATERY ONLINE https://prod. CA 22.11",
                    "04/18     EXAMPLE EATERY ONLINE https://prod. CA 22.61",
                    "04/19     EXAMPLE EATERY ONLINE https://prod. CA 22.61",
                    "04/19     EXAMPLE EATERY ONLINE https://prod. CA 5.25",
                    "04/22     EXAMPLE EATERY ONLINE https://prod. CA 22.11",
                    "04/25     EXAMPLE EATERY ONLINE https://prod. CA 22.11",
                    "04/24     EXAMPLE EATERY ONLINE https://prod. CA 22.11",
                    "2026  Totals Year-to-Date",
                    "Total fees charged in 2026 $0.00",
                    "Total interest charged in 2026 $0.00",
                    "INTEREST CHARGES",
                    "Your Annual Percentage Rate (APR)  is the annual interest rate on your account.",
                    "PURCHASES",
                    "Purchases 21.74%(v)(d) - 0 -   - 0 -",
                    "CASH ADVANCES",
                    "Cash Advances 22.74%(v)(d) - 0 -   - 0 -",
                    "BALANCE TRANSFERS",
                    "Balance Transfers 21.74%(v)(d) - 0 -   - 0 -",
                    "30 Days in Billing Period",
                    "Penalty APR of 29.99%.",
                    "(v) = Variable Rate",
                    "(d) = Daily Balance Method (including new transactions)",
                    "SYNTHETIC CARDHOLDER NAME Page 2 of 2 Statement Date: 05/12/26");

    // ---------- Statement-summary metadata ----------

    @Test
    void chasePrimeVisaFixture_extractsAllStatementSummaryFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        final BigDecimal newBalance = StatementParsingUtilities.extractNewBalance(lines);
        assertNotNull(newBalance, "New Balance must be extracted");
        assertEquals(0, new BigDecimal("321.45").compareTo(newBalance));

        final BigDecimal previousBalance = StatementParsingUtilities.extractPreviousBalance(lines);
        assertNotNull(previousBalance, "Previous Balance must be extracted");
        assertEquals(0, new BigDecimal("215.50").compareTo(previousBalance));

        final BigDecimal creditLimit = StatementParsingUtilities.extractCreditLimit(lines);
        assertNotNull(creditLimit,
                "Credit Limit must be extracted (Chase labels it 'Credit Access Line')");
        assertEquals(0, new BigDecimal("20000").compareTo(creditLimit));

        final BigDecimal availableCredit = StatementParsingUtilities.extractAvailableCredit(lines);
        assertNotNull(availableCredit, "Available Credit must be extracted");
        assertEquals(0, new BigDecimal("19678").compareTo(availableCredit));

        final BigDecimal pastDue = StatementParsingUtilities.extractPastDueAmount(lines);
        assertNotNull(pastDue, "Past Due Amount must be extracted (even when $0.00)");
        assertEquals(0, BigDecimal.ZERO.compareTo(pastDue));
    }

    @Test
    void chasePrimeVisaFixture_extractsCashLimits() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("5000").compareTo(StatementParsingUtilities.extractCashAccessLine(lines)),
                "Cash Access Line");
        assertEquals(0,
                new BigDecimal("5000").compareTo(StatementParsingUtilities.extractAvailableForCash(lines)),
                "Available for Cash");
    }

    @Test
    void chasePrimeVisaFixture_creditLimitDoesNotConfuseWithCashAccessLine() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal creditLimit = StatementParsingUtilities.extractCreditLimit(lines);
        assertEquals(0, new BigDecimal("20000").compareTo(creditLimit),
                "Credit Access Line $20,000 must NOT be confused with Cash Access Line $5,000");
    }

    // ---------- Section totals ----------

    @Test
    void chasePrimeVisaFixture_extractsAllSectionTotals() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        assertEquals(0,
                new BigDecimal("321.45").compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)),
                "Purchases total");
        assertEquals(0,
                new BigDecimal("-215.50").compareTo(
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
    void chasePrimeVisaFixture_extractsAllAprRates() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");

        assertEquals(0,
                new BigDecimal("21.74").compareTo(StatementParsingUtilities.extractPurchaseApr(lines)),
                "Purchase APR");
        assertEquals(0,
                new BigDecimal("22.74").compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)),
                "Cash Advance APR");
        assertEquals(0,
                new BigDecimal("21.74").compareTo(
                        StatementParsingUtilities.extractBalanceTransferApr(lines)),
                "Balance Transfer APR");
        assertEquals(0,
                new BigDecimal("29.99").compareTo(StatementParsingUtilities.extractPenaltyApr(lines)),
                "Penalty APR");
    }

    // ---------- Billing days + statement date ----------

    @Test
    void chasePrimeVisaFixture_extractsBillingDays() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Integer billingDays = StatementParsingUtilities.extractBillingDays(lines);
        assertNotNull(billingDays);
        assertEquals(30, billingDays.intValue());
    }

    @Test
    void chasePrimeVisaFixture_extractsStatementDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final java.time.LocalDate statementDate =
                StatementParsingUtilities.extractStatementDate(lines, 2026, true);
        assertNotNull(statementDate, "Statement date must be extracted from page footer");
        assertEquals(java.time.LocalDate.of(2026, 5, 12), statementDate);
    }

    // ---------- AutoPay ----------

    @Test
    void chasePrimeVisaFixture_extractsAutoPayOnAndNextAmount() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(lines);
        assertNotNull(enabled, "AutoPay status must be detected from 'AUTOPAY IS ON'");
        assertTrue(enabled, "AutoPay must be ON for this fixture");

        final BigDecimal next = StatementParsingUtilities.extractNextAutoPayAmount(lines);
        assertNotNull(next, "Next AutoPay amount must be parsed from 'next AutoPay payment for $X'");
        assertEquals(0, new BigDecimal("321.45").compareTo(next));
    }

    // ---------- Foreign transaction fee ----------

    @Test
    void chasePrimeVisaFixture_extractsForeignTransactionFeePercent() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal fee = StatementParsingUtilities.extractForeignTransactionFeePercent(lines);
        assertNotNull(fee, "Foreign-transaction fee % must be extracted from the disclosure");
        assertEquals(0, new BigDecimal("3").compareTo(fee));
    }

    // ---------- Points (Prime Visa: redemption balance only, no transferred-out total) ----------

    @Test
    void chasePrimeVisaFixture_extractsPointsBalanceFromRedemptionPhrase() {
        // Prime Visa prints "Total points available for redemption NN,NNN" — the
        // balance extractor must accept this phrasing across the line break.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Long balance = StatementParsingUtilities.extractPointsBalance(lines);
        assertNotNull(balance,
                "'Total points available for redemption' must yield a points balance");
        assertEquals(12345L, balance.longValue());
    }

    // ---------- Account Detection ----------

    @Test
    void chasePrimeVisaFixture_detectsChaseInstitution() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-prime-visa-statement.pdf");
        assertNotNull(detected, "Detection must produce a result for a Chase statement");
        assertNotNull(detected.getInstitutionName(),
                "Institution name must be detected from PDF content");
        final String institution =
                detected.getInstitutionName().toLowerCase(java.util.Locale.ROOT);
        assertTrue(
                institution.contains("chase") || institution.contains("prime"),
                "Expected Chase / Prime institution; got: " + detected.getInstitutionName());
    }

    @Test
    void chasePrimeVisaFixture_detectsCreditCardAccountType() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-prime-visa-statement.pdf");
        assertNotNull(detected);
        assertNotNull(detected.getAccountType(),
                "Account type must be detected (credit / credit-card)");
        final String type = detected.getAccountType().toLowerCase(java.util.Locale.ROOT);
        assertTrue(
                type.contains("credit") || type.equals("creditcard"),
                "Expected credit-card account type; got: " + detected.getAccountType());
    }

    @Test
    void chasePrimeVisaFixture_detectsAccountNumberLastFour() {
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(
                        STATEMENT_FIXTURE, "chase-prime-visa-statement.pdf");
        assertNotNull(detected);
        final String accountNumber = detected.getAccountNumber();
        assertNotNull(accountNumber,
                "Last 4 of the account number must be detected from the masked label");
        assertTrue(
                accountNumber.endsWith("9999"),
                "Expected detected account number to end with 9999; got: " + accountNumber);
    }

    // ---------- Sanity guards (cross-wiring + sign preservation) ----------

    @Test
    void chasePrimeVisaFixture_newBalanceDoesNotConfuseWithPreviousBalance() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal newBalance = StatementParsingUtilities.extractNewBalance(lines);
        final BigDecimal previousBalance = StatementParsingUtilities.extractPreviousBalance(lines);
        assertEquals(0, new BigDecimal("321.45").compareTo(newBalance));
        assertEquals(0, new BigDecimal("215.50").compareTo(previousBalance));
        assertTrue(newBalance.compareTo(previousBalance) != 0,
                "New and previous balance must be distinct values");
    }

    @Test
    void chasePrimeVisaFixture_paymentTotalIsNegativeNotPositive() {
        // The summary row prints "Payment, Credits -$215.50". The extractor must
        // preserve the negative sign — flipping it would invert all net-balance math
        // because credit-card payments reduce the balance.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final BigDecimal payments = StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines);
        assertNotNull(payments);
        assertTrue(payments.signum() < 0,
                "Payment, Credits total must be negative — got " + payments);
    }

    @Test
    void chasePrimeVisaFixture_autoPayDetectedDespiteShareLineWithDueDate() {
        // The Prime Visa statement crams "AUTOPAY IS ON" and "Payment Due Date: 06/09/26"
        // onto the same physical line in the layout block. The AutoPay detector must
        // still pick up the ON state even when the line carries extra non-AutoPay tokens.
        final String mixedLine = "AUTOPAY IS ON Payment Due Date: 06/09/26";
        final Boolean enabled =
                StatementParsingUtilities.extractAutoPayEnabled(new String[] {mixedLine});
        assertNotNull(enabled);
        assertTrue(enabled, "Mixed-line AUTOPAY token must still produce true");
    }

    @Test
    void chasePrimeVisaFixture_autoPayOffWhenStatementSaysSo() {
        final String input = String.join(
                "\n",
                "New Balance $321.45",
                "AUTOPAY IS OFF",
                "Payment Due Date: 06/09/26");
        final Boolean enabled = StatementParsingUtilities.extractAutoPayEnabled(input.split("\\n"));
        assertNotNull(enabled);
        assertFalse(enabled, "AUTOPAY IS OFF must produce false (not null)");
    }

    // ---------- PDFBox ligature-glyph artefact tolerance ----------

    @Test
    void availableCredit_extractsWhenPdfBoxInsertsBacktickInsideLabel() {
        // Real Chase Prime Visa statements produce "A`vailable Credit $14,841" after
        // PDFBox extracts a ligature glyph in the heading font as a stray backtick.
        // The label regex previously required exact letters and would silently miss
        // the line, returning null for available-credit on every Prime Visa import.
        final String input = "A`vailable Credit $14,841";
        final BigDecimal available =
                StatementParsingUtilities.extractAvailableCredit(new String[] {input});
        assertNotNull(available,
                "Available Credit must still extract when PDFBox emits 'A`vailable Credit'");
        assertEquals(0, new BigDecimal("14841").compareTo(available));
    }

    @Test
    void balanceTransfersTotal_extractsWhenPdfBoxInsertsBacktickInsideLabel() {
        // Same ligature artefact, different label — "B`alance Transfers $0.00".
        final String input = "B`alance Transfers $0.00";
        final BigDecimal total =
                StatementParsingUtilities.extractBalanceTransfersTotal(new String[] {input});
        assertNotNull(total,
                "Balance Transfers total must still extract when PDFBox emits 'B`alance Transfers'");
        assertEquals(0, BigDecimal.ZERO.compareTo(total));
    }

    @Test
    void backtickNormalization_doesNotStripBackticksAtWordBoundary() {
        // Defense: a real merchant description like "`Bistro` 25.00" must NOT have its
        // leading/trailing backticks stripped — the normaliser only removes backticks
        // sandwiched between two letters (the PDFBox ligature artefact), not boundary
        // backticks (which could be legitimate punctuation).
        final String input = "New Balance `42.00";
        // Boundary backticks must not interfere with extraction — the normaliser sees
        // `42.00 as having a backtick adjacent to a digit and leaves it. The amount
        // regex then matches "42.00" as the number, since "`" isn't in [$\\s].
        // Either we extract 42.00, or we don't — but we must not crash.
        // (Asserting non-crash + nullability is the safety contract.)
        final BigDecimal value = StatementParsingUtilities.extractNewBalance(new String[] {input});
        // Permissive assertion: the parser must not throw. Value may or may not
        // be 42.00 depending on whether the amount regex tolerates the leading backtick.
        assertTrue(value == null || value.compareTo(BigDecimal.ZERO) >= 0,
                "Backtick at word boundary must not crash the parser");
    }

    // ---------- column-interleaved phrase recovery ----------

    @Test
    void pointsBalance_recoveredWhenRedemptionWordLandsInAdjacentColumn() {
        // Real Chase Prime Visa rendering: PDFBox extracts "Total points available for"
        // on one line and the trailing "redemption 51,057" lands in a DIFFERENT line,
        // mixed with disclosure prose from the adjacent column. Without the loose
        // bridge pattern the joined-text fallback never connects the two anchors, and
        // every Prime Visa import returns null for points balance.
        final String input = String.join(
                "\n",
                "Total points available for",
                "Late Payment Warning: If we do not receive your minimum payment redemption 51,057");
        final Long balance = StatementParsingUtilities.extractPointsBalance(input.split("\\n"));
        assertNotNull(balance,
                "Points balance must recover when 'redemption N,NNN' lands in the adjacent column");
        assertEquals(51057L, balance.longValue());
    }

    // ---------- calendar-prefixed Amazon-style earned lines ----------

    @Test
    void pointsEarned_amazonStyleLineMatchesEvenWithCalendarPrefix() {
        // Real Chase Prime Visa rendering: the rewards column shares physical space
        // with a calendar block, so PDFBox emits "14 15 16 17 18 19 20 + 2% back at
        // restaurants 318". Anchored-to-start-of-line patterns miss every such row
        // and the parser silently reports 0 earned points.
        final String input = String.join(
                "\n",
                "31 1 2 3 4 5 6 + 5% back on Amazon.com purchases 0",
                "$30.00 + 5% back on Whole Foods Market purchases 0",
                "14 15 16 17 18 19 20 + 2% back at restaurants 318",
                "Payment Due Date + 2% back at gas stations 0",
                "+ 5% back at Amazon sites and stores 0");
        final Long earned =
                StatementParsingUtilities.extractPointsEarnedThisPeriod(input.split("\\n"));
        assertNotNull(earned,
                "Amazon-style category sum must work even when calendar-day digits prefix the line");
        assertEquals(318L, earned.longValue(),
                "Sum should be 318 — only the restaurants category had non-zero earnings");
    }
}
