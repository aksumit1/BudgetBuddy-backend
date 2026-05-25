package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.MerchantEnrichmentCacheTable;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * DynamoDB-backed repository for the {@code MerchantEnrichmentCache}
 * table. Exposes simple {@link #get} / {@link #put} — the call site
 * (the {@code MerchantEnrichmentStore} bean) wraps these in
 * {@link Optional} semantics and handles serialisation to/from the
 * domain {@link com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult}.
 *
 * <h3>Graceful degradation</h3>
 *
 * If the table isn't provisioned (ResourceNotFoundException), reads and
 * writes both no-op. Categorisation still works — it just doesn't learn
 * across pods. The IaC stack provisions the table on deploy; in dev /
 * test environments without it, the in-process store fallback takes
 * over.
 */
@Repository
public class MerchantEnrichmentCacheRepository {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MerchantEnrichmentCacheRepository.class);

    private final DynamoDbTable<MerchantEnrichmentCacheTable> table;

    public MerchantEnrichmentCacheRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        // Table name follows the existing prefix-aware addressing pattern
        // used elsewhere in the repo.
        final String tableName = tablePrefix + "-MerchantEnrichmentCache";
        this.table =
                enhancedClient.table(
                        tableName, TableSchema.fromBean(MerchantEnrichmentCacheTable.class));
    }

    public Optional<MerchantEnrichmentCacheTable> get(final String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        try {
            final MerchantEnrichmentCacheTable row =
                    table.getItem(Key.builder().partitionValue(cacheKey).build());
            return Optional.ofNullable(row);
        } catch (ResourceNotFoundException notProvisioned) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("MerchantEnrichmentCache table not provisioned; skipping read");
            }
            return Optional.empty();
        }
    }

    public void put(final MerchantEnrichmentCacheTable row) {
        if (row == null || row.getCacheKey() == null) {
            return;
        }
        try {
            table.putItem(row);
        } catch (ResourceNotFoundException notProvisioned) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MerchantEnrichmentCache table not provisioned; skipping write of {}",
                        row.getCacheKey());
            }
        }
    }

    /**
     * Delete a cached entry — primarily for tests / admin tools that
     * want to refresh a stale entry without waiting for TTL.
     */
    public void delete(final String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        try {
            table.deleteItem(Key.builder().partitionValue(cacheKey).build());
        } catch (ResourceNotFoundException ignored) {
            // table missing → nothing to delete
        }
    }

    /**
     * Stream every row in the cache. Used by {@link
     * com.budgetbuddy.service.category.MerchantRuleExportWorker} to
     * promote stable positive entries into a draft YAML rules file. This
     * is a full table scan and SHOULD only run on the daily export
     * schedule, never on the hot path.
     */
    public java.util.stream.Stream<MerchantEnrichmentCacheTable> scanAll() {
        try {
            return java.util.stream.StreamSupport.stream(
                    table.scan().items().spliterator(), false);
        } catch (ResourceNotFoundException notProvisioned) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("MerchantEnrichmentCache table not provisioned; scan returns empty");
            }
            return java.util.stream.Stream.empty();
        }
    }
}
