package com.budgetbuddy.api;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.PasswordResetService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;

/**
 * Authentication REST Controller
 * Supports secure client-side hashed passwords
 * Supports both /api/auth and /auth paths for backward compatibility
 */
@RestController
@RequestMapping({"/api/auth", "/auth"})
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserService userService;
    private final PasswordResetService passwordResetService;

    public AuthController(final AuthService authService, final UserService userService, final PasswordResetService passwordResetService) {
        this.authService = authService;
        this.userService = userService;
        this.passwordResetService = passwordResetService;
    }

    /**
     * Login endpoint
     * Accepts secure format (password_hash + salt)
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest loginRequest) {
        // Validate request format
        if (!loginRequest.isSecureFormat()) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "password_hash and salt must be provided");
        }

        AuthResponse response = authService.authenticate(loginRequest);
        return ResponseEntity.ok(response);
    }


    /**
     * Registration endpoint
     * Accepts secure format (password_hash + salt)
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest signUpRequest) {
        // Validate secure format
        if (!signUpRequest.isSecureFormat()) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "Registration requires password_hash and salt. Legacy password format not supported.");
        }

        // Create user with secure format
        userService.createUserSecure(
                signUpRequest.getEmail(),
                signUpRequest.getPasswordHash(),
                signUpRequest.getSalt(),
                null,
                null
        );

        // Authenticate and return tokens
        AuthRequest authRequest = new AuthRequest(
                signUpRequest.getEmail(),
                signUpRequest.getPasswordHash(),
                signUpRequest.getSalt());
        AuthResponse response = authService.authenticate(authRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    /**
     * Refresh token endpoint
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Refresh token is required");
        }

        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    /**
     * Request password reset - sends 6-digit code via email
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Request Password Reset", description = "Sends a 6-digit verification code to the user's email")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestPasswordReset(request.getEmail());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Verification code sent to your email"
        ));
    }

    /**
     * Verify reset code
     */
    @PostMapping("/verify-reset-code")
    @Operation(summary = "Verify Reset Code", description = "Verifies the 6-digit code sent via email")
    public ResponseEntity<Map<String, String>> verifyResetCode(@Valid @RequestBody VerifyCodeRequest request) {
        passwordResetService.verifyResetCode(request.getEmail(), request.getCode());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Code verified successfully"
        ));
    }

    /**
     * Password reset endpoint with code verification
     * Accepts secure format (password_hash + salt) and verification code
     */
    @PostMapping("/reset-password")
    @Operation(summary = "Reset Password", description = "Resets password using verified code")
    public ResponseEntity<PasswordResetResponse> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        // Validate secure format
        if (!request.isSecureFormat()) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "Password reset requires password_hash and salt. Legacy password format not supported.");
        }

        // Verify code and reset password
        passwordResetService.resetPassword(
                request.getEmail(),
                request.getCode(),
                request.getPasswordHash(),
                request.getSalt()
        );

        // Reset password in UserService
        userService.resetPasswordByEmail(
                request.getEmail(),
                request.getPasswordHash(),
                request.getSalt()
        );

        logger.info("Password reset successful for email: {}", request.getEmail());
        return ResponseEntity.ok(new PasswordResetResponse(true, "Password reset successful"));
    }

    /**
     * Change password endpoint (authenticated)
     * Requires current password verification and new password
     */
    @PostMapping("/change-password")
    @Operation(summary = "Change Password", description = "Changes password for authenticated user")
    public ResponseEntity<PasswordChangeResponse> changePassword(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        // Validate secure format for new password
        if (!request.isSecureFormat()) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "Password change requires new_password_hash and new_salt. Legacy password format not supported.");
        }

        // Find user
        com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Verify current password
        AuthRequest currentPasswordRequest = new AuthRequest(
                user.getEmail(),
                request.getCurrentPasswordHash(),
                request.getCurrentSalt()
        );

        try {
            authService.authenticate(currentPasswordRequest);
        } catch (AppException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) {
                throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
            }
            throw e;
        }

        // Change password
        userService.changePasswordSecure(
                user.getUserId(),
                request.getNewPasswordHash(),
                request.getNewSalt()
        );

        logger.info("Password changed successfully for user: {}", user.getEmail());
        return ResponseEntity.ok(new PasswordChangeResponse(true, "Password changed successfully"));
    }

    // Inner class for refresh token request
    public static class RefreshTokenRequest {
        private String refreshToken;

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(final String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    // Inner class for forgot password request
    public static class ForgotPasswordRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Email should be valid")
        private String email;

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }
    }

    // Inner class for verify code request
    public static class VerifyCodeRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Email should be valid")
        private String email;

        @NotBlank(message = "Verification code is required")
        private String code;

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = code;
        }
    }

    // Inner class for password reset request
    public static class PasswordResetRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Email should be valid")
        private String email;

        @NotBlank(message = "Verification code is required")
        private String code;

        @JsonProperty("passwordHash")
        @JsonAlias("password_hash")
        private String passwordHash;
        private String salt;

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = code;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(final String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getSalt() {
            return salt;
        }

        public void setSalt(final String salt) {
            this.salt = salt;
        }

        /**
         * Check if request uses secure format (password_hash + salt)
         * @JsonIgnore prevents this method from being serialized/deserialized as a JSON property
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSecureFormat() {
            return passwordHash != null && !passwordHash.isEmpty()
                   && salt != null && !salt.isEmpty();
        }
    }

    // Inner class for password reset response
    public static class PasswordResetResponse {
        private boolean success;
        private String message;

        public PasswordResetResponse() {
        }

        public PasswordResetResponse(final boolean success, final String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(final boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(final String message) {
            this.message = message;
        }
    }

    // Inner class for change password request
    public static class ChangePasswordRequest {
        @JsonProperty("currentPasswordHash")
        @JsonAlias("current_password_hash")
        @NotBlank(message = "Current password hash is required")
        private String currentPasswordHash;

        @NotBlank(message = "Current salt is required")
        private String currentSalt;

        @JsonProperty("newPasswordHash")
        @JsonAlias("new_password_hash")
        @NotBlank(message = "New password hash is required")
        private String newPasswordHash;

        @NotBlank(message = "New salt is required")
        private String newSalt;

        public String getCurrentPasswordHash() {
            return currentPasswordHash;
        }

        public void setCurrentPasswordHash(final String currentPasswordHash) {
            this.currentPasswordHash = currentPasswordHash;
        }

        public String getCurrentSalt() {
            return currentSalt;
        }

        public void setCurrentSalt(final String currentSalt) {
            this.currentSalt = currentSalt;
        }

        public String getNewPasswordHash() {
            return newPasswordHash;
        }

        public void setNewPasswordHash(final String newPasswordHash) {
            this.newPasswordHash = newPasswordHash;
        }

        public String getNewSalt() {
            return newSalt;
        }

        public void setNewSalt(final String newSalt) {
            this.newSalt = newSalt;
        }

        /**
         * Check if request uses secure format (password_hash + salt)
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSecureFormat() {
            return newPasswordHash != null && !newPasswordHash.isEmpty()
                   && newSalt != null && !newSalt.isEmpty()
                   && currentPasswordHash != null && !currentPasswordHash.isEmpty()
                   && currentSalt != null && !currentSalt.isEmpty();
        }
    }

    // Inner class for change password response
    public static class PasswordChangeResponse {
        private boolean success;
        private String message;

        public PasswordChangeResponse() {
        }

        public PasswordChangeResponse(final boolean success, final String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(final boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(final String message) {
            this.message = message;
        }
    }
}
