package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.DevicePinTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.DevicePinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for managing device PINs
 * 
 * @deprecated PIN backend endpoints have been removed. PIN is now local-only.
 * This service is kept for backward compatibility but should not be used for new code.
 * PIN should only be used locally to decrypt refresh token from Keychain.
 * 
 * BREAKING CHANGE: All PIN backend endpoints removed. PIN is local-only.
 */
@Deprecated
@Service
public class DevicePinService {

    private static final Logger logger = LoggerFactory.getLogger(DevicePinService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_SECONDS = 300; // 5 minutes

    private final DevicePinRepository devicePinRepository;

    @Autowired
    public DevicePinService(final DevicePinRepository devicePinRepository) {
        this.devicePinRepository = devicePinRepository;
    }

    /**
     * Hash PIN using SHA-256 (one-way hash)
     */
    private String hashPIN(final String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available", e);
            throw new RuntimeException("PIN hashing failed", e);
        }
    }

    /**
     * Validate PIN format (6 digits)
     */
    private void validatePINFormat(final String pin) {
        if (pin == null || pin.length() != 6) {
            throw new AppException(ErrorCode.INVALID_INPUT, "PIN must be exactly 6 digits");
        }
        if (!pin.matches("\\d{6}")) {
            throw new AppException(ErrorCode.INVALID_INPUT, "PIN must contain only digits");
        }
    }

    /**
     * Store or update device PIN
     */
    public void storePIN(final UserTable user, final String deviceId, final String pin) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }

        validatePINFormat(pin);

        // Hash the PIN
        String pinHash = hashPIN(pin);

        // Check if PIN already exists
        Optional<DevicePinTable> existing = devicePinRepository.findByUserIdAndDeviceId(user.getUserId(), deviceId);
        
        DevicePinTable devicePin;
        if (existing.isPresent()) {
            // Update existing PIN
            devicePin = existing.get();
            devicePin.setPinHash(pinHash);
            devicePin.setUpdatedAt(Instant.now());
            // Reset failed attempts and lock on PIN change
            devicePin.setFailedAttempts(0);
            devicePin.setLockedUntil(null);
        } else {
            // Create new PIN
            devicePin = new DevicePinTable();
            devicePin.setUserId(user.getUserId());
            devicePin.setDeviceId(deviceId);
            devicePin.setPinHash(pinHash);
            devicePin.setCreatedAt(Instant.now());
            devicePin.setUpdatedAt(Instant.now());
            devicePin.setFailedAttempts(0);
            devicePin.setLockedUntil(null);
        }

        devicePinRepository.save(devicePin);
        logger.info("Stored PIN for userId: {}, deviceId: {}", user.getUserId(), deviceId);
    }

    /**
     * Verify PIN and return success status
     * Also handles rate limiting and lockout
     */
    public boolean verifyPIN(final UserTable user, final String deviceId, final String pin) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }

        validatePINFormat(pin);

        // Find device PIN
        Optional<DevicePinTable> devicePinOpt = devicePinRepository.findByUserIdAndDeviceId(user.getUserId(), deviceId);
        if (devicePinOpt.isEmpty()) {
            logger.warn("PIN not found for userId: {}, deviceId: {}", user.getUserId(), deviceId);
            return false;
        }

        DevicePinTable devicePin = devicePinOpt.get();

        // Check if PIN is locked
        if (devicePin.getLockedUntil() != null && devicePin.getLockedUntil().isAfter(Instant.now())) {
            logger.warn("PIN is locked for userId: {}, deviceId: {} until {}", 
                    user.getUserId(), deviceId, devicePin.getLockedUntil());
            throw new AppException(ErrorCode.TOO_MANY_ATTEMPTS, 
                    "PIN is locked. Please try again later or use email/password login.");
        }

        // Hash provided PIN
        String pinHash = hashPIN(pin);

        // Verify PIN
        boolean isValid = pinHash.equals(devicePin.getPinHash());

        if (isValid) {
            // Reset failed attempts and update last verified time
            devicePin.setFailedAttempts(0);
            devicePin.setLockedUntil(null);
            devicePin.setLastVerifiedAt(Instant.now());
            devicePin.setUpdatedAt(Instant.now());
            devicePinRepository.save(devicePin);
            logger.info("PIN verified successfully for userId: {}, deviceId: {}", user.getUserId(), deviceId);
            return true;
        } else {
            // Increment failed attempts
            int failedAttempts = (devicePin.getFailedAttempts() != null ? devicePin.getFailedAttempts() : 0) + 1;
            devicePin.setFailedAttempts(failedAttempts);
            devicePin.setUpdatedAt(Instant.now());

            // Lock PIN if too many failures
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                devicePin.setLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_SECONDS));
                logger.warn("PIN locked after {} failed attempts for userId: {}, deviceId: {}", 
                        failedAttempts, user.getUserId(), deviceId);
            }

            devicePinRepository.save(devicePin);
            logger.warn("PIN verification failed for userId: {}, deviceId: {} (attempts: {})", 
                    user.getUserId(), deviceId, failedAttempts);
            return false;
        }
    }

    /**
     * Delete device PIN
     */
    public void deletePIN(final UserTable user, final String deviceId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }

        devicePinRepository.delete(user.getUserId(), deviceId);
        logger.info("Deleted PIN for userId: {}, deviceId: {}", user.getUserId(), deviceId);
    }
}

