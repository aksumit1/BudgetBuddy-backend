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
    void v2CutoverPathCounterTagsInstitutionAndPath() {
        metrics.recordV2CutoverPath("Chase", "v2");
        metrics.recordV2CutoverPath("Chase", "v2");
        metrics.recordV2CutoverPath("Citi", "legacy");

        final Counter v2Chase = registry.find("pdf_import_v2_cutover_total")
                .tag("institution", "chase").tag("path", "v2").counter();
        final Counter legacyCiti = registry.find("pdf_import_v2_cutover_total")
                .tag("institution", "citi").tag("path", "legacy").counter();
        assertNotNull(v2Chase);
        assertEquals(2.0, v2Chase.count(), 0.001);
        assertEquals(1.0, legacyCiti.count(), 0.001);
    }

    @Test
    void yearRolloverCorrectionsCounter() {
        metrics.recordYearRolloverCorrections("Wells Fargo", 5);
        metrics.recordYearRolloverCorrections("Wells Fargo", 3);
        // Zero / negative counts must be silently no-op.
        metrics.recordYearRolloverCorrections("Wells Fargo", 0);
        metrics.recordYearRolloverCorrections("Wells Fargo", -1);

        final Counter c = registry.find("pdf_import_year_rollover_corrections_total")
                .tag("institution", "wells-fargo").counter();
        assertNotNull(c);
        assertEquals(8.0, c.count(), 0.001);
    }

    @Test
    void fieldExtractionCounterEmitsOneDatapointPerFieldPerParse() {
        // Build a result with some fields populated, some not. Counter must
        // emit "extracted" for present fields and "missing" for absent — one
        // datapoint per known field per parse.
        final ImportResult r = buildResult("Chase", "credit", 1, "100.00", "50.00");
        r.setNewBalance(new BigDecimal("123.45"));
        r.setPreviousBalance(new BigDecimal("100.00"));
        // statement_date, credit_limit, etc. left null
        r.setPurchasesTotal(new BigDecimal("100.00"));
        r.setPaymentsAndCreditsTotal(new BigDecimal("50.00"));

        metrics.recordParse(r, null, 1000);

        final Counter newBalExtracted = registry.find("pdf_import_field_extraction_total")
                .tag("field", "new_balance").tag("status", "extracted").counter();
        final Counter stmtDateMissing = registry.find("pdf_import_field_extraction_total")
                .tag("field", "statement_date").tag("status", "missing").counter();
        assertNotNull(newBalExtracted);
        assertEquals(1.0, newBalExtracted.count(), 0.001);
        assertNotNull(stmtDateMissing);
        assertEquals(1.0, stmtDateMissing.count(), 0.001);
    }

    @Test
    void geoEnrichmentCountersTagsComponent() {
        final ImportResult r = new ImportResult();
        final AccountDetectionService.DetectedAccount a =
                new AccountDetectionService.DetectedAccount();
        a.setInstitutionName("Chase");
        a.setAccountType("credit");
        r.setDetectedAccount(a);
        final ParsedTransaction t1 = debit("STARBUCKS BELLEVUE WA", "10.00");
        t1.setCity("Bellevue");
        t1.setState("WA");
        t1.setCountry("US");
        t1.setPhoneNumber("4255551212");
        r.addTransaction(t1);
        final ParsedTransaction t2 = debit("ACH PAYMENT", "20.00");
        // No geo — counts as "none"
        r.addTransaction(t2);

        metrics.recordParse(r, null, 1000);

        final Counter cityCnt = registry.find("pdf_import_geo_enriched_total")
                .tag("component", "city").counter();
        final Counter stateCnt = registry.find("pdf_import_geo_enriched_total")
                .tag("component", "state").counter();
        final Counter noneCnt = registry.find("pdf_import_geo_enriched_total")
                .tag("component", "none").counter();
        assertNotNull(cityCnt);
        assertEquals(1.0, cityCnt.count(), 0.001);
        assertNotNull(stateCnt);
        assertEquals(1.0, stateCnt.count(), 0.001);
        assertNotNull(noneCnt);
        assertEquals(1.0, noneCnt.count(), 0.001);
    }

    @Test
    void fxAndWalletCountersFireWhenAnnotated() {
        final ImportResult r = new ImportResult();
        final AccountDetectionService.DetectedAccount a =
                new AccountDetectionService.DetectedAccount();
        a.setInstitutionName("Chase");
        a.setAccountType("credit");
        r.setDetectedAccount(a);
        final ParsedTransaction fxTx = debit("LISBON PT MERCHANT", "100.00");
        fxTx.setOriginalCurrencyCode("EUR");
        fxTx.setExchangeRate(new BigDecimal("1.08"));
        r.addTransaction(fxTx);
        final ParsedTransaction walletTx = debit("AplPay STARBUCKS", "5.00");
        walletTx.setWalletProvider("apple-pay");
        r.addTransaction(walletTx);
        final ParsedTransaction familyTx = debit("CARDHOLDER FAMILY MEMBER", "15.00");
        familyTx.setUserName("Family Member");
        familyTx.setCardLastFour("1234");
        r.addTransaction(familyTx);

        metrics.recordParse(r, null, 1000);

        final Counter fx = registry.find("pdf_import_fx_annotated_total").counter();
        final Counter wallet = registry.find("pdf_import_wallet_detected_total").counter();
        final Counter family = registry.find("pdf_import_family_card_matched_total").counter();
        assertNotNull(fx);
        assertEquals(1.0, fx.count(), 0.001);
        assertNotNull(wallet);
        assertEquals(1.0, wallet.count(), 0.001);
        assertNotNull(family);
        assertEquals(1.0, family.count(), 0.001);
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
