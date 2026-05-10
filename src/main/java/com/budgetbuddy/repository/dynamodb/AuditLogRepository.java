package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.compliance.AuditLogTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/** DynamoDB Repository for Audit Logs */
@Repository
public class AuditLogRepository {

    private final DynamoDbTable<AuditLogTable> auditLogTable;
    private final DynamoDbIndex<AuditLogTable> userIdCreatedAtIndex;
    private final String tableName;

    public AuditLogRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-AuditLogs";
        this.auditLogTable =
                enhancedClient.table(this.tableName, TableSchema.fromBean(AuditLogTable.class));
        this.userIdCreatedAtIndex = auditLogTable.index("UserIdCreatedAtIndex");
    }

    public void save(final AuditLogTable auditLog) {
        auditLogTable.putItem(auditLog);
    }

    public List<AuditLogTable> findByUserIdAndDateRange(
            final String userId, final Long startTimestamp, final Long endTimestamp) {
        final List<AuditLogTable> results = new ArrayList<>();
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AuditLogTable>>
                pages =
                        userIdCreatedAtIndex.query(
                                QueryConditional.keyEqualTo(
                                        Key.builder().partitionValue(userId).build()));
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<AuditLogTable> page :
                pages) {
            for (final AuditLogTable log : page.items()) {
                if (log.getCreatedAt() >= startTimestamp && log.getCreatedAt() <= endTimestamp) {
                    results.add(log);
                }
            }
        }
        return results;
    }
}
