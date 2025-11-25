package com.budgetbuddy.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.util.Map;

/**
 * Push Notification Service using AWS SNS
 */
@Service
public class PushNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);

    private final SnsClient snsClient;

    public PushNotificationService(final SnsClient snsClient) {
        this.snsClient = snsClient;
    }

    /**
     * Send push notification
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
     * Register device for push notifications
     */
    public String registerDevice(final String userId, final String deviceToken, final String platform) {
        try {
            CreatePlatformEndpointRequest request = CreatePlatformEndpointRequest.builder()
                    .platformApplicationArn(getPlatformApplicationArn(platform))
                    .token(deviceToken)
                    .attributes(Map.of("UserId", userId))
                    .build();

            CreatePlatformEndpointResponse response = snsClient.createPlatformEndpoint(request);

            logger.info("Device registered - EndpointArn: {}, User: {}, Platform: {}",
                    response.endpointArn(), userId, platform);
            return response.endpointArn();
        } catch (Exception e) {
            logger.error("Failed to register device: {}", e.getMessage());
            return null;
        }
    }

    private String getDeviceEndpointArn(final String userId) {
        // In production, this would query DynamoDB for user's device endpoint ARN
        // Simplified for now
        return null;
    }

    private String getPlatformApplicationArn(final String platform) {
        // In production, this would return the appropriate platform application ARN
        // from configuration
        return null;
    }

    private String createPlatformMessage(final String title, final String body, final Map<String, Object> data) {
        // Create platform-specific JSON message
        // Format: {"default": "...", "APNS": "...", "GCM": "..."}
        try {
            Map<String, String> platforms = new java.util.HashMap<>();
            platforms.put("default", body);

            // APNS (iOS) format
            Map<String, Object> apnsPayload = Map.of(
                    "aps", Map.of(
                            "alert", Map.of("title", title, "body", body),
                            "sound", "default",
                            "badge", 1
                    ),
                    "data", data != null ? data : Map.of()
            );
            platforms.put("APNS", com.fasterxml.jackson.databind.ObjectMapper.class.newInstance()
                    .writeValueAsString(apnsPayload));

            // GCM (Android) format
            Map<String, Object> gcmPayload = Map.of(
                    "notification", Map.of("title", title, "body", body),
                    "data", data != null ? data : Map.of()
            );
            platforms.put("GCM", com.fasterxml.jackson.databind.ObjectMapper.class.newInstance()
                    .writeValueAsString(gcmPayload));

            return com.fasterxml.jackson.databind.ObjectMapper.class.newInstance()
                    .writeValueAsString(platforms);
        } catch (Exception e) {
            logger.error("Failed to create platform message: {}", e.getMessage());
            return body;
        }
    }
}

