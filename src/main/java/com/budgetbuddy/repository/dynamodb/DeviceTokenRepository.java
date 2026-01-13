package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Device Token operations
 * Handles CRUD operations for device tokens
 */
@Repository
public class DeviceTokenRepository {

    private static final Logger logger = LoggerFactory.getLogger(DeviceTokenRepository.class);
    private final DynamoDbTable<DeviceTokenTable> table;
    private final String tableName;

    public DeviceTokenRepository(final DynamoDbClient dynamoDbClient,
                                 final DynamoDbEnhancedClient enhancedClient,
                                 @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-DeviceTokens";
        this.table = enhancedClient.table(this.tableName, TableSchema.fromBean(DeviceTokenTable.class));
        initializeTable(dynamoDbClient);
    }

    /**
     * Initialize DynamoDB table if it doesn't exist
     */
    private void initializeTable(final DynamoDbClient dynamoDbClient) {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder()
                    .tableName(tableName)
                    .build());
            logger.info("DeviceTokens table already exists: {}", tableName);
        } catch (ResourceNotFoundException e) {
            logger.info("DeviceTokens table does not exist, creating: {}", tableName);
            try {
                CreateTableRequest createTableRequest = CreateTableRequest.builder()
                        .tableName(tableName)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .attributeName("userId")
                                        .keyType(KeyType.HASH)
                                        .build(),
                                KeySchemaElement.builder()
                                        .attributeName("deviceToken")
                                        .keyType(KeyType.RANGE)
                                        .build()
                        )
                        .attributeDefinitions(
                                AttributeDefinition.builder()
                                        .attributeName("userId")
                                        .attributeType(ScalarAttributeType.S)
                                        .build(),
                                AttributeDefinition.builder()
                                        .attributeName("deviceToken")
                                        .attributeType(ScalarAttributeType.S)
                                        .build()
                        )
                        .billingMode(BillingMode.PAY_PER_REQUEST) // On-demand pricing
                        .build();

                dynamoDbClient.createTable(createTableRequest);
                logger.info("Created DeviceTokens table: {}", tableName);
            } catch (Exception ex) {
                if (isCredentialsError(ex)) {
                    logger.warn("⚠️ AWS credentials not configured for LocalStack or environment. Skipping DeviceTokens table creation. Error: {}", ex.getMessage());
                    return; // Exit gracefully
                }
                logger.error("Failed to create DeviceTokens table: {}", ex.getMessage());
            }
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                logger.warn("⚠️ AWS credentials not configured for LocalStack or environment. Skipping DeviceTokens table check. Error: {}", e.getMessage());
                return; // Exit gracefully
            }
            logger.error("Error checking DeviceTokens table: {}", e.getMessage());
        }
    }

    /**
     * Helper method to check if an exception is related to AWS credentials
     */
    private boolean isCredentialsError(Exception e) {
        if (e instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            String message = e.getMessage();
            return message != null && message.contains("Unable to load credentials");
        }
        // Check for wrapped SdkClientException
        Throwable cause = e.getCause();
        if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            String causeMessage = cause.getMessage();
            return causeMessage != null && causeMessage.contains("Unable to load credentials");
        }
        return false;
    }

    /**
     * Save or update device token
     */
    public void save(final DeviceTokenTable deviceToken) {
        try {
            if (deviceToken.getCreatedAt() == null) {
                deviceToken.setCreatedAt(Instant.now());
            }
            deviceToken.setUpdatedAt(Instant.now());
            if (deviceToken.getEnabled() == null) {
                deviceToken.setEnabled(true);
            }
            table.putItem(deviceToken);
            logger.debug("Saved device token for user: {}, platform: {}", 
                    deviceToken.getUserId(), deviceToken.getPlatform());
        } catch (Exception e) {
            logger.error("Failed to save device token: {}", e.getMessage());
            throw new RuntimeException("Failed to save device token", e);
        }
    }

    /**
     * Find device token by userId and deviceToken
     */
    public Optional<DeviceTokenTable> findByUserIdAndDeviceToken(final String userId, final String deviceToken) {
        try {
            Key key = Key.builder()
                    .partitionValue(userId)
                    .sortValue(deviceToken)
                    .build();
            
            DeviceTokenTable result = table.getItem(key);
            return Optional.ofNullable(result);
        } catch (Exception e) {
            logger.error("Failed to find device token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find all device tokens for a user
     */
    public List<DeviceTokenTable> findByUserId(final String userId) {
        try {
            QueryConditional queryConditional = QueryConditional
                    .keyEqualTo(Key.builder().partitionValue(userId).build());
            
            List<DeviceTokenTable> results = new ArrayList<>();
            table.query(queryConditional).items().forEach(results::add);
            
            logger.debug("Found {} device tokens for user: {}", results.size(), userId);
            return results;
        } catch (Exception e) {
            logger.error("Failed to find device tokens for user: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Find all enabled device tokens for a user
     */
    public List<DeviceTokenTable> findEnabledByUserId(final String userId) {
        List<DeviceTokenTable> allTokens = findByUserId(userId);
        return allTokens.stream()
                .filter(token -> token.getEnabled() != null && token.getEnabled())
                .toList();
    }

    /**
     * Delete device token
     */
    public void delete(final String userId, final String deviceToken) {
        try {
            Key key = Key.builder()
                    .partitionValue(userId)
                    .sortValue(deviceToken)
                    .build();
            
            table.deleteItem(key);
            logger.debug("Deleted device token for user: {}", userId);
        } catch (Exception e) {
            logger.error("Failed to delete device token: {}", e.getMessage());
        }
    }

    /**
     * Update last used timestamp
     */
    public void updateLastUsed(final String userId, final String deviceToken) {
        Optional<DeviceTokenTable> token = findByUserIdAndDeviceToken(userId, deviceToken);
        if (token.isPresent()) {
            DeviceTokenTable deviceTokenTable = token.get();
            deviceTokenTable.setLastUsedAt(Instant.now());
            save(deviceTokenTable);
        }
    }

    /**
     * Mark device token as disabled
     */
    public void disable(final String userId, final String deviceToken) {
        Optional<DeviceTokenTable> token = findByUserIdAndDeviceToken(userId, deviceToken);
        if (token.isPresent()) {
            DeviceTokenTable deviceTokenTable = token.get();
            deviceTokenTable.setEnabled(false);
            deviceTokenTable.setUpdatedAt(Instant.now());
            save(deviceTokenTable);
        }
    }
}
