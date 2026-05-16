package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.StatementExtractionReport.HealthBand;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatementExtractionReportTest {

    @Test
    void populationRatio_isExtractedOverTotal() {
        final var report = StatementExtractionReport.builder()
                .record("a", "x")
                .record("b", "y")
                .record("c", null)
                .record("d", null)
                .build();
        assertEquals(0.50, report.populationRatio(), 0.001);
    }

    @Test
    void healthBand_excellent_when90PercentPlusAndMathReconciles() {
        final var math = new StatementMathValidator().validate(
                new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
        final var report = StatementExtractionReport.builder()
                .record("a", "x")
                .record("b", "y")
                .record("c", "z")
                .record("d", "z")
                .record("e", "z")
                .record("f", "z")
                .record("g", "z")
                .record("h", "z")
                .record("i", "z")
                .record("j", "z")
                .mathResult(math)
                .build();
        assertEquals(HealthBand.EXCELLENT, report.healthBand());
    }

    @Test
    void healthBand_ok_when70To90Percent() {
        final var math = new StatementMathValidator().validate(
                new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
        final var report = StatementExtractionReport.builder()
                .record("a", "x").record("b", "y").record("c", "z")
                .record("d", "z").record("e", "z").record("f", "z")
                .record("g", "z").record("h", null).record("i", null)
                .record("j", null) // 7/10 = 70%
                .mathResult(math)
                .build();
        assertEquals(HealthBand.OK, report.healthBand());
    }

    @Test
    void healthBand_degraded_when30To69PercentExtracted() {
        final var report = StatementExtractionReport.builder()
                .record("a", "x").record("b", "y").record("c", "z")
                .record("d", "z").record("e", null).record("f", null)
                .record("g", null).record("h", null).record("i", null)
                .record("j", null) // 4/10 = 40%
                .build();
        assertEquals(HealthBand.DEGRADED, report.healthBand());
    }

    @Test
    void healthBand_fail_belowThirtyPercent() {
        final var report = StatementExtractionReport.builder()
                .record("a", "x").record("b", null).record("c", null)
                .record("d", null).record("e", null) // 1/5 = 20%
                .build();
        assertEquals(HealthBand.FAIL, report.healthBand());
    }

    @Test
    void healthBand_fail_whenMathDiffLarge() {
        // Even with high field population, a $10+ math gap downgrades to FAIL.
        final var math = new StatementMathValidator().validate(
                new BigDecimal("100"), new BigDecimal("1000"), // huge gap
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null);
        final var report = StatementExtractionReport.builder()
                .record("a", "x").record("b", "y").record("c", "z")
                .record("d", "z").record("e", "z")
                .mathResult(math)
                .build();
        assertEquals(HealthBand.FAIL, report.healthBand(),
                "Field population alone shouldn't push the band up when math is wildly off");
    }

    @Test
    void recordNull_listsAsMissing() {
        final var report = StatementExtractionReport.builder()
                .record("newBalance", null)
                .record("apr", "19.99")
                .build();
        assertTrue(report.missingFields().contains("newBalance"));
        assertEquals(1, report.extractedFields().size());
    }

    @Test
    void recordNonNullAfterNull_movesFieldToExtracted() {
        // Useful when a later override fills in a value an earlier pass missed.
        final var report = StatementExtractionReport.builder()
                .record("newBalance", null)
                .record("newBalance", new BigDecimal("100"))
                .build();
        assertTrue(report.missingFields().isEmpty());
        assertEquals(1, report.extractedFields().size());
    }

    @Test
    void summary_oneLine_includesIssuerBrandPopMathBand() {
        final var report = StatementExtractionReport.builder()
                .issuerId("chase")
                .brand("marriott-bonvoy")
                .record("a", "x").record("b", null)
                .build();
        final String summary = report.summary();
        assertTrue(summary.contains("issuer=chase"));
        assertTrue(summary.contains("brand=marriott-bonvoy"));
        assertTrue(summary.contains("pop=50%"));
        assertNotNull(summary);
    }
}
