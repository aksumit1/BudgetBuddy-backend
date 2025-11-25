package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.GoalTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DynamoDB Repository for Goals
 */
@Repository
public class GoalRepository {

    private final DynamoDbTable<GoalTable> goalTable;
    private final DynamoDbIndex<GoalTable> userIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-Goals";

    public GoalRepository(DynamoDbEnhancedClient enhancedClient) {
        this.goalTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(GoalTable.class));
        this.userIdIndex = goalTable.index("UserIdIndex");
    }

    public void save(GoalTable goal) {
        goalTable.putItem(goal);
    }

    public Optional<GoalTable> findById(String goalId) {
        GoalTable goal = goalTable.getItem(Key.builder().partitionValue(goalId).build());
        return Optional.ofNullable(goal);
    }

    public List<GoalTable> findByUserId(String userId) {
        return userIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .items()
                .stream()
                .filter(goal -> goal.getActive() != null && goal.getActive())
                .sorted((g1, g2) -> g1.getTargetDate().compareTo(g2.getTargetDate()))
                .collect(Collectors.toList());
    }

    public void delete(String goalId) {
        goalTable.deleteItem(Key.builder().partitionValue(goalId).build());
    }
}

