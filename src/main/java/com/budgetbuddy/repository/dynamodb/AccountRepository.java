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
    private final String tableName;

    public AccountRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-Accounts";
        this.accountTable = enhancedClient.table(this.tableName,
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
        if (accountId == null || accountId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(accountId);
        AccountTable account = accountTable.getItem(
                Key.builder().partitionValue(normalizedId).build());
        // If not found with normalized ID, try original (for backward compatibility with mixed-case IDs)
        if (account == null && !normalizedId.equals(accountId)) {
            account = accountTable.getItem(
                    Key.builder().partitionValue(accountId).build());
        }
        return Optional.ofNullable(account);
    }

    public List<AccountTable> findByUserId(String userId) {
        List<AccountTable> results = new ArrayList<>();
        int totalFound = 0;
        int activeCount = 0;
        int inactiveCount = 0;
        int nullActiveCount = 0;
        int duplicateCount = 0;
        
        // CRITICAL: Deduplicate accounts by both accountId and plaidAccountId
        // Use Set to track seen accountIds and plaidAccountIds to prevent duplicates
        java.util.Set<String> seenAccountIds = new java.util.HashSet<>();
        java.util.Set<String> seenPlaidAccountIds = new java.util.HashSet<>();
        
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>>
                pages = userIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>
                page : pages) {
            for (AccountTable account : page.items()) {
                totalFound++;
                
                // Check for duplicates by accountId
                String accountId = account.getAccountId();
                if (accountId != null && seenAccountIds.contains(accountId)) {
                    duplicateCount++;
                    org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                            .warn("Duplicate account detected by accountId and filtered: accountId={}, plaidAccountId={}, name={}",
                                    accountId, account.getPlaidAccountId(), account.getAccountName());
                    continue;
                }
                
                // Check for duplicates by plaidAccountId (if present)
                String plaidAccountId = account.getPlaidAccountId();
                if (plaidAccountId != null && !plaidAccountId.isEmpty()) {
                    if (seenPlaidAccountIds.contains(plaidAccountId)) {
                        duplicateCount++;
                        org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                                .warn("Duplicate account detected by plaidAccountId and filtered: accountId={}, plaidAccountId={}, name={}",
                                        accountId, plaidAccountId, account.getAccountName());
                        continue;
                    }
                    seenPlaidAccountIds.add(plaidAccountId);
                }
                
                // Account is unique, add to results
                if (accountId != null) {
                    seenAccountIds.add(accountId);
                }
                
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
                    .info("findByUserId({}): Found {} total accounts ({} active, {} inactive, {} null active, {} duplicates filtered). Returning {} unique accounts.",
                            userId, totalFound, activeCount, inactiveCount, nullActiveCount, duplicateCount, results.size());
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

    /**
     * Find account by account number (mask) and institution name
     * Used for deduplication when plaidAccountId is not available
     * Note: This scans the table, so for production with large datasets, consider adding a GSI
     */
    public Optional<AccountTable> findByAccountNumberAndInstitution(String accountNumber, String institutionName, String userId) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return Optional.empty();
        }
        
        try {
            // Query by userId first (using GSI) for efficiency
            List<AccountTable> userAccounts = findByUserId(userId);
            
            // Filter by account number and institution name (if provided)
            // CRITICAL FIX: If institutionName is null, match by accountNumber only
            // This handles cases where institutionName is missing but accountNumber is available
            if (institutionName == null || institutionName.isEmpty()) {
                // Match by accountNumber only when institutionName is not available
                return userAccounts.stream()
                        .filter(account -> accountNumber.equals(account.getAccountNumber()))
                        .findFirst();
            } else {
                // Match by both accountNumber and institutionName when both are available
                return userAccounts.stream()
                        .filter(account -> accountNumber.equals(account.getAccountNumber()) 
                                && institutionName.equals(account.getInstitutionName()))
                        .findFirst();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                    .error("Error finding account by account number {} and institution {}: {}", 
                            accountNumber, institutionName, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    /**
     * Find account by account number only (fallback when institution name is missing)
     * Used for deduplication when plaidAccountId is not available and institutionName is null
     */
    public Optional<AccountTable> findByAccountNumber(String accountNumber, String userId) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return Optional.empty();
        }
        
        try {
            List<AccountTable> userAccounts = findByUserId(userId);
            return userAccounts.stream()
                    .filter(account -> accountNumber.equals(account.getAccountNumber()))
                    .findFirst();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                    .error("Error finding account by account number {}: {}", 
                            accountNumber, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find all accounts by Plaid item ID
     * Used for webhook processing to find all accounts associated with a Plaid item
     */
    public List<AccountTable> findByPlaidItemId(String plaidItemId) {
        if (plaidItemId == null || plaidItemId.isEmpty()) {
            return List.of();
        }
        
        List<AccountTable> results = new ArrayList<>();
        try {
            // Scan table with filter expression for plaidItemId
            // Note: For production with large datasets, consider adding a GSI on plaidItemId
            software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest scanRequest =
                    software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest.builder()
                            .filterExpression(
                                    Expression.builder()
                                            .expression("plaidItemId = :itemId")
                                            .putExpressionValue(":itemId",
                                                    software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                                            .s(plaidItemId)
                                                            .build())
                                            .build())
                            .build();

            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable>> pages =
                    accountTable.scan(scanRequest);

            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<AccountTable> page : pages) {
                results.addAll(page.items());
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AccountRepository.class)
                    .error("Error finding accounts by Plaid item ID {}: {}", plaidItemId, e.getMessage(), e);
        }
        
        return results;
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
            requestItems.put(this.tableName, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            com.budgetbuddy.util.RetryHelper.executeWithRetry(() -> {
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
            requestItems.put(this.tableName, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            dynamoDbClient.batchWriteItem(batchRequest);
        }
    }
}


