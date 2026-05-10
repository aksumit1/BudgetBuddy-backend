package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * File quarantine service for suspicious files Quarantines files that: - Fail security validation -
 * Contain suspicious content - Are flagged by content scanner
 *
 * <p>Quarantined files are: - Moved to isolated directory - Logged with metadata - Can be reviewed
 * by administrators - Automatically deleted after retention period
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class FileQuarantineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileQuarantineService.class);

    private String quarantineDirectory;
    private final FileSecurityValidator fileSecurityValidator;
    private final long retentionPeriodDays;
    private volatile boolean quarantineEnabled = true;

    // Track quarantined files: fileId -> QuarantineRecord
    private final ConcurrentHashMap<String, QuarantineRecord> quarantinedFiles =
            new ConcurrentHashMap<>();
    private final ReentrantLock quarantineLock = new ReentrantLock();

    public FileQuarantineService(
            @Value("${app.security.quarantine.directory:}") final String quarantineDirectory,
            @Value("${app.security.quarantine.retention-days:30}") final long retentionPeriodDays,
            final FileSecurityValidator fileSecurityValidator) {
        // Initialize final fields first (must be done before try-catch)
        this.retentionPeriodDays = retentionPeriodDays;
        this.fileSecurityValidator = fileSecurityValidator;

        try {
            // Always default to system temp directory (which is writable) unless explicitly
            // configured
            // This ensures the directory CAN be created by default
            String effectiveDirectory = quarantineDirectory;
            if (effectiveDirectory == null || effectiveDirectory.isBlank()) {
                // Default to temp directory - this is always writable
                String tempDir = System.getProperty("java.io.tmpdir");
                if (tempDir == null || tempDir.isBlank()) {
                    // Last resort: use /tmp (standard on Unix systems)
                    tempDir = "/tmp";
                }
                effectiveDirectory = tempDir + "/budgetbuddy-quarantine";
                LOGGER.info(
                        "Using default quarantine directory (system temp): {}", effectiveDirectory);
            } else {
                // If a path is configured, we'll try it but fall back to temp if it fails
                LOGGER.info("Using configured quarantine directory: {}", effectiveDirectory);
            }
            this.quarantineDirectory = effectiveDirectory;
            initializeQuarantineDirectory();
        } catch (Exception e) {
            // Catch any exception during construction to prevent bean creation failure
            LOGGER.error(
                    "Failed to initialize FileQuarantineService. Quarantine functionality will be disabled. Error: {}",
                    e.getMessage(),
                    e);
            // Set safe defaults for quarantine directory
            try {
                String tempDir = System.getProperty("java.io.tmpdir");
                if (tempDir == null || tempDir.isBlank()) {
                    tempDir = "/tmp";
                }
                this.quarantineDirectory = tempDir + "/budgetbuddy-quarantine";
            } catch (Exception ex) {
                // Last resort fallback
                this.quarantineDirectory = "/tmp/budgetbuddy-quarantine";
            }
            this.quarantineEnabled = false;
        }
    }

    /**
     * Quarantine a suspicious file
     *
     * @param inputStream File input stream
     * @param originalFileName Original file name
     * @param reason Reason for quarantine
     * @param userId User ID who uploaded the file
     * @return Quarantine ID
     */
    public String quarantineFile(
            final InputStream inputStream, final String originalFileName, final String reason, final String userId) {
        if (!quarantineEnabled) {
            LOGGER.warn(
                    "Quarantine is disabled. File {} will not be quarantined. Reason: {}",
                    originalFileName,
                    reason);
            return null;
        }

        quarantineLock.lock();
        try {
            final String quarantineId = UUID.randomUUID().toString();
            final String timestamp =
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // Create safe filename: timestamp_quarantineId_originalName
            final String safeFileName = sanitizeFileName(originalFileName);
            final String quarantinedFileName =
                    String.format("%s_%s_%s", timestamp, quarantineId, safeFileName);

            final Path quarantinePath = Paths.get(quarantineDirectory);
            final Path quarantinedFile = quarantinePath.resolve(quarantinedFileName);

            // Validate quarantine path to prevent path traversal
            fileSecurityValidator.validateFilePath(quarantinedFile.toString());

            // Copy file to quarantine directory
            Files.copy(inputStream, quarantinedFile, StandardCopyOption.REPLACE_EXISTING);

            // Create quarantine record
            final QuarantineRecord record =
                    new QuarantineRecord(
                            quarantineId,
                            quarantinedFileName,
                            originalFileName,
                            reason,
                            userId,
                            LocalDateTime.now(),
                            quarantinedFile.toString());

            quarantinedFiles.put(quarantineId, record);

            LOGGER.warn(
                    "File quarantined: {} (ID: {}) - Reason: {} - User: {}",
                    originalFileName,
                    quarantineId,
                    reason,
                    userId);

            return quarantineId;
        } catch (IOException e) {
            LOGGER.error("Failed to quarantine file: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to quarantine file");
        } finally {
            quarantineLock.unlock();
        }
    }

    /**
     * Release file from quarantine (for manual review/approval)
     *
     * @param quarantineId Quarantine ID
     * @return Path to released file
     */
    public Path releaseFile(final String quarantineId) {
        final QuarantineRecord record = quarantinedFiles.get(quarantineId);
        if (record == null) {
            throw new AppException(ErrorCode.RECORD_NOT_FOUND, "Quarantine record not found");
        }

        final Path quarantinedFile = Paths.get(record.getQuarantinedPath());
        if (!Files.exists(quarantinedFile)) {
            throw new AppException(ErrorCode.RECORD_NOT_FOUND, "Quarantined file not found");
        }

        LOGGER.info(
                "File released from quarantine: {} (ID: {})",
                record.getOriginalFileName(),
                quarantineId);
        return quarantinedFile;
    }

    /**
     * Delete quarantined file
     *
     * @param quarantineId Quarantine ID
     */
    public void deleteQuarantinedFile(final String quarantineId) {
        final QuarantineRecord record = quarantinedFiles.remove(quarantineId);
        if (record == null) {
            return;
        }

        try {
            final Path quarantinedFile = Paths.get(record.getQuarantinedPath());
            if (Files.exists(quarantinedFile)) {
                Files.delete(quarantinedFile);
                LOGGER.info(
                        "Quarantined file deleted: {} (ID: {})",
                        record.getOriginalFileName(),
                        quarantineId);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to delete quarantined file: {}", e.getMessage(), e);
        }
    }

    /** Clean up old quarantined files (older than retention period) */
    public void cleanupOldQuarantinedFiles() {
        final LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionPeriodDays);
        int deletedCount = 0;

        quarantineLock.lock();
        try {
            final var iterator = quarantinedFiles.entrySet().iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                final QuarantineRecord record = entry.getValue();

                if (record.getQuarantinedAt().isBefore(cutoffDate)) {
                    try {
                        final Path quarantinedFile = Paths.get(record.getQuarantinedPath());
                        if (Files.exists(quarantinedFile)) {
                            Files.delete(quarantinedFile);
                        }
                        iterator.remove();
                        deletedCount++;
                    } catch (IOException e) {
                        LOGGER.error(
                                "Failed to delete old quarantined file: {}", e.getMessage(), e);
                    }
                }
            }
        } finally {
            quarantineLock.unlock();
        }

        if (deletedCount > 0) {
            LOGGER.info(
                    "Cleaned up {} old quarantined files (older than {} days)",
                    deletedCount,
                    retentionPeriodDays);
        }
    }

    /** Get quarantine record */
    public QuarantineRecord getQuarantineRecord(final String quarantineId) {
        return quarantinedFiles.get(quarantineId);
    }

    /** Get all quarantine records (for admin review) */
    public java.util.Collection<QuarantineRecord> getAllQuarantineRecords() {
        return new java.util.ArrayList<>(quarantinedFiles.values());
    }

    /** Get all quarantined files (alias for getAllQuarantineRecords) */
    public java.util.Collection<QuarantineRecord> getQuarantinedFiles() {
        return getAllQuarantineRecords();
    }

    /**
     * Cleanup old quarantined files (older than retention period)
     *
     * @return Number of files cleaned up
     */
    public int cleanupOldFiles() {
        int cleaned = 0;
        final long cutoffTime = System.currentTimeMillis() - (retentionPeriodDays * 24 * 60 * 60 * 1000L);

        quarantineLock.lock();
        try {
            final var iterator = quarantinedFiles.entrySet().iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                final QuarantineRecord record = entry.getValue();
                if (record.getQuarantinedAt()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        < cutoffTime) {
                    try {
                        final Path filePath =
                                Paths.get(record.getQuarantinedPath());
                        if (Files.exists(filePath)) {
                            Files.delete(filePath);
                        }
                        iterator.remove();
                        cleaned++;
                    } catch (Exception e) {
                        LOGGER.error(
                                "Failed to delete old quarantined file: {}",
                                record.getQuarantinedPath(),
                                e);
                    }
                }
            }
        } finally {
            quarantineLock.unlock();
        }

        return cleaned;
    }

    /**
     * Initialize quarantine directory Falls back to system temp directory if configured path fails
     * Never throws exceptions - gracefully disables quarantine if all attempts fail
     */
    private void initializeQuarantineDirectory() {
        try {
            final Path quarantinePath = Paths.get(quarantineDirectory);

            // Try to create the configured directory
            try {
                if (!Files.exists(quarantinePath)) {
                    Files.createDirectories(quarantinePath);
                    LOGGER.info("Created quarantine directory: {}", quarantineDirectory);
                } else if (!Files.isDirectory(quarantinePath)) {
                    LOGGER.warn(
                            "Quarantine path exists but is not a directory: {}",
                            quarantineDirectory);
                    quarantineEnabled = false;
                    return;
                } else if (!Files.isWritable(quarantinePath)) {
                    LOGGER.warn("Quarantine directory is not writable: {}", quarantineDirectory);
                    quarantineEnabled = false;
                    return;
                }
                LOGGER.info("Quarantine directory initialized: {}", quarantineDirectory);
                return; // Success - exit early
            } catch (IOException | SecurityException e) {
                LOGGER.warn(
                        "Failed to create quarantine directory at {}: {}. Attempting fallback to system temp directory.",
                        quarantineDirectory,
                        e.getMessage());
            }

            // Fallback to system temp directory (should always be writable)
            try {
                String tempDir = System.getProperty("java.io.tmpdir");
                if (tempDir == null || tempDir.isBlank()) {
                    tempDir = "/tmp"; // Standard Unix temp directory
                }
                final String fallbackDir = tempDir + "/budgetbuddy-quarantine";
                final Path fallbackPath = Paths.get(fallbackDir);

                // Create directory if it doesn't exist
                if (!Files.exists(fallbackPath)) {
                    Files.createDirectories(fallbackPath);
                    LOGGER.info("Created fallback quarantine directory: {}", fallbackDir);
                }

                // Verify fallback directory is writable
                if (!Files.isWritable(fallbackPath)) {
                    LOGGER.warn(
                            "Fallback quarantine directory is not writable: {}. Trying /tmp as last resort.",
                            fallbackDir);
                    // Last resort: try /tmp directly
                    final String lastResortDir = "/tmp/budgetbuddy-quarantine";
                    final Path lastResortPath = Paths.get(lastResortDir);
                    if (!Files.exists(lastResortPath)) {
                        Files.createDirectories(lastResortPath);
                    }
                    if (Files.isWritable(lastResortPath)) {
                        this.quarantineDirectory = lastResortDir;
                        LOGGER.info("Using last resort quarantine directory: {}", lastResortDir);
                        return;
                    }
                    quarantineEnabled = false;
                    return;
                }
                // Update the directory path to use fallback
                this.quarantineDirectory = fallbackDir;
                LOGGER.info("Using fallback quarantine directory: {}", fallbackDir);
            } catch (IOException | SecurityException fallbackException) {
                LOGGER.warn(
                        "Failed to create fallback quarantine directory. Quarantine functionality will be disabled. Error: {}",
                        fallbackException.getMessage());
                quarantineEnabled = false;
            }
        } catch (Exception e) {
            // Catch-all to prevent any exception from propagating during initialization
            LOGGER.warn(
                    "Unexpected error during quarantine directory initialization. Quarantine functionality will be disabled. Error: {}",
                    e.getMessage());
            quarantineEnabled = false;
        }
    }

    /** Sanitize filename for safe storage */
    private String sanitizeFileName(final String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        // Remove path separators and dangerous characters
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_")
                .substring(0, Math.min(fileName.length(), 100)); // Limit length
    }

    /** Quarantine record */
    public static class QuarantineRecord {
        private final String quarantineId;
        private final String quarantinedFileName;
        private final String originalFileName;
        private final String reason;
        private final String userId;
        private final LocalDateTime quarantinedAt;
        private final String quarantinedPath;

        public QuarantineRecord(
                final String quarantineId,
                final String quarantinedFileName,
                final String originalFileName,
                final String reason,
                final String userId,
                final LocalDateTime quarantinedAt,
                final String quarantinedPath) {
            this.quarantineId = quarantineId;
            this.quarantinedFileName = quarantinedFileName;
            this.originalFileName = originalFileName;
            this.reason = reason;
            this.userId = userId;
            this.quarantinedAt = quarantinedAt;
            this.quarantinedPath = quarantinedPath;
        }

        public String getQuarantineId() {
            return quarantineId;
        }

        public String getQuarantinedFileName() {
            return quarantinedFileName;
        }

        public String getOriginalFileName() {
            return originalFileName;
        }

        public String getReason() {
            return reason;
        }

        public String getUserId() {
            return userId;
        }

        public LocalDateTime getQuarantinedAt() {
            return quarantinedAt;
        }

        public String getQuarantinedPath() {
            return quarantinedPath;
        }
    }
}
