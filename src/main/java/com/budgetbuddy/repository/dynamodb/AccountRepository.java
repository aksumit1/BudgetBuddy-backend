package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
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
 * DynamoDB Repository for Accounts
 */
@Repository
public class AccountRepository {

    private final DynamoDbTable<AccountTable> accountTable;
    private final DynamoDbIndex<AccountTable> userIdIndex;
    private final DynamoDbIndex<AccountTable> plaidAccountIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-Accounts";

    public AccountRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.accountTable = enhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(AccountTable.class));
        this.userIdIndex = accountTable.index("UserIdIndex");
        this.plaidAccountIdIndex =
                accountTable.index("PlaidAccountIdIndex");
    }

    public void save(final AccountTable account) {
        accountTable.putItem(account);
    }

    public Optional<AccountTable> findById(String accountId) {
        AccountTable account = accountTable.getItem(
                Key.builder().partitionValue(accountId).build());
        return Optional.ofNullable(account);
    }

    public List<AccountTable> findByUserId(String userId) {
        List<AccountTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                pages = userIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>
                page : pages) {
            for (AccountTable account : page.items()) {
                if (account.getActive() != null && account.getActive()) {
                    results.add(account);
                }
            }
        }
        return results;
    }

    public Optional<AccountTable> findByPlaidAccountId(String plaidAccountId) {
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                pages = plaidAccountIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder()
                                        .partitionValue(plaidAccountId)
                                        .build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>
                page : pages) {
            for (AccountTable item : page.items()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}

