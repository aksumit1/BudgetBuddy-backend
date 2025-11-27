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
        com.budgetbuddy.model.dynamodb.UserTable user = userService.createUserSecure(
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
         */
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
}
