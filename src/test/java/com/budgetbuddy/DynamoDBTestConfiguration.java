package com.budgetbuddy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.net.URI;

/**
 * Test Configuration for DynamoDB
 * Uses LocalStack for local testing (or configured endpoint)
 */
@org.springframework.boot.test.context.TestConfiguration
public class DynamoDBTestConfiguration {

    /**
     * Get DynamoDB endpoint from environment variable, system property, or default
     */
    private static String getDynamoDbEndpoint() {
        // Check system property first (can be set via -Daws.dynamodb.endpoint=...)
        String endpoint = System.getProperty("aws.dynamodb.endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Check environment variable
        endpoint = System.getenv("DYNAMODB_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Default to localhost for LocalStack
        return "http://localhost:4566";
    }

    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        // Use configured endpoint or LocalStack endpoint for testing
        String endpoint = getDynamoDbEndpoint();
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .build();
    }

    @Bean
    @Primary
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(final DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}

