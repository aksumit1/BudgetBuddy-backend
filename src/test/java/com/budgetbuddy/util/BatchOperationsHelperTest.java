package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

/** Unit Tests for BatchOperationsHelper */
@ExtendWith(MockitoExtension.class)
class BatchOperationsHelperTest {

    @Mock private DynamoDbClient dynamoDbClient;

    private String tableName;

    @BeforeEach
    void setUp() {
        tableName = "TestTable";
    }

    @Test
    void testBatchWriteItemsWithValidItemsWritesSuccessfully() {
        // Given
        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s("id-" + i).build());
            items.add(item);
        }

        final BatchWriteItemResponse response = BatchWriteItemResponse.builder().build();
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        final int written = BatchOperationsHelper.batchWriteItems(dynamoDbClient, tableName, items);

        // Then
        assertEquals(10, written);
        verify(dynamoDbClient, atLeastOnce()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchWriteItemsWithEmptyListReturnsZero() {
        // When
        final int written =
                BatchOperationsHelper.batchWriteItems(
                        dynamoDbClient, tableName, Collections.emptyList());

        // Then
        assertEquals(0, written);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchWriteItemsWithNullListReturnsZero() {
        // When
        final int written = BatchOperationsHelper.batchWriteItems(dynamoDbClient, tableName, null);

        // Then
        assertEquals(0, written);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchWriteItemsWithMoreThan25ItemsSplitsIntoBatches() {
        // Given
        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s("id-" + i).build());
            items.add(item);
        }

        final BatchWriteItemResponse response = BatchWriteItemResponse.builder().build();
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        final int written = BatchOperationsHelper.batchWriteItems(dynamoDbClient, tableName, items);

        // Then
        assertEquals(50, written);
        // Should be called at least 2 times (50 items / 25 per batch = 2 batches)
        verify(dynamoDbClient, atLeast(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchDeleteItemsWithValidItemsDeletesSuccessfully() {
        // Given
        final List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Map<String, AttributeValue> key = new HashMap<>();
            key.put("id", AttributeValue.builder().s("id-" + i).build());
            keys.add(key);
        }

        final BatchWriteItemResponse response = BatchWriteItemResponse.builder().build();
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        final int deleted = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, tableName, keys);

        // Then
        assertEquals(10, deleted);
        verify(dynamoDbClient, atLeastOnce()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchDeleteItemsWithEmptyListReturnsZero() {
        // When
        final int deleted =
                BatchOperationsHelper.batchDeleteItems(
                        dynamoDbClient, tableName, Collections.emptyList());

        // Then
        assertEquals(0, deleted);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchDeleteItemsWithNullListReturnsZero() {
        // When
        final int deleted = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, tableName, null);

        // Then
        assertEquals(0, deleted);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void testBatchDeleteItemsWithMoreThan25ItemsSplitsIntoBatches() {
        // Given
        final List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final Map<String, AttributeValue> key = new HashMap<>();
            key.put("id", AttributeValue.builder().s("id-" + i).build());
            keys.add(key);
        }

        final BatchWriteItemResponse response = BatchWriteItemResponse.builder().build();
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        final int deleted = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, tableName, keys);

        // Then
        assertEquals(50, deleted);
        verify(dynamoDbClient, atLeast(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }
}
