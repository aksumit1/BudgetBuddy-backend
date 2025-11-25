package com.budgetbuddy.api;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public AuthController(final AuthService authService, final UserService userService) {
        this.authService = authService;
        this.userService = userService;
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
}
