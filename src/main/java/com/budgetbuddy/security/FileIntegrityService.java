package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * File integrity service for checksum generation and verification Provides: - SHA-256 checksums for
 * uploaded files - Checksum verification - Checksum storage and lookup
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class FileIntegrityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileIntegrityService.class);

    private static final String HASH_ALGORITHM = "SHA-256";

    // Store checksums: fileId -> ChecksumRecord
    private final ConcurrentHashMap<String, ChecksumRecord> checksumStore =
            new ConcurrentHashMap<>();

    /**
     * Calculate SHA-256 checksum for file
     *
     * @param inputStream File input stream
     * @return Base64-encoded SHA-256 checksum
     */
    public String calculateChecksum(final InputStream inputStream) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            final byte[] hashBytes = digest.digest();
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to calculate checksum: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to calculate file checksum", e);
        }
    }

    /**
     * Store checksum for file
     *
     * @param fileId File identifier (e.g., S3 key, file path)
     * @param checksum SHA-256 checksum
     * @param metadata Optional metadata (file size, upload time, etc.)
     */
    public void storeChecksum(
            final String fileId,
            final String checksum,
            final java.util.Map<String, Object> metadata) {
        final ChecksumRecord record =
                new ChecksumRecord(fileId, checksum, metadata, System.currentTimeMillis());
        checksumStore.put(fileId, record);
        LOGGER.debug("Stored checksum for file: {} -> {}", fileId, checksum);
    }

    /**
     * Verify file integrity by comparing checksums
     *
     * @param fileId File identifier
     * @param inputStream File input stream to verify
     * @return true if checksum matches, false otherwise
     */
    public boolean verifyIntegrity(final String fileId, final InputStream inputStream) {
        final ChecksumRecord storedRecord = checksumStore.get(fileId);
        if (storedRecord == null) {
            LOGGER.warn("No stored checksum found for file: {}", fileId);
            return false;
        }

        final String calculatedChecksum = calculateChecksum(inputStream);
        final boolean matches = calculatedChecksum.equals(storedRecord.getChecksum());

        if (!matches) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Checksum mismatch for file: {} (stored: {}, calculated: {})",
                        fileId,
                        storedRecord.getChecksum(),
                        calculatedChecksum);
            }
        } else {
            LOGGER.debug("Checksum verified for file: {}", fileId);
        }

        return matches;
    }

    /**
     * Get stored checksum for file
     *
     * @param fileId File identifier
     * @return ChecksumRecord or null if not found
     */
    public ChecksumRecord getChecksum(final String fileId) {
        return checksumStore.get(fileId);
    }

    /**
     * Remove checksum record
     *
     * @param fileId File identifier
     */
    public void removeChecksum(final String fileId) {
        checksumStore.remove(fileId);
        LOGGER.debug("Removed checksum for file: {}", fileId);
    }

    /** Checksum record */
    public static class ChecksumRecord {
        private final String fileId;
        private final String checksum;
        private final java.util.Map<String, Object> metadata;
        private final long timestamp;

        public ChecksumRecord(
                final String fileId,
                final String checksum,
                final java.util.Map<String, Object> metadata,
                final long timestamp) {
            this.fileId = fileId;
            this.checksum = checksum;
            this.metadata =
                    metadata != null
                            ? new java.util.HashMap<>(metadata)
                            : new java.util.HashMap<>();
            this.timestamp = timestamp;
        }

        public String getFileId() {
            return fileId;
        }

        public String getChecksum() {
            return checksum;
        }

        public java.util.Map<String, Object> getMetadata() {
            return new java.util.HashMap<>(metadata);
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
