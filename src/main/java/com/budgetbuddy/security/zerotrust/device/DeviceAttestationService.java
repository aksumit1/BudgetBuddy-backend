package com.budgetbuddy.security.zerotrust.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Instant;
import java.util.Map;

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
     * Enhanced with DeviceCheck/Play Integrity token verification
     */
    public boolean verifyDevice(final String deviceId, final String userId) {
        return verifyDevice(deviceId, userId, null, null);
    }

    /**
     * Verify device with attestation token (DeviceCheck/Play Integrity)
     * 
     * @param deviceId Device identifier
     * @param userId User identifier
     * @param attestationToken DeviceCheck token (iOS) or Play Integrity token (Android)
     * @param platform Platform type ("ios" or "android")
     * @return true if device is trusted
     */
    public boolean verifyDevice(final String deviceId, final String userId, final String attestationToken, final String platform) {
        try {
            // If attestation token is provided, verify it first
            if (attestationToken != null && !attestationToken.isEmpty() && platform != null && !platform.isEmpty()) {
                boolean tokenValid = verifyAttestationToken(attestationToken, platform, userId);
                if (!tokenValid) {
                    logger.warn("Device attestation token verification failed for device: {} user: {}", deviceId, userId);
                    return false;
                }
                logger.debug("Device attestation token verified successfully for device: {} user: {}", deviceId, userId);
            }

            // Check if device is registered and trusted
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "deviceId", AttributeValue.builder().s(deviceId).build(),
                            "userId", AttributeValue.builder().s(userId).build()
                    ))
                    .build());

            if (response.item() != null && !response.item().isEmpty()) {
                // Device is registered
                AttributeValue trustedValue = response.item().get("trusted");
                boolean trusted = trustedValue != null && trustedValue.bool() != null && trustedValue.bool();
                AttributeValue lastVerifiedValue = response.item().get("lastVerified");
                long lastVerified = lastVerifiedValue != null && lastVerifiedValue.n() != null 
                    ? Long.parseLong(lastVerifiedValue.n()) 
                    : Instant.now().getEpochSecond();

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
            return registerNewDevice(deviceId, userId, attestationToken, platform);
        } catch (Exception e) {
            logger.error("Failed to verify device: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify DeviceCheck (iOS) or Play Integrity (Android) attestation token
     * In production, this would call Apple/Google APIs to verify the token
     * 
     * CRITICAL FIX: Apple DeviceCheck tokens are CBOR-encoded (base64), not JWT
     * Android Play Integrity tokens are JWT format
     */
    private boolean verifyAttestationToken(final String attestationToken, final String platform, final String userId) {
        try {
            if (attestationToken == null || attestationToken.isEmpty()) {
                logger.warn("Attestation token is null or empty");
                return false;
            }
            
            if ("ios".equalsIgnoreCase(platform)) {
                // Apple DeviceCheck tokens are CBOR-encoded (base64), not JWT
                // They are base64-encoded CBOR objects, not JWT (which has 3 parts separated by dots)
                // For now, validate that it's valid base64 and has reasonable length
                // In production, decode CBOR and verify with Apple's DeviceCheck API
                return isValidAppleAttestationToken(attestationToken);
            } else if ("android".equalsIgnoreCase(platform)) {
                // Google Play Integrity tokens are JWT format
                // In production, use Google's Play Integrity API
                // For now, validate token format (JWT)
                return isValidJWTFormat(attestationToken);
            } else {
                logger.warn("Unsupported platform for device attestation: {}", platform);
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to verify attestation token: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Validate Apple DeviceCheck attestation token format
     * Apple tokens are base64-encoded CBOR, not JWT
     */
    private boolean isValidAppleAttestationToken(final String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        // Apple DeviceCheck tokens are base64-encoded CBOR
        // They should be valid base64 and have reasonable length (typically 1000-10000 chars)
        try {
            // Validate base64 encoding
            java.util.Base64.getDecoder().decode(token);
            // Check reasonable length (CBOR tokens are typically larger than JWT)
            return token.length() > 100 && token.length() < 50000;
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid base64 encoding in Apple attestation token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Basic JWT format validation (for Android Play Integrity tokens)
     */
    private boolean isValidJWTFormat(final String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        // JWT tokens have 3 parts separated by dots
        String[] parts = token.split("\\.");
        return parts.length == 3;
    }

    /**
     * Register new device (requires additional verification)
     * Enhanced with attestation token support
     */
    private boolean registerNewDevice(final String deviceId, final String userId, final String attestationToken, final String platform) {
        // If attestation token is provided and valid, trust the device immediately
        boolean shouldTrust = (attestationToken != null && !attestationToken.isEmpty() && 
                              platform != null && !platform.isEmpty() &&
                              verifyAttestationToken(attestationToken, platform, userId));

        try {
            Map<String, AttributeValue> item = new java.util.HashMap<>();
            item.put("deviceId", AttributeValue.builder().s(deviceId).build());
            item.put("userId", AttributeValue.builder().s(userId).build());
            item.put("trusted", AttributeValue.builder().bool(shouldTrust).build());
            item.put("registeredAt", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build());
            item.put("lastVerified", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build());
            item.put("ttl", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond() + 31536000)).build()); // 1 year
            
            if (platform != null && !platform.isEmpty()) {
                item.put("platform", AttributeValue.builder().s(platform).build());
            }
            if (attestationToken != null && !attestationToken.isEmpty()) {
                // Store token hash (not the token itself for security)
                item.put("attestationTokenHash", AttributeValue.builder().s(String.valueOf(attestationToken.hashCode())).build());
            }

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            if (shouldTrust) {
                logger.info("New device registered and trusted (attestation verified): {} for user: {}", deviceId, userId);
                return true;
            } else {
                logger.info("New device registered (pending verification): {} for user: {}", deviceId, userId);
                // In production, send verification email/SMS
                return false; // Require verification before allowing access
            }
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

