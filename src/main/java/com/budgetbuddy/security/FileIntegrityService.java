package com.budgetbuddy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File integrity service for checksum generation and verification
 * Provides:
 * - SHA-256 checksums for uploaded files
 * - Checksum verification
 * - Checksum storage and lookup
 */
@Service
public class FileIntegrityService {

    private static final Logger logger = LoggerFactory.getLogger(FileIntegrityService.class);

    private static final String HASH_ALGORITHM = "SHA-256";
    
    // Store checksums: fileId -> ChecksumRecord
    private final ConcurrentHashMap<String, ChecksumRecord> checksumStore = new ConcurrentHashMap<>();

    /**
     * Calculate SHA-256 checksum for file
     *
     * @param inputStream File input stream
     * @return Base64-encoded SHA-256 checksum
     */
    public String calculateChecksum(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            byte[] hashBytes = digest.digest();
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            logger.error("Failed to calculate checksum: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to calculate file checksum", e);
        }
    }

    /**
     * Store checksum for file
     *
     * @param fileId File identifier (e.g., S3 key, file path)
     * @param checksum SHA-256 checksum
     * @param metadata Optional metadata (file size, upload time, etc.)
     */
    public void storeChecksum(String fileId, String checksum, java.util.Map<String, Object> metadata) {
        ChecksumRecord record = new ChecksumRecord(fileId, checksum, metadata, System.currentTimeMillis());
        checksumStore.put(fileId, record);
        logger.debug("Stored checksum for file: {} -> {}", fileId, checksum);
    }

    /**
     * Verify file integrity by comparing checksums
     *
     * @param fileId File identifier
     * @param inputStream File input stream to verify
     * @return true if checksum matches, false otherwise
     */
    public boolean verifyIntegrity(String fileId, InputStream inputStream) {
        ChecksumRecord storedRecord = checksumStore.get(fileId);
        if (storedRecord == null) {
            logger.warn("No stored checksum found for file: {}", fileId);
            return false;
        }

        String calculatedChecksum = calculateChecksum(inputStream);
        boolean matches = calculatedChecksum.equals(storedRecord.getChecksum());

        if (!matches) {
            logger.warn("Checksum mismatch for file: {} (stored: {}, calculated: {})", 
                    fileId, storedRecord.getChecksum(), calculatedChecksum);
        } else {
            logger.debug("Checksum verified for file: {}", fileId);
        }

        return matches;
    }

    /**
     * Get stored checksum for file
     *
     * @param fileId File identifier
     * @return ChecksumRecord or null if not found
     */
    public ChecksumRecord getChecksum(String fileId) {
        return checksumStore.get(fileId);
    }

    /**
     * Remove checksum record
     *
     * @param fileId File identifier
     */
    public void removeChecksum(String fileId) {
        checksumStore.remove(fileId);
        logger.debug("Removed checksum for file: {}", fileId);
    }

    /**
     * Checksum record
     */
    public static class ChecksumRecord {
        private final String fileId;
        private final String checksum;
        private final java.util.Map<String, Object> metadata;
        private final long timestamp;

        public ChecksumRecord(String fileId, String checksum, 
                             java.util.Map<String, Object> metadata, long timestamp) {
            this.fileId = fileId;
            this.checksum = checksum;
            this.metadata = metadata != null ? new java.util.HashMap<>(metadata) : new java.util.HashMap<>();
            this.timestamp = timestamp;
        }

        public String getFileId() { return fileId; }
        public String getChecksum() { return checksum; }
        public java.util.Map<String, Object> getMetadata() { return new java.util.HashMap<>(metadata); }
        public long getTimestamp() { return timestamp; }
    }
}

