package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UncategorisedReviewItemTable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Repository for the {@code UncategorisedReviewItems} table — durable
 * backing for the LLM self-learning queue.
 *
 * <p>Operations are intentionally simple — a future scaling change can
 * add a GSI for indexed pagination without touching callers.
 */
@Repository
public class UncategorisedReviewItemRepository {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(UncategorisedReviewItemRepository.class);

    private final DynamoDbTable<UncategorisedReviewItemTable> table;

    public UncategorisedReviewItemRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.table =
                enhancedClient.table(
                        tablePrefix + "-UncategorisedReviewItems",
                        TableSchema.fromBean(UncategorisedReviewItemTable.class));
    }

    public void save(final UncategorisedReviewItemTable item) {
        if (item == null || item.getItemId() == null) {
            return;
        }
        try {
            table.putItem(item);
        } catch (ResourceNotFoundException notProvisioned) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("UncategorisedReviewItems table missing; dropping submission");
            }
        }
    }

    /**
     * Scan-and-pop up to {@code limit} items. Each item is read and
     * deleted in the same logical step (best-effort — if delete fails,
     * the item may be re-processed; the worker's idempotent write to
     * {@code MerchantEnrichmentStore} handles that gracefully).
     */
    public List<UncategorisedReviewItemTable> drain(final int limit) {
        final List<UncategorisedReviewItemTable> out = new ArrayList<>(limit);
        try {
            final Iterator<UncategorisedReviewItemTable> it =
                    table.scan(ScanEnhancedRequest.builder().limit(limit).build())
                            .items()
                            .iterator();
            while (it.hasNext() && out.size() < limit) {
                final UncategorisedReviewItemTable row = it.next();
                out.add(row);
                try {
                    table.deleteItem(
                            Key.builder().partitionValue(row.getItemId()).build());
                } catch (RuntimeException ex) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Failed to delete drained item {}: {}",
                                row.getItemId(), ex.getMessage());
                    }
                }
            }
        } catch (ResourceNotFoundException notProvisioned) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("UncategorisedReviewItems table missing; nothing to drain");
            }
        }
        return out;
    }

    /** Counts rows by scan — used for the size() metric. */
    public int approximateSize() {
        try {
            int count = 0;
            for (final UncategorisedReviewItemTable ignored : table.scan().items()) {
                count++;
            }
            return count;
        } catch (ResourceNotFoundException ignored) {
            return 0;
        }
    }
}
