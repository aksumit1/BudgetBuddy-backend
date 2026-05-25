package com.budgetbuddy.service.pdf.api;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Per-PDF state {@link PdfTransactionEnricher} needs when enriching each
 * parsed transaction. Built once by the orchestrator, reused for every
 * transaction in the import — keeps the enricher signature stable as new
 * cross-transaction context (e.g. statement period, statement currency)
 * gets added.
 *
 * <p>{@code account} is nullable because account detection can fail (rare,
 * but happens with severely mangled PDFs); enrichment must still run and
 * fall back to per-transaction heuristics.
 */
public record EnrichmentContext(
        @Nullable DetectedAccount account,
        @Nullable Integer inferredYear,
        @Nullable String statementCurrency,
        String fileName) {

    public EnrichmentContext {
        if (fileName == null) {
            throw new IllegalArgumentException("fileName must not be null");
        }
    }
}
