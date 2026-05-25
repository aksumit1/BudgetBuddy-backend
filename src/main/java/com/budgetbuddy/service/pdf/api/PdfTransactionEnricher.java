package com.budgetbuddy.service.pdf.api;

import com.budgetbuddy.service.PDFImportService.ParsedTransaction;

/**
 * Per-transaction post-processor. Fills in everything the raw extractor
 * couldn't determine from a single line of statement text: geo
 * (city/state/country), merchant-name cleanup, wallet detection,
 * payment-channel derivation, transaction-type derivation, FX attachment.
 *
 * <p>The enrich pass runs after the orchestrator has all transactions in
 * hand so it can use cross-row context (e.g. the same merchant appearing
 * in multiple rows can be normalised consistently). Some of this logic
 * already lives in
 * {@code com.budgetbuddy.service.pdf.enrich.PdfDerivedFields} — Phase 2
 * will fold that helper behind this interface.
 */
public interface PdfTransactionEnricher {

    /**
     * Enrich a single transaction in place. Implementations must be
     * idempotent — running {@code enrich} twice on the same transaction
     * must produce the same result as running it once. This invariant
     * lets the orchestrator retry safely after a partial failure.
     *
     * @param tx  the transaction to enrich; mutated in place
     * @param ctx PDF-level state shared across all transactions in this
     *            import
     */
    void enrich(ParsedTransaction tx, EnrichmentContext ctx);
}
