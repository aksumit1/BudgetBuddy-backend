package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * User REST Controller
 * Provides endpoints for user information
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*", maxAge = 3600)
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(final UserService userService) {
        this.userService = userService;
    }

    /**
     * Get current user information
     * @param userDetails Authenticated user details
     * @return User information
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Returns current authenticated user information")
    @ApiResponse(responseCode = "200", description = "User information retrieved successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
                logger.warn("getCurrentUser called without authentication");
                throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
            }

            com.budgetbuddy.model.dynamodb.UserTable user = userService.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> {
                        logger.error("User not found for email: {}", userDetails.getUsername());
                        return new AppException(ErrorCode.USER_NOT_FOUND, "User not found");
                    });

            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("userId", user.getUserId());
            userInfo.put("email", user.getEmail());
            userInfo.put("firstName", user.getFirstName() != null ? user.getFirstName() : "");
            userInfo.put("lastName", user.getLastName() != null ? user.getLastName() : "");
            userInfo.put("emailVerified", user.getEmailVerified() != null ? user.getEmailVerified() : false);
            userInfo.put("enabled", user.getEnabled() != null ? user.getEnabled() : true);

            logger.debug("Returning user info for user: {}", user.getEmail());
            return ResponseEntity.ok(userInfo);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting current user: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve user information", null, null, e);
        }
    }
}

