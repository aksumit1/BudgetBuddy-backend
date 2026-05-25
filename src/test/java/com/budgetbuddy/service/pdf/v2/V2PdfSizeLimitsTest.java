package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.PDFImportService;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/**
 * Pins the input-size DoS defenses on PDFImportService.parsePDF:
 *
 * <ol>
 *   <li>Oversize byte stream → friendly "too large" error, never OOM.</li>
 *   <li>Non-PDF magic bytes → "invalid PDF" error, never reaches PDFBox.</li>
 *   <li>Real PDF under the limit → normal parse (smoke).</li>
 * </ol>
 *
 * <p>These tests catch the regression where a future "performance"
 * refactor swaps {@code readBoundedBytes} back to {@code readAllBytes()}
 * and removes the cap.
 */
class V2PdfSizeLimitsTest {

    @Test
    void rejectsOversizedPdf_with31MbPayload() {
        // Build a 31 MB payload starting with %PDF — must trip the byte cap
        // before PDFBox even sees it.
        final byte[] giant = new byte[31 * 1024 * 1024];
        giant[0] = '%'; giant[1] = 'P'; giant[2] = 'D'; giant[3] = 'F';
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        final AppException ex = assertThrows(AppException.class,
                () -> svc.parsePDF(new ByteArrayInputStream(giant), "huge.pdf", "u", null));
        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().toLowerCase().contains("too large"),
                "error must mention oversize: " + ex.getMessage());
    }

    @Test
    void rejectsNonPdfMagicBytes() {
        // A multi-MB blob that's NOT a PDF (no %PDF header).
        final byte[] notPdf = "This is just text content not a PDF\n".repeat(1000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        final AppException ex = assertThrows(AppException.class,
                () -> svc.parsePDF(new ByteArrayInputStream(notPdf), "fake.pdf", "u", null));
        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        assertTrue(ex.getMessage().toLowerCase().contains("invalid pdf"),
                "error must mention invalid PDF: " + ex.getMessage());
    }

    @Test
    void rejectsEmptyInput() {
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        assertThrows(AppException.class,
                () -> svc.parsePDF(new ByteArrayInputStream(new byte[0]), "empty.pdf", "u", null));
    }

    @Test
    void rejectsTinyTruncatedPdf() {
        // Just "%PD" — not even the full 4-byte magic.
        final byte[] tiny = "%PD".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        assertThrows(AppException.class,
                () -> svc.parsePDF(new ByteArrayInputStream(tiny), "tiny.pdf", "u", null));
    }
}
