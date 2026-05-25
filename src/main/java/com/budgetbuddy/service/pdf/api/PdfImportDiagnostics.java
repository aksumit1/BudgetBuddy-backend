package com.budgetbuddy.service.pdf.api;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Single observability seam for the PDF import path. Collapses what is
 * today four separate try/catch blocks in {@code parsePdfInternal}
 * (metrics, diagnostic-store, raw-archive, correlation) into one call.
 *
 * <p>Implementations must not throw — observability failures must never
 * fail an import. Callers can pass a {@code Throwable} when the import
 * itself failed; the parse-success vs parse-failure distinction is the
 * implementation's job to derive from {@code failure}.
 */
public interface PdfImportDiagnostics {

    /**
     * @param result        the (possibly partial) import result
     * @param failure       null on success; the thrown exception on failure
     * @param durationNanos wall-clock duration of the parse
     * @param pdfBytes      bounded PDF bytes (for archival); may be null
     *                      when read failed before bytes were captured
     * @param fullText      decoded text (for archival); may be null when
     *                      text extraction itself failed
     * @param pageCount     pages in the document; 0 when unknown
     * @param fileName      original filename
     */
    void recordParse(
            ImportResult result,
            @Nullable Throwable failure,
            long durationNanos,
            @Nullable byte[] pdfBytes,
            @Nullable String fullText,
            int pageCount,
            String fileName);
}
