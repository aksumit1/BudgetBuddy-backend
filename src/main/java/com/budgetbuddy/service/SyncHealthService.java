package com.budgetbuddy.service;

import com.budgetbuddy.api.SyncHealthController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sync Health Service
 * Manages sync health status tracking and provides health information
 * 
 * Features:
 * - Thread-safe status tracking
 * - Health calculation
 * - Error aggregation
 * - Connection health assessment
 */
@Service
public class SyncHealthService {

    private static final Logger logger = LoggerFactory.getLogger(SyncHealthService.class);

    /**
     * Get comprehensive sync health information
     * 
     * @param userId User ID
     * @param status Current sync status
     * @return Sync health response
     */
    public SyncHealthResponse getSyncHealth(String userId, SyncHealthController.SyncStatus status) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }

        if (status == null) {
            status = new SyncHealthController.SyncStatus("idle", null, 0, "unknown", null);
        }

        // Calculate health metrics
        String healthStatus = calculateHealthStatus(status);
        boolean isStale = isConnectionStale(status);
        String timeAgo = formatTimeAgo(status.getLastSyncDate());

        SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus(status.getStatus());
        response.setLastSyncDate(status.getLastSyncDate());
        response.setConsecutiveFailures(status.getConsecutiveFailures());
        response.setConnectionHealth(healthStatus);
        response.setIsStale(isStale);
        response.setTimeAgo(timeAgo);
        response.setLastError(status.getLastError());
        response.setMessage(getStatusMessage(status));

        logger.debug("Sync health for user {}: status={}, health={}, stale={}", 
                userId, status.getStatus(), healthStatus, isStale);

        return response;
    }

    /**
     * Calculate overall health status based on sync status
     */
    private String calculateHealthStatus(SyncHealthController.SyncStatus status) {
        if (status == null) {
            return "unknown";
        }

        // If connectionHealth is explicitly set to "unknown", return it
        if (status.getConnectionHealth() != null && "unknown".equalsIgnoreCase(status.getConnectionHealth())) {
            return "unknown";
        }

        // Check for stale connection
        if (isConnectionStale(status)) {
            return "stale";
        }

        // Check consecutive failures
        if (status.getConsecutiveFailures() >= 3) {
            return "unhealthy";
        } else if (status.getConsecutiveFailures() >= 1) {
            return "degraded";
        }

        // Check last sync age
        if (status.getLastSyncDate() != null) {
            long hoursSinceSync = ChronoUnit.HOURS.between(status.getLastSyncDate(), Instant.now());
            if (hoursSinceSync > 24) {
                return "degraded";
            }
        }

        // Default to healthy
        return "healthy";
    }

    /**
     * Check if connection is stale (needs re-authentication)
     */
    private boolean isConnectionStale(SyncHealthController.SyncStatus status) {
        if (status == null) {
            return false;
        }

        // Check status
        if ("stale".equalsIgnoreCase(status.getStatus())) {
            return true;
        }

        // Check connection health
        if ("stale".equalsIgnoreCase(status.getConnectionHealth())) {
            return true;
        }

        // Check error message for stale indicators
        if (status.getLastError() != null) {
            String error = status.getLastError().toLowerCase();
            return error.contains("login required") ||
                   error.contains("reconnect") ||
                   error.contains("re-authenticate") ||
                   error.contains("invalid access token") ||
                   error.contains("token expired");
        }

        return false;
    }

    /**
     * Format time ago string
     */
    private String formatTimeAgo(Instant date) {
        if (date == null) {
            return "never";
        }

        long secondsAgo = ChronoUnit.SECONDS.between(date, Instant.now());
        
        if (secondsAgo < 60) {
            return "just now";
        } else if (secondsAgo < 3600) {
            long minutes = secondsAgo / 60;
            return minutes + "m ago";
        } else if (secondsAgo < 86400) {
            long hours = secondsAgo / 3600;
            return hours + "h ago";
        } else {
            long days = secondsAgo / 86400;
            return days + "d ago";
        }
    }

    /**
     * Get user-friendly status message
     */
    private String getStatusMessage(SyncHealthController.SyncStatus status) {
        if (status == null) {
            return "Not synced yet";
        }

        switch (status.getStatus().toLowerCase()) {
            case "idle":
                if (status.getLastSyncDate() != null) {
                    return "Last synced " + formatTimeAgo(status.getLastSyncDate());
                }
                return "Not synced yet";
            case "syncing":
                return "Syncing...";
            case "success":
                return "Up to date";
            case "failure":
                if (status.getLastError() != null) {
                    return getUserFriendlyErrorMessage(status.getLastError());
                }
                return "Sync failed";
            case "stale":
                return "Connection needs refresh";
            default:
                return "Unknown status";
        }
    }

    /**
     * Get user-friendly error message
     */
    private String getUserFriendlyErrorMessage(String error) {
        if (error == null || error.isEmpty()) {
            return "Sync failed - please try again";
        }

        String errorLower = error.toLowerCase();
        
        if (errorLower.contains("offline") || errorLower.contains("no internet")) {
            return "No internet connection";
        } else if (errorLower.contains("timeout")) {
            return "Connection timed out";
        } else if (errorLower.contains("rate limit")) {
            return "Too many requests - please wait";
        } else if (errorLower.contains("server error") || errorLower.contains("500")) {
            return "Server error - please try again";
        } else if (errorLower.contains("login required") || errorLower.contains("reconnect")) {
            return "Connection needs refresh";
        } else {
            return "Sync failed - please try again";
        }
    }

    /**
     * Sync Health Response DTO
     */
    public static class SyncHealthResponse {
        private String status;
        private Instant lastSyncDate;
        private int consecutiveFailures;
        private String connectionHealth;
        private boolean isStale;
        private String timeAgo;
        private String lastError;
        private String message;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Instant getLastSyncDate() { return lastSyncDate; }
        public void setLastSyncDate(Instant lastSyncDate) { this.lastSyncDate = lastSyncDate; }

        public int getConsecutiveFailures() { return consecutiveFailures; }
        public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

        public String getConnectionHealth() { return connectionHealth; }
        public void setConnectionHealth(String connectionHealth) { this.connectionHealth = connectionHealth; }

        public boolean getIsStale() { return isStale; }
        public void setIsStale(boolean isStale) { this.isStale = isStale; }

        public String getTimeAgo() { return timeAgo; }
        public void setTimeAgo(String timeAgo) { this.timeAgo = timeAgo; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}

