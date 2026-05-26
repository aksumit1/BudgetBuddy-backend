package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Validates that the v2 YAML schema loads cleanly and carries the expected
 * extraction rules. This locks in the schema shape — if someone deletes a rule
 * in {@code us-bank.yaml}, this test fails fast.
 */
class PdfTemplateV2Test {

    @Test
    void loadsUsBankV2Template() throws Exception {
        final ObjectMapper m = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream(
                "/pdf-templates-v2/us-bank.yaml")) {
            assertNotNull(is, "us-bank.yaml v2 must be on the classpath");
            final PdfTemplateV2 t = m.readValue(is, PdfTemplateV2.class);

            assertEquals("us-bank-v2", t.getId());
            assertEquals("U.S. Bank", t.getInstitution());
            assertTrue(t.isV2(), "Loaded template should carry v2 sections");

            // Card detection
            assertNotNull(t.getCardDetection());
            assertFalse(t.getCardDetection().getInstitutionMatch().isEmpty());
            assertFalse(t.getCardDetection().getLastFour().isEmpty());
            assertTrue(t.getCardDetection().getLastFour().getFirst().getFilenameFallback());

            // Metadata — USB template now uses explicit regex patterns (not
            // label-adjacent) for total extraction so the rule's pattern field
            // is set, not its label field.
            assertNotNull(t.getMetadata());
            assertNotNull(t.getMetadata().getNewBalance().getFirst().getPattern());
            assertTrue(t.getMetadata().getNewBalance().getFirst().getPattern()
                    .contains("New\\s+Balance"));

            // Layouts (existing v1 shape, kept)
            assertEquals(1, t.getLayouts().size());
            assertEquals("usb-credit-card-single-line",
                    t.getLayouts().getFirst().getName());
        }
    }
}
