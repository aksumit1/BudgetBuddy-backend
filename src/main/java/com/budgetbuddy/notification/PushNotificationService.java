package com.budgetbuddy.notification;

import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Push Notification Service using AWS SNS
 * Enhanced with device token management and multi-device support
 */
@Service
public class PushNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);

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
            @Value("${app.notifications.sns.ios-platform-arn:}") final String iosPlatformApplicationArn,
            @Value("${app.notifications.sns.android-platform-arn:}") final String androidPlatformApplicationArn,
            final PushNotificationMetrics metrics) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.deviceTokenRepository = deviceTokenRepository;
        this.iosPlatformApplicationArn = iosPlatformApplicationArn;
        this.androidPlatformApplicationArn = androidPlatformApplicationArn;
        this.metrics = metrics;
    }

    /**
     * Send push notification to a specific device
     */
    public boolean sendPushNotification(final String userId, final String title, final String body, final Map<String, Object> data) {
        try {
            // Get device endpoint ARN for user
            String endpointArn = getDeviceEndpointArn(userId);

            if (endpointArn == null || endpointArn.isEmpty()) {
                logger.warn("No device endpoint found for user: {}", userId);
                return false;
            }

            // Create platform-specific message
            String message = createPlatformMessage(title, body, data);

            PublishRequest request = PublishRequest.builder()
                    .targetArn(endpointArn)
                    .message(message)
                    .messageStructure("json")
                    .build();

            PublishResponse response = snsClient.publish(request);

            logger.info("Push notification sent - MessageId: {}, User: {}", response.messageId(), userId);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send push notification: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send push notification to all user's devices
     * Returns count of successful sends
     */
    public int sendPushNotificationToAllDevices(final String userId, final String title, final String body, final Map<String, Object> data) {
        try {
            List<DeviceTokenTable> devices = deviceTokenRepository.findEnabledByUserId(userId);
            
            if (devices.isEmpty()) {
                logger.debug("No enabled devices found for user: {}", userId);
                return 0;
            }

            long startTime = System.currentTimeMillis();
            int successCount = 0;
            int failureCount = 0;
            
            for (DeviceTokenTable device : devices) {
                try {
                    if (device.getEndpointArn() == null || device.getEndpointArn().isEmpty()) {
                        logger.warn("Device {} has no endpoint ARN, skipping", device.getDeviceToken());
                        continue;
                    }

                    String message = createPlatformMessage(title, body, data, device.getPlatform());

                    PublishRequest request = PublishRequest.builder()
                            .targetArn(device.getEndpointArn())
                            .message(message)
                            .messageStructure("json")
                            .build();

                    PublishResponse response = snsClient.publish(request);
                    
                    // Update last used timestamp
                    deviceTokenRepository.updateLastUsed(userId, device.getDeviceToken());
                    
                    successCount++;
                    long deliveryTime = System.currentTimeMillis() - startTime;
                    metrics.recordNotificationDelivered(1, deliveryTime);
                    
                    logger.debug("Push notification sent to device {} - MessageId: {}", 
                            device.getDeviceToken().substring(0, Math.min(8, device.getDeviceToken().length())), 
                            response.messageId());
                } catch (InvalidParameterException e) {
                    // Invalid endpoint - likely device uninstalled app or token expired
                    logger.warn("Invalid endpoint ARN for device {}, disabling: {}", 
                            device.getDeviceToken().substring(0, Math.min(8, device.getDeviceToken().length())), 
                            e.getMessage());
                    deviceTokenRepository.disable(userId, device.getDeviceToken());
                    metrics.recordInvalidEndpoint();
                    metrics.recordDeviceDisabled();
                    failureCount++;
                } catch (Exception e) {
                    logger.error("Failed to send push notification to device {}: {}", 
                            device.getDeviceToken().substring(0, Math.min(8, device.getDeviceToken().length())), 
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

            logger.info("Sent push notifications to {}/{} devices for user: {}", 
                    successCount, devices.size(), userId);
            return successCount;
        } catch (Exception e) {
            logger.error("Failed to send push notifications to all devices for user {}: {}", 
                    userId, e.getMessage());
            return 0;
        }
    }

    /**
     * Register device for push notifications
     * Creates or updates device token in database and registers with AWS SNS
     */
    public String registerDevice(final String userId, final String deviceToken, final String platform) {
        try {
            String platformArn = getPlatformApplicationArn(platform);
            if (platformArn == null || platformArn.isEmpty()) {
                logger.error("Platform application ARN not configured for platform: {}", platform);
                return null;
            }

            // Check if device token already exists
            Optional<DeviceTokenTable> existing = deviceTokenRepository.findByUserIdAndDeviceToken(userId, deviceToken);
            
            String endpointArn;
            if (existing.isPresent() && existing.get().getEndpointArn() != null && !existing.get().getEndpointArn().isEmpty()) {
                // Device already registered, try to update endpoint
                endpointArn = existing.get().getEndpointArn();
                try {
                    // Verify endpoint is still valid
                    snsClient.getEndpointAttributes(GetEndpointAttributesRequest.builder()
                            .endpointArn(endpointArn)
                            .build());
                    logger.debug("Device endpoint already exists and is valid: {}", endpointArn);
                } catch (Exception e) {
                    // Endpoint invalid, create new one
                    logger.info("Existing endpoint invalid, creating new endpoint");
                    endpointArn = createNewEndpoint(userId, deviceToken, platform, platformArn);
                }
            } else {
                // New device, create endpoint
                endpointArn = createNewEndpoint(userId, deviceToken, platform, platformArn);
            }

            // Save or update device token in database
            DeviceTokenTable deviceTokenTable = existing.orElse(new DeviceTokenTable());
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

            logger.info("Device registered - EndpointArn: {}, User: {}, Platform: {}",
                    endpointArn, userId, platform);
            return endpointArn;
        } catch (Exception e) {
            logger.error("Failed to register device: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create new SNS endpoint for device
     */
    private String createNewEndpoint(final String userId, final String deviceToken, 
                                    final String platform, final String platformArn) {
        try {
            CreatePlatformEndpointRequest request = CreatePlatformEndpointRequest.builder()
                    .platformApplicationArn(platformArn)
                    .token(deviceToken)
                    .attributes(Map.of("UserId", userId))
                    .build();

            CreatePlatformEndpointResponse response = snsClient.createPlatformEndpoint(request);
            return response.endpointArn();
        } catch (Exception e) {
            logger.error("Failed to create SNS endpoint: {}", e.getMessage());
            throw new RuntimeException("Failed to create SNS endpoint", e);
        }
    }

    /**
     * Get device endpoint ARN for user (first enabled device)
     * @deprecated Use sendPushNotificationToAllDevices instead
     */
    @Deprecated
    private String getDeviceEndpointArn(final String userId) {
        List<DeviceTokenTable> devices = deviceTokenRepository.findEnabledByUserId(userId);
        if (devices.isEmpty()) {
            return null;
        }
        return devices.get(0).getEndpointArn();
    }

    /**
     * Get platform application ARN from configuration
     */
    private String getPlatformApplicationArn(final String platform) {
        if ("ios".equalsIgnoreCase(platform)) {
            return iosPlatformApplicationArn;
        } else if ("android".equalsIgnoreCase(platform)) {
            return androidPlatformApplicationArn;
        }
        logger.warn("Unknown platform: {}", platform);
        return null;
    }

    /**
     * Create platform-specific message for all platforms
     */
    private String createPlatformMessage(final String title, final String body, final Map<String, Object> data) {
        return createPlatformMessage(title, body, data, null);
    }

    /**
     * Create platform-specific message
     * Supports silent notifications (content-available: 1) for data sync
     */
    private String createPlatformMessage(final String title, final String body, 
                                        final Map<String, Object> data, final String platform) {
        try {
            boolean isSilent = data != null && Boolean.TRUE.equals(data.get("silent"));
            
            Map<String, String> platforms = new HashMap<>();
            platforms.put("default", body);

            // APNS (iOS) format
            Map<String, Object> apsMap = new HashMap<>();
            if (!isSilent) {
                apsMap.put("alert", Map.of("title", title, "body", body));
                apsMap.put("sound", "default");
                apsMap.put("badge", 1);
            } else {
                // Silent notification for data sync
                apsMap.put("content-available", 1);
                apsMap.put("sound", ""); // Empty sound for silent
            }
            
            Map<String, Object> apnsPayload = new HashMap<>();
            apnsPayload.put("aps", apsMap);
            apnsPayload.put("data", data != null ? data : Map.of());
            
            platforms.put("APNS", objectMapper.writeValueAsString(apnsPayload));

            // GCM (Android) format
            Map<String, Object> gcmPayload = new HashMap<>();
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
            logger.error("Failed to create platform message: {}", e.getMessage());
            return body;
        }
    }
}

