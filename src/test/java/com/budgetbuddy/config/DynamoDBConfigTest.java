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

    private static final String US_EAST_1 = "us-east-1";
    private static final String TIMEOUTSECONDS = "timeoutSeconds";
    private static final String DYNAMODBENDPOINT = "dynamoDbEndpoint";
    private static final String AWSREGION = "awsRegion";

    private DynamoDBConfig dynamoDBConfig = new DynamoDBConfig();

    @Test
    void testDynamoDbClientWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(dynamoDBConfig, DYNAMODBENDPOINT, "");
        ReflectionTestUtils.setField(dynamoDBConfig, TIMEOUTSECONDS, 10);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testDynamoDbClientWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(dynamoDBConfig, DYNAMODBENDPOINT, "http://localhost:8000");
        ReflectionTestUtils.setField(dynamoDBConfig, TIMEOUTSECONDS, 10);

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
        ReflectionTestUtils.setField(dynamoDBConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(dynamoDBConfig, DYNAMODBENDPOINT, "");
        ReflectionTestUtils.setField(dynamoDBConfig, TIMEOUTSECONDS, 30);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        // Verify timeout is configured (can't directly check, but client should be created)
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testDynamoDbEnhancedClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(dynamoDBConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(dynamoDBConfig, DYNAMODBENDPOINT, "");
        ReflectionTestUtils.setField(dynamoDBConfig, TIMEOUTSECONDS, 10);
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
        ReflectionTestUtils.setField(dynamoDBConfig, AWSREGION, "us-west-2");
        ReflectionTestUtils.setField(dynamoDBConfig, DYNAMODBENDPOINT, "");
        ReflectionTestUtils.setField(dynamoDBConfig, TIMEOUTSECONDS, 10);

        // When
        final DynamoDbClient client = dynamoDBConfig.dynamoDbClient();

        // Then
        assertNotNull(client);
        assertEquals("us-west-2", client.serviceClientConfiguration().region().id());
    }
}
