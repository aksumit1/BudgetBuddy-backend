package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AnomalyFeedbackTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Flow 7 / O1 — DynamoDB access for anomaly feedback. Small surface: save, find-by-id,
 * list-by-user. The per-user list is read every time we run detection so we can filter out
 * previously-dismissed patterns — callers should cache it for the window of a single detection
 * pass.
 */
@Repository
public class AnomalyFeedbackRepository {

    private final DynamoDbTable<AnomalyFeedbackTable> table;
    private final DynamoDbIndex<AnomalyFeedbackTable> userIdIndex;

    public AnomalyFeedbackRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.table =
                enhancedClient.table(
                        tablePrefix + "-AnomalyFeedback",
                        TableSchema.fromBean(AnomalyFeedbackTable.class));
        this.userIdIndex = table.index("UserIdIndex");
    }

    public void save(final AnomalyFeedbackTable feedback) {
        table.putItem(feedback);
    }

    public Optional<AnomalyFeedbackTable> findById(final String feedbackId) {
        if (feedbackId == null || feedbackId.isEmpty()) {
            return Optional.empty();
        }
        final AnomalyFeedbackTable found =
                table.getItem(Key.builder().partitionValue(feedbackId).build());
        return Optional.ofNullable(found);
    }

    public List<AnomalyFeedbackTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<AnomalyFeedbackTable> out = new ArrayList<>();
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AnomalyFeedbackTable>>
                pages =
                        userIdIndex.query(
                                QueryConditional.keyEqualTo(
                                        Key.builder().partitionValue(userId).build()));
        for (final var page : pages) {
            for (final var item : page.items()) {
                out.add(item);
            }
        }
        return out;
    }
}
