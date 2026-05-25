package com.budgetbuddy.service.pdf.api;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Bag of artefacts produced by {@link PdfBytesReader}. Carries everything
 * downstream stages need without re-reading or re-parsing the PDF.
 *
 * <p>The {@code doc} handle is nullable because OCR-only paths (image PDFs
 * we couldn't open with PDFBox) still produce {@code fullText} but have no
 * usable {@link PDDocument}. Callers must null-check before using it.
 *
 * <p>{@code bytes} is the bounded, magic-byte-validated copy — never the
 * raw upload stream. Bounded means {@link PdfBytesReader} has already
 * enforced the configured max-file-size cap, so consumers can hash it,
 * archive it, or pass it to extractors without re-checking size limits.
 */
public record PdfDocumentContext(
        byte[] bytes,
        @Nullable PDDocument doc,
        String fullText,
        int pageCount) {

    public PdfDocumentContext {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (fullText == null) {
            throw new IllegalArgumentException("fullText must not be null");
        }
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must be >= 0");
        }
    }
}
