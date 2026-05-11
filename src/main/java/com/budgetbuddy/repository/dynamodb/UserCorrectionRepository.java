package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserCorrectionTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Repository for the {@code UserCorrections} table — per-user ML training rows produced when a user
 * re-categorises a transaction. {@link com.budgetbuddy.service.CategoryLearningService} still
 * accesses the table directly via {@link DynamoDbTable} for its read/write hot paths; this
 * repository's only job is exposing {@link #deleteByUserId(String)} so {@link
 * com.budgetbuddy.service.UserDeletionService} can sweep these rows for GDPR account-erasure.
 * Without it the corrections would persist indefinitely after account deletion.
 */
@Repository
public class UserCorrectionRepository {

    private final DynamoDbTable<UserCorrectionTable> table;
    private final DynamoDbIndex<UserCorrectionTable> userIdIndex;

    public UserCorrectionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        // CategoryLearningService currently constructs the same DynamoDbTable with a hardcoded
        // "UserCorrections" name (no prefix). That should migrate too, but for now we mirror the
        // hardcoded name so deletion targets the same physical table the service writes to.
        this.table =
                enhancedClient.table(
                        "UserCorrections", TableSchema.fromBean(UserCorrectionTable.class));
        this.userIdIndex = table.index("UserIdDateIndex");
    }

    /**
     * Delete every correction owned by {@code userId}. Walks the {@code UserIdDateIndex} GSI so we
     * never table-scan, then issues a per-row deleteItem against the {@code correctionId} partition
     * key. Returns the count actually removed for audit logging.
     */
    public int deleteByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        try {
            final var pages =
                    userIdIndex.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()));
            for (final var page : pages) {
                for (final UserCorrectionTable row : page.items()) {
                    if (row.getCorrectionId() != null) {
                        table.deleteItem(
                                Key.builder().partitionValue(row.getCorrectionId()).build());
                        deleted++;
                    }
                }
            }
        } catch (
                software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                        notProvisioned) {
            // Table or GSI not present in this environment (e.g. localstack without that table).
            // Treat as no-op so the deletion flow doesn't fail wholesale.
        }
        return deleted;
    }
}
