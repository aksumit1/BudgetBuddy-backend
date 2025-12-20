package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB Transaction Helper
 * Provides support for multi-item atomic operations using DynamoDB Transactions
 * 
 * CRITICAL: DynamoDB Transactions ensure atomicity across multiple items
 * - All operations succeed or all fail (ACID properties)
 * - Eliminates TOCTOU windows and race conditions
 * - Maximum 25 items per transaction
 */
public class DynamoDbTransactionHelper {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTransactionHelper.class);
    private static final int MAX_TRANSACTION_ITEMS = 25;

    private DynamoDbTransactionHelper() {
        // Utility class
    }

    /**
     * Execute a DynamoDB transaction with retry logic
     * 
     * @param dynamoDbClient DynamoDB client
     * @param tableName Table name
     * @param items List of transaction items (Put, Update, Delete, ConditionCheck)
     * @return Transaction response
     * @throws IllegalArgumentException if more than 25 items provided
     */
    public static TransactWriteItemsResponse executeTransaction(
            final DynamoDbClient dynamoDbClient,
            final String tableName,
            final List<TransactWriteItem> items) {
        
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Transaction items cannot be empty");
        }
        
        if (items.size() > MAX_TRANSACTION_ITEMS) {
            throw new IllegalArgumentException(
                    String.format("DynamoDB transactions support maximum %d items, got %d", 
                            MAX_TRANSACTION_ITEMS, items.size()));
        }

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(items)
                .build();

        // Execute with retry for throttling
        return RetryHelper.executeDynamoDbWithRetry(() -> {
            try {
                TransactWriteItemsResponse response = dynamoDbClient.transactWriteItems(request);
                logger.debug("DynamoDB transaction completed successfully with {} items", items.size());
                return response;
            } catch (ConditionalCheckFailedException e) {
                // Don't retry conditional check failures - they indicate business logic conflicts
                logger.warn("DynamoDB transaction failed due to conditional check: {}", e.getMessage());
                throw e;
            }
        });
    }

    /**
     * Create a Put item for transaction
     */
    public static TransactWriteItem createPutItem(
            final String tableName,
            final Map<String, AttributeValue> item,
            final String conditionExpression) {
        
        Put.Builder putBuilder = Put.builder()
                .tableName(tableName)
                .item(item);
        
        if (conditionExpression != null && !conditionExpression.isEmpty()) {
            putBuilder.conditionExpression(conditionExpression);
        }
        
        return TransactWriteItem.builder()
                .put(putBuilder.build())
                .build();
    }

    /**
     * Create an Update item for transaction
     */
    public static TransactWriteItem createUpdateItem(
            final String tableName,
            final Map<String, AttributeValue> key,
            final String updateExpression,
            final Map<String, String> expressionAttributeNames,
            final Map<String, AttributeValue> expressionAttributeValues,
            final String conditionExpression) {
        
        Update.Builder updateBuilder = Update.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression);
        
        if (expressionAttributeNames != null && !expressionAttributeNames.isEmpty()) {
            updateBuilder.expressionAttributeNames(expressionAttributeNames);
        }
        
        if (expressionAttributeValues != null && !expressionAttributeValues.isEmpty()) {
            updateBuilder.expressionAttributeValues(expressionAttributeValues);
        }
        
        if (conditionExpression != null && !conditionExpression.isEmpty()) {
            updateBuilder.conditionExpression(conditionExpression);
        }
        
        return TransactWriteItem.builder()
                .update(updateBuilder.build())
                .build();
    }

    /**
     * Example: Create transaction and update budget in a single atomic operation
     * This ensures both operations succeed or both fail
     */
    public static void createTransactionAndUpdateBudget(
            final DynamoDbClient dynamoDbClient,
            final String transactionTableName,
            final String budgetTableName,
            final Map<String, AttributeValue> transactionItem,
            final Map<String, AttributeValue> budgetKey,
            final String budgetUpdateExpression,
            final Map<String, String> budgetExpressionAttributeNames,
            final Map<String, AttributeValue> budgetExpressionAttributeValues) {
        
        List<TransactWriteItem> items = new ArrayList<>();
        
        // Add transaction creation
        items.add(createPutItem(
                transactionTableName,
                transactionItem,
                "attribute_not_exists(transactionId)" // Prevent duplicates
        ));
        
        // Add budget update
        items.add(createUpdateItem(
                budgetTableName,
                budgetKey,
                budgetUpdateExpression,
                budgetExpressionAttributeNames,
                budgetExpressionAttributeValues,
                "attribute_exists(budgetId)" // Ensure budget exists
        ));
        
        executeTransaction(dynamoDbClient, transactionTableName, items);
        logger.info("Successfully created transaction and updated budget atomically");
    }
}

