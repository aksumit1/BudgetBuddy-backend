package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository for Transaction Actions
 * Uses GSI for efficient queries by transactionId and userId
 */
@Repository
public class TransactionActionRepository {

    private final DynamoDbTable<TransactionActionTable> actionTable;
    private final DynamoDbIndex<TransactionActionTable> transactionIdIndex;
    private final DynamoDbIndex<TransactionActionTable> userIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-TransactionActions";

    public TransactionActionRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.actionTable = enhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(TransactionActionTable.class));
        this.transactionIdIndex = actionTable.index("TransactionIdIndex");
        this.userIdIndex = actionTable.index("UserIdIndex");
    }

    public void save(final TransactionActionTable action) {
        if (action == null) {
            throw new IllegalArgumentException("Transaction action cannot be null");
        }
        actionTable.putItem(action);
    }

    public Optional<TransactionActionTable> findById(final String actionId) {
        if (actionId == null || actionId.isEmpty()) {
            return Optional.empty();
        }
        TransactionActionTable action = actionTable.getItem(
                Key.builder().partitionValue(actionId).build());
        return Optional.ofNullable(action);
    }

    /**
     * Find all actions for a transaction using GSI
     */
    public List<TransactionActionTable> findByTransactionId(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return List.of();
        }
        List<TransactionActionTable> results = new ArrayList<>();
        transactionIdIndex.query(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(transactionId).build())
        ).items().forEach(results::add);
        return results;
    }

    /**
     * Find all actions for a user using GSI
     */
    public List<TransactionActionTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        List<TransactionActionTable> results = new ArrayList<>();
        userIdIndex.query(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build())
        ).items().forEach(results::add);
        return results;
    }

    public void delete(final String actionId) {
        if (actionId == null || actionId.isEmpty()) {
            throw new IllegalArgumentException("Action ID cannot be null or empty");
        }
        actionTable.deleteItem(Key.builder().partitionValue(actionId).build());
    }
}

