package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ProviderService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Provider REST Controller
 * Manages multiple financial data providers with graceful fallback
 * 
 * Features:
 * - Multi-provider support (Plaid, Stripe, Finicity, Teller)
 * - Provider health tracking
 * - Automatic fallback
 * - Stale connection detection
 * - Thread-safe operations
 */
@RestController
@RequestMapping("/api/providers")
@Tag(name = "Providers", description = "Financial data provider management")
public class ProviderController {

    private static final Logger logger = LoggerFactory.getLogger(ProviderController.class);

    private final ProviderService providerService;
    private final UserService userService;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ProviderController(final ProviderService providerService, final UserService userService) {
        this.providerService = providerService;
        this.userService = userService;
    }

    /**
     * Get All Providers
     * Returns list of all configured providers with their health status
     * 
     * @param userDetails Authenticated user details
     * @return List of providers with health status
     */
    @GetMapping
    @Operation(
        summary = "Get All Providers",
        description = "Returns list of all configured financial data providers with their health status"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Providers retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<List<ProviderHealthResponse>> getAllProviders(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.debug("Get providers request for user: {}", user.getUserId());
        
        lock.readLock().lock();
        try {
            List<ProviderHealthResponse> providers = providerService.getAllProviders(user.getUserId());
            return ResponseEntity.ok(providers);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get Provider Health
     * Returns health status for a specific provider
     * 
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier (e.g., "plaid", "stripe", "finicity", "teller")
     * @return Provider health status
     */
    @GetMapping("/{providerId}/health")
    @Operation(
        summary = "Get Provider Health",
        description = "Returns health status for a specific financial data provider"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider health retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "Provider not found")
    })
    public ResponseEntity<ProviderHealthResponse> getProviderHealth(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String providerId) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Provider ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.debug("Get provider health request for user: {}, provider: {}", user.getUserId(), providerId);
        
        lock.readLock().lock();
        try {
            ProviderHealthResponse health = providerService.getProviderHealth(user.getUserId(), providerId);
            if (health == null) {
                throw new AppException(ErrorCode.USER_NOT_FOUND, "Provider not found: " + providerId);
            }
            return ResponseEntity.ok(health);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update Provider Health
     * Updates health status for a provider (called by iOS after sync attempts)
     * Thread-safe to prevent race conditions
     * 
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @param request Health update request
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/health")
    @Operation(
        summary = "Update Provider Health",
        description = "Updates health status for a provider. Thread-safe to prevent race conditions."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider health updated successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<ProviderHealthResponse> updateProviderHealth(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String providerId,
            @Valid @RequestBody ProviderHealthUpdateRequest request) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Provider ID is required");
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Request body is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.info("Updating provider health for user: {}, provider: {}, healthy: {}", 
                user.getUserId(), providerId, request.getIsHealthy());
        
        lock.writeLock().lock();
        try {
            ProviderHealthResponse health = providerService.updateProviderHealth(
                    user.getUserId(), 
                    providerId, 
                    request.getIsHealthy(),
                    request.getIsStale(),
                    request.getLastError()
            );
            
            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark Provider as Stale
     * Marks a provider connection as stale (needs re-authentication)
     * 
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/mark-stale")
    @Operation(
        summary = "Mark Provider as Stale",
        description = "Marks a provider connection as stale, indicating it needs re-authentication"
    )
    public ResponseEntity<ProviderHealthResponse> markProviderAsStale(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String providerId) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Provider ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.info("Marking provider as stale for user: {}, provider: {}", user.getUserId(), providerId);
        
        lock.writeLock().lock();
        try {
            ProviderHealthResponse health = providerService.markProviderAsStale(user.getUserId(), providerId);
            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clear Stale Status
     * Clears stale status after successful re-authentication
     * 
     * @param userDetails Authenticated user details
     * @param providerId Provider identifier
     * @return Updated provider health
     */
    @PostMapping("/{providerId}/clear-stale")
    @Operation(
        summary = "Clear Stale Status",
        description = "Clears stale status after successful re-authentication"
    )
    public ResponseEntity<ProviderHealthResponse> clearStaleStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String providerId) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (providerId == null || providerId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Provider ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.info("Clearing stale status for user: {}, provider: {}", user.getUserId(), providerId);
        
        lock.writeLock().lock();
        try {
            ProviderHealthResponse health = providerService.clearStaleStatus(user.getUserId(), providerId);
            return ResponseEntity.ok(health);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Request/Response DTOs
    public static class ProviderHealthUpdateRequest {
        private Boolean isHealthy;
        private Boolean isStale;
        private String lastError;

        public Boolean getIsHealthy() { return isHealthy; }
        public void setIsHealthy(Boolean isHealthy) { this.isHealthy = isHealthy; }

        public Boolean getIsStale() { return isStale; }
        public void setIsStale(Boolean isStale) { this.isStale = isStale; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
    }

    public static class ProviderHealthResponse {
        private String providerId;
        private Boolean isHealthy;
        private java.time.Instant lastSuccess;
        private Integer failureCount;
        private Boolean isStale;
        private String lastError;

        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }

        public Boolean getIsHealthy() { return isHealthy; }
        public void setIsHealthy(Boolean isHealthy) { this.isHealthy = isHealthy; }

        public java.time.Instant getLastSuccess() { return lastSuccess; }
        public void setLastSuccess(java.time.Instant lastSuccess) { this.lastSuccess = lastSuccess; }

        public Integer getFailureCount() { return failureCount; }
        public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }

        public Boolean getIsStale() { return isStale; }
        public void setIsStale(Boolean isStale) { this.isStale = isStale; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
    }
}

