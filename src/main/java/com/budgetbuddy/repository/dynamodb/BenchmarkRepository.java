package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.BenchmarkTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Repository for the `Benchmarks` table. Writes happen only from the {@link
 * com.budgetbuddy.service.benchmark.BenchmarkAggregationService} cron; the API controller reads by
 * bucketId.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Repository
public class BenchmarkRepository {

    private final DynamoDbTable<BenchmarkTable> table;

    public BenchmarkRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        final String tableName = tablePrefix + "-Benchmarks";
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(BenchmarkTable.class));
    }

    public void save(final BenchmarkTable row) {
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    table.putItem(row);
                    return null;
                });
    }

    public List<BenchmarkTable> findByBucket(final String bucketId) {
        if (bucketId == null || bucketId.isEmpty()) {
            return List.of();
        }
        final List<BenchmarkTable> out = new ArrayList<>();
        try {
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BenchmarkTable>>
                    pages =
                            table.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(bucketId).build()));
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<BenchmarkTable> page :
                    pages) {
                out.addAll(page.items());
            }
        } catch (ResourceNotFoundException e) {
            // Table not yet provisioned — return empty so the controller can fall
            // back to the seeded placeholders.
        }
        return out;
    }
}
