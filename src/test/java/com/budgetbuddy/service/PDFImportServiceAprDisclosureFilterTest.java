package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the APR-disclosure row filter.
 *
 * <p>Wells Fargo (and several other issuers) print an Interest Charge Calculation
 * table near the end of every credit-card statement. Rows in that table follow
 * the shape {@code "PURCHASES 18.49% variable $0.00 31 $0.00 $545.91"} — a
 * balance-type label, an APR, a daily-periodic rate, the number of days in the
 * billing cycle, the interest charged, and the balance subject to that rate.
 *
 * <p>These rows are <b>not</b> transactions. The structured-parse path and
 * {@link EnhancedPatternMatcher} already reject them (no MM/DD date token), but
 * we keep an explicit guard in three places so that any future regex relaxation
 * — particularly a more permissive loose-fallback — cannot accidentally promote
 * an APR row into the transaction list.
 *
 * <p>This test pins the filter behavior end-to-end through {@code parsePDFText}
 * with a synthetic statement that includes the APR table.
 */
class PDFImportServiceAprDisclosureFilterTest {

    private PDFImportService service;

    @BeforeEach
    void setUp() {
        service =
                new PDFImportService(
                        org.mockito.Mockito.mock(AccountDetectionService.class),
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        new EnhancedPatternMatcher(),
                        null);
    }

    @Test
    @DisplayName(
            "Wells Fargo APR-disclosure row is never promoted to a transaction")
    void aprDisclosureRow_isFilteredOutOfTransactions() throws Exception {
        final String statement =
                String.join(
                        "\n",
                        "Wells Fargo Active Cash Visa Card",
                        "Statement Period 03/19/2026 to 04/17/2026",
                        "Trans Date  Description  Amount",
                        "04/12  WHOLE FOODS  $42.18",
                        "04/18  CHEVRON  $54.00",
                        "TOTAL PURCHASES FOR THIS PERIOD $96.18",
                        "Interest Charge Calculation",
                        "Type of Balance",
                        "PURCHASES 18.49% variable $0.00 31 $0.00 $545.91",
                        "CASH ADVANCES 27.99% variable $0.00 31 $0.00 $0.00",
                        "BALANCE TRANSFERS 21.99% variable $0.00 31 $0.00 $0.00",
                        "");

        final List<Map<String, String>> rows = invokeParsePDFText(statement);

        // The two real transactions survive.
        assertTrue(
                rows.stream().anyMatch(r -> "WHOLE FOODS".equals(r.get("description"))),
                "Real WHOLE FOODS transaction should be extracted");
        assertTrue(
                rows.stream().anyMatch(r -> "CHEVRON".equals(r.get("description"))),
                "Real CHEVRON transaction should be extracted");

        // The APR-disclosure rows must NOT be extracted, regardless of which
        // path the parser took. The strongest tell is a description that
        // collapses to a balance-type label after our trimming — "PURCHASES",
        // "CASH ADVANCES", or "BALANCE TRANSFERS" with no merchant detail.
        for (final Map<String, String> r : rows) {
            final String desc = r.get("description");
            if (desc == null) {
                continue;
            }
            final String descUpper = desc.toUpperCase(Locale.ROOT).trim();
            assertFalse(
                    descUpper.startsWith("PURCHASES ")
                            && descUpper.contains("VARIABLE"),
                    "APR-disclosure PURCHASES row was wrongly promoted to a "
                            + "transaction: " + r);
            assertFalse(
                    descUpper.startsWith("CASH ADVANCES")
                            && descUpper.contains("VARIABLE"),
                    "APR-disclosure CASH ADVANCES row was wrongly promoted to "
                            + "a transaction: " + r);
            assertFalse(
                    descUpper.startsWith("BALANCE TRANSFERS")
                            && descUpper.contains("VARIABLE"),
                    "APR-disclosure BALANCE TRANSFERS row was wrongly promoted "
                            + "to a transaction: " + r);
        }
    }

    @Test
    @DisplayName("Fallback path also filters APR-disclosure rows when no table header is detected")
    void aprDisclosureRow_isFilteredInFallbackPath() throws Exception {
        // Drop the recognizable header so parsePDFText takes the fallback path.
        final String statement =
                String.join(
                        "\n",
                        "Wells Fargo Active Cash Visa Card",
                        "Statement Period 03/19/2026 to 04/17/2026",
                        "04/12 WHOLE FOODS $42.18",
                        "PURCHASES 18.49% variable $0.00 31 $0.00 $545.91",
                        "");

        final List<Map<String, String>> rows = invokeParsePDFText(statement);

        for (final Map<String, String> r : rows) {
            final String desc = r.get("description");
            if (desc == null) {
                continue;
            }
            final String descUpper = desc.toUpperCase(Locale.ROOT);
            assertFalse(
                    descUpper.startsWith("PURCHASES")
                            && descUpper.contains("VARIABLE"),
                    "Fallback path leaked APR-disclosure row as transaction: " + r);
        }
    }

    @Test
    @DisplayName("AutoPay-disclosure prose row never becomes a transaction")
    void autoPayDisclosureProse_isFiltered() throws Exception {
        // Amex prints "$1,966.00 on 04/03/26. This date may not be the same date your bank"
        // — Pattern 7 would otherwise glue these into a phantom row at $1,966.00
        // with description "on . This date may not be the same date your bank".
        final String statement = String.join("\n",
                "American Express",
                "Trans Date  Description  Amount",
                "04/12  WHOLE FOODS  $42.18",
                "We will debit your bank account for your monthly AutoPay payment of",
                "$1,966.00 on 04/03/26. This date may not be the same date your bank",
                "");
        final List<Map<String, String>> rows = invokeParsePDFText(statement);
        for (final Map<String, String> r : rows) {
            final String desc = r.get("description");
            if (desc == null) continue;
            final String descLower = desc.toLowerCase(Locale.ROOT);
            assertFalse(
                    descLower.contains("date may not be the same")
                            || descLower.contains("debit your bank account"),
                    "AutoPay-disclosure prose leaked as transaction: " + r);
        }
    }

