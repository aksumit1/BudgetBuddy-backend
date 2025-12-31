package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter specifically for file uploads
 * Prevents abuse by limiting:
 * - Number of uploads per user per time window
 * - Total file size uploaded per user per time window
 * - Upload frequency (minimum time between uploads)
 */
@Component
public class FileUploadRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadRateLimiter.class);

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    // Rate limit configuration - configurable via properties for tests
    @Value("${app.file-upload.max-uploads-per-hour:50}")
    private int maxUploadsPerHour;

    @Value("${app.file-upload.max-total-size-per-hour:104857600}") // 100MB default
    private long maxTotalSizePerHour;

    @Value("${app.file-upload.min-time-between-uploads-ms:1000}") // 1 second default
    private long minTimeBetweenUploadsMs;

    // Per-user tracking: userId -> UploadStats
    private final Map<String, UploadStats> userStats = new ConcurrentHashMap<>();

    // Cleanup interval: remove old entries every 2 hours
    private static final long CLEANUP_INTERVAL_MS = 2 * 60 * 60 * 1000; // 2 hours
    private long lastCleanup = System.currentTimeMillis();

    /**
     * Check if file upload is allowed for user
     *
     * @param userId User ID
     * @param fileSize Size of file being uploaded in bytes
     * @throws AppException if rate limit exceeded
     */
    public void checkRateLimit(String userId, long fileSize) {
        // If rate limiting is disabled, always allow
        if (!rateLimitEnabled) {
            return;
        }

        // Cleanup old entries periodically
        cleanupOldEntries();

        UploadStats stats = userStats.computeIfAbsent(userId, k -> new UploadStats());

        long currentTime = System.currentTimeMillis();

        // Check minimum time between uploads
        if (stats.lastUploadTime > 0) {
            long timeSinceLastUpload = currentTime - stats.lastUploadTime;
            if (timeSinceLastUpload < minTimeBetweenUploadsMs) {
                long waitTime = (minTimeBetweenUploadsMs - timeSinceLastUpload) / 1000;
                throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED,
                        String.format("Please wait %d seconds before uploading another file", waitTime));
            }
        }

        // Reset counters if hour has passed
        if (currentTime - stats.windowStartTime > 3600 * 1000) { // 1 hour
            stats.reset();
        }

        // Check upload count limit
        if (stats.uploadCount.get() >= maxUploadsPerHour) {
            long timeUntilReset = (stats.windowStartTime + 3600 * 1000 - currentTime) / 1000;
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    String.format("Upload limit exceeded. Maximum %d uploads per hour. Try again in %d seconds.",
                            maxUploadsPerHour, timeUntilReset));
        }

        // Check total size limit
        if (stats.totalSize.get() + fileSize > maxTotalSizePerHour) {
            long remainingSize = maxTotalSizePerHour - stats.totalSize.get();
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    String.format("Upload size limit exceeded. Maximum %d MB per hour. Remaining: %d MB.",
                            maxTotalSizePerHour / (1024 * 1024), remainingSize / (1024 * 1024)));
        }

        // Update stats
        stats.uploadCount.incrementAndGet();
        stats.totalSize.addAndGet(fileSize);
        stats.lastUploadTime = currentTime;

        logger.debug("File upload allowed for user {}: {}/{} uploads, {}/{} MB",
                userId, stats.uploadCount.get(), maxUploadsPerHour,
                stats.totalSize.get() / (1024 * 1024), maxTotalSizePerHour / (1024 * 1024));
    }

    /**
     * Record successful file upload (called after upload completes)
     */
    public void recordUpload(String userId, long fileSize) {
        // Stats are already updated in checkRateLimit, but we can add additional logging here
        logger.info("File upload completed for user {}: {} MB", userId, fileSize / (1024 * 1024));
    }

    /**
     * Clean up old entries to prevent memory leaks
     */
    private void cleanupOldEntries() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanup < CLEANUP_INTERVAL_MS) {
            return; // Not time to cleanup yet
        }

        lastCleanup = currentTime;
        int removed = 0;

        // Remove entries older than 2 hours
        userStats.entrySet().removeIf(entry -> {
            UploadStats stats = entry.getValue();
            return currentTime - stats.windowStartTime > 2 * 3600 * 1000; // 2 hours
        });

        if (removed > 0) {
            logger.debug("Cleaned up {} old file upload rate limit entries", removed);
        }
    }

    /**
     * Get current upload stats for user (for monitoring/debugging)
     */
    public UploadStats getStats(String userId) {
        return userStats.getOrDefault(userId, new UploadStats());
    }

    /**
     * Per-user upload statistics
     */
    public static class UploadStats {
        private final AtomicInteger uploadCount = new AtomicInteger(0);
        private final AtomicLong totalSize = new AtomicLong(0);
        private long windowStartTime = System.currentTimeMillis();
        private long lastUploadTime = 0;

        public void reset() {
            uploadCount.set(0);
            totalSize.set(0);
            windowStartTime = System.currentTimeMillis();
            lastUploadTime = 0;
        }

        public int getUploadCount() {
            return uploadCount.get();
        }

        public long getTotalSize() {
            return totalSize.get();
        }

        public long getWindowStartTime() {
            return windowStartTime;
        }

        public long getLastUploadTime() {
            return lastUploadTime;
        }
    }
}

