package com.budgetbuddy.service.dynamodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DynamoDBTableManager
 * Tests DynamoDB table creation and initialization
 */
@ExtendWith(MockitoExtension.class)
class DynamoDBTableManagerTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @InjectMocks
    private DynamoDBTableManager dynamoDBTableManager;

    @BeforeEach
    void setUp() {
        dynamoDBTableManager = new DynamoDBTableManager(dynamoDbClient, "TestPrefix");
    }

    @Test
    void testInitializeTables_WithTableAlreadyExists_HandlesGracefully() {
        // Given
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenThrow(ResourceInUseException.builder().build());

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> {
            dynamoDBTableManager.initializeTables();
        });
    }

    @Test
    void testInitializeTables_WithTableCreationSuccess_CreatesTables() {
        // Given
        CreateTableResponse response = CreateTableResponse.builder().build();
        when(dynamoDbClient.createTable(any(CreateTableRequest.class))).thenReturn(response);

        // When
        dynamoDBTableManager.initializeTables();

        // Then
        verify(dynamoDbClient, atLeastOnce()).createTable(any(CreateTableRequest.class));
    }

    @Test
    void testInitializeTables_WithException_HandlesGracefully() {
        // Given
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> {
            dynamoDBTableManager.initializeTables();
        });
    }
}

