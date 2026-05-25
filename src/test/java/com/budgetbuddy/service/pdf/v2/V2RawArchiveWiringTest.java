package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.diagnostics.PdfRawArchive;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the wire-up between {@link PDFImportService} and
 * {@link PdfRawArchive}. The archive itself has 10 unit tests, but those
 * cover the archive in isolation — none of them prove the import service
 * actually CALLS archive on success and failure paths. That's the wiring
 * regression this test pins:
 *
 * <ol>
 *   <li>Success path: when archive is enabled and a real PDF parses
 *       successfully, the bytes land on disk under the institution slug.</li>
 *   <li>Failure path: when a non-PDF blob is fed, the failure branch
 *       (archiveRawPdfOnFailure) routes to the {@code _unparseable}
 *       bucket so the dev team can review.</li>
 *   <li>Disabled archive (default): no files written.</li>
 * </ol>
 *
 * Catches the regression where someone refactors {@code parsePdfInternal}
 * and removes the {@code archiveRawPdfIfEnabled} call by accident.
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2RawArchiveWiringTest {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    @TempDir
    Path archiveRoot;

    @Test
    void successParse_archivesPdfUnderInstitutionSlug() throws Exception {
        final File pdf = new File(CORPUS_DIR, "011826 WellsFargo.pdf");
        assumeTrue(pdf.exists(), "corpus sample missing: " + pdf);

        final PdfRawArchive archive = newArchive(true);
        final PDFImportService svc = newSvcWithArchive(archive);

        try (InputStream in = new FileInputStream(pdf)) {
            final var r = svc.parsePDF(in, pdf.getName(), "wiring-test", null);
            assertNotNull(r, "parse should succeed");
        }

        // The bytes must land under the institution slug — verifies that
        // PDFImportService.archiveRawPdfIfEnabled was called and passed
        // the institution from the DetectedAccount.
        final Path wfDir = archiveRoot.resolve("wells-fargo");
        assertTrue(Files.exists(wfDir),
                "Wells Fargo directory must exist after success parse");
        try (var stream = Files.walk(wfDir)) {
            final long pdfCount = stream
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .count();
            assertTrue(pdfCount >= 1,
                    "at least one .pdf must be archived under wells-fargo/");
        }
    }

    @Test
    void failureParse_archivesUnderUnparseableSlug() throws Exception {
        // Feed a blob that LOOKS like a PDF (passes the magic-bytes check
        // in archiveRawPdfOnFailure) but corrupts after the header so the
        // parser throws. The failure path should route to _unparseable/.
        final byte[] fakePdf = new byte[5000];
        fakePdf[0] = '%'; fakePdf[1] = 'P'; fakePdf[2] = 'D'; fakePdf[3] = 'F';
        // Rest is zero-fill — PDFBox will throw on this.
        final PdfRawArchive archive = newArchive(true);
        final PDFImportService svc = newSvcWithArchive(archive);

        try (InputStream in = new java.io.ByteArrayInputStream(fakePdf)) {
            try {
                svc.parsePDF(in, "broken.pdf", "wiring-test", null);
            } catch (final RuntimeException expected) {
                // Expected — PDFBox throws on corrupt content.
            }
        }
        // The bytes must land under "unparseable" — proves the
        // catch-block archiveRawPdfOnFailure was actually called.
        final Path unparseable = archiveRoot.resolve("unparseable");
        assertTrue(Files.exists(unparseable),
                "_unparseable directory must exist after failed parse");
        try (var stream = Files.walk(unparseable)) {
            final long pdfCount = stream
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .count();
            assertTrue(pdfCount >= 1,
                    "at least one .pdf must be archived under unparseable/");
        }
    }

    @Test
    void disabledArchive_writesNothing() throws Exception {
        final File pdf = new File(CORPUS_DIR, "011826 WellsFargo.pdf");
        assumeTrue(pdf.exists(), "corpus sample missing");

        final PdfRawArchive disabled = newArchive(false);
        final PDFImportService svc = newSvcWithArchive(disabled);

        try (InputStream in = new FileInputStream(pdf)) {
            svc.parsePDF(in, pdf.getName(), "wiring-test", null);
        }
        // Root must be empty (or only contain dirs from the @TempDir
        // initialization — but no .pdf files).
        try (var stream = Files.walk(archiveRoot)) {
            final long pdfCount = stream
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .count();
            assertTrue(pdfCount == 0,
                    "disabled archive must NOT write any .pdf files");
        }
    }

    // --- helpers ---

    private PdfRawArchive newArchive(final boolean enabled) throws Exception {
        final PdfRawArchive a = new PdfRawArchive(null);
        setField(a, "enabled", enabled);
        setField(a, "root", archiveRoot.toString());
        setField(a, "maxBytes", 10_000_000L);
        setField(a, "s3Bucket", "");
        setField(a, "s3Prefix", "");
        return a;
    }

    private PDFImportService newSvcWithArchive(final PdfRawArchive archive) {
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        svc.setPdfRawArchive(archive);
        return svc;
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
