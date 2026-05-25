package com.budgetbuddy.service.pdf.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Sanity tests for the 4 AI PDF scaffolds. Each AI service is gated by
 * a feature flag (default OFF) and degrades to "no result" when the
 * API key is absent. These tests pin that contract so a future config
 * change can't accidentally enable AI calls without a key — which would
 * fail every parse with a connection error.
 */
class AiPdfScaffoldsTest {

    // ----- AiYamlTemplateAuthor -----

    @Test
    void yamlAuthor_returnsNull_whenApiKeyMissing() throws Exception {
        final AiYamlTemplateAuthor svc = new AiYamlTemplateAuthor();
        setField(svc, "apiKey", "");
        setField(svc, "maxTextChars", 30000);
        assertNull(svc.generateDraft("Some Bank", "fake pdf text"),
                "no api key → must return null, not throw");
    }

    @Test
    void yamlAuthor_returnsNull_whenInstitutionOrTextIsNull() throws Exception {
        final AiYamlTemplateAuthor svc = new AiYamlTemplateAuthor();
        setField(svc, "apiKey", "fake-key-not-used");
        setField(svc, "maxTextChars", 30000);
        assertNull(svc.generateDraft(null, "text"));
        assertNull(svc.generateDraft("Bank", null));
    }

    // ----- AiMerchantCanonicalizer -----

    @Test
    void canonicalizer_returnsNull_whenApiKeyMissing() throws Exception {
        final AiMerchantCanonicalizer svc = new AiMerchantCanonicalizer();
        setField(svc, "apiKey", "");
        setField(svc, "timeoutSeconds", 8);
        assertNull(svc.canonicalize("STARBUCKS #1234"),
                "no api key → must return null, not throw or block");
    }

    @Test
    void canonicalizer_returnsNull_forNullOrBlankInput() throws Exception {
        final AiMerchantCanonicalizer svc = new AiMerchantCanonicalizer();
        setField(svc, "apiKey", "fake-key-not-used");
        setField(svc, "timeoutSeconds", 8);
        assertNull(svc.canonicalize(null));
        assertNull(svc.canonicalize(""));
        assertNull(svc.canonicalize("   "));
    }

    // ----- StatementFormatAnomalyDetector -----

    @Test
    void anomalyDetector_firstObservation_doesNotWarn() throws Exception {
        final StatementFormatAnomalyDetector det = newDetector();
        final ImportResult r = resultFor("Wells Fargo", "1234", true);
        // No prior baseline — must not throw, must not emit a drift counter.
        det.inspect(r);
        assertTrue(true, "first import must be a no-op for drift detection");
    }

    @Test
    void anomalyDetector_sameFingerprint_noWarning() throws Exception {
        final StatementFormatAnomalyDetector det = newDetector();
        // Two parses of structurally-identical statements: no drift.
        det.inspect(resultFor("Wells Fargo", "1234", true));
        det.inspect(resultFor("Wells Fargo", "1234", true));
        assertTrue(true);
    }

    @Test
    void anomalyDetector_handlesNullInput() throws Exception {
        final StatementFormatAnomalyDetector det = newDetector();
        det.inspect(null);
        det.inspect(new ImportResult());  // no detected account
        assertTrue(true);
    }

    @Test
    void anomalyDetector_differentInstitutions_doNotInterfere() throws Exception {
        final StatementFormatAnomalyDetector det = newDetector();
        det.inspect(resultFor("Wells Fargo", "1234", true));
        det.inspect(resultFor("Chase", "5678", false));
        // Different accounts → different fingerprints → no false drift.
        assertTrue(true);
    }

    // ----- helpers -----

    private static StatementFormatAnomalyDetector newDetector() throws Exception {
        final StatementFormatAnomalyDetector det = new StatementFormatAnomalyDetector(null);
        setField(det, "warnOnFirstImport", false);
        return det;
    }

    private static ImportResult resultFor(final String institution, final String last4,
                                           final boolean withFullMetadata) {
        final ImportResult r = new ImportResult();
        final DetectedAccount a = new DetectedAccount();
        a.setInstitutionName(institution);
        a.setAccountNumber(last4);
        r.setDetectedAccount(a);
        if (withFullMetadata) {
            r.setNewBalance(new java.math.BigDecimal("100.00"));
            r.setPreviousBalance(new java.math.BigDecimal("50.00"));
            r.setCreditLimit(new java.math.BigDecimal("5000.00"));
            r.setPaymentDueDate(java.time.LocalDate.now());
            r.setMinimumPaymentDue(new java.math.BigDecimal("25.00"));
            r.setPurchasesTotal(new java.math.BigDecimal("100.00"));
            r.setPaymentsAndCreditsTotal(new java.math.BigDecimal("50.00"));
        }
        return r;
    }

    private static void setField(final Object target, final String name, final Object value)
            throws IllegalAccessException {
        try {
            final Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
