package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

/** Unit Tests for DynamoDbTransactionHelper */
@ExtendWith(MockitoExtension.class)
class DynamoDbTransactionHelperTest {

    private static final String TEST_ID = "test-id";

    @Mock private DynamoDbClient dynamoDbClient;

    private String tableName;

    @BeforeEach
    void setUp() {
        tableName = "TestTable";
    }

    @Test
    void testExecuteTransactionWithValidItemsExecutesSuccessfully() {
        // Given
        final List<TransactWriteItem> items = new ArrayList<>();
        items.add(
                TransactWriteItem.builder()
                        .put(
                                Put.builder()
                                        .tableName(tableName)
                                        .item(
                                                Collections.singletonMap(
                                                        "id",
                                                        AttributeValue.builder()
                                                                .s(TEST_ID)
                                                                .build()))
                                        .build())
                        .build());

        final TransactWriteItemsResponse response = TransactWriteItemsResponse.builder().build();
        when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(response);

        // When
        final TransactWriteItemsResponse result =
                DynamoDbTransactionHelper.executeTransaction(dynamoDbClient, tableName, items);

        // Then
        assertNotNull(result);
        verify(dynamoDbClient, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void testExecuteTransactionWithEmptyItemsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DynamoDbTransactionHelper.executeTransaction(
                                dynamoDbClient, tableName, Collections.emptyList()));
    }

    @Test
    void testExecuteTransactionWithNullItemsThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DynamoDbTransactionHelper.executeTransaction(
                                dynamoDbClient, tableName, null));
    }

    @Test
    void testExecuteTransactionWithMoreThan25ItemsThrowsException() {
        // Given
        final List<TransactWriteItem> items = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            items.add(
                    TransactWriteItem.builder()
                            .put(
                                    Put.builder()
                                            .tableName(tableName)
                                            .item(
                                                    Collections.singletonMap(
                                                            "id",
                                                            AttributeValue.builder()
                                                                    .s("id-" + i)
                                                                    .build()))
                                            .build())
                            .build());
        }

        // When/Then
        final IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                DynamoDbTransactionHelper.executeTransaction(
                                        dynamoDbClient, tableName, items));
        assertTrue(exception.getMessage().contains("25"));
    }

    @Test
    void testExecuteTransactionWithConditionalCheckFailedThrowsException() {
        // Given
        final List<TransactWriteItem> items = new ArrayList<>();
        items.add(
                TransactWriteItem.builder()
                        .put(
                                Put.builder()
                                        .tableName(tableName)
                                        .item(
                                                Collections.singletonMap(
                                                        "id",
                                                        AttributeValue.builder()
                                                                .s(TEST_ID)
                                                                .build()))
                                        .build())
                        .build());

        final ConditionalCheckFailedException conditionalException =
                ConditionalCheckFailedException.builder()
                        .message("Conditional check failed")
                        .build();
        when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(conditionalException);

        // When/Then
        assertThrows(
                ConditionalCheckFailedException.class,
                () ->
                        DynamoDbTransactionHelper.executeTransaction(
                                dynamoDbClient, tableName, items));
    }

    @Test
    void testCreatePutItemWithValidInputsCreatesPutItem() {
        // Given
        final Map<String, AttributeValue> item =
                Collections.singletonMap("id", AttributeValue.builder().s(TEST_ID).build());

        // When
        final TransactWriteItem transactItem =
                DynamoDbTransactionHelper.createPutItem(tableName, item, null);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.put());
        assertEquals(tableName, transactItem.put().tableName());
    }

    @Test
    void testCreatePutItemWithConditionExpressionIncludesCondition() {
        // Given
        final Map<String, AttributeValue> item =
                Collections.singletonMap("id", AttributeValue.builder().s(TEST_ID).build());
        final String conditionExpression = "attribute_not_exists(id)";

        // When
        final TransactWriteItem transactItem =
                DynamoDbTransactionHelper.createPutItem(tableName, item, conditionExpression);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.put());
        assertNotNull(transactItem.put().conditionExpression());
    }

    @Test
    void testCreateUpdateItemWithValidInputsCreatesUpdateItem() {
        // Given
        final Map<String, AttributeValue> key =
                Collections.singletonMap("id", AttributeValue.builder().s(TEST_ID).build());
        final String updateExpression = "SET #attr = :val";
        final Map<String, String> expressionAttributeNames =
                Collections.singletonMap("#attr", "name");
        final Map<String, AttributeValue> expressionAttributeValues =
                Collections.singletonMap(":val", AttributeValue.builder().s("test-value").build());

        // When
        final TransactWriteItem transactItem =
                DynamoDbTransactionHelper.createUpdateItem(
                        tableName,
                        key,
                        updateExpression,
                        expressionAttributeNames,
                        expressionAttributeValues,
                        null);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.update());
        assertEquals(tableName, transactItem.update().tableName());
    }

    @Test
    void testCreateUpdateItemWithConditionExpressionIncludesCondition() {
        // Given
        final Map<String, AttributeValue> key =
                Collections.singletonMap("id", AttributeValue.builder().s(TEST_ID).build());
        final String updateExpression = "SET #attr = :val";
        final Map<String, String> expressionAttributeNames =
                Collections.singletonMap("#attr", "name");
        final Map<String, AttributeValue> expressionAttributeValues =
                Collections.singletonMap(":val", AttributeValue.builder().s("test-value").build());
        final String conditionExpression = "attribute_exists(id)";

        // When
        final TransactWriteItem transactItem =
                DynamoDbTransactionHelper.createUpdateItem(
                        tableName,
                        key,
                        updateExpression,
                        expressionAttributeNames,
                        expressionAttributeValues,
                        conditionExpression);

        // Then
        assertNotNull(transactItem);
        assertNotNull(transactItem.update());
        assertNotNull(transactItem.update().conditionExpression());
    }
}
