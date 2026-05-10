package com.budgetbuddy.service.dynamodb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;

/** Unit Tests for DynamoDBTableManager Tests DynamoDB table creation and initialization */
@ExtendWith(MockitoExtension.class)
class DynamoDBTableManagerTest {

    @Mock private DynamoDbClient dynamoDbClient;

    @InjectMocks private DynamoDBTableManager dynamoDBTableManager;

    @BeforeEach
    void setUp() {
        dynamoDBTableManager = new DynamoDBTableManager(dynamoDbClient, "TestPrefix");
    }

    @Test
    void testInitializeTablesWithTableAlreadyExistsHandlesGracefully() {
        // Given
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenThrow(ResourceInUseException.builder().build());

        // When/Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    dynamoDBTableManager.initializeTables();
                });
    }

    @Test
    void testInitializeTablesWithTableCreationSuccessCreatesTables() {
        // Given
        final CreateTableResponse response = CreateTableResponse.builder().build();
        when(dynamoDbClient.createTable(any(CreateTableRequest.class))).thenReturn(response);

        // When
        dynamoDBTableManager.initializeTables();

        // Then
        verify(dynamoDbClient, atLeastOnce()).createTable(any(CreateTableRequest.class));
    }

    @Test
    void testInitializeTablesWithExceptionHandlesGracefully() {
        // Given
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When/Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    dynamoDBTableManager.initializeTables();
                });
    }
}
