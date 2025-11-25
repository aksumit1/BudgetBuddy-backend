package com.budgetbuddy.security.zerotrust.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Device Attestation Service
 * Verifies device integrity and trustworthiness
 * Implements continuous device verification
 */
@Service
public class DeviceAttestationService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAttestationService.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName = "BudgetBuddy-DeviceAttestations";

    public DeviceAttestationService(final DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        initializeTable();
    }

    /**
     * Verify device and register if new
     */
    public boolean verifyDevice(final String deviceId, final String userId) {
        try {
            // Check if device is registered and trusted
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "deviceId", AttributeValue.builder().s(deviceId).build(),
                            "userId", AttributeValue.builder().s(userId).build()
                    ))
                    .build());

            if (response.item() != null) {
                // Device is registered
                boolean trusted = Boolean.parseBoolean(response.item().getOrDefault("trusted", AttributeValue.builder().bool(false).build()).bool().toString());
                long lastVerified = Long.parseLong(response.item().get("lastVerified").n());

                // Check if verification is recent (within 24 hours)
                if (trusted && (Instant.now().getEpochSecond() - lastVerified) < 86400) {
                    // Update last verified timestamp
                    updateLastVerified(deviceId, userId);
                    return true;
                } else if (!trusted) {
                    logger.warn("Device not trusted: {} for user: {}", deviceId, userId);
                    return false;
                }
            }

            // New device - requires additional verification
            logger.info("New device detected: {} for user: {}", deviceId, userId);
            return registerNewDevice(deviceId, userId);
        } catch (Exception e) {
            logger.error("Failed to verify device: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Register new device (requires additional verification)
     */
    private boolean registerNewDevice(final String deviceId, final String userId) {
        // In production, this would require:
        // 1. Email verification
        // 2. SMS verification
        // 3. Biometric verification
        // For now, we'll register with pending status

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "deviceId", AttributeValue.builder().s(deviceId).build(),
                            "userId", AttributeValue.builder().s(userId).build(),
                            "trusted", AttributeValue.builder().bool(false).build(), // Requires verification
                            "registeredAt", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build(),
                            "lastVerified", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build(),
                            "ttl", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond() + 31536000)).build() // 1 year
                    ))
                    .build());

            logger.info("New device registered (pending verification): {} for user: {}", deviceId, userId);
            // In production, send verification email/SMS
            return false; // Require verification before allowing access
        } catch (Exception e) {
            logger.error("Failed to register device: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get device trust level
     */
    public DeviceTrustLevel getDeviceTrustLevel(final String deviceId) {
        // Simplified trust level calculation
        // In production, this would consider:
        // - Device age
        // - Previous security incidents
        // - Device type (mobile vs desktop)
        // - OS version
        // - Jailbreak/root status

        return DeviceTrustLevel.MEDIUM; // Default to medium trust
    }

    private void updateLastVerified(final String deviceId, final String userId) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "deviceId", AttributeValue.builder().s(deviceId).build(),
                            "userId", AttributeValue.builder().s(userId).build()
                    ))
                    .updateExpression("SET lastVerified = :timestamp")
                    .expressionAttributeValues(Map.of(
                            ":timestamp", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build()
                    ))
                    .build());
        } catch (Exception e) {
            logger.error("Failed to update last verified: {}", e.getMessage());
        }
    }

    private void initializeTable() {
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
                logger.warn("Failed to configure TTL for device attestation table: {}", e.getMessage());
            }
            logger.info("Device attestation table created");
        } catch (ResourceInUseException e) {
            logger.debug("Device attestation table already exists");
        } catch (Exception e) {
            logger.error("Failed to create device attestation table: {}", e.getMessage());
        }
    }

    /**
     * Device Trust Level
     */
    public enum DeviceTrustLevel {
        HIGH(20),
        MEDIUM(50),
        LOW(80),
        UNTRUSTED(100);

        private final int score;

        DeviceTrustLevel(int score) {
            this.score = score;
        }

        public int getScore() {
            return score;
        }
    }
}

