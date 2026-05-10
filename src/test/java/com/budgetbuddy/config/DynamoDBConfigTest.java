package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Unit Tests for DynamoDBConfig Tests DynamoDB client configuration including LocalStack support
 * and timeouts
 */
class DynamoDBConfigTest {

    private DynamoDBConfig dynamoDBConfig = new DynamoDBConfig();

    @Test
    void testDynamoDbClientWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(dynamoDBConfig, "dynamoDbEndpoint", "");
        ReflectionTestUtils.setField(dynamoDBConfig, "timeoutSeconds", 10);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testDynamoDbClientWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(dynamoDBConfig, "dynamoDbEndpoint", "http://localhost:8000");
        ReflectionTestUtils.setField(dynamoDBConfig, "timeoutSeconds", 10);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        final URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:8000", endpoint.toString());
    }

    @Test
    void testDynamoDbClientWithCustomTimeout() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(dynamoDBConfig, "dynamoDbEndpoint", "");
        ReflectionTestUtils.setField(dynamoDBConfig, "timeoutSeconds", 30);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        // Verify timeout is configured (can't directly check, but client should be created)
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testDynamoDbEnhancedClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(dynamoDBConfig, "dynamoDbEndpoint", "");
        ReflectionTestUtils.setField(dynamoDBConfig, "timeoutSeconds", 10);
        final DynamoDbClient dynamoDbClient = dynamoDBConfig.dynamoDbClient();

        // When
        final DynamoDbEnhancedClient enhancedClient =
                dynamoDBConfig.dynamoDbEnhancedClient(dynamoDbClient);

        // Then
        assertNotNull(enhancedClient);
        // DynamoDbEnhancedClient wraps the client but doesn't expose it directly
        // We can verify it's created by checking it's not null
    }

    @Test
    void testDynamoDbClientWithDifferentRegion() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, "awsRegion", "us-west-2");
        ReflectionTestUtils.setField(dynamoDBConfig, "dynamoDbEndpoint", "");
        ReflectionTestUtils.setField(dynamoDBConfig, "timeoutSeconds", 10);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }
}
