package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.zerotrust.device.DeviceAttestationService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DeviceAttestationController
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DeviceAttestationControllerTest {

    @Mock
    private DeviceAttestationService deviceAttestationService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private DeviceAttestationController controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testVerifyDevice_WithValidRequest_ReturnsSuccess() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("device-123");
        request.setAttestationToken("token-123");
        request.setPlatform("ios");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice("device-123", "user-123", "token-123", "ios"))
                .thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = controller.verifyDevice(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertTrue((Boolean) response.getBody().get("trusted"));
        assertEquals("device-123", response.getBody().get("deviceId"));
    }

    @Test
    void testVerifyDevice_WithFailedAttestation_ThrowsException() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("device-123");
        request.setAttestationToken("invalid-token");
        request.setPlatform("ios");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice("device-123", "user-123", "invalid-token", "ios"))
                .thenReturn(false);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controller.verifyDevice(userDetails, request));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testVerifyDevice_WithNullUserDetails_ThrowsException() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("device-123");

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(null, request));
    }

    @Test
    void testVerifyDevice_WithNullRequest_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, null));
    }

    @Test
    void testVerifyDevice_WithEmptyDeviceId_ThrowsException() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("");

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, request));
    }

    @Test
    void testVerifyDevice_WithNullDeviceId_ThrowsException() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(null);

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, request));
    }

    @Test
    void testVerifyDevice_WithAndroidPlatform_ReturnsSuccess() {
        // Given
        DeviceAttestationController.VerifyDeviceRequest request = new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("device-123");
        request.setAttestationToken("token-123");
        request.setPlatform("android");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice("device-123", "user-123", "token-123", "android"))
                .thenReturn(true);

        // When
        ResponseEntity<Map<String, Object>> response = controller.verifyDevice(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }
}

