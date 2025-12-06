package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Standalone utility to initialize DynamoDB tables before tests run
 * This can be called directly from CI/CD pipelines or test setup
 */
public class TableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(TableInitializer.class);
    private static final String TABLE_PREFIX = "TestBudgetBuddy";

    /**
     * Initialize all required DynamoDB tables
     * This method can be called before running tests to ensure tables exist
     */
    public static void initializeTables(DynamoDbClient dynamoDbClient) {
        logger.info("Initializing DynamoDB tables with prefix: {}", TABLE_PREFIX);
        
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-Users", "userId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-Accounts", "accountId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-Transactions", "transactionId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-Budgets", "budgetId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-Goals", "goalId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-TransactionActions", "actionId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-AuditLogs", "auditLogId", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-NotFoundTracking", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-RateLimits", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-DDoSProtection", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-DeviceAttestation", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-FIDO2Credentials", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-FIDO2Challenges", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-MFACredentials", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-MFABackupCodes", "id", ScalarAttributeType.S);
        createTableIfNotExists(dynamoDbClient, TABLE_PREFIX + "-MFAOTPCodes", "id", ScalarAttributeType.S);
        
        logger.info("✅ DynamoDB tables initialization complete");
    }

    private static void createTableIfNotExists(DynamoDbClient dynamoDbClient, String tableName, String keyName, ScalarAttributeType keyType) {
        try {
            // Check if table exists
            try {
                DescribeTableResponse response = dynamoDbClient.describeTable(DescribeTableRequest.builder()
                        .tableName(tableName)
                        .build());
                logger.info("✅ Table {} already exists (status: {})", tableName, response.table().tableStatus());
                return;
            } catch (ResourceNotFoundException e) {
                // Table doesn't exist, create it
                logger.info("Table {} does not exist, creating...", tableName);
            }

            // Create table
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName(keyName)
                                    .attributeType(keyType)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName(keyName)
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build();

            logger.info("Creating table: {} with key: {}", tableName, keyName);
            CreateTableResponse response = dynamoDbClient.createTable(createTableRequest);
            logger.info("✅ Created table: {} (status: {})", tableName, response.tableDescription().tableStatus());
            
            // Wait for table to be active
            waitForTableActive(dynamoDbClient, tableName);
            
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists (ResourceInUseException)", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {} - {}", tableName, e.getClass().getSimpleName(), e.getMessage());
            if (e.getCause() != null) {
                logger.error("   Caused by: {}", e.getCause().getMessage());
            }
            // Don't throw - continue with other tables, but log the error clearly
        }
    }

    private static void waitForTableActive(DynamoDbClient dynamoDbClient, String tableName) {
        int maxAttempts = 10;
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                DescribeTableResponse response = dynamoDbClient.describeTable(DescribeTableRequest.builder()
                        .tableName(tableName)
                        .build());
                
                if (response.table().tableStatus() == TableStatus.ACTIVE) {
                    logger.debug("Table {} is now active", tableName);
                    return;
                }
                
                Thread.sleep(500); // Wait 500ms before checking again
                attempt++;
            } catch (Exception e) {
                logger.warn("Error checking table status for {}: {}", tableName, e.getMessage());
                attempt++;
            }
        }
        logger.warn("Table {} may not be fully active yet", tableName);
    }
}

