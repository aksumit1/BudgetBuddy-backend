package com.budgetbuddy.service.pdf.api;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Extracts the transaction rows from a PDF. The largest of the five
 * services after the split (~2,500 LOC) because it carries both the
 * legacy Pattern 1-7 regex parser and the V2 YAML cutover.
 *
 * <p>Cutover policy lives behind the interface: implementations consult
 * the {@code V2_TX_PRODUCTION_ISSUERS} allow-list and pick the legacy or
 * V2 path per-issuer. Shadow comparison (V2 runs in shadow mode against
 * legacy and divergences get logged) is also an implementation detail.
 */
public interface PdfTransactionExtractor {

    /**
     * @param result        already-populated with metadata; may be read
     *                      from for issuer/account context but should not
     *                      be mutated here (transactions are returned
     *                      separately and merged by the orchestrator)
     * @param fullText      decoded PDF text
     * @param pdfBytes      bounded PDF bytes (for parsers that need to
     *                      re-open the document, e.g. for table extraction)
     * @param fileName      original filename
     * @param inferredYear  year hint; null when none available
     * @param password      optional password for encrypted PDFs
     * @return parsed transactions, in source order; never null, may be empty
     */
    List<ParsedTransaction> extract(
            ImportResult result,
            String fullText,
            byte[] pdfBytes,
            String fileName,
            @Nullable Integer inferredYear,
            @Nullable String password);
}
