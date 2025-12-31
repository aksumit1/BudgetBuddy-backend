package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DynamoDbTransactionHelper
 */
@ExtendWith(MockitoExtension.class)
class DynamoDbTransactionHelperTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private String tableName;

    @BeforeEach
    void setUp() {
        tableName = "TestTable";
    }

    @Test
    void testExecuteTransaction_WithValidItems_ExecutesSuccessfully() {
        // Given
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(Collections.singletonMap("id", AttributeValue.builder().s("test-id").build()))
                        .build())
                .build());

        TransactWriteItemsResponse response = TransactWriteItemsResponse.builder().build();
        when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(response);

        // When
        TransactWriteItemsResponse result = DynamoDbTransactionHelper.executeTransaction(
                dynamoDbClient, tableName, items);

        // Then
        assertNotNull(result);
        verify(dynamoDbClient, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void testExecuteTransaction_WithEmptyItems_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, tableName, Collections.emptyList()));
    }

    @Test
    void testExecuteTransaction_WithNullItems_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, tableName, null));
    }

    @Test
    void testExecuteTransaction_WithMoreThan25Items_ThrowsException() {
        // Given
        List<TransactWriteItem> items = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            items.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(Collections.singletonMap("id", AttributeValue.builder().s("id-" + i).build()))
                            .build())
                    .build());
        }

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, tableName, items));
        assertTrue(exception.getMessage().contains("25"));
    }

    @Test
    void testExecuteTransaction_WithConditionalCheckFailed_ThrowsException() {
        // Given
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(Collections.singletonMap("id", AttributeValue.builder().s("test-id").build()))
                        .build())
                .build());

        ConditionalCheckFailedException conditionalException = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();
        when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(conditionalException);

        // When/Then
        assertThrows(ConditionalCheckFailedException.class, () ->
                DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, tableName, items));
    }

    @Test
    void testCreatePutItem_WithValidInputs_CreatesPutItem() {
        // Given
        Map<String, AttributeValue> item = Collections.singletonMap(
                "id", AttributeValue.builder().s("test-id").build());

        // When
        TransactWriteItem transactItem = DynamoDbTransactionHelper.createPutItem(tableName, item, null);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.put());
        assertEquals(tableName, transactItem.put().tableName());
    }

    @Test
    void testCreatePutItem_WithConditionExpression_IncludesCondition() {
        // Given
        Map<String, AttributeValue> item = Collections.singletonMap(
                "id", AttributeValue.builder().s("test-id").build());
        String conditionExpression = "attribute_not_exists(id)";

        // When
        TransactWriteItem transactItem = DynamoDbTransactionHelper.createPutItem(
                tableName, item, conditionExpression);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.put());
        assertNotNull(transactItem.put().conditionExpression());
    }

    @Test
    void testCreateUpdateItem_WithValidInputs_CreatesUpdateItem() {
        // Given
        Map<String, AttributeValue> key = Collections.singletonMap(
                "id", AttributeValue.builder().s("test-id").build());
        String updateExpression = "SET #attr = :val";
        Map<String, String> expressionAttributeNames = Collections.singletonMap("#attr", "name");
        Map<String, AttributeValue> expressionAttributeValues = Collections.singletonMap(
                ":val", AttributeValue.builder().s("test-value").build());

        // When
        TransactWriteItem transactItem = DynamoDbTransactionHelper.createUpdateItem(
                tableName, key, updateExpression,
                expressionAttributeNames, expressionAttributeValues, null);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.update());
        assertEquals(tableName, transactItem.update().tableName());
    }

    @Test
    void testCreateUpdateItem_WithConditionExpression_IncludesCondition() {
        // Given
        Map<String, AttributeValue> key = Collections.singletonMap(
                "id", AttributeValue.builder().s("test-id").build());
        String updateExpression = "SET #attr = :val";
        Map<String, String> expressionAttributeNames = Collections.singletonMap("#attr", "name");
        Map<String, AttributeValue> expressionAttributeValues = Collections.singletonMap(
                ":val", AttributeValue.builder().s("test-value").build());
        String conditionExpression = "attribute_exists(id)";

        // When
        TransactWriteItem transactItem = DynamoDbTransactionHelper.createUpdateItem(
                tableName, key, updateExpression,
                expressionAttributeNames, expressionAttributeValues, conditionExpression);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.update());
        assertNotNull(transactItem.update().conditionExpression());
    }
}
