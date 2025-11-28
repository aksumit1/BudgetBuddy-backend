package com.budgetbuddy.api;

import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.DevicePinService;
import com.budgetbuddy.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Device PIN Management
 * Handles PIN storage, verification, and token refresh
 */
@RestController
@RequestMapping("/api/pin")
public class PINController {

    private static final Logger logger = LoggerFactory.getLogger(PINController.class);

    private final DevicePinService devicePinService;
    private final UserService userService;
    private final AuthService authService;

    public PINController(
            final DevicePinService devicePinService,
            final UserService userService,
            final AuthService authService) {
        this.devicePinService = devicePinService;
        this.userService = userService;
        this.authService = authService;
    }

    /**
     * Store or update device PIN
     * POST /api/pin
     */
    @PostMapping
    public ResponseEntity<Void> storePIN(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody StorePINRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null || request.getDeviceId() == null || request.getDeviceId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }
        if (request.getPin() == null || request.getPin().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "PIN is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        devicePinService.storePIN(user, request.getDeviceId(), request.getPin());

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Verify PIN and refresh token
     * POST /api/pin/verify
     * Returns new JWT token on successful verification
     */
    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verifyPIN(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody VerifyPINRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null || request.getDeviceId() == null || request.getDeviceId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }
        if (request.getPin() == null || request.getPin().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "PIN is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Verify PIN
        boolean isValid = devicePinService.verifyPIN(user, request.getDeviceId(), request.getPin());

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid PIN");
        }

        // Generate new JWT token (refresh certificate)
        AuthResponse response = authService.generateTokensForUser(user);

        logger.info("PIN verified and token refreshed for userId: {}, deviceId: {}", 
                user.getUserId(), request.getDeviceId());

        return ResponseEntity.ok(response);
    }

    /**
     * Delete device PIN
     * DELETE /api/pin/{deviceId}
     */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> deletePIN(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String deviceId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        devicePinService.deletePIN(user, deviceId);

        return ResponseEntity.noContent().build();
    }

    // DTOs
    public static class StorePINRequest {
        private String deviceId;
        private String pin;

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(final String deviceId) { this.deviceId = deviceId; }
        public String getPin() { return pin; }
        public void setPin(final String pin) { this.pin = pin; }
    }

    public static class VerifyPINRequest {
        private String deviceId;
        private String pin;

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(final String deviceId) { this.deviceId = deviceId; }
        public String getPin() { return pin; }
        public void setPin(final String pin) { this.pin = pin; }
    }
}

