package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Validates the runtime safety net: after parsing, the sum of parsed transactions
 * by direction is compared to the issuer-printed section totals. A delta &gt; $1
 * triggers an info message on the import result so the iOS app can surface
 * "we may have missed transactions on this statement" to the user.
 *
 * <p>Missing transactions is the #1 trust-buster of the PDF import pipeline.
 * This check turns silent under-extraction into a visible warning.
 */
class TransactionSumReconciliationTest {

    @Test
    void emitsWarningWhenDebitSumIsShort() throws Exception {
        final ImportResult r = buildResultWithTotals(
                /* purchases */ new BigDecimal("100.00"),
                /* fees      */ new BigDecimal("5.00"),
                /* interest  */ BigDecimal.ZERO,
                /* payments  */ new BigDecimal("50.00"),
                debit("MERCHANT A", "60.00"),
                debit("MERCHANT B", "30.00"),
                // missing $15 of debits — should trigger reconciliation warning
                credit("PAYMENT", "50.00"));

        invokeReconcile(r);

        assertTrue(
                r.getInfoMessages().stream().anyMatch(m -> m.contains("debits")),
                "Expected debit-sum mismatch warning, got: " + r.getInfoMessages());
    }

    @Test
    void emitsWarningWhenCreditSumIsHigh() throws Exception {
        final ImportResult r = buildResultWithTotals(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50.00"),
                debit("MERCHANT", "100.00"),
                credit("PAYMENT 1", "50.00"),
                credit("DUPLICATE PAYMENT", "50.00")); // extra credit row
        invokeReconcile(r);
        assertTrue(
                r.getInfoMessages().stream().anyMatch(m -> m.contains("payments")),
                "Expected credit-sum mismatch warning, got: " + r.getInfoMessages());
    }

    @Test
    void silentWhenSumsReconcile() throws Exception {
        final ImportResult r = buildResultWithTotals(
                new BigDecimal("100.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.50"),
                new BigDecimal("50.00"),
                debit("PURCHASE", "100.00"),
                debit("FEE", "5.00"),
                debit("INTEREST", "2.50"),
                credit("PAYMENT", "50.00"));
        invokeReconcile(r);
        assertFalse(
                r.getInfoMessages().stream().anyMatch(m -> m.contains("mismatch")),
                "Expected no mismatch warning when sums tally, got: " + r.getInfoMessages());
    }

    @Test
    void absorbsRoundingNoiseUpToOneDollar() throws Exception {
        final ImportResult r = buildResultWithTotals(
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50.00"),
                debit("PURCHASE", "100.45"), // 45 cents off — within tolerance
                credit("PAYMENT", "50.00"));
        invokeReconcile(r);
        assertFalse(
                r.getInfoMessages().stream().anyMatch(m -> m.contains("mismatch")),
                "45-cent delta should be within $1 tolerance, got: " + r.getInfoMessages());
    }

    private static ImportResult buildResultWithTotals(
            final BigDecimal purchases,
            final BigDecimal fees,
            final BigDecimal interest,
            final BigDecimal payments,
            final ParsedTransaction... txs) {
        final ImportResult r = new ImportResult();
        r.setPurchasesTotal(purchases);
        r.setFeesChargedTotal(fees);
        r.setInterestChargedTotal(interest);
        r.setPaymentsAndCreditsTotal(payments);
        for (final ParsedTransaction t : txs) r.addTransaction(t);
        return r;
    }

    private static ParsedTransaction debit(final String desc, final String amt) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setAmount(new BigDecimal(amt).negate());
        t.setFlowDirection(FlowDirection.DEBIT);
        return t;
    }

    private static ParsedTransaction credit(final String desc, final String amt) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setAmount(new BigDecimal(amt));
        t.setFlowDirection(FlowDirection.CREDIT);
        return t;
    }

    private static void invokeReconcile(final ImportResult r) throws Exception {
        final PDFImportService svc = newSvc();
        final java.lang.reflect.Method m =
                PDFImportService.class.getDeclaredMethod(
                        "reconcileTransactionSums", ImportResult.class);
        m.setAccessible(true);
        m.invoke(svc, r);
    }

    private static PDFImportService newSvc() {
        final AccountDetectionService det =
                org.mockito.Mockito.mock(AccountDetectionService.class);
        final ImportCategoryParser cat =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final EnhancedPatternMatcher pm = new EnhancedPatternMatcher();
        return new PDFImportService(det, cat, pm, null);
    }
}
