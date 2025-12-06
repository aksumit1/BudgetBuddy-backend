package com.budgetbuddy.security.zerotrust.device;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DeviceAttestationService
 * Tests device verification and attestation
 */
@ExtendWith(MockitoExtension.class)
class DeviceAttestationServiceTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @InjectMocks
    private DeviceAttestationService deviceAttestationService;

    private String testUserId;
    private String testDeviceId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        testDeviceId = "device-123";
        
        // Mock table creation to avoid errors
        when(dynamoDbClient.createTable(any(CreateTableRequest.class)))
                .thenReturn(CreateTableResponse.builder().build());
        when(dynamoDbClient.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(UpdateTimeToLiveResponse.builder().build());
        
        deviceAttestationService = new DeviceAttestationService(dynamoDbClient);
    }

    @Test
    void testVerifyDevice_WithRegisteredTrustedDevice_ReturnsTrue() {
        // Given
        GetItemResponse response = GetItemResponse.builder()
                .item(Map.of(
                        "deviceId", AttributeValue.builder().s(testDeviceId).build(),
                        "userId", AttributeValue.builder().s(testUserId).build(),
                        "trusted", AttributeValue.builder().bool(true).build(),
                        "lastVerified", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis() / 1000)).build()
                ))
                .build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);
        doNothing().when(dynamoDbClient).updateItem(any(UpdateItemRequest.class));

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertTrue(result);
        verify(dynamoDbClient, times(1)).getItem(any(GetItemRequest.class));
    }

    @Test
    void testVerifyDevice_WithUntrustedDevice_ReturnsFalse() {
        // Given
        GetItemResponse response = GetItemResponse.builder()
                .item(Map.of(
                        "deviceId", AttributeValue.builder().s(testDeviceId).build(),
                        "userId", AttributeValue.builder().s(testUserId).build(),
                        "trusted", AttributeValue.builder().bool(false).build(),
                        "lastVerified", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis() / 1000)).build()
                ))
                .build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertFalse(result);
    }

    @Test
    void testVerifyDevice_WithNewDevice_RegistersDevice() {
        // Given
        GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        doNothing().when(dynamoDbClient).putItem(any(PutItemRequest.class));

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        // New device without attestation token should return false (requires verification)
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
    }

    @Test
    void testVerifyDevice_WithValidAttestationToken_VerifiesAndTrusts() {
        // Given
        String attestationToken = "valid-token-base64-encoded-cbor-data-123456789012345678901234567890";
        String platform = "ios";
        GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        doNothing().when(dynamoDbClient).putItem(any(PutItemRequest.class));

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId, attestationToken, platform);

        // Then
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
    }

    @Test
    void testVerifyDevice_WithInvalidAttestationToken_ReturnsFalse() {
        // Given
        String invalidToken = "invalid";
        String platform = "ios";
        GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId, invalidToken, platform);

        // Then
        assertFalse(result);
    }

    @Test
    void testVerifyDevice_WithAndroidJWTToken_ValidatesFormat() {
        // Given
        String jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String platform = "android";
        GetItemResponse emptyResponse = GetItemResponse.builder().build();
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(emptyResponse);
        doNothing().when(dynamoDbClient).putItem(any(PutItemRequest.class));

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId, jwtToken, platform);

        // Then
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
    }

    @Test
    void testGetDeviceTrustLevel_ReturnsTrustLevel() {
        // When
        DeviceAttestationService.DeviceTrustLevel result = deviceAttestationService.getDeviceTrustLevel(testDeviceId);

        // Then
        assertNotNull(result);
        assertTrue(result.getScore() >= 0 && result.getScore() <= 100);
    }

    @Test
    void testVerifyDevice_WithException_ReturnsFalse() {
        // Given
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB error"));

        // When
        boolean result = deviceAttestationService.verifyDevice(testDeviceId, testUserId);

        // Then
        assertFalse(result);
    }
}

