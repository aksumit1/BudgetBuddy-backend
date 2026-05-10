package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.NetWorthSnapshotTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Flow 7 / O8 — DynamoDB access for net-worth snapshots.
 *
 * <p>Two methods cover all needs: {@code save} (nightly job) and {@code findByUserIdSince} (trend
 * chart loading the last N snapshots). The job uses a deterministic primary key so re-running it
 * for the same day is idempotent.
 */
@Repository
public class NetWorthSnapshotRepository {

    private final DynamoDbTable<NetWorthSnapshotTable> table;
    private final DynamoDbIndex<NetWorthSnapshotTable> userIdDateIndex;

    public NetWorthSnapshotRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.table =
                enhancedClient.table(
                        tablePrefix + "-NetWorthSnapshots",
                        TableSchema.fromBean(NetWorthSnapshotTable.class));
        this.userIdDateIndex = table.index("UserIdSnapshotDateIndex");
    }

    public void save(final NetWorthSnapshotTable snapshot) {
        table.putItem(snapshot);
    }

    public List<NetWorthSnapshotTable> findByUserIdSince(
            final String userId, final String sinceDate) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<NetWorthSnapshotTable> out = new ArrayList<>();
        final var pages =
                userIdDateIndex.query(
                        QueryConditional.sortGreaterThanOrEqualTo(
                                Key.builder().partitionValue(userId).sortValue(sinceDate).build()));
        for (final var page : pages) {
            for (final var item : page.items()) {
                out.add(item);
            }
        }
        // Sort ascending by date so the chart consumes them in order.
        out.sort(
                (a, b) -> {
                    final String da = a.getSnapshotDate() == null ? "" : a.getSnapshotDate();
                    final String db = b.getSnapshotDate() == null ? "" : b.getSnapshotDate();
                    return da.compareTo(db);
                });
        return out;
    }
}
