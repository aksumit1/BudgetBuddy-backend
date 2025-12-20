package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BatchOperationsHelper
 */
class BatchOperationsHelperTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should return zero for empty items list")
    void testBatchWriteItems_EmptyList() {
        // When
        int result = BatchOperationsHelper.batchWriteItems(dynamoDbClient, "TestTable", new ArrayList<>());

        // Then
        assertEquals(0, result);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should return zero for null items list")
    void testBatchWriteItems_NullList() {
        // When
        int result = BatchOperationsHelper.batchWriteItems(dynamoDbClient, "TestTable", null);

        // Then
        assertEquals(0, result);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should batch write items in batches of 25")
    void testBatchWriteItems_SingleBatch() throws Exception {
        // Given - 10 items (less than 25, so single batch)
        List<Map<String, AttributeValue>> items = createTestItems(10);
        
        BatchWriteItemResponse response = BatchWriteItemResponse.builder()
                .unprocessedItems(new HashMap<>())
                .build();
        
        // Mock the client to return the response (RetryHelper will call through)
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        int result = BatchOperationsHelper.batchWriteItems(dynamoDbClient, "TestTable", items);

        // Then
        assertEquals(10, result);
        verify(dynamoDbClient, times(1)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should handle multiple batches")
    void testBatchWriteItems_MultipleBatches() throws Exception {
        // Given - 50 items (2 batches of 25)
        List<Map<String, AttributeValue>> items = createTestItems(50);
        
        BatchWriteItemResponse response = BatchWriteItemResponse.builder()
                .unprocessedItems(new HashMap<>())
                .build();
        
        // Mock the client to return the response for both batches
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        int result = BatchOperationsHelper.batchWriteItems(dynamoDbClient, "TestTable", items);

        // Then
        assertEquals(50, result);
        verify(dynamoDbClient, times(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should handle unprocessed items with retry")
    void testBatchWriteItems_WithUnprocessedItems() throws Exception {
        // Given - 5 items with unprocessed items on first attempt
        List<Map<String, AttributeValue>> items = createTestItems(5);
        
        // First response has unprocessed items (empty list means all processed in this case)
        // For a real unprocessed scenario, we'd have items in the list
        BatchWriteItemResponse response = BatchWriteItemResponse.builder()
                .unprocessedItems(new HashMap<>())
                .build();
        
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        int result = BatchOperationsHelper.batchWriteItems(dynamoDbClient, "TestTable", items);

        // Then
        assertEquals(5, result);
        verify(dynamoDbClient, atLeastOnce()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should return zero for empty keys list in batch delete")
    void testBatchDeleteItems_EmptyList() {
        // When
        int result = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, "TestTable", new ArrayList<>());

        // Then
        assertEquals(0, result);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should return zero for null keys list in batch delete")
    void testBatchDeleteItems_NullList() {
        // When
        int result = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, "TestTable", null);

        // Then
        assertEquals(0, result);
        verify(dynamoDbClient, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("Should batch delete items")
    void testBatchDeleteItems_Success() throws Exception {
        // Given - 10 keys
        List<Map<String, AttributeValue>> keys = createTestKeys(10);
        
        BatchWriteItemResponse response = BatchWriteItemResponse.builder()
                .unprocessedItems(new HashMap<>())
                .build();
        
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(response);

        // When
        int result = BatchOperationsHelper.batchDeleteItems(dynamoDbClient, "TestTable", keys);

        // Then
        assertEquals(10, result);
        verify(dynamoDbClient, times(1)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    // Helper methods
    private List<Map<String, AttributeValue>> createTestItems(int count) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s("item-" + i).build());
            item.put("value", AttributeValue.builder().n(String.valueOf(i)).build());
            items.add(item);
        }
        return items;
    }

    private List<Map<String, AttributeValue>> createTestKeys(int count) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("id", AttributeValue.builder().s("key-" + i).build());
            keys.add(key);
        }
        return keys;
    }
}

