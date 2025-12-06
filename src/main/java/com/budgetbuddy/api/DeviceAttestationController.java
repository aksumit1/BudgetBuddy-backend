package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.zerotrust.device.DeviceAttestationService;
import com.budgetbuddy.service.UserService;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.HashMap;
import java.util.Map;

/**
 * Device Attestation REST Controller
 * Handles device attestation verification for Zero Trust security
 */
@RestController
@RequestMapping("/api/device/attestation")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DeviceAttestationController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceAttestationController.class);

    private final DeviceAttestationService deviceAttestationService;
    private final UserService userService;

    public DeviceAttestationController(
            final DeviceAttestationService deviceAttestationService,
            final UserService userService) {
        this.deviceAttestationService = deviceAttestationService;
        this.userService = userService;
    }

    /**
     * Verify device attestation
     * POST /api/device/attestation/verify
     */
    @PostMapping("/verify")
    @Operation(summary = "Verify Device Attestation", description = "Verifies device integrity and trustworthiness")
    @ApiResponse(responseCode = "200", description = "Device attestation verified successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "400", description = "Device attestation failed")
    public ResponseEntity<Map<String, Object>> verifyDevice(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody VerifyDeviceRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null || request.getDeviceId() == null || request.getDeviceId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        boolean isTrusted = deviceAttestationService.verifyDevice(
                request.getDeviceId(),
                user.getUserId(),
                request.getAttestationToken(),
                request.getPlatform()
        );

        if (!isTrusted) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Device attestation failed");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Device attestation verified successfully");
        response.put("deviceId", request.getDeviceId());
        response.put("trusted", true);

        logger.info("Device attestation verified for device: {} user: {}", request.getDeviceId(), user.getUserId());
        return ResponseEntity.ok(response);
    }

    // MARK: - DTOs

    public static class VerifyDeviceRequest {
        private String deviceId;
        private String attestationToken;
        private String platform; // "ios" or "android"

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(final String deviceId) {
            this.deviceId = deviceId;
        }

        public String getAttestationToken() {
            return attestationToken;
        }

        public void setAttestationToken(final String attestationToken) {
            this.attestationToken = attestationToken;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(final String platform) {
            this.platform = platform;
        }
    }
}

