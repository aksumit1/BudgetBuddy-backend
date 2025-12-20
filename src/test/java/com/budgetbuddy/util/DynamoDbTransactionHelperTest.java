package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DynamoDbTransactionHelper
 */
class DynamoDbTransactionHelperTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should throw exception for null items")
    void testExecuteTransaction_NullItems() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, "TestTable", null);
        });
    }

    @Test
    @DisplayName("Should throw exception for empty items")
    void testExecuteTransaction_EmptyItems() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, "TestTable", new ArrayList<>());
        });
    }

    @Test
    @DisplayName("Should throw exception for more than 25 items")
    void testExecuteTransaction_TooManyItems() {
        // Given - 26 items (exceeds limit of 25)
        List<TransactWriteItem> items = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            items.add(TransactWriteItem.builder().build());
        }

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, "TestTable", items);
        });
    }

    @Test
    @DisplayName("Should execute transaction successfully")
    void testExecuteTransaction_Success() throws Exception {
        // Given
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(createPutItem("TestTable", createTestItem("id1")));
        
        TransactWriteItemsResponse response = TransactWriteItemsResponse.builder().build();
        when(dynamoDbClient.transactWriteItems(any(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest.class)))
                .thenReturn(response);

        // When
        TransactWriteItemsResponse result = DynamoDbTransactionHelper.executeTransaction(
                dynamoDbClient, "TestTable", items);

        // Then
        assertNotNull(result);
        verify(dynamoDbClient, times(1))
                .transactWriteItems(any(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest.class));
    }

    @Test
    @DisplayName("Should create Put item without condition")
    void testCreatePutItem_WithoutCondition() {
        // Given
        Map<String, AttributeValue> item = createTestItem("id1");

        // When
        TransactWriteItem result = DynamoDbTransactionHelper.createPutItem("TestTable", item, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.put());
        assertEquals("TestTable", result.put().tableName());
    }

    @Test
    @DisplayName("Should create Put item with condition")
    void testCreatePutItem_WithCondition() {
        // Given
        Map<String, AttributeValue> item = createTestItem("id1");
        String condition = "attribute_not_exists(id)";

        // When
        TransactWriteItem result = DynamoDbTransactionHelper.createPutItem("TestTable", item, condition);

        // Then
        assertNotNull(result);
        assertNotNull(result.put());
        assertEquals("TestTable", result.put().tableName());
        assertEquals(condition, result.put().conditionExpression());
    }

    @Test
    @DisplayName("Should create Update item")
    void testCreateUpdateItem() {
        // Given
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("id", AttributeValue.builder().s("id1").build());
        String updateExpression = "SET #value = :value";
        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#value", "value");
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":value", AttributeValue.builder().s("newValue").build());

        // When
        TransactWriteItem result = DynamoDbTransactionHelper.createUpdateItem(
                "TestTable", key, updateExpression, expressionAttributeNames, 
                expressionAttributeValues, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.update());
        assertEquals("TestTable", result.update().tableName());
        assertEquals(updateExpression, result.update().updateExpression());
    }

    @Test
    @DisplayName("Should create Update item with condition")
    void testCreateUpdateItem_WithCondition() {
        // Given
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("id", AttributeValue.builder().s("id1").build());
        String updateExpression = "SET #value = :value";
        String condition = "attribute_exists(id)";

        // When
        TransactWriteItem result = DynamoDbTransactionHelper.createUpdateItem(
                "TestTable", key, updateExpression, null, null, condition);

        // Then
        assertNotNull(result);
        assertNotNull(result.update());
        assertEquals(condition, result.update().conditionExpression());
    }

    @Test
    @DisplayName("Should create transaction and update budget atomically")
    void testCreateTransactionAndUpdateBudget() throws Exception {
        // Given
        Map<String, AttributeValue> transactionItem = createTestItem("txn1");
        Map<String, AttributeValue> budgetKey = new HashMap<>();
        budgetKey.put("budgetId", AttributeValue.builder().s("budget1").build());
        String budgetUpdateExpression = "ADD #spent :amount";
        Map<String, String> budgetExpressionAttributeNames = new HashMap<>();
        budgetExpressionAttributeNames.put("#spent", "spent");
        Map<String, AttributeValue> budgetExpressionAttributeValues = new HashMap<>();
        budgetExpressionAttributeValues.put(":amount", AttributeValue.builder().n("100").build());

        TransactWriteItemsResponse response = TransactWriteItemsResponse.builder().build();
        when(dynamoDbClient.transactWriteItems(any(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest.class)))
                .thenReturn(response);

        // When
        assertDoesNotThrow(() -> {
            DynamoDbTransactionHelper.createTransactionAndUpdateBudget(
                    dynamoDbClient, "Transactions", "Budgets",
                    transactionItem, budgetKey, budgetUpdateExpression,
                    budgetExpressionAttributeNames, budgetExpressionAttributeValues);
        });

        // Then
        verify(dynamoDbClient, times(1))
                .transactWriteItems(any(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest.class));
    }

    // Helper methods
    private TransactWriteItem createPutItem(String tableName, Map<String, AttributeValue> item) {
        return DynamoDbTransactionHelper.createPutItem(tableName, item, null);
    }

    private Map<String, AttributeValue> createTestItem(String id) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("value", AttributeValue.builder().s("test").build());
        return item;
    }
}

