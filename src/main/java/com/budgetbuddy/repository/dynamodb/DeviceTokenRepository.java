package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Repository for Device Token operations Handles CRUD operations for device tokens */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Repository
public class DeviceTokenRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceTokenRepository.class);
    private final DynamoDbTable<DeviceTokenTable> table;
    private final String tableName;

    public DeviceTokenRepository(
            final DynamoDbClient dynamoDbClient,
            final DynamoDbEnhancedClient enhancedClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-DeviceTokens";
        this.table =
                enhancedClient.table(this.tableName, TableSchema.fromBean(DeviceTokenTable.class));
        initializeTable(dynamoDbClient);
    }

    /** Initialize DynamoDB table if it doesn't exist */
    private void initializeTable(final DynamoDbClient dynamoDbClient) {
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            LOGGER.info("DeviceTokens table already exists: {}", tableName);
        } catch (ResourceNotFoundException e) {
            LOGGER.info("DeviceTokens table does not exist, creating: {}", tableName);
            try {
                final CreateTableRequest createTableRequest =
                        CreateTableRequest.builder()
                                .tableName(tableName)
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("userId")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName("deviceToken")
                                                .keyType(KeyType.RANGE)
                                                .build())
                                .attributeDefinitions(
                                        AttributeDefinition.builder()
                                                .attributeName("userId")
                                                .attributeType(ScalarAttributeType.S)
                                                .build(),
                                        AttributeDefinition.builder()
                                                .attributeName("deviceToken")
                                                .attributeType(ScalarAttributeType.S)
                                                .build())
                                .billingMode(BillingMode.PAY_PER_REQUEST) // On-demand pricing
                                .build();

                dynamoDbClient.createTable(createTableRequest);
                LOGGER.info("Created DeviceTokens table: {}", tableName);
            } catch (Exception ex) {
                if (isCredentialsError(ex)) {
                    LOGGER.warn(
                            "⚠️ AWS credentials not configured for LocalStack or environment. Skipping DeviceTokens table creation. Error: {}",
                            ex.getMessage());
                    return; // Exit gracefully
                }
                LOGGER.error("Failed to create DeviceTokens table: {}", ex.getMessage());
            }
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping DeviceTokens table check. Error: {}",
                        e.getMessage());
                return; // Exit gracefully
            }
            LOGGER.error("Error checking DeviceTokens table: {}", e.getMessage());
        }
    }

    /** Helper method to check if an exception is related to AWS credentials */
    private boolean isCredentialsError(final Exception e) {
        if (e instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String message = e.getMessage();
            return message != null && message.contains("Unable to load credentials");
        }
        // Check for wrapped SdkClientException
        final Throwable cause = e.getCause();
        if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
            final String causeMessage = cause.getMessage();
            return causeMessage != null && causeMessage.contains("Unable to load credentials");
        }
        return false;
    }

    /** Save or update device token */
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
            LOGGER.debug(
                    "Saved device token for user: {}, platform: {}",
                    deviceToken.getUserId(),
                    deviceToken.getPlatform());
        } catch (Exception e) {
            LOGGER.error("Failed to save device token: {}", e.getMessage());
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to save device token", e);
        }
    }

    /** Find device token by userId and deviceToken */
    public Optional<DeviceTokenTable> findByUserIdAndDeviceToken(
            final String userId, final String deviceToken) {
        try {
            final Key key = Key.builder().partitionValue(userId).sortValue(deviceToken).build();

            final DeviceTokenTable result = table.getItem(key);
            return Optional.ofNullable(result);
        } catch (Exception e) {
            LOGGER.error("Failed to find device token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Find all device tokens for a user */
    public List<DeviceTokenTable> findByUserId(final String userId) {
        try {
            final QueryConditional queryConditional =
                    QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build());

            final List<DeviceTokenTable> results = new ArrayList<>();
            table.query(queryConditional).items().forEach(results::add);

            LOGGER.debug("Found {} device tokens for user: {}", results.size(), userId);
            return results;
        } catch (Exception e) {
            LOGGER.error("Failed to find device tokens for user: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Find all enabled device tokens for a user */
    public List<DeviceTokenTable> findEnabledByUserId(final String userId) {
        final List<DeviceTokenTable> allTokens = findByUserId(userId);
        return allTokens.stream()
                .filter(token -> token.getEnabled() != null && token.getEnabled())
                .toList();
    }

    /** Delete device token */
    public void delete(final String userId, final String deviceToken) {
        try {
            final Key key = Key.builder().partitionValue(userId).sortValue(deviceToken).build();

            table.deleteItem(key);
            LOGGER.debug("Deleted device token for user: {}", userId);
        } catch (Exception e) {
            LOGGER.error("Failed to delete device token: {}", e.getMessage());
        }
    }

    /** Update last used timestamp */
    public void updateLastUsed(final String userId, final String deviceToken) {
        final Optional<DeviceTokenTable> token = findByUserIdAndDeviceToken(userId, deviceToken);
        if (token.isPresent()) {
            final DeviceTokenTable deviceTokenTable = token.get();
            deviceTokenTable.setLastUsedAt(Instant.now());
            save(deviceTokenTable);
        }
    }

    /** Mark device token as disabled */
    public void disable(final String userId, final String deviceToken) {
        final Optional<DeviceTokenTable> token = findByUserIdAndDeviceToken(userId, deviceToken);
        if (token.isPresent()) {
            final DeviceTokenTable deviceTokenTable = token.get();
            deviceTokenTable.setEnabled(false);
            deviceTokenTable.setUpdatedAt(Instant.now());
            save(deviceTokenTable);
        }
    }
}
