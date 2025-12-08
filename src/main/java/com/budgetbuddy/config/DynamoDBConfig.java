package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

/**
 * DynamoDB Configuration
 * Uses IAM roles for authentication (no credentials needed)
 * Optimized for cost: on-demand billing, minimal provisioned capacity
 */
@Configuration
@org.springframework.context.annotation.Profile("!test") // Don't load in tests - use AWSTestConfiguration instead
public class DynamoDBConfig {

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${app.aws.dynamodb.endpoint:}")
    private String dynamoDbEndpoint;

    @Value("${app.aws.dynamodb.timeout-seconds:10}")
    private int timeoutSeconds;

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient() {
        // Configure client with timeouts to prevent long hangs
        ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(timeoutSeconds))
                .apiCallAttemptTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        var builder = DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create()) // Uses IAM role in ECS/EKS
                .overrideConfiguration(clientConfig);

        // For local development with LocalStack
        if (!dynamoDbEndpoint.isEmpty()) {
            builder.endpointOverride(URI.create(dynamoDbEndpoint));
        }

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(final DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}

