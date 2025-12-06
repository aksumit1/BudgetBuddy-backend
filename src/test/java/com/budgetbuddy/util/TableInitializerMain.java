package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

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
            // Get configuration from system properties first (Maven -D flags), then environment variables
            // This allows Maven exec plugin to pass configuration via -D flags
            String endpoint = System.getProperty("aws.dynamodb.endpoint");
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = System.getenv("DYNAMODB_ENDPOINT");
            }
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = "http://localhost:4566";
            }
            
            String region = System.getProperty("aws.region");
            if (region == null || region.isEmpty()) {
                region = System.getenv("AWS_REGION");
            }
            if (region == null || region.isEmpty()) {
                region = "us-east-1";
            }
            
            String accessKey = System.getProperty("aws.accessKeyId");
            if (accessKey == null || accessKey.isEmpty()) {
                accessKey = System.getenv("AWS_ACCESS_KEY_ID");
            }
            if (accessKey == null || accessKey.isEmpty()) {
                accessKey = "test";
            }
            
            String secretKey = System.getProperty("aws.secretAccessKey");
            if (secretKey == null || secretKey.isEmpty()) {
                secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
            }
            if (secretKey == null || secretKey.isEmpty()) {
                secretKey = "test";
            }
            
            logger.info("Configuration:");
            logger.info("  - DynamoDB Endpoint: {}", endpoint);
            logger.info("  - AWS Region: {}", region);
            logger.info("  - Access Key ID: {} (masked)", accessKey.length() > 4 ? accessKey.substring(0, 4) + "***" : "***");
            
            // Create DynamoDB client
            DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            
            // Initialize tables
            logger.info("Initializing all DynamoDB tables...");
            TableInitializer.initializeTables(dynamoDbClient);
            
            logger.info("✅ All DynamoDB tables initialized successfully!");
            
            // Wait a moment for tables to be fully active (especially GSIs)
            logger.info("Waiting for tables to be fully active...");
            Thread.sleep(2000); // 2 seconds
            
            // Verify critical tables exist and are accessible
            logger.info("Verifying critical tables exist and are accessible...");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Users");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Accounts");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Transactions");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Budgets");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Goals");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-TransactionActions");
            
            logger.info("✅ Table initialization and verification complete!");
            logger.info("✅ All required tables are ready for testing!");
            System.exit(0);
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize DynamoDB tables: {}", e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void verifyTableExists(DynamoDbClient dynamoDbClient, String tableName) {
        try {
            var response = dynamoDbClient.describeTable(builder -> builder.tableName(tableName));
            var status = response.table().tableStatus();
            logger.info("✅ Verified table exists: {} (status: {})", tableName, status);
            
            if (status != TableStatus.ACTIVE) {
                logger.warn("⚠️ Table {} is not ACTIVE (status: {}), waiting...", tableName, status);
                // Wait a bit more for table to become active
                Thread.sleep(1000);
            }
        } catch (ResourceNotFoundException e) {
            logger.error("❌ Table {} does not exist: {}", tableName, e.getMessage());
            throw new RuntimeException("Table verification failed - table does not exist: " + tableName, e);
        } catch (Exception e) {
            logger.error("❌ Table {} verification error: {}", tableName, e.getMessage());
            throw new RuntimeException("Table verification failed for: " + tableName, e);
        }
    }
}

