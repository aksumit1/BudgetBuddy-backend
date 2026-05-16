package com.budgetbuddy.service.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PdfImportDiagnosticBuilderTest {

    private final PdfImportDiagnosticBuilder builder = new PdfImportDiagnosticBuilder();

    @Test
    void returnsNullWhenParseLooksClean() {
        final ImportResult r = new ImportResult();
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.setPaymentsAndCreditsTotal(new BigDecimal("50.00"));
        r.addTransaction(debit("MERCHANT", "100.00"));
        r.addTransaction(credit("PAYMENT", "50.00"));

        final PdfImportDiagnostic d = builder.buildIfInteresting(
                r, new byte[]{1, 2, 3}, "raw text", 1, "v1", false);
        assertNull(d, "Clean parse should not produce a diagnostic blob");
    }

    @Test
    void capturesWhenDebitSumIsShort() {
        final ImportResult r = new ImportResult();
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.setPaymentsAndCreditsTotal(BigDecimal.ZERO);
        r.addTransaction(debit("PARTIAL", "60.00"));
        // Missing $40 to make this an "interesting" parse — simulate the
        // reconciliation warning that ParsePDF would have already emitted.
        r.addInfo("Transaction sum mismatch on debits: statement says 100.00, parsed sum is 60.00");

        final PdfImportDiagnostic d = builder.buildIfInteresting(
                r, new byte[]{1}, "Statement\nPurchases $100.00\nMERCHANT $60.00", 2, "v1", false);
        assertNotNull(d);
        final PdfImportDiagnostic.ReconciliationDelta debitDelta =
                d.getReconciliation().stream()
                        .filter(x -> "debit".equals(x.bucket))
                        .findFirst().orElseThrow();
        assertEquals(new BigDecimal("-40.00"), debitDelta.delta.setScale(2));
        assertEquals("fail", debitDelta.severity);
    }

    @Test
    void redactsPiiInExcerptAndParsedRows() {
        final ImportResult r = new ImportResult();
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.addTransaction(debitWith("MERCHANT — call us 206-685-1140",
                "MERCHANT", "100.00"));
        r.addInfo("Transaction sum mismatch on debits: statement says 100.00, parsed sum is 0");

        final String rawWithPii =
                "John Smith\n4111 1111 1111 1111\nCall (415) 555-1234\nstatement@example.com";
        final PdfImportDiagnostic d = builder.buildIfInteresting(
                r, new byte[]{1}, rawWithPii, 1, "v1", false);
        assertNotNull(d);
        assertFalse(d.getPdfTextExcerpt().contains("4111 1111 1111 1111"),
                "Card number must be redacted");
        assertFalse(d.getPdfTextExcerpt().contains("(415) 555-1234"),
                "Phone must be redacted");
        assertFalse(d.getPdfTextExcerpt().contains("statement@example.com"),
                "Email must be redacted");
        assertFalse(
                d.getParsedRows().get(0).descriptionRedacted.contains("206-685-1140"),
                "Phone in description must be redacted");
        assertFalse(d.getRedactionApplied().isEmpty());
    }

    @Test
    void masksAccountNumberInDetectedAccount() {
        final ImportResult r = new ImportResult();
        final AccountDetectionService.DetectedAccount a =
                new AccountDetectionService.DetectedAccount();
        a.setInstitutionName("Chase");
        a.setAccountType("credit");
        a.setAccountNumber("4666");
        r.setDetectedAccount(a);
        r.addError("forced capture");

        final PdfImportDiagnostic d = builder.buildIfInteresting(
                r, new byte[]{1}, "", 1, "v1", false);
        assertNotNull(d);
        assertEquals("****4666", d.getDetectedAccount().lastFourMasked);
    }

    @Test
    void honoursForceCaptureFlag() {
        final ImportResult r = new ImportResult();
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.addTransaction(debit("OK", "100.00"));

        final PdfImportDiagnostic d = builder.buildIfInteresting(
                r, new byte[]{1}, "raw", 1, "v1", /* forceCapture */ true);
        assertNotNull(d, "forceCapture=true should produce a blob even on clean parses");
    }

    @Test
    void pdfHashChangesWithContent() {
        final ImportResult r = new ImportResult();
        r.addError("forced");
        final PdfImportDiagnostic a = builder.buildIfInteresting(
                r, new byte[]{1, 2, 3}, "", 1, "v1", false);
        final PdfImportDiagnostic b = builder.buildIfInteresting(
                r, new byte[]{1, 2, 4}, "", 1, "v1", false);
        assertNotNull(a.getPdfHash());
        assertNotNull(b.getPdfHash());
        assertFalse(a.getPdfHash().equals(b.getPdfHash()),
                "Different PDF bytes must produce different hashes for dedup");
    }

    private static ParsedTransaction debit(final String desc, final String amt) {
        return debitWith(desc, null, amt);
    }

    private static ParsedTransaction debitWith(
            final String desc, final String merchant, final String amt) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setMerchantName(merchant);
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
}
