package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
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
 * DynamoDB Repository for Accounts
 */
@Repository
public class AccountRepository {

    private final DynamoDbTable<AccountTable> accountTable;
    private final DynamoDbIndex<AccountTable> userIdIndex;
    private final DynamoDbIndex<AccountTable> plaidAccountIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-Accounts";

    public AccountRepository(DynamoDbEnhancedClient enhancedClient) {
        this.accountTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(AccountTable.class));
        this.userIdIndex = accountTable.index("UserIdIndex");
        this.plaidAccountIdIndex = accountTable.index("PlaidAccountIdIndex");
    }

    public void save(AccountTable account) {
        accountTable.putItem(account);
    }

    public Optional<AccountTable> findById(String accountId) {
        AccountTable account = accountTable.getItem(Key.builder().partitionValue(accountId).build());
        return Optional.ofNullable(account);
    }

    public List<AccountTable> findByUserId(String userId) {
        return userIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .items()
                .stream()
                .filter(account -> account.getActive() != null && account.getActive())
                .collect(Collectors.toList());
    }

    public Optional<AccountTable> findByPlaidAccountId(String plaidAccountId) {
        var result = plaidAccountIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(plaidAccountId).build()))
                .items()
                .stream()
                .findFirst();
        return result;
    }
}