    @Test
    @DisplayName("Amex account-ending suffix line (1-21002 -$28.36) is filtered")
    void amexAccountEndingSuffix_isFiltered() throws Exception {
        // Summary line "AGARWAL SUMIT KUMAR 1-21002 -$28.36" has "1-21" that
        // mimics a 1/21 date for the DATE_PATTERN matcher. Without the suffix
        // filter, this becomes a phantom row {date=1/21, desc="002",
        // amount=-$28.36}.
        final String statement = String.join("\n",
                "American Express",
                "Trans Date  Description  Amount",
                "04/12  WHOLE FOODS  $42.18",
                "AGARWAL SUMIT KUMAR 1-21002 -$28.36",
                "");
        final List<Map<String, String>> rows = invokeParsePDFText(statement);
        for (final Map<String, String> r : rows) {
            final String desc = r.get("description");
            if (desc == null) continue;
            assertFalse(
                    "002".equals(desc.trim()) || desc.contains("1-21002"),
                    "Amex account-ending suffix line leaked as transaction: " + r);
        }
    }

    @Test
    @DisplayName("Trailing column header (Credits Amount / Fees) stripped from description")
    void trailingColumnHeader_strippedFromDescription() throws Exception {
        // PDFBox sometimes glues the next section's column header onto the
        // previous transaction's description via Pattern 7's multi-line
        // lookahead. Real bug: "02/03/26* AUTOPAY PAYMENT RECEIVED - THANK YOU"
        // + "JPMorgan Chase Bank, NA" + "-$1,396.00" + "Credits Amount" (next
        // section header) collapsed into a description ending in "...Bank, NA
        // Credits Amount". The orchestrator must strip that suffix.
        final String statement = String.join("\n",
                "American Express",
                "Trans Date  Description  Amount",
                "02/03/26* AGARWAL SUMIT KUMAR AUTOPAY PAYMENT RECEIVED - THANK YOU",
                "JPMorgan Chase Bank, NA",
                "-$1,396.00",
                "Credits Amount",
                "");
        final List<Map<String, String>> rows = invokeParsePDFText(statement);
        for (final Map<String, String> r : rows) {
            final String desc = r.get("description");
            if (desc == null) continue;
            assertFalse(
                    desc.toLowerCase(Locale.ROOT).endsWith("credits amount"),
                    "Trailing 'Credits Amount' not stripped from description: " + r);
        }
    }

    @Test
    @DisplayName("Amex 3-line FX block: USD amount wins, not foreign-currency amount")
    void amexFxBlock_usdAmountExtracted() {
        // Amex prints FX as:
        //   "<merchant>"
        //   "           72,107.44"      ← foreign amount, no $ symbol
        //   "Indian Rupees"             ← currency display
        //   "$776.02 ⧫"                ← USD amount with diamond marker
        // stripAmexFxBlocks drops the foreign-amount and currency-name lines so
        // stitching glues "<merchant> $776.02 ⧫" — Pattern 2 then correctly
        // picks the USD amount.
        final String beforeStrip = String.join("\n",
                "04/04/26 BLRW FO 5 560066 KA",
                "LODGING",
                "           72,107.44",
                "Indian Rupees",
                "$776.02 ⧫");
        final String stripped = PDFImportService.stripAmexFxBlocks(beforeStrip);
        // The foreign-amount line and currency-name line should be gone.
        assertFalse(stripped.contains("72,107.44"),
                "Foreign-currency amount line must be stripped");
        assertFalse(stripped.contains("Indian Rupees"),
                "Currency-name line must be stripped");
        // The USD line must remain.
        assertTrue(stripped.contains("$776.02"),
                "USD amount line must survive");
        assertTrue(stripped.contains("BLRW FO 5"),
                "Merchant line must survive");
    }

    @Test
    @DisplayName("stripAmexFxBlocks is idempotent and only matches the full 3-line shape")
    void amexFxBlock_isIdempotent_andSelective() {
        // Lone "72,107.44" without the currency+USD lines should NOT be
        // stripped (could be a real number elsewhere). Same for partial
        // matches.
        final String partial = String.join("\n",
                "Some random line",
                "           1,234.56",
                "Some other line",
                "");
        assertEquals(partial, PDFImportService.stripAmexFxBlocks(partial),
                "Partial match (no currency-name + USD lines) must pass through");

        // Idempotent: running twice produces the same output.
        final String fx = String.join("\n",
                "MERCHANT X",
                "           500.00",
                "Mexican Pesos",
                "$25.00 ⧫");
        final String once = PDFImportService.stripAmexFxBlocks(fx);
        final String twice = PDFImportService.stripAmexFxBlocks(once);
        assertEquals(once, twice, "stripAmexFxBlocks must be idempotent");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> invokeParsePDFText(final String statement) throws Exception {
        final Method method =
                PDFImportService.class.getDeclaredMethod(
                        "parsePDFText",
                        String.class,
                        Integer.class,
                        boolean.class,
                        AccountDetectionService.DetectedAccount.class);
        method.setAccessible(true);
        return (List<Map<String, String>>) method.invoke(service, statement, 2026, true, null);
    }
}
