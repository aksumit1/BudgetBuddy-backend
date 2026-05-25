package com.budgetbuddy.service.pdf.jobs;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.TransactionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared batch-persist helper used by BOTH the synchronous
 * {@code /import-pdf} controller path AND the async
 * {@link PdfImportJobWorker}. Extracted so the worker doesn't need to
 * call back into the controller (wrong dependency direction —
 * service → controller cycles).
 *
 * <p>Returns a tally so callers can update job status + return counts
 * to the user. Per-row failures are caught + counted; one bad row
 * doesn't fail the rest of the batch.
 */
@Service
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
public class PdfTransactionPersister {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfTransactionPersister.class);

    private final TransactionService transactionService;

    public PdfTransactionPersister(final TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** Tally returned to the caller for status-reporting. */
    public record Tally(int total, int created, int failed) { }

    /**
     * Persist each parsed transaction via the consolidated create-and-
     * enrich path. Single-call per tx (createTransactionFromParsedPdf
     * collapses create + update). Errors per-row are caught and counted
     * so a single bad row doesn't fail the whole batch.
     */
    public Tally persist(
            final UserTable user,
            final List<PDFImportService.ParsedTransaction> parsedTxs,
            final String importSource,
            final String fileName) {
        if (parsedTxs == null || parsedTxs.isEmpty()) {
            return new Tally(0, 0, 0);
        }
        final String batchId = UUID.randomUUID().toString();
        int created = 0;
        int failed = 0;
        for (final PDFImportService.ParsedTransaction parsed : parsedTxs) {
            try {
                final TransactionTable createdTx =
                        transactionService.createTransactionFromParsedPdf(
                                user, parsed, batchId, fileName);
                if (createdTx != null) created++;
                else failed++;
            } catch (final Exception e) {
                failed++;
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Per-tx persist failed (continuing batch): desc='{}' amount={} cause={}",
                            parsed.getDescription(),
                            parsed.getAmount(),
                            e.getMessage());
                }
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "PdfTransactionPersister: batch {} → {} created, {} failed (file={})",
                    batchId, created, failed, fileName);
        }
        return new Tally(parsedTxs.size(), created, failed);
    }
}
