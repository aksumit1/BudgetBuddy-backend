package com.budgetbuddy.service.importer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.DuplicateDetectionService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.TransactionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the create-many-transactions flow that CSV, Excel, and PDF import endpoints share.
 *
 * <p><strong>Why this exists.</strong> Prior to the extraction, {@code TransactionController} owned
 * {@code processBatchImport} — a 150-line private method that combined duplicate detection,
 * batching, per-row {@code createTransaction}, and async subscription detection. Every new format
 * (PDF, Excel, chunked upload) forked the method, so bug fixes had to be ported across four
 * near-copies. This class is the single shared implementation; the controllers call {@link
 * #processBatch} and focus on routing + HTTP concerns.
 *
 * <p>Contract: never throws into the caller. Failure modes are reported via {@link
 * BatchImportResult} counts; the caller decides the HTTP status.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass"})
@Service
public class TransactionImportOrchestrator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionImportOrchestrator.class);

    /**
     * Tuned for balance of memory use and UI progress granularity. Larger batches starve the main
     * thread on big imports; smaller batches multiply DynamoDB round-trips. 500 is the sweet spot
     * measured on iPhone 13 Pro over LTE.
     */
    private static final int BATCH_SIZE = 500;

    private final TransactionService transactionService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final SubscriptionService subscriptionService;

    public TransactionImportOrchestrator(
            final TransactionService transactionService,
            final DuplicateDetectionService duplicateDetectionService,
            final SubscriptionService subscriptionService) {
        this.transactionService = transactionService;
        this.duplicateDetectionService = duplicateDetectionService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Process a list of parsed transactions: detect duplicates, create the non-duplicates in
     * batches, kick off async subscription detection if anything was created.
     */
    public BatchImportResult processBatch(
            final UserTable user,
            final List<CSVImportService.ParsedTransaction> transactions,
            final String importSource,
            final String fileName) {
        final String batchId = UUID.randomUUID().toString();
        final int totalTransactions = transactions.size();

        // Step 1 — detect duplicates in bulk so we don't query per-row.
        final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicateMap =
                detectDuplicates(user, transactions);

        // Step 2 — process in batches.
        LOGGER.info(
                "📦 Processing {} transactions in batches of {} for {} import",
                totalTransactions,
                BATCH_SIZE,
                importSource);
        int created = 0;
        int failed = 0;
        int duplicates = 0;
        for (int i = 0; i < totalTransactions; i += BATCH_SIZE) {
            final int endIndex = Math.min(i + BATCH_SIZE, totalTransactions);
            final int batchNumber = (i / BATCH_SIZE) + 1;
            final int totalBatches = (int) Math.ceil((double) totalTransactions / BATCH_SIZE);
            int batchCreated = 0;
            int batchFailed = 0;
            int batchDuplicates = 0;

            for (int j = i; j < endIndex; j++) {
                final CSVImportService.ParsedTransaction parsed = transactions.get(j);
                if (duplicateMap.containsKey(j)) {
                    logImportDuplicate(j, parsed, duplicateMap.get(j));
                    batchDuplicates++;
                    duplicates++;
                    continue;
                }
                try {
                    createOne(user, parsed, importSource, batchId, fileName);
                    batchCreated++;
                    created++;
                } catch (Exception e) {
                    LOGGER.error("Failed to create transaction from import: {}", e.getMessage(), e);
                    batchFailed++;
                    failed++;
                }
            }
            LOGGER.info(
                    "✅ Batch {}/{} completed: {} created, {} failed, {} duplicates (total: {}/{}/{})",
                    batchNumber,
                    totalBatches,
                    batchCreated,
                    batchFailed,
                    batchDuplicates,
                    created,
                    failed,
                    duplicates);
        }

        LOGGER.info(
                "🎉 Import completed: {} total, {} created, {} failed, {} duplicates",
                totalTransactions,
                created,
                failed,
                duplicates);

        // Step 3 — subscription detection is a happy-path bonus, not a
        // correctness requirement, so it runs async and errors are swallowed.
        if (created > 0) {
            triggerSubscriptionDetection(user.getUserId(), importSource, created);
        }

        return new BatchImportResult(totalTransactions, created, failed, duplicates);
    }

    private Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> detectDuplicates(
            final UserTable user, final List<CSVImportService.ParsedTransaction> transactions) {
        final List<DuplicateDetectionService.ParsedTransaction> forCheck =
                new ArrayList<>(transactions.size());
        for (final CSVImportService.ParsedTransaction parsed : transactions) {
            final DuplicateDetectionService.ParsedTransaction dupTx =
                    new DuplicateDetectionService.ParsedTransaction(
                            parsed.getDate(), parsed.getAmount(),
                            parsed.getDescription(), parsed.getMerchantName());
            dupTx.setTransactionId(parsed.getTransactionId());
            forCheck.add(dupTx);
        }
        return duplicateDetectionService.detectDuplicates(user.getUserId(), forCheck);
    }

    private void createOne(
            final UserTable user,
            final CSVImportService.ParsedTransaction parsed,
            final String importSource,
            final String batchId,
            final String fileName) {
        transactionService.createTransaction(
                user,
                parsed.getAccountId(),
                parsed.getAmount(),
                parsed.getDate(),
                parsed.getDescription(),
                parsed.getCategoryPrimary(),
                parsed.getCategoryDetailed(),
                parsed.getImporterCategoryPrimary(),
                parsed.getImporterCategoryDetailed(),
                parsed.getTransactionId(),
                null, // notes
                null, // plaidAccountId
                null, // plaidTransactionId
                parsed.getTransactionType(),
                parsed.getCurrencyCode(),
                importSource,
                batchId,
                fileName,
                null, // reviewStatus
                parsed.getMerchantName(),
                parsed.getLocation(),
                parsed.getPaymentChannel(),
                null, // userName
                null, // goalId
                null // linkedTransactionId
                );
    }

    private static void logImportDuplicate(
            final int index,
            final CSVImportService.ParsedTransaction parsed,
            final List<DuplicateDetectionService.DuplicateMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            LOGGER.info(
                    "⏭️ Skipping exact duplicate (index {}): merchant='{}', amount={}, date={}",
                    index,
                    parsed.getMerchantName(),
                    parsed.getAmount(),
                    parsed.getDate());
        } else {
            LOGGER.info(
                    "⏭️ Skipping fuzzy duplicate (index {}): merchant='{}', amount={}, date={} (similarity: {})",
                    index,
                    parsed.getMerchantName(),
                    parsed.getAmount(),
                    parsed.getDate(),
                    matches.get(0).getSimilarity());
        }
    }

    private void triggerSubscriptionDetection(
            final String userId, final String importSource, final int createdCount) {
        try {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            final List<com.budgetbuddy.model.Subscription> detected =
                                    subscriptionService.detectSubscriptions(userId);
                            if (!detected.isEmpty()) {
                                subscriptionService.saveSubscriptions(userId, detected);
                                LOGGER.info(
                                        "Detected {} subscriptions after {} import ({} transactions created)",
                                        detected.size(),
                                        importSource,
                                        createdCount);
                            }
                        } catch (Exception e) {
                            LOGGER.warn(
                                    "Subscription detection after {} import failed: {}",
                                    importSource,
                                    e.getMessage());
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn(
                    "Could not schedule subscription detection after {} import: {}",
                    importSource,
                    e.getMessage());
        }
    }

    /**
     * Immutable result of a batch import. Controllers wrap this in a response DTO; anything else
     * (logs, metrics, tests) reads the fields directly.
     */
    public static final class BatchImportResult {
        private final int total;
        private final int created;
        private final int failed;
        private final int duplicates;

        public BatchImportResult(
                final int total, final int created, final int failed, final int duplicates) {
            this.total = total;
            this.created = created;
            this.failed = failed;
            this.duplicates = duplicates;
        }

        public int getTotal() {
            return total;
        }

        public int getCreated() {
            return created;
        }

        public int getFailed() {
            return failed;
        }

        public int getDuplicates() {
            return duplicates;
        }
    }
}
