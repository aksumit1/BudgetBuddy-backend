package com.budgetbuddy.service.pdf.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of the monolith split lands the contracts before any code
 * moves. These tests pin the contracts so accidental breaking changes
 * (renaming a method, changing a record field) get caught even though
 * none of the production code calls these interfaces yet. Phase 2 will
 * extract real implementations and the compilation guarantees from this
 * test stay useful as anchors.
 */
class PdfApiContractTest {

    @Test
    void pdfDocumentContext_rejectsNullBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new PdfDocumentContext(null, null, "text", 1));
    }

    @Test
    void pdfDocumentContext_rejectsNullFullText() {
        assertThrows(IllegalArgumentException.class,
                () -> new PdfDocumentContext(new byte[0], null, null, 1));
    }

    @Test
    void pdfDocumentContext_rejectsNegativePageCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new PdfDocumentContext(new byte[0], null, "text", -1));
    }

    @Test
    void pdfDocumentContext_zeroPageCount_isLegal() {
        // OCR fallback can produce text with pageCount=0 when PDFBox
        // couldn't open the document. Don't reject it.
        final PdfDocumentContext ctx = new PdfDocumentContext(new byte[]{1}, null, "ocr-text", 0);
        assertEquals(0, ctx.pageCount());
        assertNull(ctx.doc());
        assertEquals("ocr-text", ctx.fullText());
    }

    @Test
    void enrichmentContext_rejectsNullFileName() {
        assertThrows(IllegalArgumentException.class,
                () -> new EnrichmentContext(null, null, null, null));
    }

    @Test
    void enrichmentContext_allowsAllOtherFieldsNull() {
        final EnrichmentContext ctx = new EnrichmentContext(null, null, null, "x.pdf");
        assertNull(ctx.account());
        assertNull(ctx.inferredYear());
        assertNull(ctx.statementCurrency());
        assertEquals("x.pdf", ctx.fileName());
    }

    // The remaining tests are compile-time pins: if a method signature in
    // one of the interfaces ever changes, these stub implementations
    // refuse to compile. We don't assert on behaviour — Phase 2 will own
    // the real implementations.

    @Test
    void pdfBytesReader_canBeImplemented() {
        final PdfBytesReader reader =
                (final InputStream in, final String fileName, final String password) ->
                        new PdfDocumentContext(new byte[0], null, "", 0);
        try (InputStream in = new ByteArrayInputStream(new byte[0])) {
            assertNotNull(reader.read(in, "stub.pdf", null));
        } catch (final java.io.IOException unreachable) {
            throw new AssertionError(unreachable);
        }
    }

    @Test
    void pdfMetadataExtractor_canBeImplemented() {
        final PdfMetadataExtractor extractor =
                (final ImportResult result, final String text,
                        final DetectedAccount account, final Integer year) -> { /* no-op */ };
        extractor.extract(new ImportResult(), "", null, null);
    }

    @Test
    void pdfTransactionExtractor_canBeImplemented() {
        final PdfTransactionExtractor extractor =
                (final ImportResult result, final String text, final byte[] bytes,
                        final String fileName, final Integer year, final String password) ->
                        new ArrayList<>();
        final List<ParsedTransaction> out = extractor.extract(
                new ImportResult(), "", new byte[0], "x.pdf", null, null);
        assertTrue(out.isEmpty());
    }

    @Test
    void pdfTransactionEnricher_canBeImplemented() {
        final PdfTransactionEnricher enricher =
                (final ParsedTransaction tx, final EnrichmentContext ctx) -> { /* no-op */ };
        enricher.enrich(new ParsedTransaction(), new EnrichmentContext(null, null, null, "x.pdf"));
    }

    @Test
    void pdfImportDiagnostics_canBeImplemented() {
        final PdfImportDiagnostics diag =
                (final ImportResult result, final Throwable failure, final long nanos,
                        final byte[] bytes, final String text, final int pages,
                        final String fileName) -> { /* no-op */ };
        diag.recordParse(new ImportResult(), null, 0L, null, null, 0, "x.pdf");
    }
}
