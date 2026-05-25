package com.budgetbuddy.service.pdf.api;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Populates the non-transaction fields of {@link ImportResult}: issuer,
 * statement period, totals, fees, balances, rewards. Runs once per PDF,
 * after account detection and before transaction extraction.
 *
 * <p>Today this work is spread across {@code IssuerProfile} implementations
 * (Wells Fargo, Chase, Citi, etc.) plus the V2 fill-missing pass. Phase 2
 * will collapse them behind this single interface.
 */
public interface PdfMetadataExtractor {

    /**
     * Read metadata out of {@code fullText} and write it into {@code result}.
     *
     * @param result        accumulator to populate; mutated in place
     * @param fullText      decoded PDF text
     * @param account       already-detected account; may be null
     * @param inferredYear  year inferred from filename or statement period;
     *                      may be null when the source has no year hint
     */
    void extract(
            ImportResult result,
            String fullText,
            @Nullable DetectedAccount account,
            @Nullable Integer inferredYear);
}
