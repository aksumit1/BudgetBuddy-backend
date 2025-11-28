package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.GoalTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.HashMap;
import java.util.Map;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository for Goals
 */
@Repository
public class GoalRepository {

    private final DynamoDbTable<GoalTable> goalTable;
    private final DynamoDbIndex<GoalTable> userIdIndex;
    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = "BudgetBuddy-Goals";

    public GoalRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient) {
        this.goalTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(GoalTable.class));
        this.userIdIndex = goalTable.index("UserIdIndex");
        this.dynamoDbClient = dynamoDbClient;
    }

    public void save(final GoalTable goal) {
        goalTable.putItem(goal);
    }

    public Optional<GoalTable> findById(final String goalId) {
        if (goalId == null || goalId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(goalId);
        GoalTable goal = goalTable.getItem(Key.builder().partitionValue(normalizedId).build());
        // If not found with normalized ID, try original (for backward compatibility with mixed-case IDs)
        if (goal == null && !normalizedId.equals(goalId)) {
            goal = goalTable.getItem(Key.builder().partitionValue(goalId).build());
        }
        return Optional.ofNullable(goal);
    }

    public List<GoalTable> findByUserId(final String userId) {
        List<GoalTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<GoalTable>> pages =
                userIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<GoalTable> page : pages) {
            for (GoalTable goal : page.items()) {
                if (goal.getActive() != null && goal.getActive()) {
                    results.add(goal);
                }
            }
        }
        results.sort((g1, g2) -> g1.getTargetDate().compareTo(g2.getTargetDate()));
        return results;
    }

    public void delete(final String goalId) {
        goalTable.deleteItem(Key.builder().partitionValue(goalId).build());
    }

    /**
     * Increment goal progress using UpdateItem with ADD expression (cost-optimized and atomic)
     * Eliminates read-before-write pattern and race conditions
     * 
     * CRITICAL FIX: The previous implementation read the current value and then wrote back
     * the incremented value, which is not atomic. Two concurrent calls could both read the
     * same value, increment it, and write back, causing one increment to be lost.
     * 
     * Solution: Use DynamoDB's low-level client with UpdateItem and ADD expression, which
     * is atomic. This ensures concurrent increments are properly accumulated.
     */
    public void incrementProgress(final String goalId, final BigDecimal amount) {
        if (goalId == null || goalId.isEmpty()) {
            throw new IllegalArgumentException("Goal ID cannot be null or empty");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }

        // Use low-level DynamoDB client for atomic ADD operation
        // DynamoDB Enhanced Client doesn't directly support ADD expressions
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("goalId", AttributeValue.builder().s(goalId).build());

        // Build update expression: ADD currentAmount :amount, SET updatedAt = :updatedAt
        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#currentAmount", "currentAmount");
        expressionAttributeNames.put("#updatedAt", "updatedAt");

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":amount", AttributeValue.builder().n(amount.toString()).build());
        // CRITICAL: Write updatedAt as ISO8601 string (S) to match Enhanced Client's InstantAsStringAttributeConverter
        expressionAttributeValues.put(":updatedAt", AttributeValue.builder().s(Instant.now().toString()).build());

        // Use ADD for atomic increment and SET for updatedAt
        String updateExpression = "ADD #currentAmount :amount SET #updatedAt = :updatedAt";

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                .conditionExpression("attribute_exists(goalId)") // Ensure goal exists
                .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            throw new IllegalArgumentException("Goal not found: " + goalId);
        }
    }

    /**
     * Save goal only if it doesn't exist (conditional write)
     * Prevents accidental overwrites
     */
    public boolean saveIfNotExists(final GoalTable goal) {
        if (goal == null) {
            throw new IllegalArgumentException("Goal cannot be null");
        }
        if (goal.getGoalId() == null || goal.getGoalId().isEmpty()) {
            throw new IllegalArgumentException("Goal ID is required");
        }

        try {
            goalTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(GoalTable.class)
                            .item(goal)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(goalId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // Goal already exists
        }
    }
}

