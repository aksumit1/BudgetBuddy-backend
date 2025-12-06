package com.budgetbuddy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Standalone utility to initialize DynamoDB tables before tests run
 * This can be called directly from CI/CD pipelines or test setup
 * Creates tables with the same schema as DynamoDBTableManager (including GSIs)
 */
public class TableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(TableInitializer.class);
    private static final String TABLE_PREFIX = "TestBudgetBuddy";

    // Global flag to track if tables have been initialized (shared across all test classes)
    private static volatile boolean globalTablesInitialized = false;
    private static final Object initializationLock = new Object();

    /**
     * Initialize all required DynamoDB tables with full schemas (including GSIs)
     * This method can be called before running tests to ensure tables exist
     * @throws RuntimeException if any critical table fails to be created
     */
    public static void initializeTables(DynamoDbClient dynamoDbClient) {
        logger.info("Initializing DynamoDB tables with prefix: {} (with full schemas including GSIs)", TABLE_PREFIX);
        
        try {
            // Create critical tables first (required for most tests)
            createUsersTable(dynamoDbClient);
            createAccountsTable(dynamoDbClient);
            createTransactionsTable(dynamoDbClient);
            createBudgetsTable(dynamoDbClient);
            createGoalsTable(dynamoDbClient);
            createTransactionActionsTable(dynamoDbClient);
            createAuditLogsTable(dynamoDbClient);
            
            // Create supporting tables
            createNotFoundTrackingTable(dynamoDbClient);
            createRateLimitTable(dynamoDbClient);
            createDDoSProtectionTable(dynamoDbClient);
            createDeviceAttestationTable(dynamoDbClient);
            createFIDO2CredentialsTable(dynamoDbClient);
            createFIDO2ChallengesTable(dynamoDbClient);
            createMFACredentialsTable(dynamoDbClient);
            createMFABackupCodesTable(dynamoDbClient);
            createMFAOTPCodesTable(dynamoDbClient);
            
            logger.info("✅ DynamoDB tables initialization complete");
        } catch (Exception e) {
            logger.error("❌ Failed to initialize DynamoDB tables: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize DynamoDB tables: " + e.getMessage(), e);
        }
    }

    /**
     * Ensure tables are initialized and verified, with global synchronization.
     * This method is thread-safe and can be called from multiple test classes in parallel.
     * It ensures tables exist and are ACTIVE before returning.
     * 
     * @param dynamoDbClient The DynamoDB client to use
     * @throws RuntimeException if tables cannot be initialized or verified
     */
    public static void ensureTablesInitializedAndVerified(DynamoDbClient dynamoDbClient) {
        // Use global lock to ensure only one test class initializes tables at a time
        synchronized (initializationLock) {
            if (!globalTablesInitialized) {
                logger.info("🔧 Ensuring DynamoDB tables are initialized (global lock)...");
                try {
                    initializeTables(dynamoDbClient);
                    verifyCriticalTablesActive(dynamoDbClient);
                    logger.info("✅ Tables initialized and verified (global)");
                    globalTablesInitialized = true;
                } catch (Exception e) {
                    // If connection pool is shut down, this might be during Spring context shutdown
                    // In this case, assume tables were already initialized in a previous test run
                    if (isConnectionPoolShutdown(e)) {
                        logger.warn("⚠️ Connection pool shut down during table initialization (likely during Spring context shutdown)");
                        logger.warn("⚠️ Assuming tables are already initialized from previous test run");
                        // Mark as initialized to prevent retries, but don't verify (can't verify with shut down pool)
                        globalTablesInitialized = true;
                        return; // Exit gracefully without throwing
                    }
                    logger.error("❌ Failed to initialize tables: {}", e.getMessage(), e);
                    throw new RuntimeException("Failed to initialize DynamoDB tables", e);
                }
            } else {
                // Even if tables were already initialized, verify they're still active
                logger.debug("🔍 Tables were already initialized, verifying they're still active...");
                try {
                    verifyCriticalTablesActive(dynamoDbClient);
                    logger.debug("✅ Tables verified and ready");
                } catch (Exception e) {
                    // CRITICAL: If connection pool is shut down (during Spring context shutdown),
                    // don't try to re-initialize - tables were already initialized earlier
                    if (isConnectionPoolShutdown(e)) {
                        logger.debug("⚠️ Connection pool shut down (likely during Spring context shutdown), assuming tables are still initialized");
                        // Don't throw - tables were already initialized, just can't verify due to shutdown
                        return;
                    }
                    
                    logger.warn("⚠️ Table verification failed, re-initializing: {}", e.getMessage());
                    try {
                        initializeTables(dynamoDbClient);
                        verifyCriticalTablesActive(dynamoDbClient);
                        logger.info("✅ Tables re-initialized and verified");
                    } catch (Exception e2) {
                        // If re-initialization also fails due to connection pool shutdown, assume tables are OK
                        if (isConnectionPoolShutdown(e2)) {
                            logger.debug("⚠️ Connection pool shut down during re-initialization, assuming tables are still initialized");
                            return;
                        }
                        logger.error("❌ Failed to re-initialize tables: {}", e2.getMessage(), e2);
                        throw new RuntimeException("Failed to re-initialize DynamoDB tables", e2);
                    }
                }
            }
        }
    }

    /**
     * Verify that critical tables exist and are ACTIVE
     */
    private static void verifyCriticalTablesActive(DynamoDbClient dynamoDbClient) throws Exception {
        String[] criticalTables = {
            TABLE_PREFIX + "-Users",
            TABLE_PREFIX + "-Accounts",
            TABLE_PREFIX + "-Transactions",
            TABLE_PREFIX + "-Budgets",
            TABLE_PREFIX + "-Goals",
            TABLE_PREFIX + "-TransactionActions"
        };
        
        int maxAttempts = 10;
        for (String tableName : criticalTables) {
            int attempt = 0;
            while (attempt < maxAttempts) {
                try {
                    DescribeTableResponse response = dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                    TableStatus status = response.table().tableStatus();
                    if (status == TableStatus.ACTIVE) {
                        logger.debug("✅ Table {} is ACTIVE", tableName);
                        break;
                    } else {
                        logger.debug("⏳ Table {} status: {}, waiting...", tableName, status);
                        Thread.sleep(500);
                        attempt++;
                    }
                } catch (ResourceNotFoundException e) {
                    logger.warn("⚠️ Table {} not found, attempt {}/{}, waiting...", tableName, attempt + 1, maxAttempts);
                    Thread.sleep(500);
                    attempt++;
                    if (attempt >= maxAttempts) {
                        throw new RuntimeException("Table " + tableName + " not found after " + maxAttempts + " attempts");
                    }
                }
            }
        }
        // Give tables a moment to be fully ready after all are ACTIVE
        Thread.sleep(500);
    }

    /**
     * Check if the exception is due to connection pool being shut down
     * This can happen during Spring context shutdown when DynamoDB client is being closed
     */
    private static boolean isConnectionPoolShutdown(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("Connection pool shut down")) {
            return true;
        }
        // Also check for IllegalStateException with connection pool shutdown message
        if (e instanceof IllegalStateException && message != null && message.contains("shut down")) {
            return true;
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof IllegalStateException) {
                String causeMessage = cause.getMessage();
                if (causeMessage != null && (causeMessage.contains("Connection pool shut down") || causeMessage.contains("shut down"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void createUsersTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-Users";
        try {
            // Check if table exists
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
                // Table doesn't exist, create it
            }

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("email").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("EmailIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("email").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createAccountsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-Accounts";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("accountId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("plaidAccountId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("accountId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("PlaidAccountIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("plaidAccountId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createTransactionsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-Transactions";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("transactionId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("transactionDate").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("plaidTransactionId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("accountId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("transactionId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdDateIndex")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("transactionDate").keyType(KeyType.RANGE).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("AccountIdTransactionDateIndex")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("accountId").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("transactionDate").keyType(KeyType.RANGE).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("PlaidTransactionIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("plaidTransactionId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createBudgetsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-Budgets";
        createTableWithUserIdIndex(dynamoDbClient, tableName, "budgetId");
    }

    private static void createGoalsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-Goals";
        createTableWithUserIdIndex(dynamoDbClient, tableName, "goalId");
    }

    private static void createTransactionActionsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-TransactionActions";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("actionId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("transactionId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("reminderDatePartition").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("reminderDate").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("actionId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("TransactionIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("transactionId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("ReminderDateIndex")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("reminderDatePartition").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("reminderDate").keyType(KeyType.RANGE).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createAuditLogsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-AuditLogs";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("auditLogId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("createdAt").attributeType(ScalarAttributeType.N).build(),
                            AttributeDefinition.builder().attributeName("action").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("auditLogId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdCreatedAtIndex")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build(),
                            GlobalSecondaryIndex.builder()
                                    .indexName("ActionCreatedAtIndex")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("action").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createNotFoundTrackingTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-NotFoundTracking";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "sourceId");
    }

    private static void createRateLimitTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-RateLimits";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "key");
    }

    private static void createDDoSProtectionTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-DDoSProtection";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "ipAddress");
    }

    private static void createDeviceAttestationTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-DeviceAttestation";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("deviceId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("deviceId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.RANGE).build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            configureTTL(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createFIDO2CredentialsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-FIDO2Credentials";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("credentialId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("credentialId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createFIDO2ChallengesTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-FIDO2Challenges";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "challengeKey");
    }

    private static void createMFACredentialsTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-MFACredentials";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("mfaType").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("mfaType").keyType(KeyType.RANGE).build())
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createMFABackupCodesTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-MFABackupCodes";
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("codeHash").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("codeHash").keyType(KeyType.RANGE).build())
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createMFAOTPCodesTable(DynamoDbClient dynamoDbClient) {
        String tableName = TABLE_PREFIX + "-MFAOTPCodes";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "otpKey");
    }

    // Helper methods

    private static void createTableWithUserIdIndex(DynamoDbClient dynamoDbClient, String tableName, String keyName) {
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName(keyName).attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName(keyName).keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("UserIdIndex")
                                    .keySchema(KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build())
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createSimpleTableWithTTL(DynamoDbClient dynamoDbClient, String tableName, String keyName) {
        try {
            try {
                dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
                logger.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {}

            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName(keyName).attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName(keyName).keyType(KeyType.HASH).build())
                    .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            configureTTL(dynamoDbClient, tableName);
            logger.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException("Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void configureTTL(DynamoDbClient dynamoDbClient, String tableName) {
        try {
            dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .enabled(true)
                            .attributeName("ttl")
                            .build())
                    .build());
        } catch (Exception e) {
            logger.warn("Failed to configure TTL for table {}: {}", tableName, e.getMessage());
        }
    }

    private static void waitForTableActive(DynamoDbClient dynamoDbClient, String tableName) {
        int maxAttempts = 20; // Increased for CI stability
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
        logger.warn("Table {} may not be fully active yet (checked {} times)", tableName, maxAttempts);
    }
}
