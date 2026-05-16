package com.budgetbuddy.service.category;

import com.budgetbuddy.model.dynamodb.UncategorisedReviewItemTable;
import com.budgetbuddy.repository.dynamodb.UncategorisedReviewItemRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Records transactions the deterministic categoriser (L0-L8) couldn't
 * resolve. The {@link SelfLearningWorker} drains this queue
 * asynchronously, calls the {@link LlmCategorySuggester}, and writes
 * accepted suggestions to {@link MerchantEnrichmentStore} so the
 * next import sees a cache hit.
 *
 * <h3>Why a queue (not a synchronous LLM call)</h3>
 *
 * Calling an LLM during PDF import would add seconds per row, cost money,
 * and risk rate-limits killing imports. The queue decouples "got an
 * uncategorised row" from "ask the LLM" so the import path stays fast
 * and deterministic.
 *
 * <h3>Storage</h3>
 *
 * {@link InProcess} default holds the queue in-memory — fine for one
 * pod, lost on restart. Production should back this with SQS or a
 * DynamoDB table so the queue survives restarts and scales across
 * multiple worker instances.
 */
public interface UncategorisedReviewQueue {

    /**
     * Submit a transaction context that fell through the cascade. The
     * caller never blocks; submission is fire-and-forget.
     */
    void submit(LlmCategorySuggester.SuggestionContext context);

    /**
     * Drain up to {@code maxItems} pending items. Called by the
     * background worker. Order is FIFO. Items are removed from the
     * queue when drained; if the worker crashes mid-batch they're lost
     * (unless the production impl uses a transactional queue like SQS).
     */
    Iterable<LlmCategorySuggester.SuggestionContext> drain(int maxItems);

    int size();

    /**
     * In-process FIFO default. Production should swap in an SQS- or
     * DynamoDB-backed implementation for durability + multi-pod scale.
     */
    @Service
    final class InProcess implements UncategorisedReviewQueue {

        private static final Logger LOGGER = LoggerFactory.getLogger(InProcess.class);
        private static final int MAX_SIZE = 10_000;

        private final ConcurrentLinkedQueue<LlmCategorySuggester.SuggestionContext> queue =
                new ConcurrentLinkedQueue<>();

        @Override
        public void submit(final LlmCategorySuggester.SuggestionContext context) {
            if (context == null) {
                return;
            }
            // Bounded: drop oldest if we exceed the cap (back-pressure).
            // In a production SQS-backed impl this becomes "let SQS handle it".
            if (queue.size() >= MAX_SIZE) {
                queue.poll();
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "UncategorisedReviewQueue full ({} items); dropped oldest. "
                                    + "Run worker more often or move to SQS-backed impl.",
                            MAX_SIZE);
                }
            }
            queue.offer(context);
        }

        @Override
        public Iterable<LlmCategorySuggester.SuggestionContext> drain(final int maxItems) {
            return () -> new Iterator<>() {
                int taken = 0;

                @Override
                public boolean hasNext() {
                    return taken < maxItems && !queue.isEmpty();
                }

                @Override
                public LlmCategorySuggester.SuggestionContext next() {
                    taken++;
                    return queue.poll();
                }
            };
        }

        @Override
        public int size() {
            return queue.size();
        }
    }

    /**
     * DynamoDB-backed durable queue. Items survive pod restarts and span
     * multiple worker pods. Stale items auto-evict via the table's 7-day
     * TTL.
     *
     * <p>Marked {@code @Primary} so when the table-bearing infrastructure
     * is deployed, this implementation wins over the {@link InProcess}
     * default automatically. In environments without DynamoDB (local
     * tests, dev pods without IAM), the repo no-ops gracefully and the
     * queue effectively becomes write-only / read-empty — categorisation
     * still works, the LLM loop just doesn't run.
     */
    @Service
    @Primary
    final class DynamoDbBacked implements UncategorisedReviewQueue {

        private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDbBacked.class);
        private static final long ITEM_TTL_DAYS = 7;

        private final UncategorisedReviewItemRepository repo;

        public DynamoDbBacked(final UncategorisedReviewItemRepository repo) {
            this.repo = repo;
        }

        @Override
        public void submit(final LlmCategorySuggester.SuggestionContext context) {
            if (context == null) {
                return;
            }
            try {
                final Instant now = Instant.now();
                final UncategorisedReviewItemTable row = new UncategorisedReviewItemTable();
                row.setItemId(UUID.randomUUID().toString());
                row.setMerchantName(context.merchantName);
                row.setDescription(context.description);
                row.setCity(context.city);
                row.setState(context.state);
                row.setCountry(context.country);
                row.setAmount(context.amount);
                row.setIssuerName(context.issuerName);
                row.setAccountType(context.accountType);
                row.setSubmittedAt(now);
                row.setTtl(now.plus(ITEM_TTL_DAYS, ChronoUnit.DAYS).getEpochSecond());
                repo.save(row);
            } catch (RuntimeException ex) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Failed to persist review item for '{}': {}",
                            context.merchantName, ex.getMessage());
                }
            }
        }

        @Override
        public Iterable<LlmCategorySuggester.SuggestionContext> drain(final int maxItems) {
            final List<UncategorisedReviewItemTable> rows = repo.drain(maxItems);
            final List<LlmCategorySuggester.SuggestionContext> out = new ArrayList<>(rows.size());
            for (final UncategorisedReviewItemTable row : rows) {
                out.add(new LlmCategorySuggester.SuggestionContext(
                        row.getMerchantName(),
                        row.getDescription(),
                        row.getCity(),
                        row.getState(),
                        row.getCountry(),
                        row.getAmount(),
                        row.getIssuerName(),
                        row.getAccountType()));
            }
            return out;
        }

        @Override
        public int size() {
            return repo.approximateSize();
        }
    }
}
