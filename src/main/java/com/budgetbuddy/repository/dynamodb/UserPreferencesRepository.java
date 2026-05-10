package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserPreferencesTable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Persistence for per-user preferences (notifications, opt-ins).
 *
 * <p>Opt-in lookups use sparse GSIs ({@code DailyReadEmailOptInIndex}, {@code
 * AnonymisedStatsOptInIndex}) instead of full-table scans. The flag columns are populated only when
 * the user opts in — {@code null} keeps the row out of the index, so the query cost is O(opt-ins)
 * rather than O(users).
 */
@Repository
public class UserPreferencesRepository {

    /** Canonical "opted-in" sentinel written to the sparse-GSI flag columns. */
    public static final String OPTED_IN_FLAG = "1";

    private final DynamoDbTable<UserPreferencesTable> table;
    private final DynamoDbIndex<UserPreferencesTable> dailyReadOptInIndex;
    private final DynamoDbIndex<UserPreferencesTable> anonymisedStatsOptInIndex;

    public UserPreferencesRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        final String tableName = tablePrefix + "-UserPreferences";
        this.table =
                enhancedClient.table(tableName, TableSchema.fromBean(UserPreferencesTable.class));
        this.dailyReadOptInIndex = table.index("DailyReadEmailOptInIndex");
        this.anonymisedStatsOptInIndex = table.index("AnonymisedStatsOptInIndex");
    }

    public Optional<UserPreferencesTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(table.getItem(Key.builder().partitionValue(userId).build()));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    public UserPreferencesTable save(final UserPreferencesTable row) {
        // Keep sparse-GSI flag columns consistent with the boolean primary state
        // so the GSI stays sparse — absent when opted-out, present when opted-in.
        row.setDailyReadEmailEnabledFlag(
                Boolean.TRUE.equals(row.getDailyReadEmailEnabled()) ? OPTED_IN_FLAG : null);
        row.setAnonymisedStatsOptInFlag(
                Boolean.TRUE.equals(row.getShareAnonymisedStats()) ? OPTED_IN_FLAG : null);
        row.setUpdatedAt(Instant.now());
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    table.putItem(row);
                    return null;
                });
        return row;
    }

    /** Opt-ins for the morning-read email. GSI query — O(opt-in count). */
    public List<UserPreferencesTable> findDailyReadOptIns() {
        return queryOptInIndex(dailyReadOptInIndex);
    }

    /** Opt-ins for anonymised-stats contribution to the benchmark aggregator. */
    public List<UserPreferencesTable> findAnonymisedStatsOptIns() {
        return queryOptInIndex(anonymisedStatsOptInIndex);
    }

    private List<UserPreferencesTable> queryOptInIndex(
            final DynamoDbIndex<UserPreferencesTable> index) {
        final List<UserPreferencesTable> out = new ArrayList<>();
        try {
            final var pages =
                    index.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(OPTED_IN_FLAG).build()));
            for (final var page : pages) {
                out.addAll(page.items());
            }
        } catch (ResourceNotFoundException e) {
            // Table or GSI not provisioned yet — degrade to empty result.
        }
        return out;
    }
}
