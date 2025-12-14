package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
 * 
 * Resilience Features:
 * - DNS cache TTL configured via DnsCacheConfig (prevents stale DNS entries)
 * - Retry policy with exponential backoff (handles transient failures)
 * - Connection timeouts (prevents hanging connections)
 */
@Configuration
@org.springframework.context.annotation.Profile("!test") // Don't load in tests - use AWSTestConfiguration instead
public class DynamoDBConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDBConfig.class);

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${app.aws.dynamodb.endpoint:}")
    private String dynamoDbEndpoint;

    @Value("${app.aws.dynamodb.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;

    /**
     * Credentials provider that uses IAM role in ECS/EKS, or static credentials for LocalStack
     */
    private AwsCredentialsProvider getCredentialsProvider() {
        // For LocalStack, use static credentials if provided
        if (!accessKeyId.isEmpty() && !secretAccessKey.isEmpty()) {
            logger.debug("Using static credentials for DynamoDB (LocalStack mode)");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }
        // For production, use IAM role or default credentials provider
        logger.debug("Using default credentials provider for DynamoDB (AWS mode)");
        return DefaultCredentialsProvider.create();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient() {
        // Configure client with timeouts for resilience
        // AWS SDK v2 has built-in retry logic with exponential backoff (default: 3 attempts)
        // This handles transient failures (network errors, DNS failures, etc.)
        // DNS cache TTL (configured in DnsCacheConfig) ensures quick recovery from DNS failures
        ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(timeoutSeconds))
                .apiCallAttemptTimeout(Duration.ofSeconds(timeoutSeconds))
                // Note: AWS SDK v2 has default retry logic (3 attempts with exponential backoff)
                // Additional retry configuration can be done via system properties if needed
                .build();

        var builder = DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .overrideConfiguration(clientConfig);

        // For local development with LocalStack
        if (!dynamoDbEndpoint.isEmpty()) {
            try {
                URI endpointUri = URI.create(dynamoDbEndpoint);
                builder.endpointOverride(endpointUri);
                logger.info("DynamoDB client configured with endpoint: {} (LocalStack)", dynamoDbEndpoint);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid DynamoDB endpoint URI: {}. Error: {}", dynamoDbEndpoint, e.getMessage());
                throw new IllegalStateException("Invalid DynamoDB endpoint configuration: " + dynamoDbEndpoint, e);
            }
        } else {
            logger.info("DynamoDB client configured for AWS (no endpoint override)");
        }

        logger.info("DynamoDB client configured: timeout={}s (default retry: 3 attempts with exponential backoff)", 
                timeoutSeconds);

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(final DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}

