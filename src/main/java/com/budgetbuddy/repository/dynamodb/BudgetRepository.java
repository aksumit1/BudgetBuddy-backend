package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.BudgetTable;
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
 * DynamoDB Repository for Budgets
 */
@Repository
public class BudgetRepository {

    private final DynamoDbTable<BudgetTable> budgetTable;
    private final DynamoDbIndex<BudgetTable> userIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-Budgets";

    public BudgetRepository(DynamoDbEnhancedClient enhancedClient) {
        this.budgetTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(BudgetTable.class));
        this.userIdIndex = budgetTable.index("UserIdIndex");
    }

    public void save(BudgetTable budget) {
        budgetTable.putItem(budget);
    }

    public Optional<BudgetTable> findById(String budgetId) {
        BudgetTable budget = budgetTable.getItem(Key.builder().partitionValue(budgetId).build());
        return Optional.ofNullable(budget);
    }

    public List<BudgetTable> findByUserId(String userId) {
        List<BudgetTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages = 
                userIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable> page : pages) {
            for (BudgetTable item : page.items()) {
                results.add(item);
            }
        }
        return results;
    }

    public Optional<BudgetTable> findByUserIdAndCategory(String userId, String category) {
        return findByUserId(userId).stream()
                .filter(b -> category.equals(b.getCategory()))
                .findFirst();
    }

    public void delete(String budgetId) {
        budgetTable.deleteItem(Key.builder().partitionValue(budgetId).build());
    }
}

