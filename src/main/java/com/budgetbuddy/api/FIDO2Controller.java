package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.FIDO2Service;
import com.budgetbuddy.service.UserService;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FIDO2/WebAuthn REST Controller
 * Implements passkey authentication using WebAuthn standard
 * Compliant with: FIDO2, WebAuthn, W3C standards
 */
@RestController
@RequestMapping("/api/fido2")
@CrossOrigin(origins = "*", maxAge = 3600)
public class FIDO2Controller {

    private static final Logger logger = LoggerFactory.getLogger(FIDO2Controller.class);

    private final FIDO2Service fido2Service;
    private final UserService userService;

    public FIDO2Controller(final FIDO2Service fido2Service, final UserService userService) {
        this.fido2Service = fido2Service;
        this.userService = userService;
    }

    /**
     * Generate registration challenge
     * POST /api/fido2/register/challenge
     */
    @PostMapping("/register/challenge")
    @Operation(summary = "Generate Registration Challenge", description = "Generates challenge for passkey registration")
    @ApiResponse(responseCode = "200", description = "Registration challenge generated successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> generateRegistrationChallenge(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        FIDO2Service.RegistrationChallengeResult result = fido2Service.generateRegistrationChallenge(
                user.getUserId(), user.getEmail());

        Map<String, Object> response = new HashMap<>();
        response.put("challenge", result.getOptions().getChallenge().getBase64Url());
        response.put("options", result.getOptions());
        response.put("message", "Registration challenge generated. Use this to create a passkey.");

        logger.info("Registration challenge generated for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify registration
     * POST /api/fido2/register/verify
     */
    @PostMapping("/register/verify")
    @Operation(summary = "Verify Registration", description = "Verifies passkey registration and stores credential")
    @ApiResponse(responseCode = "200", description = "Passkey registered successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "400", description = "Invalid registration data")
    public ResponseEntity<Map<String, Object>> verifyRegistration(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody RegisterPasskeyRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (request == null || request.getCredentialJson() == null || request.getCredentialJson().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        boolean isValid = fido2Service.verifyRegistration(
                user.getUserId(),
                request.getCredentialJson()
        );

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid registration data");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Passkey registered successfully");

        logger.info("Passkey registered for user: {}", user.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Generate authentication challenge
     * POST /api/fido2/authenticate/challenge
     */
    @PostMapping("/authenticate/challenge")
    @Operation(summary = "Generate Authentication Challenge", description = "Generates challenge for passkey authentication")
    @ApiResponse(responseCode = "200", description = "Authentication challenge generated successfully")
    @ApiResponse(responseCode = "400", description = "No passkeys registered")
    public ResponseEntity<Map<String, Object>> generateAuthenticationChallenge(
            @RequestBody AuthenticateChallengeRequest request) {
        if (request == null || request.getUserId() == null || request.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        FIDO2Service.AuthenticationChallengeResult result = fido2Service.generateAuthenticationChallenge(
                request.getUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("challenge", result.getOptions().getChallenge().getBase64Url());
        response.put("options", result.getOptions());
        response.put("message", "Authentication challenge generated. Use this to authenticate with your passkey.");

        logger.info("Authentication challenge generated for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Verify authentication
     * POST /api/fido2/authenticate/verify
     */
    @PostMapping("/authenticate/verify")
    @Operation(summary = "Verify Authentication", description = "Verifies passkey authentication")
    @ApiResponse(responseCode = "200", description = "Passkey authentication successful")
    @ApiResponse(responseCode = "400", description = "Invalid authentication data")
    public ResponseEntity<Map<String, Object>> verifyAuthentication(
            @RequestBody AuthenticatePasskeyRequest request) {
        if (request == null || request.getUserId() == null || request.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (request.getCredentialJson() == null || request.getCredentialJson().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        boolean isValid = fido2Service.verifyAuthentication(
                request.getUserId(),
                request.getCredentialJson()
        );

        if (!isValid) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid authentication data");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Passkey authentication successful");

        logger.info("Passkey authentication successful for user: {}", request.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * List passkeys for authenticated user
     * GET /api/fido2/passkeys
     */
    @GetMapping("/passkeys")
    @Operation(summary = "List Passkeys", description = "Returns list of passkeys for the authenticated user")
    @ApiResponse(responseCode = "200", description = "Passkeys retrieved successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> listPasskeys(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<FIDO2Service.PasskeyInfo> passkeys = fido2Service.listPasskeys(user.getUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("passkeys", passkeys);
        response.put("count", passkeys.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a passkey
     * DELETE /api/fido2/passkeys/{credentialId}
     */
    @DeleteMapping("/passkeys/{credentialId}")
    @Operation(summary = "Delete Passkey", description = "Deletes a passkey for the authenticated user")
    @ApiResponse(responseCode = "204", description = "Passkey deleted successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "404", description = "Passkey not found")
    public ResponseEntity<Void> deletePasskey(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String credentialId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (credentialId == null || credentialId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        fido2Service.deletePasskey(user.getUserId(), credentialId);

        logger.info("Passkey deleted for user: {}, credential: {}", user.getUserId(), credentialId);
        return ResponseEntity.noContent().build();
    }

    // MARK: - DTOs

    public static class RegisterPasskeyRequest {
        private String credentialJson; // Full PublicKeyCredential JSON from client

        public String getCredentialJson() {
            return credentialJson;
        }

        public void setCredentialJson(final String credentialJson) {
            this.credentialJson = credentialJson;
        }
    }

    public static class AuthenticateChallengeRequest {
        private String userId;

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }
    }

    public static class AuthenticatePasskeyRequest {
        private String userId;
        private String credentialJson; // Full PublicKeyCredential JSON from client

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public String getCredentialJson() {
            return credentialJson;
        }

        public void setCredentialJson(final String credentialJson) {
            this.credentialJson = credentialJson;
        }
    }
}

