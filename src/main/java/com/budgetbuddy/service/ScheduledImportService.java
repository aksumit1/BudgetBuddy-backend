package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled Import Service Automatically processes imports from configured sources (cloud storage,
 * email, etc.)
 *
 * <p>Features: - Scheduled imports from cloud storage (S3, Dropbox, Google Drive) - Email-based
 * imports (forward statements) - Automatic processing and notification - Configurable schedule
 * (daily, weekly, monthly)
 */
@Service
public class ScheduledImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledImportService.class);

    @Value("${app.import.scheduled.enabled:false}")
    private boolean scheduledImportEnabled;

    /**
     * Scheduled import job — currently a stub. Behavior intentionally fails fast unless the feature
     * flag {@code app.import.scheduled.enabled} is set to {@code true}: returns immediately so the
     * cron fires without doing work. The previous version logged "processing completed" each fire,
     * which was misleading.
     */
    @Scheduled(
            cron = "${app.import.scheduled.cron:0 45 2 * * ?}",
            zone = "UTC") // Default: 02:45 UTC (staggered)
    public void processScheduledImports() {
        if (!scheduledImportEnabled) {
            LOGGER.debug("Scheduled imports disabled — skipping cron tick");
            return;
        }
        // Cloud-storage / email-import wiring is not implemented yet. The flag is opt-in,
        // so reaching this branch is a configuration mistake worth surfacing loudly.
        LOGGER.warn("Scheduled imports flag is enabled but the importer is not implemented; no-op");
    }

    /** Process imports for a specific user. Not implemented — throws on invocation. */
    public void processUserImports(final UserTable user) {
        if (user == null || user.getUserId() == null) {
            LOGGER.warn("Invalid user for scheduled import");
            return;
        }
        throw new UnsupportedOperationException(
                "Scheduled-import wiring is not implemented; callers must not rely on it");
    }

    /** Enable scheduled imports for a user. Not implemented — throws on invocation. */
    public void enableScheduledImports(
            final String userId, final String sourceType, final String sourceConfig) {
        throw new UnsupportedOperationException(
                "Scheduled-import configuration is not implemented (user="
                        + userId
                        + ", source="
                        + sourceType
                        + ")");
    }

    /** Disable scheduled imports for a user. Not implemented — throws on invocation. */
    public void disableScheduledImports(final String userId) {
        throw new UnsupportedOperationException(
                "Scheduled-import configuration is not implemented (user=" + userId + ")");
    }
}
