package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.zerotrust.device.DeviceAttestationService;
import com.budgetbuddy.service.UserService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for DeviceAttestationController */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DeviceAttestationControllerTest {

    private static final String DEVICE_123 = "device-123";

    @Mock private DeviceAttestationService deviceAttestationService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private DeviceAttestationController controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testVerifyDeviceWithValidRequestReturnsSuccess() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(DEVICE_123);
        request.setAttestationToken("token-123");
        request.setPlatform("ios");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice(DEVICE_123, "user-123", "token-123", "ios"))
                .thenReturn(true);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.verifyDevice(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertTrue((Boolean) response.getBody().get("trusted"));
        assertEquals(DEVICE_123, response.getBody().get("deviceId"));
    }

    @Test
    void testVerifyDeviceWithFailedAttestationThrowsException() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(DEVICE_123);
        request.setAttestationToken("invalid-token");
        request.setPlatform("ios");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice(DEVICE_123, "user-123", "invalid-token", "ios"))
                .thenReturn(false);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class, () -> controller.verifyDevice(userDetails, request));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testVerifyDeviceWithNullUserDetailsThrowsException() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(DEVICE_123);

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(null, request));
    }

    @Test
    void testVerifyDeviceWithNullRequestThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, null));
    }

    @Test
    void testVerifyDeviceWithEmptyDeviceIdThrowsException() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId("");

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, request));
    }

    @Test
    void testVerifyDeviceWithNullDeviceIdThrowsException() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(null);

        // When/Then
        assertThrows(AppException.class, () -> controller.verifyDevice(userDetails, request));
    }

    @Test
    void testVerifyDeviceWithAndroidPlatformReturnsSuccess() {
        // Given
        final DeviceAttestationController.VerifyDeviceRequest request =
                new DeviceAttestationController.VerifyDeviceRequest();
        request.setDeviceId(DEVICE_123);
        request.setAttestationToken("token-123");
        request.setPlatform("android");

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(deviceAttestationService.verifyDevice(DEVICE_123, "user-123", "token-123", "android"))
                .thenReturn(true);

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.verifyDevice(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
    }
}
