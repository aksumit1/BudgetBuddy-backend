package com.budgetbuddy.util;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * Standalone main class to initialize DynamoDB tables before tests run This can be executed
 * directly from Maven or CI/CD pipelines
 *
 * <p>Usage: java -cp ... com.budgetbuddy.util.TableInitializerMain
 *
 * <p>Environment variables: DYNAMODB_ENDPOINT (default: http://localhost:4566) AWS_REGION (default:
 * us-east-1) AWS_ACCESS_KEY_ID (default: test) AWS_SECRET_ACCESS_KEY (default: test)
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class TableInitializerMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableInitializerMain.class);

    public static void main(final String[] args) {
        LOGGER.info("🚀 Starting DynamoDB table initialization...");

        try {
            // Get configuration from system properties first (Maven -D flags), then environment
            // variables
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

            LOGGER.info("Configuration:");
            LOGGER.info("  - DynamoDB Endpoint: {}", endpoint);
            LOGGER.info("  - AWS Region: {}", region);
            LOGGER.info(
                    "  - Access Key ID: {} (masked)",
                    accessKey.length() > 4 ? accessKey.substring(0, 4) + "***" : "***");

            // Create DynamoDB client
            final DynamoDbClient dynamoDbClient =
                    DynamoDbClient.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.of(region))
                            .credentialsProvider(
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(accessKey, secretKey)))
                            .build();

            // Initialize tables
            LOGGER.info("Initializing all DynamoDB tables...");
            TableInitializer.initializeTables(dynamoDbClient);

            LOGGER.info("✅ All DynamoDB tables initialized successfully!");

            // Wait a moment for tables to be fully active (especially GSIs)
            LOGGER.info("Waiting for tables to be fully active...");
            Thread.sleep(2000); // 2 seconds

            // Verify critical tables exist and are accessible
            LOGGER.info("Verifying critical tables exist and are accessible...");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Users");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Accounts");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Transactions");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Budgets");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-Goals");
            verifyTableExists(dynamoDbClient, "TestBudgetBuddy-TransactionActions");

            LOGGER.info("✅ Table initialization and verification complete!");
            LOGGER.info("✅ All required tables are ready for testing!");
            System.exit(0);

        } catch (Exception e) {
            LOGGER.error("❌ Failed to initialize DynamoDB tables: {}", e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyTableExists(
            final DynamoDbClient dynamoDbClient, final String tableName) {
        try {
            final var response =
                    dynamoDbClient.describeTable(builder -> builder.tableName(tableName));
            final var status = response.table().tableStatus();
            LOGGER.info("✅ Verified table exists: {} (status: {})", tableName, status);

            if (status != TableStatus.ACTIVE) {
                LOGGER.warn(
                        "⚠️ Table {} is not ACTIVE (status: {}), waiting...", tableName, status);
                // Wait a bit more for table to become active
                Thread.sleep(1000);
            }
        } catch (ResourceNotFoundException e) {
            LOGGER.error("❌ Table {} does not exist: {}", tableName, e.getMessage());
            throw new RuntimeException(
                    "Table verification failed - table does not exist: " + tableName, e);
        } catch (Exception e) {
            LOGGER.error("❌ Table {} verification error: {}", tableName, e.getMessage());
            throw new RuntimeException("Table verification failed for: " + tableName, e);
        }
    }
}
