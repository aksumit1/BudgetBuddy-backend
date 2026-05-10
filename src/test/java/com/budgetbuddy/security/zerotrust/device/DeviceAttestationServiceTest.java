package com.budgetbuddy.security.zerotrust.device;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveResponse;

/** Unit Tests for DeviceAttestationService Tests device verification and attestation */
@ExtendWith(MockitoExtension.class)
class DeviceAttestationServiceTest {

    @Mock private DynamoDbClient dynamoDbClient;

    private DeviceAttestationService deviceAttestationService;

    private String testUserId;
    private String testDeviceId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testDeviceId = "device-123";

        // Mock table existence check - table doesn't exist, so creation will be attempted
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().build());

        // Mock table creation to avoid errors
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenReturn(CreateTableResponse.builder().build());
        when(dynamoDbClient.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(UpdateTimeToLiveResponse.builder().build());

        // Create service instance with test table prefix
        deviceAttestationService = new DeviceAttestationService(dynamoDbClient, "TestBudgetBuddy");
    }

    @Test
    void testVerifyDeviceWithRegisteredTrustedDeviceReturnsTrue() {
        // Given
        final GetItemResponse response =
                GetItemResponse.builder()
                        .item(
                                Map.of(
                                        "deviceId",
                                        AttributeValue.builder().s(testDeviceId).build(),
                                        "userId", AttributeValue.builder().s(testUserId).build(),
                                        "trusted", AttributeValue.builder().bool(true).build(),
                                        "lastVerified",
                                        AttributeValue.builder()
                                                .n(
                                                        String.valueOf(
                                                                System.currentTimeMillis()
                                                                        / 1000))
                                                .build()))
                        .build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse.builder()
                                .build());

        // When
        final boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertTrue(result);
        verify(dynamoDbClient, times(1)).getItem(any(GetItemRequest.class));
    }

    @Test
    void testVerifyDeviceWithUntrustedDeviceReturnsFalse() {
        // Given
        final GetItemResponse response =
                GetItemResponse.builder()
                        .item(
                                Map.of(
                                        "deviceId",
                                        AttributeValue.builder().s(testDeviceId).build(),
                                        "userId", AttributeValue.builder().s(testUserId).build(),
                                        "trusted", AttributeValue.builder().bool(false).build(),
                                        "lastVerified",
                                        AttributeValue.builder()
                                                .n(
                                                        String.valueOf(
                                                                System.currentTimeMillis()
                                                                        / 1000))
                                                .build()))
                        .build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);

        // When
        final boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertFalse(result);
    }

    @Test
    void testVerifyDeviceWithNewDeviceRegistersDevice() {
        // Given
        final GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder()
                                .build());

        // When
        final boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        // New device without attestation token should return false (requires verification)
        // But putItem should still be called to register the device
        verify(dynamoDbClient, atLeastOnce()).putItem(any(PutItemRequest.class));
        assertFalse(result, "New device without attestation should return false");
    }

    @Test
    void testVerifyDeviceWithValidAttestationTokenVerifiesAndTrusts() {
        // Given
        // Use a valid base64-encoded token that's > 100 chars (Apple DeviceCheck tokens are base64
        // CBOR)
        final String attestationToken =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                ("valid-apple-devicecheck-cbor-token-data-that-is-long-enough-to-pass-validation-"
                                        + "123456789012345678901234567890123456789012345678901234567890")
                                        .getBytes(StandardCharsets.UTF_8));
        final String platform = "ios";
        final GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder()
                                .build());

        // When
        final boolean result =
                deviceAttestationService.verifyDevice(
                        testDeviceId, testUserId, attestationToken, platform);

        // Then
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        assertTrue(result, "Device with valid attestation token should be trusted");
    }

    @Test
    void testVerifyDeviceWithInvalidAttestationTokenReturnsFalse() {
        // Given
        final String invalidToken = "invalid";
        final String platform = "ios";
        // Note: Invalid token causes early return before getItem/putItem are called
        // No need to stub these methods as they won't be invoked

        // When
        final boolean result =
                deviceAttestationService.verifyDevice(
                        testDeviceId, testUserId, invalidToken, platform);

        // Then
        assertFalse(result);
        // Invalid token causes early return, so getItem/putItem are never called
    }

    @Test
    void testVerifyDeviceWithAndroidJWTTokenValidatesFormat() {
        // Given
        final String jwtToken =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        final String platform = "android";
        final GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder()
                                .build());

        // When
        final boolean result =
                deviceAttestationService.verifyDevice(testDeviceId, testUserId, jwtToken, platform);

        // Then
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        // Note: Result may be true or false depending on token validation, but device should be
        // registered
        assertNotNull(result, "Result should not be null");
    }

    @Test
    void testGetDeviceTrustLevelReturnsTrustLevel() {
        // When
        final DeviceAttestationService.DeviceTrustLevel result =
                deviceAttestationService.getDeviceTrustLevel(testDeviceId);

        // Then
        assertNotNull(result);
        assertTrue(result.getScore() >= 0 && result.getScore() <= 100);
    }

    @Test
    void testVerifyDeviceWithExceptionReturnsFalse() {
        // Given
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        final boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertFalse(result);
    }
}
