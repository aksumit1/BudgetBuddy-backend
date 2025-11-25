package com.budgetbuddy;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.net.URI;

/**
 * Test Configuration for DynamoDB
 * Uses LocalStack for local testing
 */
@TestConfiguration
public class TestConfiguration {

    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        // Use LocalStack endpoint for testing
        return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:4566"))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .build();
    }

    @Bean
    @Primary
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}

