package com.budgetbuddy.service.dynamodb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;

/**
 * DynamoDB Table Manager
 * Creates tables on application startup if they don't exist
 * Uses on-demand billing for cost optimization
 */
@Service
public class DynamoDBTableManager {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDBTableManager.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tablePrefix;

    public DynamoDBTableManager(final DynamoDbClient dynamoDbClient, @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") String tablePrefix) {
        this.dynamoDbClient = dynamoDbClient;
        this.tablePrefix = tablePrefix;
    }

    /**
     * Initialize all DynamoDB tables
     * Called on application startup
     */
    public void initializeTables() {
        createUsersTable();
        createAccountsTable();
        createTransactionsTable();
        createBudgetsTable();
        createGoalsTable();
        createAuditLogsTable();
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
                                    .build())
                    .build());
            logger.info("Created table: {}", tableName);
        } catch (ResourceInUseException e) {
            logger.debug("Table {} already exists", tableName);
        }
    }
}

