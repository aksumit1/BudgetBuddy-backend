package com.budgetbuddy.security.zerotrust.device;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Device Attestation Service Verifies device integrity and trustworthiness Implements continuous
 * device verification
 */
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
@Service
public final class DeviceAttestationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAttestationService.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DeviceAttestationService(
            final DynamoDbClient dynamoDbClient,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tablePrefix + "-DeviceAttestations";
        initializeTable();
    }

    /**
     * Verify device and register if new Enhanced with DeviceCheck/Play Integrity token verification
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
    public boolean verifyDevice(
            final String deviceId,
            final String userId,
            final String attestationToken,
            final String platform) {
        try {
            // If attestation token is provided, verify it first
            if (attestationToken != null
                    && !attestationToken.isEmpty()
                    && platform != null
                    && !platform.isEmpty()) {
                final boolean tokenValid = verifyAttestationToken(attestationToken, platform, userId);
                if (!tokenValid) {
                    LOGGER.warn(
                            "Device attestation token verification failed for device: {} user: {}",
                            deviceId,
                            userId);
                    return false;
                }
                LOGGER.debug(
                        "Device attestation token verified successfully for device: {} user: {}",
                        deviceId,
                        userId);
            }

            // Check if device is registered and trusted
            final GetItemResponse response =
                    dynamoDbClient.getItem(
                            GetItemRequest.builder()
                                    .tableName(tableName)
                                    .key(
                                            Map.of(
                                                    "deviceId",
                                                    AttributeValue.builder()
                                                            .s(deviceId)
                                                            .build(),
                                                    "userId",
                                                    AttributeValue.builder()
                                                            .s(userId)
                                                            .build()))
                                    .build());

            if (response.item() != null && !response.item().isEmpty()) {
                // Device is registered
                final AttributeValue trustedValue = response.item().get("trusted");
                final boolean trusted =
                        trustedValue != null && trustedValue.bool() != null && trustedValue.bool();
                final AttributeValue lastVerifiedValue = response.item().get("lastVerified");
                final long lastVerified =
                        lastVerifiedValue != null && lastVerifiedValue.n() != null
                                ? Long.parseLong(lastVerifiedValue.n())
                                : Instant.now().getEpochSecond();

                // Check if verification is recent (within 24 hours)
                if (trusted && (Instant.now().getEpochSecond() - lastVerified) < 86_400) {
                    // Update last verified timestamp
                    updateLastVerified(deviceId, userId);
                    return true;
                } else if (!trusted) {
                    LOGGER.warn("Device not trusted: {} for user: {}", deviceId, userId);
                    return false;
                }
            }

            // New device - requires additional verification
            LOGGER.info("New device detected: {} for user: {}", deviceId, userId);
            return registerNewDevice(deviceId, userId, attestationToken, platform);
        } catch (Exception e) {
            LOGGER.error("Failed to verify device: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify DeviceCheck (iOS) or Play Integrity (Android) attestation token In production, this
     * would call Apple/Google APIs to verify the token
     *
     * <p>CRITICAL FIX: Apple DeviceCheck tokens are CBOR-encoded (base64), not JWT Android Play
     * Integrity tokens are JWT format
     */
    private boolean verifyAttestationToken(
            final String attestationToken, final String platform, final String userId) {
        try {
            if (attestationToken == null || attestationToken.isEmpty()) {
                LOGGER.warn("Attestation token is null or empty");
                return false;
            }

            if ("ios".equalsIgnoreCase(platform)) {
                // Apple DeviceCheck tokens are CBOR-encoded (base64), not JWT
                // They are base64-encoded CBOR objects, not JWT (which has 3 parts separated by
                // dots)
                // For now, validate that it's valid base64 and has reasonable length
                // In production, decode CBOR and verify with Apple's DeviceCheck API
                return isValidAppleAttestationToken(attestationToken);
            } else if ("android".equalsIgnoreCase(platform)) {
                // Google Play Integrity tokens are JWT format
                // In production, use Google's Play Integrity API
                // For now, validate token format (JWT)
                return isValidJWTFormat(attestationToken);
            } else {
                LOGGER.warn("Unsupported platform for device attestation: {}", platform);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to verify attestation token: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Validate Apple DeviceCheck attestation token format Apple tokens are base64-encoded CBOR, not
     * JWT
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
            return token.length() > 100 && token.length() < 50_000;
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Invalid base64 encoding in Apple attestation token: {}", e.getMessage());
            return false;
        }
    }

    /** Basic JWT format validation (for Android Play Integrity tokens) */
    private boolean isValidJWTFormat(final String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        // JWT tokens have 3 parts separated by dots
        final String[] parts = token.split("\\.");
        return parts.length == 3;
    }

    /**
     * Register new device (requires additional verification) Enhanced with attestation token
     * support
     */
    private boolean registerNewDevice(
            final String deviceId,
            final String userId,
            final String attestationToken,
            final String platform) {
        // If attestation token is provided and valid, trust the device immediately
        final boolean shouldTrust =
                attestationToken != null
                        && !attestationToken.isEmpty()
                        && platform != null
                        && !platform.isEmpty()
                        && verifyAttestationToken(attestationToken, platform, userId);

        try {
            final Map<String, AttributeValue> item = new java.util.HashMap<>();
            item.put("deviceId", AttributeValue.builder().s(deviceId).build());
            item.put("userId", AttributeValue.builder().s(userId).build());
            item.put("trusted", AttributeValue.builder().bool(shouldTrust).build());
            item.put(
                    "registeredAt",
                    AttributeValue.builder()
                            .n(String.valueOf(Instant.now().getEpochSecond()))
                            .build());
            item.put(
                    "lastVerified",
                    AttributeValue.builder()
                            .n(String.valueOf(Instant.now().getEpochSecond()))
                            .build());
            item.put(
                    "ttl",
                    AttributeValue.builder()
                            .n(String.valueOf(Instant.now().getEpochSecond() + 31_536_000))
                            .build()); // 1 year

            if (platform != null && !platform.isEmpty()) {
                item.put("platform", AttributeValue.builder().s(platform).build());
            }
            if (attestationToken != null && !attestationToken.isEmpty()) {
                // Store token hash (not the token itself for security)
                item.put(
                        "attestationTokenHash",
                        AttributeValue.builder()
                                .s(String.valueOf(attestationToken.hashCode()))
                                .build());
            }

            dynamoDbClient.putItem(
                    PutItemRequest.builder().tableName(tableName).item(item).build());

            if (shouldTrust) {
                LOGGER.info(
                        "New device registered and trusted (attestation verified): {} for user: {}",
                        deviceId,
                        userId);
                return true;
            } else {
                LOGGER.info(
                        "New device registered (pending verification): {} for user: {}",
                        deviceId,
                        userId);
                // In production, send verification email/SMS
                return false; // Require verification before allowing access
            }
        } catch (Exception e) {
            LOGGER.error("Failed to register device: {}", e.getMessage());
            return false;
        }
    }

    /** Get device trust level */
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
            dynamoDbClient.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(
                                    Map.of(
                                            "deviceId",
                                                    AttributeValue.builder().s(deviceId).build(),
                                            "userId", AttributeValue.builder().s(userId).build()))
                            .updateExpression("SET lastVerified = :timestamp")
                            .expressionAttributeValues(
                                    Map.of(
                                            ":timestamp",
                                            AttributeValue.builder()
                                                    .n(
                                                            String.valueOf(
                                                                    Instant.now().getEpochSecond()))
                                                    .build()))
                            .build());
        } catch (Exception e) {
            LOGGER.error("Failed to update last verified: {}", e.getMessage());
        }
    }

    private void initializeTable() {
        // Check if table already exists before attempting to create it
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            // Table exists, no need to create it
            return;
        } catch (ResourceNotFoundException e) {
            // Table doesn't exist, proceed with creation
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping device attestation table check. Error: {}",
                        e.getMessage());
                return; // Exit gracefully
            }
            LOGGER.warn("Failed to check if device attestation table exists: {}", e.getMessage());
            // Continue with creation attempt
        }

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
                if (isCredentialsError(e)) {
                    LOGGER.warn(
                            "⚠️ AWS credentials not configured. Skipping TTL configuration for device attestation table. Error: {}",
                            e.getMessage());
                } else {
                    LOGGER.warn(
                            "Failed to configure TTL for device attestation table: {}",
                            e.getMessage());
                }
            }
            LOGGER.info("Device attestation table created");
        } catch (ResourceInUseException e) {
            // Table was created by another instance between check and create - this is fine
            LOGGER.debug("Device attestation table already exists (race condition)");
        } catch (Exception e) {
            if (isCredentialsError(e)) {
                LOGGER.warn(
                        "⚠️ AWS credentials not configured for LocalStack or environment. Skipping device attestation table creation. Error: {}",
                        e.getMessage());
                return; // Exit gracefully
            }
            LOGGER.error("Failed to create device attestation table: {}", e.getMessage());
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

    /** Device Trust Level */
    public enum DeviceTrustLevel {
        HIGH(20),
        MEDIUM(50),
        LOW(80),
        UNTRUSTED(100);

        private final int score;

        DeviceTrustLevel(final int score) {
            this.score = score;
        }

        public int getScore() {
            return score;
        }
    }
}
