package com.budgetbuddy.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse;
import software.amazon.awssdk.services.sns.model.GetEndpointAttributesRequest;
import software.amazon.awssdk.services.sns.model.GetEndpointAttributesResponse;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;
import software.amazon.awssdk.services.sns.model.NotFoundException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/** Unit tests for PushNotificationService */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    private static final String IOS = "ios";

    @Mock private SnsClient snsClient;

    @Mock private ObjectMapper objectMapper;

    @Mock private DeviceTokenRepository deviceTokenRepository;

    @Mock private PushNotificationMetrics metrics;

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
        pushNotificationService =
                new PushNotificationService(
                        snsClient,
                        objectMapper,
                        deviceTokenRepository,
                        iosPlatformArn,
                        androidPlatformArn,
                        metrics);
    }

    @Test
    void testSendPushNotificationToAllDevicesSuccess() throws Exception {
        // Given
        final DeviceTokenTable device1 = createDeviceToken("device1", IOS, testEndpointArn);
        final DeviceTokenTable device2 = createDeviceToken("device2", "android", testEndpointArn);
        final List<DeviceTokenTable> devices = List.of(device1, device2);

        when(deviceTokenRepository.findEnabledByUserId(testUserId)).thenReturn(devices);
        when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId("msg-123").build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"aps\":{}}");
        doNothing().when(metrics).recordNotificationSent(anyInt());
        doNothing().when(metrics).recordNotificationDelivered(anyInt(), anyLong());
        doNothing().when(metrics).updateActiveDevices(anyInt());

        // When
        final int result =
                pushNotificationService.sendPushNotificationToAllDevices(
                        testUserId, "Test Title", "Test Body", Map.of("key", "value"));

        // Then
        assertEquals(2, result);
        verify(snsClient, times(2)).publish(any(PublishRequest.class));
        verify(deviceTokenRepository, times(2)).updateLastUsed(eq(testUserId), anyString());
        verify(metrics).recordNotificationSent(2);
    }

    @Test
    void testSendPushNotificationToAllDevicesNoDevices() {
        // Given
        when(deviceTokenRepository.findEnabledByUserId(testUserId)).thenReturn(List.of());

        // When
        final int result =
                pushNotificationService.sendPushNotificationToAllDevices(
                        testUserId, "Test Title", "Test Body", Map.of());

        // Then
        assertEquals(0, result);
        verify(snsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    void testSendPushNotificationToAllDevicesInvalidEndpoint() throws Exception {
        // Given
        final DeviceTokenTable device = createDeviceToken("device1", IOS, testEndpointArn);
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
        final int result =
                pushNotificationService.sendPushNotificationToAllDevices(
                        testUserId, "Test Title", "Test Body", Map.of());

        // Then
        assertEquals(0, result);
        verify(deviceTokenRepository).disable(testUserId, device.getDeviceToken());
        verify(metrics).recordInvalidEndpoint();
        verify(metrics).recordDeviceDisabled();
    }

    @Test
    void testRegisterDeviceNewDevice() throws Exception {
        // Given
        final String newEndpointArn =
                "arn:aws:sns:us-east-1:123456789012:endpoint/APNS/test-app/new-endpoint";
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.empty());
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(
                        CreatePlatformEndpointResponse.builder()
                                .endpointArn(newEndpointArn)
                                .build());

        // When
        final String result =
                pushNotificationService.registerDevice(testUserId, testDeviceToken, IOS);

        // Then
        assertNotNull(result);
        assertEquals(newEndpointArn, result);
        verify(deviceTokenRepository).save(any(DeviceTokenTable.class));
    }

    @Test
    void testRegisterDeviceExistingDevice() throws Exception {
        // Given
        final DeviceTokenTable existingDevice =
                createDeviceToken(testDeviceToken, IOS, testEndpointArn);
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.of(existingDevice));
        when(snsClient.getEndpointAttributes(any(GetEndpointAttributesRequest.class)))
                .thenReturn(GetEndpointAttributesResponse.builder().build());

        // When
        final String result =
                pushNotificationService.registerDevice(testUserId, testDeviceToken, IOS);

        // Then
        assertEquals(testEndpointArn, result);
        verify(snsClient, never()).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
        verify(deviceTokenRepository).save(existingDevice);
    }

    @Test
    void testRegisterDeviceInvalidEndpointCreatesNew() throws Exception {
        // Given
        final String newEndpointArn =
                "arn:aws:sns:us-east-1:123456789012:endpoint/APNS/test-app/new-endpoint";
        final DeviceTokenTable existingDevice =
                createDeviceToken(testDeviceToken, IOS, testEndpointArn);
        when(deviceTokenRepository.findByUserIdAndDeviceToken(testUserId, testDeviceToken))
                .thenReturn(java.util.Optional.of(existingDevice));
        when(snsClient.getEndpointAttributes(any(GetEndpointAttributesRequest.class)))
                .thenThrow(NotFoundException.builder().build());
        when(snsClient.createPlatformEndpoint(any(CreatePlatformEndpointRequest.class)))
                .thenReturn(
                        CreatePlatformEndpointResponse.builder()
                                .endpointArn(newEndpointArn)
                                .build());

        // When
        final String result =
                pushNotificationService.registerDevice(testUserId, testDeviceToken, IOS);

        // Then
        assertEquals(newEndpointArn, result);
        verify(snsClient).createPlatformEndpoint(any(CreatePlatformEndpointRequest.class));
        verify(deviceTokenRepository).save(any(DeviceTokenTable.class));
    }

    private DeviceTokenTable createDeviceToken(
            final String deviceToken, final String platform, final String endpointArn) {
        final DeviceTokenTable device = new DeviceTokenTable();
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
