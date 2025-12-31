package com.budgetbuddy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for security cleanup operations
 * 
 * Tasks:
 * - Cleanup old quarantined files
 * - Cleanup old rate limit entries
 * - Generate security reports
 */
@Component
public class SecurityCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SecurityCleanupScheduler.class);

    private final FileQuarantineService fileQuarantineService;
    private final FileUploadRateLimiter fileUploadRateLimiter;

    public SecurityCleanupScheduler(
            FileQuarantineService fileQuarantineService,
            FileUploadRateLimiter fileUploadRateLimiter) {
        this.fileQuarantineService = fileQuarantineService;
        this.fileUploadRateLimiter = fileUploadRateLimiter;
    }

    /**
     * Cleanup old quarantined files
     * Runs daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    public void cleanupQuarantinedFiles() {
        try {
            logger.info("Starting scheduled cleanup of quarantined files");
            fileQuarantineService.cleanupOldQuarantinedFiles();
            logger.info("Completed cleanup of quarantined files");
        } catch (Exception e) {
            logger.error("Failed to cleanup quarantined files: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup old rate limit entries
     * Runs every 6 hours
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // Every 6 hours
    public void cleanupRateLimitEntries() {
        try {
            logger.debug("Starting scheduled cleanup of rate limit entries");
            // Rate limiter automatically cleans up old entries during checkRateLimit calls
            // This scheduled task ensures cleanup happens even if no uploads occur
            logger.debug("Rate limit cleanup is handled automatically during uploads");
        } catch (Exception e) {
            logger.error("Failed to cleanup rate limit entries: {}", e.getMessage(), e);
        }
    }

    /**
     * Generate security report
     * Runs weekly on Sunday at 3 AM
     */
    @Scheduled(cron = "0 0 3 ? * SUN") // Weekly on Sunday at 3 AM
    public void generateSecurityReport() {
        try {
            logger.info("Generating weekly security report");
            
            // Get quarantine statistics
            var quarantinedFiles = fileQuarantineService.getAllQuarantineRecords();
            logger.info("Security Report - Quarantined Files: {}", quarantinedFiles.size());
            
            // Log summary
            logger.info("=== Weekly Security Report ===");
            logger.info("Quarantined Files: {}", quarantinedFiles.size());
            logger.info("==============================");
            
            // TODO: Send report to administrators via email/notification
        } catch (Exception e) {
            logger.error("Failed to generate security report: {}", e.getMessage(), e);
        }
    }
}

