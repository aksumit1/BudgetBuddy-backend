package com.budgetbuddy.service.pdf.api;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.InputStream;

/**
 * Reads a PDF upload into a bounded, validated in-memory representation
 * plus extracted full text. The single seam where all input-DoS defenses
 * live: file-size cap, magic-byte validation, page-count cap,
 * text-length cap, and OCR fallback for image PDFs.
 *
 * <p>Phase 1 contract: no implementation in this package yet —
 * {@code PDFImportService} still owns the reading logic. The interface is
 * defined so Phase 2 can lift it out without rewriting callers.
 *
 * @see <a href="file:../../../../docs/pdf-importer-split-design.md">design doc</a>
 */
public interface PdfBytesReader {

    /**
     * Read {@code in} into a bounded byte buffer, validate it, and extract
     * full text. Implementations must close {@code in}.
     *
     * @param in       upload stream; closed by callee
     * @param fileName original filename (for diagnostics + OCR hint)
     * @param password optional password for encrypted PDFs; may be null
     * @return populated context; never null
     * @throws RuntimeException on size-cap exceeded, bad magic bytes, or
     *                          PDFBox failure that OCR couldn't rescue
     */
    PdfDocumentContext read(InputStream in, String fileName, @Nullable String password);
}
