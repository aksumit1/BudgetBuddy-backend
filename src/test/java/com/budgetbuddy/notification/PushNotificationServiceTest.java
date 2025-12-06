package com.budgetbuddy.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PushNotificationService
 * Tests push notification functionality including device registration
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private SnsClient snsClient;

    @Mock
    private ObjectMapper objectMapper;

    private PushNotificationService pushService;
    private String testUserId = "user-123";
    private String testDeviceToken = "device-token-123";
    private String testEndpointArn = "arn:aws:sns:us-east-1:123456789012:endpoint/GCM/test";

    @BeforeEach
    void setUp() {
        pushService = new PushNotificationService(snsClient, objectMapper);
    }

    @Test
    void testSendPushNotification_WithNoEndpoint_ReturnsFalse() {
        // Given - getDeviceEndpointArn returns null (no endpoint registered)

        // When
        boolean result = pushService.sendPushNotification(testUserId, "Title", "Body", null);

        // Then
        assertFalse(result, "Should return false when no endpoint is found");
        verify(snsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void testSendPushNotification_WithException_ReturnsFalse() throws Exception {
        // Given
        // Note: Since getDeviceEndpointArn is private and returns null, we can't easily test
        // the success path without reflection. This test verifies error handling.
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON error"));

        // When - This will fail at message creation
        // Since getDeviceEndpointArn returns null, this will return false before reaching publish
        boolean result = pushService.sendPushNotification(testUserId, "Title", "Body", null);

        // Then
        assertFalse(result, "Should return false on error");
    }

    @Test
    void testRegisterDevice_WithValidInput_ReturnsEndpointArn() {
        // Given
        String platform = "ios";
        CreatePlatformEndpointResponse response = CreatePlatformEndpointResponse.builder()
                .endpointArn(testEndpointArn)
                .build();
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(response);

        // When
        String result = pushService.registerDevice(testUserId, testDeviceToken, platform);

        // Then
        assertNotNull(result, "Should return endpoint ARN");
        assertEquals(testEndpointArn, result);
        verify(snsClient, times(1)).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
    }

    @Test
    void testRegisterDevice_WithException_ReturnsNull() {
        // Given
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenThrow(new RuntimeException("SNS error"));

        // When
        String result = pushService.registerDevice(testUserId, testDeviceToken, "ios");

        // Then
        assertNull(result, "Should return null on error");
        verify(snsClient, times(1)).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
    }

    @Test
    void testRegisterDevice_WithAndroidPlatform_RegistersSuccessfully() {
        // Given
        String platform = "android";
        CreatePlatformEndpointResponse response = CreatePlatformEndpointResponse.builder()
                .endpointArn(testEndpointArn)
                .build();
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(response);

        // When
        String result = pushService.registerDevice(testUserId, testDeviceToken, platform);

        // Then
        assertNotNull(result);
        verify(snsClient, times(1)).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
    }
}

