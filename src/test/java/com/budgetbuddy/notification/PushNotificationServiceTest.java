package com.budgetbuddy.notification;

import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PushNotificationService
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private SnsClient snsClient;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private PushNotificationMetrics metrics;

    private PushNotificationService pushNotificationService;

    private String testUserId;
    private String testDeviceToken;
    private String testEndpointArn;
    private String iosPlatformArn;
    private String androidPlatformArn;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-id";
        testDeviceToken = "test-device-token";
        testEndpointArn = "arn:aws:sns:us-east-1:123456789012:endpoint/APNS/test-app/test-endpoint";
        iosPlatformArn = "arn:aws:sns:us-east-1:123456789012:app/APNS/test-app";
        androidPlatformArn = "arn:aws:sns:us-east-1:123456789012:app/GCM/test-app";
        
        // Manually construct service with mocked dependencies
        pushNotificationService = new PushNotificationService(
                snsClient, objectMapper, deviceTokenRepository, iosPlatformArn, androidPlatformArn, metrics);
    }

    @Test
    void testSendPushNotificationToAllDevices_Success() throws Exception {
        // Given
        DeviceTokenTable device1 = createDeviceToken("device1", "ios", testEndpointArn);
        DeviceTokenTable device2 = createDeviceToken("device2", "android", testEndpointArn);
        List<DeviceTokenTable> devices = List.of(device1, device2);

        when(deviceTokenRepository.findEnabledByUserId(testUserId)).thenReturn(devices);
        when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("msg-123").build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"aps\":{}}");
        doNothing().when(metrics).recordNotificationSent(anyInt());
        doNothing().when(metrics).recordNotificationDelivered(anyInt(), anyLong());
        doNothing().when(metrics).updateActiveDevices(anyInt());

        // When
        int result = pushNotificationService.sendPushNotificationToAllDevices(
                testUserId, "Test Title", "Test Body", Map.of("key", "value"));

        // Then
        assertEquals(2, result);
        verify(snsClient, times(2)).publish(any(PublishRequest.class));
        verify(deviceTokenRepository, times(2)).updateLastUsed(eq(testUserId), anyString());
        verify(metrics).recordNotificationSent(2);
    }

    @Test
    void testSendPushNotificationToAllDevices_NoDevices() {
        // Given
        when(deviceTokenRepository.findEnabledByUserId(testUserId)).thenReturn(List.of());

        // When
        int result = pushNotificationService.sendPushNotificationToAllDevices(
                testUserId, "Test Title", "Test Body", Map.of());

        // Then
        assertEquals(0, result);
        verify(snsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void testSendPushNotificationToAllDevices_InvalidEndpoint() throws Exception {
        // Given
        DeviceTokenTable device = createDeviceToken("device1", "ios", testEndpointArn);
        when(deviceTokenRepository.findEnabledByUserId(testUserId)).thenReturn(List.of(device));
        when(snsClient.publish(any(PublishRequest.class)))
                .thenThrow(InvalidParameterException.builder().message("Invalid endpoint").build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"aps\":{}}");
        doNothing().when(metrics).recordNotificationSent(anyInt());
        doNothing().when(metrics).recordNotificationFailed(anyInt());
        doNothing().when(metrics).recordInvalidEndpoint();
        doNothing().when(metrics).recordDeviceDisabled();
        doNothing().when(metrics).updateActiveDevices(anyInt());

        // When
        int result = pushNotificationService.sendPushNotificationToAllDevices(
                testUserId, "Test Title", "Test Body", Map.of());

        // Then
        assertEquals(0, result);
        verify(deviceTokenRepository).disable(testUserId, device.getDeviceToken());
        verify(metrics).recordInvalidEndpoint();
        verify(metrics).recordDeviceDisabled();
    }

    @Test
    void testRegisterDevice_NewDevice() throws Exception {
        // Given
        String newEndpointArn = "arn:aws:sns:us-east-1:123456789012:endpoint/APNS/test-app/new-endpoint";
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.empty());
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(CreatePlatformEndpointResponse.builder().endpointArn(newEndpointArn).build());

        // When
        String result = pushNotificationService.registerDevice(testUserId, testDeviceToken, "ios");

        // Then
        assertNotNull(result);
        assertEquals(newEndpointArn, result);
        verify(deviceTokenRepository).save(any(DeviceTokenTable.class));
    }

    @Test
    void testRegisterDevice_ExistingDevice() throws Exception {
        // Given
        DeviceTokenTable existingDevice = createDeviceToken(testDeviceToken, "ios", testEndpointArn);
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.of(existingDevice));
        when(snsClient.getEndpointAttributes(any(GetEndpointAttributesRequest.class)))
                .thenReturn(GetEndpointAttributesResponse.builder().build());

        // When
        String result = pushNotificationService.registerDevice(testUserId, testDeviceToken, "ios");

        // Then
        assertEquals(testEndpointArn, result);
        verify(snsClient, never()).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
        verify(deviceTokenRepository).save(existingDevice);
    }

    @Test
    void testRegisterDevice_InvalidEndpoint_CreatesNew() throws Exception {
        // Given
        String newEndpointArn = "arn:aws:sns:us-east-1:123456789012:endpoint/APNS/test-app/new-endpoint";
        DeviceTokenTable existingDevice = createDeviceToken(testDeviceToken, "ios", testEndpointArn);
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.of(existingDevice));
        when(snsClient.getEndpointAttributes(any(GetEndpointAttributesRequest.class)))
                .thenThrow(NotFoundException.builder().build());
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(CreatePlatformEndpointResponse.builder().endpointArn(newEndpointArn).build());

        // When
        String result = pushNotificationService.registerDevice(testUserId, testDeviceToken, "ios");

        // Then
        assertEquals(newEndpointArn, result);
        verify(snsClient).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
        verify(deviceTokenRepository).save(any(DeviceTokenTable.class));
    }

    private DeviceTokenTable createDeviceToken(String deviceToken, String platform, String endpointArn) {
        DeviceTokenTable device = new DeviceTokenTable();
        device.setUserId(testUserId);
        device.setDeviceToken(deviceToken);
        device.setPlatform(platform);
        device.setEndpointArn(endpointArn);
        device.setEnabled(true);
        device.setCreatedAt(Instant.now());
        device.setUpdatedAt(Instant.now());
        return device;
    }
}
