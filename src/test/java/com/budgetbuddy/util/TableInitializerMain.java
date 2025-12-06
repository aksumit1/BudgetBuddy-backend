package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Standalone main class to initialize DynamoDB tables before tests run
 * This can be executed directly from Maven or CI/CD pipelines
 * 
 * Usage:
 *   java -cp ... com.budgetbuddy.util.TableInitializerMain
 * 
 * Environment variables:
 *   DYNAMODB_ENDPOINT (default: http://localhost:4566)
 *   AWS_REGION (default: us-east-1)
 *   AWS_ACCESS_KEY_ID (default: test)
 *   AWS_SECRET_ACCESS_KEY (default: test)
 */
public class TableInitializerMain {

    private static final Logger logger = LoggerFactory.getLogger(TableInitializerMain.class);

    public static void main(String[] args) {
        logger.info("🚀 Starting DynamoDB table initialization...");
        
        try {
            // Get configuration from environment variables
            String endpoint = System.getenv("DYNAMODB_ENDPOINT");
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = "http://localhost:4566";
            }
            
            String region = System.getenv("AWS_REGION");
            if (region == null || region.isEmpty()) {
                region = "us-east-1";
            }
            
            String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
            if (accessKey == null || accessKey.isEmpty()) {
                accessKey = "test";
            }
            
            String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
            if (secretKey == null || secretKey.isEmpty()) {
                secretKey = "test";
            }
            
            logger.info("Connecting to DynamoDB at: {}", endpoint);
            logger.info("Region: {}", region);
            
            // Create DynamoDB client
            DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            
            // Initialize tables
            TableInitializer.initializeTables(dynamoDbClient);
            
            logger.info("✅ All DynamoDB tables initialized successfully!");
            
            // Verify critical tables exist
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Users");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Accounts");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Transactions");
            
            logger.info("✅ Table initialization and verification complete!");
            System.exit(0);
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize DynamoDB tables: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    private static void verifyTableExists(DynamoDbClient dynamoDbClient, String tableName) {
        try {
            dynamoDbClient.describeTable(builder -> builder.tableName(tableName));
            logger.info("✅ Verified table exists: {}", tableName);
        } catch (Exception e) {
            logger.error("❌ Table {} does not exist or is not accessible: {}", tableName, e.getMessage());
            throw new RuntimeException("Table verification failed for: " + tableName, e);
        }
    }
}

