package com.budgetbuddy.util;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Standalone utility to initialize DynamoDB tables before tests run This can be called directly
 * from CI/CD pipelines or test setup Creates tables with the same schema as DynamoDBTableManager
 * (including GSIs)
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class TableInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableInitializer.class);
    private static final String TABLE_PREFIX = "TestBudgetBuddy";

    // Global flag to track if tables have been initialized (shared across all test classes)
    private static volatile boolean globalTablesInitialized = false;
    private static final Object INITIALIZATION_LOCK = new Object();

    /**
     * Initialize all required DynamoDB tables with full schemas (including GSIs) This method can be
     * called before running tests to ensure tables exist
     *
     * @throws RuntimeException if any critical table fails to be created
     */
    public static void initializeTables(final DynamoDbClient dynamoDbClient) {
        LOGGER.info(
                "Initializing DynamoDB tables with prefix: {} (with full schemas including GSIs)",
                TABLE_PREFIX);

        try {
            // Create critical tables first (required for most tests)
            createUsersTable(dynamoDbClient);
            createAccountsTable(dynamoDbClient);
            createTransactionsTable(dynamoDbClient);
            createBudgetsTable(dynamoDbClient);
            createGoalsTable(dynamoDbClient);
            createTransactionActionsTable(dynamoDbClient);
            createSubscriptionsTable(dynamoDbClient);
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

            LOGGER.info("✅ DynamoDB tables initialization complete");
        } catch (Exception e) {
            LOGGER.error("❌ Failed to initialize DynamoDB tables: {}", e.getMessage(), e);
            throw new RuntimeException(
                    "Failed to initialize DynamoDB tables: " + e.getMessage(), e);
        }
    }

    /**
     * Ensure tables are initialized and verified, with global synchronization. This method is
     * thread-safe and can be called from multiple test classes in parallel. It ensures tables exist
     * and are ACTIVE before returning.
     *
     * <p>CRITICAL: This method gracefully handles cases where LocalStack is not available or
     * credentials are missing by catching and logging the error instead of failing tests.
     *
     * @param dynamoDbClient The DynamoDB client to use
     * @throws RuntimeException if tables cannot be initialized or verified (only if not a
     *     credentials/localstack issue)
     */
    public static void ensureTablesInitializedAndVerified(final DynamoDbClient dynamoDbClient) {
        // Check if client is null or not properly configured
        if (dynamoDbClient == null) {
            LOGGER.warn(
                    "⚠️ DynamoDB client is null - skipping table initialization. Tests may fail if they require tables.");
            return;
        }

        // Use global lock to ensure only one test class initializes tables at a time
        synchronized (INITIALIZATION_LOCK) {
            if (!globalTablesInitialized) {
                LOGGER.info("🔧 Ensuring DynamoDB tables are initialized (global lock)...");
                try {
                    initializeTables(dynamoDbClient);
                    verifyCriticalTablesActive(dynamoDbClient);
                    LOGGER.info("✅ Tables initialized and verified (global)");
                    globalTablesInitialized = true;
                } catch (Exception e) {
                    // CRITICAL: Handle credentials/localstack errors gracefully
                    // If LocalStack is not running or credentials are missing, log warning but
                    // don't fail tests
                    if (isCredentialsError(e) || isLocalStackUnavailable(e)) {
                        LOGGER.warn(
                                "⚠️ DynamoDB/LocalStack not available - skipping table initialization: {}",
                                e.getMessage());
                        LOGGER.warn(
                                "⚠️ Tests that require DynamoDB tables may fail or be skipped. Ensure LocalStack is running for integration tests.");
                        // Don't mark as initialized, but don't throw - allow tests to continue
                        return;
                    }

                    // If connection pool is shut down, this might be during Spring context shutdown
                    // In this case, assume tables were already initialized in a previous test run
                    if (isConnectionPoolShutdown(e)) {
                        LOGGER.warn(
                                "⚠️ Connection pool shut down during table initialization (likely during Spring context shutdown)");
                        LOGGER.warn(
                                "⚠️ Assuming tables are already initialized from previous test run");
                        // Mark as initialized to prevent retries, but don't verify (can't verify
                        // with shut down pool)
                        globalTablesInitialized = true;
                        return; // Exit gracefully without throwing
                    }
                    LOGGER.error("❌ Failed to initialize tables: {}", e.getMessage(), e);
                    throw new RuntimeException("Failed to initialize DynamoDB tables", e);
                }
            } else {
                // Even if tables were already initialized, verify they're still active
                LOGGER.debug(
                        "🔍 Tables were already initialized, verifying they're still active...");
                try {
                    verifyCriticalTablesActive(dynamoDbClient);
                    LOGGER.debug("✅ Tables verified and ready");
                } catch (Exception e) {
                    // CRITICAL: Handle credentials/localstack errors gracefully
                    if (isCredentialsError(e) || isLocalStackUnavailable(e)) {
                        LOGGER.debug(
                                "⚠️ DynamoDB/LocalStack not available - skipping table verification: {}",
                                e.getMessage());
                        // Don't throw - allow tests to continue
                        return;
                    }

                    // CRITICAL: If connection pool is shut down (during Spring context shutdown),
                    // don't try to re-initialize - tables were already initialized earlier
                    if (isConnectionPoolShutdown(e)) {
                        LOGGER.debug(
                                "⚠️ Connection pool shut down (likely during Spring context shutdown), assuming tables are still initialized");
                        // Don't throw - tables were already initialized, just can't verify due to
                        // shutdown
                        return;
                    }

                    // CRITICAL: If verification fails with ResourceNotFoundException, tables
                    // definitely don't exist
                    // Reset the flag so we can re-initialize
                    final boolean isResourceNotFound =
                            e
                                    instanceof
                                    software.amazon.awssdk.services.dynamodb.model
                                            .ResourceNotFoundException
                                    || (e.getCause() != null
                                    && e.getCause()
                                    instanceof
                                    software.amazon.awssdk.services.dynamodb.model
                                            .ResourceNotFoundException);

                    if (isResourceNotFound) {
                        LOGGER.warn(
                                "⚠️ Table verification failed with ResourceNotFoundException - tables don't exist, re-initializing: {}",
                                e.getMessage());
                        globalTablesInitialized = false; // Reset flag to allow re-initialization
                    } else {
                        LOGGER.warn(
                                "⚠️ Table verification failed (tables may not exist), re-initializing: {}",
                                e.getMessage());
                        globalTablesInitialized = false; // Reset flag to allow re-initialization
                    }

                    try {
                        initializeTables(dynamoDbClient);
                        verifyCriticalTablesActive(dynamoDbClient);
                        globalTablesInitialized = true; // Mark as initialized again
                        LOGGER.info("✅ Tables re-initialized and verified");
                    } catch (Exception e2) {
                        // CRITICAL: Handle credentials/localstack errors gracefully
                        if (isCredentialsError(e2) || isLocalStackUnavailable(e2)) {
                            LOGGER.warn(
                                    "⚠️ DynamoDB/LocalStack not available - skipping table re-initialization: {}",
                                    e2.getMessage());
                            return;
                        }

                        // If re-initialization also fails due to connection pool shutdown, assume
                        // tables are OK
                        if (isConnectionPoolShutdown(e2)) {
                            LOGGER.debug(
                                    "⚠️ Connection pool shut down during re-initialization, assuming tables are still initialized");
                            globalTablesInitialized =
                                    true; // Mark as initialized to prevent further attempts
                            return;
                        }
                        LOGGER.error("❌ Failed to re-initialize tables: {}", e2.getMessage(), e2);
                        throw new RuntimeException("Failed to re-initialize DynamoDB tables", e2);
                    }
                }
            }
        }
    }

    /** Verify that critical tables exist and are ACTIVE */
    private static void verifyCriticalTablesActive(final DynamoDbClient dynamoDbClient) throws Exception {
        final String[] criticalTables = {
                TABLE_PREFIX + "-Users",
                TABLE_PREFIX + "-Accounts",
                TABLE_PREFIX + "-Transactions",
                TABLE_PREFIX + "-Budgets",
                TABLE_PREFIX + "-Goals",
                TABLE_PREFIX + "-TransactionActions",
                TABLE_PREFIX + "-AuditLogs" // Required for DMA compliance and GDPR exports
        };

        final int maxAttempts = 10;
        for (final String tableName : criticalTables) {
            int attempt = 0;
            while (attempt < maxAttempts) {
                try {
                    final DescribeTableResponse response =
                            dynamoDbClient.describeTable(
                                    DescribeTableRequest.builder().tableName(tableName).build());
                    final TableStatus status = response.table().tableStatus();
                    if (status == TableStatus.ACTIVE) {
                        LOGGER.debug("✅ Table {} is ACTIVE", tableName);
                        break;
                    } else {
                        LOGGER.debug("⏳ Table {} status: {}, waiting...", tableName, status);
                        Thread.sleep(500);
                        attempt++;
                    }
                } catch (ResourceNotFoundException e) {
                    LOGGER.warn(
                            "⚠️ Table {} not found, attempt {}/{}, waiting...",
                            tableName,
                            attempt + 1,
                            maxAttempts);
                    Thread.sleep(500);
                    attempt++;
                    if (attempt >= maxAttempts) {
                        throw new RuntimeException(
                                "Table "
                                        + tableName
                                        + " not found after "
                                        + maxAttempts
                                        + " attempts");
                    }
                }
            }
        }
        // Give tables a moment to be fully ready after all are ACTIVE
        Thread.sleep(500);
    }

    /**
     * Check if the exception is due to connection pool being shut down This can happen during
     * Spring context shutdown when DynamoDB client is being closed
     */
    private static boolean isConnectionPoolShutdown(final Exception e) {
        final String message = e.getMessage();
        if (message != null && message.contains("Connection pool shut down")) {
            return true;
        }
        // Also check for IllegalStateException with connection pool shutdown message
        if (e instanceof IllegalStateException
                && message != null
                && message.contains("shut down")) {
            return true;
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof IllegalStateException) {
                final String causeMessage = cause.getMessage();
                if (causeMessage != null
                        && (causeMessage.contains("Connection pool shut down")
                                || causeMessage.contains("shut down"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if the exception is due to missing AWS credentials This happens when DynamoDB client
     * doesn't have credentials configured (LocalStack not running, etc.)
     */
    private static boolean isCredentialsError(final Exception e) {
        final String message = e.getMessage();
        if (message != null
                && (message.contains("Unable to load credentials")
                        || message.contains("credentials") && message.contains("missing"))) {
            return true;
        }
        // Check for SdkClientException with credentials error
        if (e instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String exceptionMessage = e.getMessage();
            if (exceptionMessage != null
                    && exceptionMessage.contains("Unable to load credentials")) {
                return true;
            }
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
                final String causeMessage = cause.getMessage();
                if (causeMessage != null && causeMessage.contains("Unable to load credentials")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if the exception is due to LocalStack being unavailable (connection refused, etc.) This
     * happens when LocalStack is not running or the endpoint is wrong
     */
    private static boolean isLocalStackUnavailable(final Exception e) {
        final String message = e.getMessage();
        if (message != null
                && (message.contains("Connection refused")
                        || message.contains("connect timed out")
                        || message.contains("java.net.ConnectException"))) {
            return true;
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.ConnectException
                    || (cause.getMessage() != null
                            && cause.getMessage().contains("Connection refused"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void createUsersTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Users";
        try {
            // Check if table exists
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
                // Table doesn't exist, create it
            } catch (Exception e) {
                if (isCredentialsError(e)) {
                    LOGGER.warn(
                            "⚠️ AWS credentials not configured. Skipping table check for {}. Error: {}",
                            tableName,
                            e.getMessage());
                    return; // Exit gracefully
                }
                // Re-throw other exceptions
                throw e;
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("email")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("userId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("EmailIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("email")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createAccountsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Accounts";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("accountId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("plaidAccountId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("updatedAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("accountId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("PlaidAccountIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("plaidAccountId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdUpdatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("updatedAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createTransactionsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Transactions";
        try {
            try {
                final DescribeTableResponse existingTable =
                        dynamoDbClient.describeTable(
                                DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);

                // CRITICAL: Verify all required GSIs exist
                // If table exists but GSIs are missing, we need to add them
                final List<String> existingGSINames =
                        existingTable.table().globalSecondaryIndexes() != null
                                ? existingTable.table().globalSecondaryIndexes().stream()
                                .map(gsi -> gsi.indexName())
                                .collect(java.util.stream.Collectors.toList())
                                : new ArrayList<>();

                final List<String> requiredGSIs =
                        List.of(
                                "UserIdDateIndex",
                                "AccountIdTransactionDateIndex",
                                "PlaidTransactionIdIndex",
                                "UserIdUpdatedAtIndex");
                final List<String> missingGSIs =
                        requiredGSIs.stream()
                                .filter(gsiName -> !existingGSINames.contains(gsiName))
                                .collect(java.util.stream.Collectors.toList());

                if (!missingGSIs.isEmpty()) {
                    LOGGER.warn(
                            "⚠️ Table {} exists but is missing GSIs: {}. This can happen if table was created before GSIs were added.",
                            tableName,
                            missingGSIs);
                    LOGGER.warn(
                            "⚠️ Note: DynamoDB doesn't support adding GSIs to existing tables via UpdateTable in LocalStack.");
                    LOGGER.warn(
                            "⚠️ Please delete the table and recreate it, or the fallback logic will handle missing GSIs.");
                    // Note: We can't add GSIs to existing tables - DynamoDB requires table
                    // recreation
                    // The fallback logic in repositories will handle this gracefully
                } else {
                    LOGGER.debug(
                            "✅ Table {} has all required GSIs: {}", tableName, existingGSINames);
                }
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("transactionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("transactionDate")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("plaidTransactionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("accountId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("updatedAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("transactionId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdDateIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("transactionDate")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("AccountIdTransactionDateIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("accountId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("transactionDate")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("PlaidTransactionIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("plaidTransactionId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdUpdatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("updatedAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createBudgetsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Budgets";
        createTableWithUserIdIndex(dynamoDbClient, tableName, "budgetId");
    }

    private static void createGoalsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Goals";
        createTableWithUserIdIndex(dynamoDbClient, tableName, "goalId");
    }

    private static void createTransactionActionsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-TransactionActions";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("actionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("transactionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("reminderDatePartition")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("reminderDate")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("updatedAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("actionId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("TransactionIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("transactionId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("ReminderDateIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("reminderDatePartition")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("reminderDate")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdUpdatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("updatedAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createSubscriptionsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-Subscriptions";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
                // Table doesn't exist, proceed with creation
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("subscriptionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("subscriptionId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
        }
    }

    private static void createAuditLogsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-AuditLogs";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("auditLogId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("createdAt")
                                            .attributeType(ScalarAttributeType.N)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("action")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("auditLogId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdCreatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("createdAt")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("ActionCreatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("action")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("createdAt")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createNotFoundTrackingTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-NotFoundTracking";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "sourceId");
    }

    private static void createRateLimitTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-RateLimits";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "key");
    }

    private static void createDDoSProtectionTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-DDoSProtection";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "ipAddress");
    }

    private static void createDeviceAttestationTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-DeviceAttestation";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("deviceId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("deviceId")
                                            .keyType(KeyType.HASH)
                                            .build(),
                                    KeySchemaElement.builder()
                                            .attributeName("userId")
                                            .keyType(KeyType.RANGE)
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            configureTTL(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createFIDO2CredentialsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-FIDO2Credentials";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("credentialId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("credentialId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .streamSpecification(
                                    StreamSpecification.builder()
                                            .streamEnabled(true)
                                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createFIDO2ChallengesTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-FIDO2Challenges";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "challengeKey");
    }

    private static void createMFACredentialsTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-MFACredentials";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("mfaType")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("userId")
                                            .keyType(KeyType.HASH)
                                            .build(),
                                    KeySchemaElement.builder()
                                            .attributeName("mfaType")
                                            .keyType(KeyType.RANGE)
                                            .build())
                            .streamSpecification(
                                    StreamSpecification.builder()
                                            .streamEnabled(true)
                                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createMFABackupCodesTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-MFABackupCodes";
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("codeHash")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("userId")
                                            .keyType(KeyType.HASH)
                                            .build(),
                                    KeySchemaElement.builder()
                                            .attributeName("codeHash")
                                            .keyType(KeyType.RANGE)
                                            .build())
                            .streamSpecification(
                                    StreamSpecification.builder()
                                            .streamEnabled(true)
                                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createMFAOTPCodesTable(final DynamoDbClient dynamoDbClient) {
        final String tableName = TABLE_PREFIX + "-MFAOTPCodes";
        createSimpleTableWithTTL(dynamoDbClient, tableName, "otpKey");
    }

    // Helper methods

    private static void createTableWithUserIdIndex(
            final DynamoDbClient dynamoDbClient, final String tableName, final String keyName) {
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(keyName)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("updatedAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(keyName)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdUpdatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("userId")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("updatedAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void createSimpleTableWithTTL(
            final DynamoDbClient dynamoDbClient, final String tableName, final String keyName) {
        try {
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build());
                LOGGER.info("✅ Table {} already exists", tableName);
                return;
            } catch (ResourceNotFoundException e) {
            }

            final CreateTableRequest request =
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(keyName)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(keyName)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .build();

            dynamoDbClient.createTable(request);
            waitForTableActive(dynamoDbClient, tableName);
            configureTTL(dynamoDbClient, tableName);
            LOGGER.info("✅ Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            LOGGER.info("✅ Table {} already exists", tableName);
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping table creation for {}. Error: {}",
                        tableName,
                        e.getMessage());
                // Don't throw - allow initialization to continue for other tables
                return;
            }
            LOGGER.error("❌ Failed to create table {}: {}", tableName, e.getMessage(), e);
            // Re-throw for critical tables to fail fast
            throw new RuntimeException(
                    "Failed to create table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static void configureTTL(final DynamoDbClient dynamoDbClient, final String tableName) {
        try {
            dynamoDbClient.updateTimeToLive(
                    UpdateTimeToLiveRequest.builder()
                            .tableName(tableName)
                            .timeToLiveSpecification(
                                    TimeToLiveSpecification.builder()
                                            .enabled(true)
                                            .attributeName("ttl")
                                            .build())
                            .build());
        } catch (Exception e) {
            LOGGER.warn("Failed to configure TTL for table {}: {}", tableName, e.getMessage());
        }
    }

    private static void waitForTableActive(final DynamoDbClient dynamoDbClient, final String tableName) {
        final int maxAttempts = 20; // Increased for CI stability
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                final DescribeTableResponse response =
                        dynamoDbClient.describeTable(
                                DescribeTableRequest.builder().tableName(tableName).build());

                final TableDescription table = response.table();

                // Check if table is ACTIVE
                if (table.tableStatus() != TableStatus.ACTIVE) {
                    LOGGER.debug("Table {} status: {}, waiting...", tableName, table.tableStatus());
                    Thread.sleep(500);
                    attempt++;
                    continue;
                }

                // CRITICAL: Also check if all GSIs are ACTIVE
                // GSIs can still be CREATING even when table is ACTIVE
                boolean allGSIsActive = true;
                if (table.globalSecondaryIndexes() != null
                        && !table.globalSecondaryIndexes().isEmpty()) {
                    for (final GlobalSecondaryIndexDescription gsi : table.globalSecondaryIndexes()) {
                        if (gsi.indexStatus() != IndexStatus.ACTIVE) {
                            allGSIsActive = false;
                            LOGGER.debug(
                                    "Table {} GSI {} status: {}, waiting...",
                                    tableName,
                                    gsi.indexName(),
                                    gsi.indexStatus());
                            break;
                        }
                    }
                }

                if (allGSIsActive) {
                    LOGGER.debug("Table {} and all GSIs are now active", tableName);
                    return;
                }

                Thread.sleep(500); // Wait 500ms before checking again
                attempt++;
            } catch (Exception e) {
                LOGGER.warn("Error checking table status for {}: {}", tableName, e.getMessage());
                attempt++;
            }
        }
        LOGGER.warn(
                "Table {} or its GSIs may not be fully active yet (checked {} times)",
                tableName,
                maxAttempts);
    }

    private TableInitializer() {
    }
}
