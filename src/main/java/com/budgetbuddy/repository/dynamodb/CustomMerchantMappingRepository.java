package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Repository for the {@code CustomMerchantMappings} table — per-user merchant→category overrides
 * written when a user pins a categorisation. Like {@link UserCorrectionRepository} this only
 * exposes {@link #deleteByUserId(String)} for GDPR account-erasure; the read/write hot path still
 * lives in {@link com.budgetbuddy.service.CategoryLearningService}.
 */
@Repository
public class CustomMerchantMappingRepository {

    private final DynamoDbTable<CustomMerchantMappingTable> table;
    private final DynamoDbIndex<CustomMerchantMappingTable> userIdIndex;

    public CustomMerchantMappingRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        // Mirror CategoryLearningService's hardcoded table name. When that service migrates to
        // prefix-aware addressing this repository should too.
        this.table =
                enhancedClient.table(
                        "CustomMerchantMappings",
                        TableSchema.fromBean(CustomMerchantMappingTable.class));
        this.userIdIndex = table.index("UserIdActiveIndex");
    }

    /**
     * Delete every mapping owned by {@code userId}. Walks the {@code UserIdActiveIndex} GSI so the
     * call is per-user, deletes by {@code mappingId} partition key. Returns the count actually
     * removed for audit logging.
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
                for (final CustomMerchantMappingTable row : page.items()) {
                    if (row.getMappingId() != null) {
                        table.deleteItem(Key.builder().partitionValue(row.getMappingId()).build());
                        deleted++;
                    }
                }
            }
        } catch (
                software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                        notProvisioned) {
            // Table or GSI not present in this environment — degrade to no-op.
        }
        return deleted;
    }
}
