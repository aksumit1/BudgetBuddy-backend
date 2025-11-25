package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.GoalTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.ArrayList;
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

    public GoalRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.goalTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(GoalTable.class));
        this.userIdIndex = goalTable.index("UserIdIndex");
    }

    public void save(final GoalTable goal) {
        goalTable.putItem(goal);
    }

    public Optional<GoalTable> findById(String goalId) {
        GoalTable goal = goalTable.getItem(Key.builder().partitionValue(goalId).build());
        return Optional.ofNullable(goal);
    }

    public List<GoalTable> findByUserId(String userId) {
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
        results.sort(g1, g2) -> g1.getTargetDate().compareTo(g2.getTargetDate()));
        return results;
    }

    public void delete(final String goalId) {
        goalTable.deleteItem(Key.builder().partitionValue(goalId).build());
    }
}

