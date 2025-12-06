package com.budgetbuddy.service.dynamodb;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * DynamoDB Table Manager
 * Creates tables on application startup if they don't exist
 * Uses on-demand billing for cost optimization
 */
@Service
@ConditionalOnProperty(name = "app.aws.dynamodb.auto-create-tables", havingValue = "true", matchIfMissing = true)
public class DynamoDBTableManager {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDBTableManager.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tablePrefix;

    public DynamoDBTableManager(final DynamoDbClient dynamoDbClient, @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") String tablePrefix) {
        this.dynamoDbClient = dynamoDbClient;
        this.tablePrefix = tablePrefix;
    }

    /**
     * Initialize all DynamoDB tables on application startup
     * Called automatically via @PostConstruct
     */
    @PostConstruct
    public void initializeTables() {
        createUsersTable();
        createAccountsTable();
        createTransactionsTable();
        createBudgetsTable();
        createGoalsTable();
        createTransactionActionsTable();
        createAuditLogsTable();
        createNotFoundTrackingTable();
        createRateLimitTable();
        createDDoSProtectionTable();
        createDeviceAttestationTable();
        createFIDO2CredentialsTable();
        createFIDO2ChallengesTable();
        createMFACredentialsTable();
        createMFABackupCodesTable();
        createMFAOTPCodesTable();
        // BREAKING CHANGE: DevicePin table creation removed - PIN backend endpoints removed
        // DevicePin table is deprecated and removed - PIN is now local-only
        logger.info("DynamoDB tables initialized");
    }

    private void createUsersTable() {
        String tableName = tablePrefix + "-Users";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST) // On-demand for cost optimization
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createAccountsTable() {
        String tableName = tablePrefix + "-Accounts";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createTransactionsTable() {
        String tableName = tablePrefix + "-Transactions";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createBudgetsTable() {
        String tableName = tablePrefix + "-Budgets";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("budgetId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("userId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("budgetId")
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createGoalsTable() {
        String tableName = tablePrefix + "-Goals";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("goalId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("userId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("goalId")
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createTransactionActionsTable() {
        String tableName = tablePrefix + "-TransactionActions";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createAuditLogsTable() {
        String tableName = tablePrefix + "-AuditLogs";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                                    .projection(Projection.builder()
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.warn("Failed to create table {}: {}. This may be expected if LocalStack is not running.", tableName, e.getMessage());
        }
    }

    private void createNotFoundTrackingTable() {
        String tableName = tablePrefix + "-NotFoundTracking";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("sourceId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("sourceId")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());

            // Configure TTL separately
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for 404 tracking table: {}", e.getMessage());
            }
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }


    private void createRateLimitTable() {
        String tableName = tablePrefix + "-RateLimits";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("key")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("key")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());
            
            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for RateLimits table: {}", e.getMessage());
            }
            
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createDDoSProtectionTable() {
        String tableName = tablePrefix + "-DDoSProtection";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("ipAddress")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("ipAddress")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());
            
            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for DDoSProtection table: {}", e.getMessage());
            }
            
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createDeviceAttestationTable() {
        String tableName = tablePrefix + "-DeviceAttestation";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                    .build());
            
            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for DeviceAttestation table: {}", e.getMessage());
            }
            
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createFIDO2CredentialsTable() {
        String tableName = tablePrefix + "-FIDO2Credentials";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.ALL)
                                            .build())
                                    .build())
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createFIDO2ChallengesTable() {
        String tableName = tablePrefix + "-FIDO2Challenges";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("challengeKey")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("challengeKey")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());
            
            // Configure TTL separately
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for FIDO2Challenges table: {}", e.getMessage());
            }
            
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createMFACredentialsTable() {
        String tableName = tablePrefix + "-MFACredentials";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createMFABackupCodesTable() {
        String tableName = tablePrefix + "-MFABackupCodes";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
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
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }

    private void createMFAOTPCodesTable() {
        String tableName = tablePrefix + "-MFAOTPCodes";
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("otpKey")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("otpKey")
                                    .keyType(KeyType.HASH)
                                    .build())
                    .build());
            
            // Configure TTL
            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build())
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to configure TTL for MFAOTPCodes table: {}", e.getMessage());
            }
            
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage());
        }
    }
}

