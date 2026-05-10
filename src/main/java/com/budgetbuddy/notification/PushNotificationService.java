package com.budgetbuddy.notification;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse;
import software.amazon.awssdk.services.sns.model.GetEndpointAttributesRequest;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Push Notification Service using AWS SNS Enhanced with device token management and multi-device
 * support
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
public class PushNotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushNotificationService.class);

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final DeviceTokenRepository deviceTokenRepository;
    private final String iosPlatformApplicationArn;
    private final String androidPlatformApplicationArn;
    private final PushNotificationMetrics metrics;

    public PushNotificationService(
            final SnsClient snsClient,
            final ObjectMapper objectMapper,
            final DeviceTokenRepository deviceTokenRepository,
            @Value("${app.notifications.sns.ios-platform-arn:}")
                    final String iosPlatformApplicationArn,
            @Value("${app.notifications.sns.android-platform-arn:}")
                    final String androidPlatformApplicationArn,
            final PushNotificationMetrics metrics) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.deviceTokenRepository = deviceTokenRepository;
        this.iosPlatformApplicationArn = iosPlatformApplicationArn;
        this.androidPlatformApplicationArn = androidPlatformApplicationArn;
        this.metrics = metrics;
    }

    /** Send push notification to a specific device */
    public boolean sendPushNotification(
            final String userId,
            final String title,
            final String body,
            final Map<String, Object> data) {
        try {
            // Get device endpoint ARN for user
            final String endpointArn = getDeviceEndpointArn(userId);

            if (endpointArn == null || endpointArn.isEmpty()) {
                LOGGER.warn("No device endpoint found for user: {}", userId);
                return false;
            }

            // Create platform-specific message
            final String message = createPlatformMessage(title, body, data);

            final PublishRequest request =
                    PublishRequest.builder()
                            .targetArn(endpointArn)
                            .message(message)
                            .messageStructure("json")
                            .build();

            final PublishResponse response = snsClient.publish(request);

            LOGGER.info(
                    "Push notification sent - MessageId: {}, User: {}",
                    response.messageId(),
                    userId);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send push notification: {}", e.getMessage());
            return false;
        }
    }

    /** Send push notification to all user's devices Returns count of successful sends */
    public int sendPushNotificationToAllDevices(
            final String userId,
            final String title,
            final String body,
            final Map<String, Object> data) {
        try {
            final List<DeviceTokenTable> devices = deviceTokenRepository.findEnabledByUserId(userId);

            if (devices.isEmpty()) {
                LOGGER.debug("No enabled devices found for user: {}", userId);
                return 0;
            }

            final long startTime = System.currentTimeMillis();
            int successCount = 0;
            int failureCount = 0;

            for (final DeviceTokenTable device : devices) {
                try {
                    if (device.getEndpointArn() == null || device.getEndpointArn().isEmpty()) {
                        LOGGER.warn(
                                "Device {} has no endpoint ARN, skipping", device.getDeviceToken());
                        continue;
                    }

                    final String message = createPlatformMessage(title, body, data, device.getPlatform());

                    final PublishRequest request =
                            PublishRequest.builder()
                                    .targetArn(device.getEndpointArn())
                                    .message(message)
                                    .messageStructure("json")
                                    .build();

                    final PublishResponse response = snsClient.publish(request);

                    // Update last used timestamp
                    deviceTokenRepository.updateLastUsed(userId, device.getDeviceToken());

                    successCount++;
                    final long deliveryTime = System.currentTimeMillis() - startTime;
                    metrics.recordNotificationDelivered(1, deliveryTime);

                    LOGGER.debug(
                            "Push notification sent to device {} - MessageId: {}",
                            device.getDeviceToken()
                                    .substring(0, Math.min(8, device.getDeviceToken().length())),
                            response.messageId());
                } catch (InvalidParameterException e) {
                    // Invalid endpoint - likely device uninstalled app or token expired
                    LOGGER.warn(
                            "Invalid endpoint ARN for device {}, disabling: {}",
                            device.getDeviceToken()
                                    .substring(0, Math.min(8, device.getDeviceToken().length())),
                            e.getMessage());
                    deviceTokenRepository.disable(userId, device.getDeviceToken());
                    metrics.recordInvalidEndpoint();
                    metrics.recordDeviceDisabled();
                    failureCount++;
                } catch (Exception e) {
                    LOGGER.error(
                            "Failed to send push notification to device {}: {}",
                            device.getDeviceToken()
                                    .substring(0, Math.min(8, device.getDeviceToken().length())),
                            e.getMessage());
                    failureCount++;
                }
            }

            // Record metrics
            metrics.recordNotificationSent(devices.size());
            if (failureCount > 0) {
                metrics.recordNotificationFailed(failureCount);
            }
            metrics.updateActiveDevices(devices.size() - failureCount);

            LOGGER.info(
                    "Sent push notifications to {}/{} devices for user: {}",
                    successCount,
                    devices.size(),
                    userId);
            return successCount;
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to send push notifications to all devices for user {}: {}",
                    userId,
                    e.getMessage());
            return 0;
        }
    }

    /**
     * Register device for push notifications Creates or updates device token in database and
     * registers with AWS SNS
     */
    public String registerDevice(
            final String userId, final String deviceToken, final String platform) {
        try {
            final String platformArn = getPlatformApplicationArn(platform);
            if (platformArn == null || platformArn.isEmpty()) {
                LOGGER.error("Platform application ARN not configured for platform: {}", platform);
                return null;
            }

            // Check if device token already exists
            final Optional<DeviceTokenTable> existing =
                    deviceTokenRepository.findByUserIdAndDeviceToken(userId, deviceToken);

            String endpointArn;
            if (existing.isPresent()
                    && existing.get().getEndpointArn() != null
                    && !existing.get().getEndpointArn().isEmpty()) {
                // Device already registered, try to update endpoint
                endpointArn = existing.get().getEndpointArn();
                try {
                    // Verify endpoint is still valid
                    snsClient.getEndpointAttributes(
                            GetEndpointAttributesRequest.builder()
                                    .endpointArn(endpointArn)
                                    .build());
                    LOGGER.debug("Device endpoint already exists and is valid: {}", endpointArn);
                } catch (Exception e) {
                    // Endpoint invalid, create new one
                    LOGGER.info("Existing endpoint invalid, creating new endpoint");
                    endpointArn = createNewEndpoint(userId, deviceToken, platform, platformArn);
                }
            } else {
                // New device, create endpoint
                endpointArn = createNewEndpoint(userId, deviceToken, platform, platformArn);
            }

            // Save or update device token in database
            final DeviceTokenTable deviceTokenTable = existing.orElse(new DeviceTokenTable());
            deviceTokenTable.setUserId(userId);
            deviceTokenTable.setDeviceToken(deviceToken);
            deviceTokenTable.setPlatform(platform);
            deviceTokenTable.setEndpointArn(endpointArn);
            deviceTokenTable.setEnabled(true);
            if (deviceTokenTable.getCreatedAt() == null) {
                deviceTokenTable.setCreatedAt(Instant.now());
            }
            deviceTokenTable.setUpdatedAt(Instant.now());
            deviceTokenRepository.save(deviceTokenTable);

            LOGGER.info(
                    "Device registered - EndpointArn: {}, User: {}, Platform: {}",
                    endpointArn,
                    userId,
                    platform);
            return endpointArn;
        } catch (Exception e) {
            LOGGER.error("Failed to register device: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Create new SNS endpoint for device */
    private String createNewEndpoint(
            final String userId,
            final String deviceToken,
            final String platform,
            final String platformArn) {
        try {
            final CreatePlatformEndpointRequest request =
                    CreatePlatformEndpointRequest.builder()
                            .platformApplicationArn(platformArn)
                            .token(deviceToken)
                            .attributes(Map.of("UserId", userId))
                            .build();

            final CreatePlatformEndpointResponse response = snsClient.createPlatformEndpoint(request);
            return response.endpointArn();
        } catch (Exception e) {
            LOGGER.error("Failed to create SNS endpoint: {}", e.getMessage());
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create SNS endpoint", e);
        }
    }

    /**
     * Get device endpoint ARN for user (first enabled device)
     *
     * @deprecated Use sendPushNotificationToAllDevices instead
     */
    @Deprecated
    private String getDeviceEndpointArn(final String userId) {
        final List<DeviceTokenTable> devices = deviceTokenRepository.findEnabledByUserId(userId);
        if (devices.isEmpty()) {
            return null;
        }
        return devices.get(0).getEndpointArn();
    }

    /** Get platform application ARN from configuration */
    private String getPlatformApplicationArn(final String platform) {
        if ("ios".equalsIgnoreCase(platform)) {
            return iosPlatformApplicationArn;
        } else if ("android".equalsIgnoreCase(platform)) {
            return androidPlatformApplicationArn;
        }
        LOGGER.warn("Unknown platform: {}", platform);
        return null;
    }

    /** Create platform-specific message for all platforms */
    private String createPlatformMessage(
            final String title, final String body, final Map<String, Object> data) {
        return createPlatformMessage(title, body, data, null);
    }

    /**
     * Create platform-specific message Supports silent notifications (content-available: 1) for
     * data sync
     */
    private String createPlatformMessage(
            final String title,
            final String body,
            final Map<String, Object> data,
            final String platform) {
        try {
            final boolean isSilent = data != null && Boolean.TRUE.equals(data.get("silent"));

            final Map<String, String> platforms = new HashMap<>();
            platforms.put("default", body);

            // APNS (iOS) format
            final Map<String, Object> apsMap = new HashMap<>();
            if (!isSilent) {
                apsMap.put("alert", Map.of("title", title, "body", body));
                apsMap.put("sound", "default");
                apsMap.put("badge", 1);
            } else {
                // Silent notification for data sync
                apsMap.put("content-available", 1);
                apsMap.put("sound", ""); // Empty sound for silent
            }

            final Map<String, Object> apnsPayload = new HashMap<>();
            apnsPayload.put("aps", apsMap);
            apnsPayload.put("data", data != null ? data : Map.of());

            platforms.put("APNS", objectMapper.writeValueAsString(apnsPayload));

            // GCM (Android) format
            final Map<String, Object> gcmPayload = new HashMap<>();
            if (!isSilent) {
                gcmPayload.put("notification", Map.of("title", title, "body", body));
            }
            gcmPayload.put("data", data != null ? data : Map.of());
            if (isSilent) {
                gcmPayload.put("priority", "high");
            }

            platforms.put("GCM", objectMapper.writeValueAsString(gcmPayload));

            // If platform is specified, return only that platform's message
            if (platform != null) {
                if ("ios".equalsIgnoreCase(platform)) {
                    return platforms.get("APNS");
                } else if ("android".equalsIgnoreCase(platform)) {
                    return platforms.get("GCM");
                }
            }

            // Return multi-platform message
            return objectMapper.writeValueAsString(platforms);
        } catch (Exception e) {
            LOGGER.error("Failed to create platform message: {}", e.getMessage());
            return body;
        }
    }
}
