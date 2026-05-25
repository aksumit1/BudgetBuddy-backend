/**
 * Phase 1 of the PDFImportService monolith split (see
 * {@code docs/pdf-importer-split-design.md}).
 *
 * <p>This package defines the seams the orchestrator will collapse onto in
 * Phase 2 — 5 narrow interfaces plus the shared records they exchange. No
 * implementations live here yet; {@code PDFImportService} continues to do
 * the work end-to-end until Phase 2 extracts each responsibility into a
 * dedicated service. Landing the contracts first lets review of each
 * extraction PR focus on whether the new bean honours the contract,
 * instead of debating the contract itself in the middle of a 2k-line diff.
 *
 * <p>The dependency graph is one-directional: orchestrator → all
 * interfaces; no interface depends on another. {@link
 * com.budgetbuddy.service.pdf.api.PdfTransactionEnricher} is the only
 * per-transaction call; the others run once per PDF.
 */
package com.budgetbuddy.service.pdf.api;
