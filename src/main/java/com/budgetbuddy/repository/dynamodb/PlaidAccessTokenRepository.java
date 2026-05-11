package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.PlaidAccessTokenTable;
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
 * Backend-side store for Plaid access tokens. See {@link PlaidAccessTokenTable} for schema
 * rationale; this repository keeps the surface small (save / find / delete) so the encryption
 * boundary stays at the table level — no caller can accidentally bypass KMS.
 *
 * <p>Used by {@code PlaidSyncService.scheduledSync} so the nightly batch can iterate every user's
 * Plaid items without relying on the iOS keychain. Used by the webhook handler to look up the
 * access token associated with an inbound {@code item_id}.
 */
@Repository
public class PlaidAccessTokenRepository {

    private final DynamoDbTable<PlaidAccessTokenTable> table;
    private final DynamoDbIndex<PlaidAccessTokenTable> itemIdIndex;

    public PlaidAccessTokenRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.table =
                enhancedClient.table(
                        tablePrefix + "-PlaidAccessTokens",
                        TableSchema.fromBean(PlaidAccessTokenTable.class));
        this.itemIdIndex = table.index("PlaidItemIdIndex");
    }

    /**
     * Persist a new or updated access-token row. Always set {@code createdAt} on first insert and
     * {@code updatedAt} on every save.
     */
    public void save(final PlaidAccessTokenTable row) {
        if (row == null) {
            return;
        }
        final Instant now = Instant.now();
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        table.putItem(row);
    }

    /**
     * Look up by (userId, plaidItemId). Returns empty when the row is missing or the table isn't
     * provisioned yet (typical in localstack test runs before this CFN ships).
     */
    public Optional<PlaidAccessTokenTable> findByUserAndItem(
            final String userId, final String plaidItemId) {
        if (userId == null || plaidItemId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    table.getItem(
                            Key.builder().partitionValue(userId).sortValue(plaidItemId).build()));
        } catch (ResourceNotFoundException notProvisioned) {
            return Optional.empty();
        }
    }

    /** Look up by item-id only — used by the inbound webhook handler. */
    public Optional<PlaidAccessTokenTable> findByItemId(final String plaidItemId) {
        if (plaidItemId == null || plaidItemId.isEmpty()) {
            return Optional.empty();
        }
        try {
            for (final var page :
                    itemIdIndex.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(plaidItemId).build()))) {
                for (final PlaidAccessTokenTable row : page.items()) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        } catch (ResourceNotFoundException notProvisioned) {
            return Optional.empty();
        }
    }

    /**
     * Iterate every access-token row in the table — used by the daily scheduled sync. Uses a
     * lazy {@link Iterable} so callers can stream without loading every row into a single list
     * (~bounded but still cheaper if there are many users). Guarded at the call site by
     * {@code ScanRateLimiter} so this rare-but-table-scan operation can't blow the DDB bill.
     */
    public Iterable<PlaidAccessTokenTable> findAll() {
        try {
            return () -> {
                final java.util.Iterator<PlaidAccessTokenTable> empty =
                        java.util.Collections.emptyIterator();
                try {
                    final var pages = table.scan();
                    final java.util.Iterator<
                                    software.amazon.awssdk.enhanced.dynamodb.model.Page<
                                            PlaidAccessTokenTable>>
                            pageIt = pages.iterator();
                    return new java.util.Iterator<>() {
                        private java.util.Iterator<PlaidAccessTokenTable> current =
                                java.util.Collections.emptyIterator();

                        @Override
                        public boolean hasNext() {
                            while (!current.hasNext() && pageIt.hasNext()) {
                                current = pageIt.next().items().iterator();
                            }
                            return current.hasNext();
                        }

                        @Override
                        public PlaidAccessTokenTable next() {
                            if (!hasNext()) {
                                throw new java.util.NoSuchElementException();
                            }
                            return current.next();
                        }
                    };
                } catch (ResourceNotFoundException notProvisioned) {
                    return empty;
                }
            };
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Find every access-token row for a user — used by the scheduled sync path. */
    public List<PlaidAccessTokenTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<PlaidAccessTokenTable> out = new ArrayList<>();
        try {
            for (final var page :
                    table.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()))) {
                for (final PlaidAccessTokenTable row : page.items()) {
                    out.add(row);
                }
            }
        } catch (ResourceNotFoundException notProvisioned) {
            // degrade to empty
        }
        return out;
    }

    /**
     * Delete all access tokens for a user — GDPR account-erasure hook. Walks the partition key
     * directly (not a GSI) so it's a single Dynamo query + per-row deletes.
     */
    public int deleteByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        try {
            for (final var page :
                    table.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()))) {
                for (final PlaidAccessTokenTable row : page.items()) {
                    if (row.getUserId() != null && row.getPlaidItemId() != null) {
                        table.deleteItem(
                                Key.builder()
                                        .partitionValue(row.getUserId())
                                        .sortValue(row.getPlaidItemId())
                                        .build());
                        deleted++;
                    }
                }
            }
        } catch (ResourceNotFoundException notProvisioned) {
            // degrade to no-op
        }
        return deleted;
    }
}
