package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.DevicePinTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.DevicePinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DevicePinService
 * Tests PIN storage, verification, and rate limiting logic
 */
@ExtendWith(MockitoExtension.class)
class DevicePinServiceTest {

    @Mock
    private DevicePinRepository devicePinRepository;

    @InjectMocks
    private DevicePinService devicePinService;

    private UserTable testUser;
    private String testDeviceId;
    private String testPIN;
    private DevicePinTable existingDevicePin;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testDeviceId = "device-456";
        testPIN = "123456";

        existingDevicePin = new DevicePinTable();
        existingDevicePin.setUserId(testUser.getUserId());
        existingDevicePin.setDeviceId(testDeviceId);
        existingDevicePin.setPinHash(hashPIN(testPIN)); // Use helper method
        existingDevicePin.setCreatedAt(Instant.now());
        existingDevicePin.setUpdatedAt(Instant.now());
        existingDevicePin.setFailedAttempts(0);
        existingDevicePin.setLockedUntil(null);
    }

    @Test
    void testStorePIN_WithNewPIN_CreatesDevicePin() {
        // Given
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.empty());

        // When
        devicePinService.storePIN(testUser, testDeviceId, testPIN);

        // Then
        verify(devicePinRepository).findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId);
        verify(devicePinRepository).save(any(DevicePinTable.class));
    }

    @Test
    void testStorePIN_WithExistingPIN_UpdatesDevicePin() {
        // Given
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.of(existingDevicePin));

        // When
        devicePinService.storePIN(testUser, testDeviceId, "654321");

        // Then
        verify(devicePinRepository).findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId);
        verify(devicePinRepository).save(existingDevicePin);
        assertEquals(0, existingDevicePin.getFailedAttempts());
        assertNull(existingDevicePin.getLockedUntil());
    }

    @Test
    void testStorePIN_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.storePIN(null, testDeviceId, testPIN));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testStorePIN_WithNullDeviceId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.storePIN(testUser, null, testPIN));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testStorePIN_WithEmptyDeviceId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.storePIN(testUser, "", testPIN));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testStorePIN_WithInvalidPINLength_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.storePIN(testUser, testDeviceId, "12345")); // 5 digits
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testStorePIN_WithNonNumericPIN_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.storePIN(testUser, testDeviceId, "12345a"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testVerifyPIN_WithValidPIN_ReturnsTrue() {
        // Given
        String correctPIN = "123456";
        String pinHash = hashPIN(correctPIN);
        existingDevicePin.setPinHash(pinHash);
        
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.of(existingDevicePin));

        // When
        boolean result = devicePinService.verifyPIN(testUser, testDeviceId, correctPIN);

        // Then
        assertTrue(result);
        // verifyPIN() only calls findByUserIdAndDeviceId() once to retrieve the device PIN
        verify(devicePinRepository, times(1)).findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId);
        verify(devicePinRepository).save(existingDevicePin);
        assertEquals(0, existingDevicePin.getFailedAttempts());
        assertNull(existingDevicePin.getLockedUntil());
    }

    @Test
    void testVerifyPIN_WithInvalidPIN_ReturnsFalse() {
        // Given
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.of(existingDevicePin));

        // When
        boolean result = devicePinService.verifyPIN(testUser, testDeviceId, "999999");

        // Then
        assertFalse(result);
        verify(devicePinRepository).save(existingDevicePin);
        assertEquals(1, existingDevicePin.getFailedAttempts());
    }

    @Test
    void testVerifyPIN_WithNonExistentPIN_ReturnsFalse() {
        // Given
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.empty());

        // When
        boolean result = devicePinService.verifyPIN(testUser, testDeviceId, testPIN);

        // Then
        assertFalse(result);
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testVerifyPIN_WithLockedPIN_ThrowsException() {
        // Given
        existingDevicePin.setLockedUntil(Instant.now().plusSeconds(300));
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.of(existingDevicePin));

        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.verifyPIN(testUser, testDeviceId, testPIN));
        assertEquals(ErrorCode.TOO_MANY_ATTEMPTS, exception.getErrorCode());
        verify(devicePinRepository, never()).save(any());
    }

    @Test
    void testVerifyPIN_WithMaxFailedAttempts_LocksPIN() {
        // Given
        existingDevicePin.setFailedAttempts(4); // One less than max
        when(devicePinRepository.findByUserIdAndDeviceId(testUser.getUserId(), testDeviceId))
                .thenReturn(Optional.of(existingDevicePin));

        // When
        boolean result = devicePinService.verifyPIN(testUser, testDeviceId, "999999");

        // Then
        assertFalse(result);
        verify(devicePinRepository).save(existingDevicePin);
        assertEquals(5, existingDevicePin.getFailedAttempts());
        assertNotNull(existingDevicePin.getLockedUntil());
    }

    @Test
    void testDeletePIN_WithValidInput_DeletesSuccessfully() {
        // When
        devicePinService.deletePIN(testUser, testDeviceId);

        // Then
        verify(devicePinRepository).delete(testUser.getUserId(), testDeviceId);
    }

    @Test
    void testDeletePIN_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.deletePIN(null, testDeviceId));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).delete(anyString(), anyString());
    }

    @Test
    void testDeletePIN_WithNullDeviceId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, 
                () -> devicePinService.deletePIN(testUser, null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        verify(devicePinRepository, never()).delete(anyString(), anyString());
    }

    // Helper method to hash PIN (same logic as DevicePinService)
    private String hashPIN(final String pin) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("PIN hashing failed", e);
        }
    }
}

