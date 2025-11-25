package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.Optional;

/**
 * DynamoDB Repository for Users
 * Uses GSI for email lookup (cost-optimized)
 */
@Repository
public class UserRepository {

    private final DynamoDbTable<UserTable> userTable;
    private static final String TABLE_NAME = "BudgetBuddy-Users";
    private static final String EMAIL_INDEX = "EmailIndex";

    public UserRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.userTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(UserTable.class));
    }

    public void save((final UserTable user) {
        userTable.putItem(user);
    }

    public Optional<UserTable> findById(String userId) {
        UserTable user = userTable.getItem(Key.builder().partitionValue(userId).build());
        return Optional.ofNullable(user);
    }

    public Optional<UserTable> findByEmail(String email) {
        // Query GSI for email lookup
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable>> pages =
                userTable.index(EMAIL_INDEX).query(QueryConditional.keyEqualTo(Key.builder().partitionValue(email).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable> page : pages) {
            for (UserTable item : page.items()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public boolean existsByEmail((final String email) {
        return findByEmail(email).isPresent();
    }

    public void delete((final String userId) {
        userTable.deleteItem(Key.builder().partitionValue(userId).build());
    }
}

