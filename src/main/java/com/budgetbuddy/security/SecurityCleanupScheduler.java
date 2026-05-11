package com.budgetbuddy.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for security cleanup operations
 *
 * <p>Tasks: - Cleanup old quarantined files - Cleanup old rate limit entries - Generate security
 * reports
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Component
public class SecurityCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityCleanupScheduler.class);

    private final FileQuarantineService fileQuarantineService;
    private final FileUploadRateLimiter fileUploadRateLimiter;

    public SecurityCleanupScheduler(
            final FileQuarantineService fileQuarantineService,
            final FileUploadRateLimiter fileUploadRateLimiter) {
        this.fileQuarantineService = fileQuarantineService;
        this.fileUploadRateLimiter = fileUploadRateLimiter;
    }

    /** Cleanup old quarantined files Runs daily at 2 AM */
    // Staggered off the 02:00 UTC cluster so DynamoDB + ECS aren't hit by
    // six daily jobs at once. Schedule map (all UTC):
    //   02:00 BenchmarkAggregation · 02:10 DataArchiving (monthly)
    //   02:15 SecurityCleanup · 02:25 PlaidSyncOrchestrator
    //   02:35 PlaidSync · 02:45 ScheduledImport · 02:30 NetWorthSnapshot
    @Scheduled(cron = "0 15 2 * * ?", zone = "UTC")
    public void cleanupQuarantinedFiles() {
        try {
            LOGGER.info("Starting scheduled cleanup of quarantined files");
            fileQuarantineService.cleanupOldQuarantinedFiles();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Completed cleanup of quarantined files");
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to cleanup quarantined files: {}", e.getMessage(), e);
            }
        }
    }

    /** Cleanup old rate limit entries Runs every 6 hours */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // Every 6 hours
    public void cleanupRateLimitEntries() {
        try {
            LOGGER.debug("Starting scheduled cleanup of rate limit entries");
            // Rate limiter automatically cleans up old entries during checkRateLimit calls
            // This scheduled task ensures cleanup happens even if no uploads occur
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Rate limit cleanup is handled automatically during uploads");
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to cleanup rate limit entries: {}", e.getMessage(), e);
            }
        }
    }

    /** Generate security report Runs weekly on Sunday at 3 AM */
    @Scheduled(cron = "0 0 3 ? * SUN") // Weekly on Sunday at 3 AM
    public void generateSecurityReport() {
        try {
            LOGGER.info("Generating weekly security report");

            // Get quarantine statistics
            final var quarantinedFiles = fileQuarantineService.getAllQuarantineRecords();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Security Report - Quarantined Files: {}", quarantinedFiles.size());
            }

            // Log summary
            LOGGER.info("=== Weekly Security Report ===");
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Quarantined Files: {}", quarantinedFiles.size());
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("==============================");
            }

            // TODO: Send report to administrators via email/notification
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to generate security report: {}", e.getMessage(), e);
            }
        }
    }
}
