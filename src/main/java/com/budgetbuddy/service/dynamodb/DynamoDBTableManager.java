package com.budgetbuddy.service.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * DynamoDB Table Manager Creates tables on application startup if they don't exist Uses on-demand
 * billing for cost optimization
 *
 * <p>CRITICAL: This service does NOT run in test profile - tests use TableInitializer instead
 */
@Service
@ConditionalOnProperty(
        name = "app.aws.dynamodb.auto-create-tables",
        havingValue = "true",
        matchIfMissing = true)
@org.springframework.context.annotation.Profile(
        "!test") // Don't run in tests - tests use TableInitializer
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class DynamoDBTableManager {

    private static final String CREATED_TABLE = "Created table: {}";

    private static final String FAILED_TO_CREATE_TABLE = "Failed to create table {}: {}";

    private static final String FAILED_TO_CREATE_TABLE_THIS_MAY_BE = "Failed to create table {}: {}. This may be expected if LocalStack is not running.";

    private static final String TABLE_ALREADY_EXISTS = "Table {} already exists";

    private static final String USER_ID_INDEX = "UserIdIndex";

    private static final String USER_ID_UPDATED_AT_INDEX = "UserIdUpdatedAtIndex";

    private static final String ACCOUNT_ID = "accountId";

    private static final String CREATED_AT = "createdAt";

    private static final String GOAL_ID = "goalId";

    private static final String TRANSACTION_DATE = "transactionDate";

    private static final String TRANSACTION_ID = "transactionId";

    private static final String UPDATED_AT_TIMESTAMP = "updatedAtTimestamp";

    private static final String USER_ID = "userId";

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDBTableManager.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tablePrefix;

    public DynamoDBTableManager(
            final DynamoDbClient dynamoDbClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.dynamoDbClient = dynamoDbClient;
        this.tablePrefix = tablePrefix;
    }

    /**
     * Initialize all DynamoDB tables on application startup Called automatically via @PostConstruct
     */
    @PostConstruct
    public void initializeTables() {
        createUsersTable();
        createAccountsTable();
        createTransactionsTable();
        createBudgetsTable();
        createGoalsTable();
        createTransactionActionsTable();
        createSubscriptionsTable();
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
        createImportHistoryTable();
        createAnomalyFeedbackTable();
        // BREAKING CHANGE: DevicePin table creation removed - PIN backend endpoints removed
        // DevicePin table is deprecated and removed - PIN is now local-only
        LOGGER.info("DynamoDB tables initialized");
    }

    private void createUsersTable() {
        final String tableName = tablePrefix + "-Users";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(
                                    BillingMode.PAY_PER_REQUEST) // On-demand for cost optimization
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("email")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("lastLoginAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("activeStatus")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(USER_ID)
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
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("ActiveUsersIndex")
                                            .keySchema(
                                                    // Use activeStatus (String) as partition key
                                                    // (ACTIVE/INACTIVE)
                                                    // Use lastLoginAtTimestamp as sort key for
                                                    // range queries
                                                    KeySchemaElement.builder()
                                                            .attributeName("activeStatus")
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("lastLoginAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createAccountsTable() {
        final String tableName = tablePrefix + "-Accounts";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(ACCOUNT_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("plaidAccountId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("plaidItemId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(ACCOUNT_ID)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
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
                                            .indexName("PlaidItemIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName("plaidItemId")
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_UPDATED_AT_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createTransactionsTable() {
        final String tableName = tablePrefix + "-Transactions";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(TRANSACTION_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(TRANSACTION_DATE)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("plaidTransactionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(ACCOUNT_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                            .attributeType(ScalarAttributeType.N)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(GOAL_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(TRANSACTION_ID)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdDateIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(TRANSACTION_DATE)
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
                                                            .attributeName(ACCOUNT_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(TRANSACTION_DATE)
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
                                            .indexName(USER_ID_UPDATED_AT_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdGoalIdIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(GOAL_ID)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createBudgetsTable() {
        final String tableName = tablePrefix + "-Budgets";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("budgetId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("budgetId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_UPDATED_AT_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createGoalsTable() {
        final String tableName = tablePrefix + "-Goals";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(GOAL_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(GOAL_ID)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_UPDATED_AT_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createTransactionActionsTable() {
        final String tableName = tablePrefix + "-TransactionActions";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("actionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(TRANSACTION_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
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
                                            .attributeName(UPDATED_AT_TIMESTAMP)
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
                                                            .attributeName(TRANSACTION_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
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
                                            .indexName(USER_ID_UPDATED_AT_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(UPDATED_AT_TIMESTAMP)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createSubscriptionsTable() {
        final String tableName = tablePrefix + "-Subscriptions";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("subscriptionId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("subscriptionId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createAuditLogsTable() {
        final String tableName = tablePrefix + "-AuditLogs";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("auditLogId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(CREATED_AT)
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
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName(CREATED_AT)
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
                                                            .attributeName(CREATED_AT)
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createNotFoundTrackingTable() {
        final String tableName = tablePrefix + "-NotFoundTracking";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
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
                LOGGER.warn("Failed to configure TTL for 404 tracking table: {}", e.getMessage());
            }
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createRateLimitTable() {
        final String tableName = tablePrefix + "-RateLimits";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
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
                LOGGER.warn("Failed to configure TTL for RateLimits table: {}", e.getMessage());
            }

            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createDDoSProtectionTable() {
        final String tableName = tablePrefix + "-DDoSProtection";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
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
                LOGGER.warn("Failed to configure TTL for DDoSProtection table: {}", e.getMessage());
            }

            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createDeviceAttestationTable() {
        final String tableName = tablePrefix + "-DeviceAttestation";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("deviceId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("deviceId")
                                            .keyType(KeyType.HASH)
                                            .build(),
                                    KeySchemaElement.builder()
                                            .attributeName(USER_ID)
                                            .keyType(KeyType.RANGE)
                                            .build())
                            .build());

            // Configure TTL
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
                LOGGER.warn(
                        "Failed to configure TTL for DeviceAttestation table: {}", e.getMessage());
            }

            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createFIDO2CredentialsTable() {
        final String tableName = tablePrefix + "-FIDO2Credentials";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("credentialId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("credentialId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
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
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createFIDO2ChallengesTable() {
        final String tableName = tablePrefix + "-FIDO2Challenges";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
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
                LOGGER.warn(
                        "Failed to configure TTL for FIDO2Challenges table: {}", e.getMessage());
            }

            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createMFACredentialsTable() {
        final String tableName = tablePrefix + "-MFACredentials";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("mfaType")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(USER_ID)
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
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createMFABackupCodesTable() {
        final String tableName = tablePrefix + "-MFABackupCodes";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("codeHash")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(USER_ID)
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
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createMFAOTPCodesTable() {
        final String tableName = tablePrefix + "-MFAOTPCodes";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
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
                LOGGER.warn("Failed to configure TTL for MFAOTPCodes table: {}", e.getMessage());
            }

            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.error(FAILED_TO_CREATE_TABLE, tableName, e.getMessage());
        }
    }

    private void createImportHistoryTable() {
        final String tableName = tablePrefix + "-ImportHistory";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("importId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("createdAtTimestamp")
                                            .attributeType(ScalarAttributeType.N)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("importId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build(),
                                    GlobalSecondaryIndex.builder()
                                            .indexName("UserIdCreatedAtIndex")
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build(),
                                                    KeySchemaElement.builder()
                                                            .attributeName("createdAtTimestamp")
                                                            .keyType(KeyType.RANGE)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }

    private void createAnomalyFeedbackTable() {
        final String tableName = tablePrefix + "-AnomalyFeedback";
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("feedbackId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName(USER_ID)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("feedbackId")
                                            .keyType(KeyType.HASH)
                                            .build())
                            .globalSecondaryIndexes(
                                    GlobalSecondaryIndex.builder()
                                            .indexName(USER_ID_INDEX)
                                            .keySchema(
                                                    KeySchemaElement.builder()
                                                            .attributeName(USER_ID)
                                                            .keyType(KeyType.HASH)
                                                            .build())
                                            .projection(
                                                    Projection.builder()
                                                            .projectionType(ProjectionType.ALL)
                                                            .build())
                                            .build())
                            .build());
            LOGGER.info(CREATED_TABLE, tableName);
        } catch (ResourceInUseException e) {
            LOGGER.debug(TABLE_ALREADY_EXISTS, tableName);
        } catch (Exception e) {
            LOGGER.warn(
                    FAILED_TO_CREATE_TABLE_THIS_MAY_BE,
                    tableName,
                    e.getMessage());
        }
    }
}
