package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = "BudgetBuddy-Accounts";

    public AccountRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient) {
        this.accountTable = enhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(AccountTable.class));
        this.userIdIndex = accountTable.index("UserIdIndex");
        this.plaidAccountIdIndex =
                accountTable.index("PlaidAccountIdIndex");
        this.dynamoDbClient = dynamoDbClient;
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
        int totalFound = 0;
        int activeCount = 0;
        int inactiveCount = 0;
        int nullActiveCount = 0;
        
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                pages = userIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>
                page : pages) {
            for (AccountTable account : page.items()) {
                totalFound++;
                if (account.getActive() == null) {
                    nullActiveCount++;
                    // Include accounts with null active (treat as active for backward compatibility)
                    results.add(account);
                } else if (account.getActive()) {
                    activeCount++;
                    results.add(account);
                } else {
                    inactiveCount++;
                }
            }
        }
        
        // Log for debugging
        if (totalFound > 0) {
            org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                    .debug("findByUserId({}): Found {} total accounts ({} active, {} inactive, {} null active). Returning {} accounts.",
                            userId, totalFound, activeCount, inactiveCount, nullActiveCount, results.size());
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

    public void delete(final String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        accountTable.deleteItem(Key.builder().partitionValue(accountId).build());
    }

    /**
     * Save account only if it doesn't exist (conditional write)
     * Prevents duplicate accounts (deduplication)
     */
    public boolean saveIfNotExists(final AccountTable account) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if (account.getAccountId() == null || account.getAccountId().isEmpty()) {
            throw new IllegalArgumentException("Account ID is required");
        }

        try {
            accountTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(AccountTable.class)
                            .item(account)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(accountId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // Account already exists
        }
    }

    /**
     * Batch save accounts using BatchWriteItem (cost-optimized)
     * DynamoDB allows up to 25 items per batch
     */
    public void batchSave(final List<AccountTable> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        List<List<AccountTable>> batches = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i += batchSize) {
            batches.add(accounts.subList(i, Math.min(i + batchSize, accounts.size())));
        }

        for (List<AccountTable> batch : batches) {
            List<WriteRequest> writeRequests = batch.stream()
                    .map(account -> {
                        Map<String, AttributeValue> item = new HashMap<>();
                        item.put("accountId", AttributeValue.builder().s(account.getAccountId()).build());
                        if (account.getUserId() != null) {
                            item.put("userId", AttributeValue.builder().s(account.getUserId()).build());
                        }
                        // Add other attributes as needed
                        return WriteRequest.builder()
                                .putRequest(PutRequest.builder().item(item).build())
                                .build();
                    })
                    .collect(Collectors.toList());

            Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(TABLE_NAME, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            BatchWriteItemResponse response = com.budgetbuddy.util.RetryHelper.executeWithRetry(() -> {
                BatchWriteItemResponse resp = dynamoDbClient.batchWriteItem(batchRequest);
                
                // Retry if there are unprocessed items
                if (!resp.unprocessedItems().isEmpty()) {
                    throw new RuntimeException("Unprocessed items in batch write");
                }
                
                return resp;
            });

            // All items processed successfully
        }
    }

    /**
     * Batch delete accounts using BatchWriteItem
     */
    public void batchDelete(final List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        for (int i = 0; i < accountIds.size(); i += batchSize) {
            List<String> batch = accountIds.subList(i, Math.min(i + batchSize, accountIds.size()));

            List<WriteRequest> writeRequests = batch.stream()
                    .map(id -> {
                        Map<String, AttributeValue> key = new HashMap<>();
                        key.put("accountId", AttributeValue.builder().s(id).build());
                        return WriteRequest.builder()
                                .deleteRequest(software.amazon.awssdk.services.dynamodb.model.DeleteRequest.builder()
                                        .key(key)
                                        .build())
                                .build();
                    })
                    .collect(Collectors.toList());

            Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(TABLE_NAME, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            dynamoDbClient.batchWriteItem(batchRequest);
        }
    }
}


