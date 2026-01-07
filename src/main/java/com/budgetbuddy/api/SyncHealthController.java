package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SyncHealthService;
import com.budgetbuddy.service.SyncHealthService.SyncHealthResponse;
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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Sync Health REST Controller
 * Provides endpoints for sync health status tracking and monitoring
 * 
 * Features:
 * - Sync status tracking
 * - Connection health monitoring
 * - Last sync timestamp
 * - Error tracking
 * - Race condition protection
 */
@RestController
@RequestMapping("/api/sync/health")
@Tag(name = "Sync Health", description = "Sync health status and monitoring")
public class SyncHealthController {

    private static final Logger logger = LoggerFactory.getLogger(SyncHealthController.class);

    private final SyncHealthService syncHealthService;
    private final UserService userService;
    
    // Thread-safe storage for sync status per user
    private final Map<String, SyncStatus> userSyncStatus = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock statusLock = new ReentrantReadWriteLock();

    public SyncHealthController(final SyncHealthService syncHealthService, final UserService userService) {
        this.syncHealthService = syncHealthService;
        this.userService = userService;
    }

    /**
     * Get Sync Health Status
     * Returns comprehensive sync health information for the authenticated user
     * 
     * @param userDetails Authenticated user details
     * @return Sync health status
     */
    @GetMapping
    @Operation(
        summary = "Get Sync Health Status",
        description = "Returns comprehensive sync health information including last sync time, connection status, and error details"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sync health retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<SyncHealthResponse> getSyncHealth(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is invalid");
        }

        logger.debug("Sync health request for user: {}", user.getUserId());
        
        // Thread-safe read
        statusLock.readLock().lock();
        try {
            SyncStatus status = userSyncStatus.getOrDefault(user.getUserId(), 
                    new SyncStatus("idle", null, 0, "unknown", null));
            
            SyncHealthResponse response = syncHealthService.getSyncHealth(user.getUserId(), status);
            
            return ResponseEntity.ok(response);
        } finally {
            statusLock.readLock().unlock();
        }
    }

    /**
     * Update Sync Status
     * Updates the sync status for the authenticated user
     * Thread-safe to prevent race conditions
     * 
     * @param userDetails Authenticated user details
     * @param request Sync status update request
     * @return Updated sync health
     */
    @PostMapping("/status")
    @Operation(
        summary = "Update Sync Status",
        description = "Updates the sync status for the user. Thread-safe to prevent race conditions."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sync status updated successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<SyncHealthResponse> updateSyncStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody SyncStatusUpdateRequest request) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (request == null || request.getStatus() == null || request.getStatus().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Status is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.info("Updating sync status for user: {} to: {}", user.getUserId(), request.getStatus());
        
        // Thread-safe write
        statusLock.writeLock().lock();
        try {
            SyncStatus status = new SyncStatus(
                    request.getStatus(),
                    request.getLastSyncDate(),
                    request.getConsecutiveFailures() != null ? request.getConsecutiveFailures() : 0,
                    request.getConnectionHealth() != null ? request.getConnectionHealth() : "unknown",
                    request.getLastError()
            );
            
            userSyncStatus.put(user.getUserId(), status);
            
            SyncHealthResponse response = syncHealthService.getSyncHealth(user.getUserId(), status);
            
            return ResponseEntity.ok(response);
        } finally {
            statusLock.writeLock().unlock();
        }
    }

    /**
     * Clear Sync Errors
     * Clears error state and resets consecutive failure count
     * 
     * @param userDetails Authenticated user details
     * @return Updated sync health
     */
    @PostMapping("/clear-errors")
    @Operation(
        summary = "Clear Sync Errors",
        description = "Clears error state and resets consecutive failure count for the user"
    )
    public ResponseEntity<SyncHealthResponse> clearSyncErrors(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null || userDetails.getUsername() == null || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        logger.info("Clearing sync errors for user: {}", user.getUserId());
        
        // Thread-safe write
        statusLock.writeLock().lock();
        try {
            SyncStatus currentStatus = userSyncStatus.get(user.getUserId());
            if (currentStatus != null) {
                SyncStatus clearedStatus = new SyncStatus(
                        "idle",
                        currentStatus.getLastSyncDate(),
                        0,
                        "healthy",
                        null
                );
                userSyncStatus.put(user.getUserId(), clearedStatus);
            }
            
            SyncStatus status = userSyncStatus.getOrDefault(user.getUserId(), 
                    new SyncStatus("idle", null, 0, "healthy", null));
            
            SyncHealthResponse response = syncHealthService.getSyncHealth(user.getUserId(), status);
            
            return ResponseEntity.ok(response);
        } finally {
            statusLock.writeLock().unlock();
        }
    }

    // Inner classes for request/response
    public static class SyncStatusUpdateRequest {
        private String status;
        private Instant lastSyncDate;
        private Integer consecutiveFailures;
        private String connectionHealth;
        private String lastError;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public Instant getLastSyncDate() { return lastSyncDate; }
        public void setLastSyncDate(Instant lastSyncDate) { this.lastSyncDate = lastSyncDate; }
        
        public Integer getConsecutiveFailures() { return consecutiveFailures; }
        public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
        
        public String getConnectionHealth() { return connectionHealth; }
        public void setConnectionHealth(String connectionHealth) { this.connectionHealth = connectionHealth; }
        
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
    }

    public static class SyncStatus {
        private final String status;
        private final Instant lastSyncDate;
        private final int consecutiveFailures;
        private final String connectionHealth;
        private final String lastError;

        public SyncStatus(String status, Instant lastSyncDate, int consecutiveFailures, 
                         String connectionHealth, String lastError) {
            this.status = status;
            this.lastSyncDate = lastSyncDate;
            this.consecutiveFailures = consecutiveFailures;
            this.connectionHealth = connectionHealth;
            this.lastError = lastError;
        }

        public String getStatus() { return status; }
        public Instant getLastSyncDate() { return lastSyncDate; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public String getConnectionHealth() { return connectionHealth; }
        public String getLastError() { return lastError; }
    }
}

