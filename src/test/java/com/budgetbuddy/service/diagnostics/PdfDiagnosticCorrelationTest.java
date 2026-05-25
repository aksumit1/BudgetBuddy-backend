package com.budgetbuddy.service.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract that {@link PdfImportDiagnosticStore} (JSON
 * summaries) and {@link PdfRawArchive} (raw PDF bytes) co-locate their
 * outputs in a derivable way. Without this guarantee, debugging a parse
 * failure means manually correlating two filesystems by hash.
 */
class PdfDiagnosticCorrelationTest {

    @Test
    void slugifyMatchesBothStores() {
        // The slug rule MUST be identical to what both stores apply.
        // If either store changes its rule, this test fires the
        // mismatch-by-build-break-not-mystery-debug.
        assertEquals("wells-fargo",
                PdfDiagnosticCorrelation.institutionSlug("Wells Fargo"));
        assertEquals("chase",
                PdfDiagnosticCorrelation.institutionSlug("Chase"));
        assertEquals("american-express",
                PdfDiagnosticCorrelation.institutionSlug("American Express"));
        assertEquals("u-s-bank",
                PdfDiagnosticCorrelation.institutionSlug("U.S. Bank"));
        assertEquals("unknown",
                PdfDiagnosticCorrelation.institutionSlug(null));
        assertEquals("unknown",
                PdfDiagnosticCorrelation.institutionSlug(""));
        assertEquals("unknown",
                PdfDiagnosticCorrelation.institutionSlug("   "));
    }

    @Test
    void relativePathStem_followsInstitutionYyyymmShaScheme() {
        final String stem = PdfDiagnosticCorrelation.relativePathStem(
                "Wells Fargo", LocalDate.of(2026, 5, 23), "abc123");
        assertEquals("wells-fargo/2026-05/abc123", stem);
    }

    @Test
    void pdfAndJsonPaths_areSiblings_underTheirRespectiveRoots() {
        final Path pdfRoot = Paths.get("/tmp/archive");
        final Path jsonRoot = Paths.get("/tmp/diagnostics");
        final LocalDate today = LocalDate.of(2026, 5, 23);
        final String sha = "deadbeef";

        final Path pdf = PdfDiagnosticCorrelation.pdfPath(
                pdfRoot, "Wells Fargo", today, sha);
        final Path json = PdfDiagnosticCorrelation.jsonPath(
                jsonRoot, "Wells Fargo", today, sha);

        assertEquals("/tmp/archive/wells-fargo/2026-05/deadbeef.pdf", pdf.toString());
        assertEquals("/tmp/diagnostics/wells-fargo/2026-05/deadbeef.json", json.toString());
        // The relative-from-root portions match, just different extensions.
        assertEquals(
                pdfRoot.relativize(pdf).toString().replace(".pdf", ""),
                jsonRoot.relativize(json).toString().replace(".json", ""));
    }

    @Test
    void longInstitutionNameTruncates() {
        final String veryLong = "Some Very Long Institution Name That Exceeds Forty Characters";
        final String slug = PdfDiagnosticCorrelation.institutionSlug(veryLong);
        assertNotNull(slug);
        assertEquals(true, slug.length() <= 40,
                "slug must cap at 40 chars (DDB GSI cardinality budget). got: " + slug);
    }
}
