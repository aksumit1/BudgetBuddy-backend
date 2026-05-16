package com.budgetbuddy.service.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PdfImportMetricsTest {

    private MeterRegistry registry;
    private PdfImportMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PdfImportMetrics(registry);
    }

    @Test
    void emitsSuccessCounterWithIssuerTag() {
        final ImportResult r = buildResult("Chase", "credit", 5, "100.00", "50.00");
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.setPaymentsAndCreditsTotal(new BigDecimal("50.00"));

        metrics.recordParse(r, null, 50_000_000L); // 50ms

        final Counter c = registry.find("pdf_import_total")
                .tag("institution", "chase")
                .tag("account_type", "credit")
                .tag("status", "success")
                .counter();
        assertNotNull(c, "Should emit pdf_import_total counter with success tag");
        assertEquals(1.0, c.count(), 0.001);
    }

    @Test
    void emitsErrorCounterWithReasonTag() {
        metrics.recordParse(null, new RuntimeException("boom"), 0L);

        final Counter c = registry.find("pdf_import_failure_reasons_total")
                .tag("institution", "unknown")
                .tag("reason", "RuntimeException")
                .counter();
        assertNotNull(c);
        assertEquals(1.0, c.count(), 0.001);
    }

    @Test
    void reconciliationCounterTagsByBucketAndStatus() {
        final ImportResult r = buildResult("Citi", "credit", 5, "100.00", "50.00");
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.setPaymentsAndCreditsTotal(new BigDecimal("50.00"));

        metrics.recordParse(r, null, 1000);

        final Counter debitPass = registry.find("pdf_import_reconciliation_total")
                .tag("bucket", "debit").tag("status", "pass").counter();
        final Counter creditPass = registry.find("pdf_import_reconciliation_total")
                .tag("bucket", "credit").tag("status", "pass").counter();
        assertNotNull(debitPass);
        assertNotNull(creditPass);
        assertEquals(1.0, debitPass.count(), 0.001);
        assertEquals(1.0, creditPass.count(), 0.001);
    }

    @Test
    void reconciliationFailWhenDebitSumIsShort() {
        // Statement says $100 purchases but parsed only $60 in debits — fail.
        final ImportResult r = new ImportResult();
        final AccountDetectionService.DetectedAccount a =
                new AccountDetectionService.DetectedAccount();
        a.setInstitutionName("Chase");
        a.setAccountType("credit");
        r.setDetectedAccount(a);
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.addTransaction(debit("PARTIAL", "60.00"));
        metrics.recordParse(r, null, 1000);

        final Counter c = registry.find("pdf_import_reconciliation_total")
                .tag("bucket", "debit").tag("status", "fail").counter();
        assertNotNull(c);
        assertEquals(1.0, c.count(), 0.001);
    }

    @Test
    void noRegistryProducesSilentMetrics() {
        // A no-op registry path must not throw — used when actuator is absent.
        final PdfImportMetrics m = new PdfImportMetrics(null);
        final ImportResult r = buildResult("Wells Fargo", "credit", 1, "10.00", "0");
        m.recordParse(r, null, 1000);
        // Should not throw — that's the assertion.
        assertTrue(true);
    }

    private static ImportResult buildResult(
            final String institution, final String accountType, final int txCount,
            final String purchases, final String payments) {
        final ImportResult r = new ImportResult();
        final AccountDetectionService.DetectedAccount a =
                new AccountDetectionService.DetectedAccount();
        a.setInstitutionName(institution);
        a.setAccountType(accountType);
        r.setDetectedAccount(a);
        // Add txs to match the declared totals so reconciliation passes
        if (txCount > 0) {
            r.addTransaction(debit("PURCHASE", purchases));
            r.addTransaction(credit("PAYMENT", payments));
        }
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
}
