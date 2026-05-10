package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for handling chunked file uploads Stores chunks in memory temporarily and assembles them
 * on finalize
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification = "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class ChunkedUploadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkedUploadService.class);

    // Store upload sessions (uploadId -> chunks)
    private final Map<String, UploadSession> uploadSessions = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    // Cleanup old sessions (older than 1 hour)
    private static final long SESSION_TIMEOUT_MS = 60 * 60 * 1000; // 1 hour

    /** Upload session tracking chunks */
    private static class UploadSession {
        final String uploadId;
        final int totalChunks;
        final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        final long createdAt;
        String filename;
        String contentType;

        UploadSession(final String uploadId, final int totalChunks) {
            this.uploadId = uploadId;
            this.totalChunks = totalChunks;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isComplete() {
            return chunks.size() == totalChunks;
        }

        byte[] assemble() throws IOException {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int i = 0; i < totalChunks; i++) {
                final byte[] chunk = chunks.get(i);
                if (chunk == null) {
                    throw new IOException("Missing chunk " + i);
                }
                output.write(chunk);
            }
            return output.toByteArray();
        }
    }

    /**
     * Uploads a chunk
     *
     * @param uploadId Upload session ID
     * @param chunkIndex Chunk index (0-based)
     * @param totalChunks Total number of chunks
     * @param chunkData Chunk data
     * @param filename Original filename (from first chunk)
     * @param contentType Content type (from first chunk)
     * @return true if all chunks received, false otherwise
     */
    public boolean uploadChunk(
            final String uploadId,
            final int chunkIndex,
            final int totalChunks,
            final byte[] chunkData,
            final String filename,
            final String contentType) {
        if (uploadId == null || uploadId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Upload ID is required");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    String.format(
                            "Invalid chunk index: %d (total chunks: %d)", chunkIndex, totalChunks));
        }
        if (chunkData == null || chunkData.length == 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Chunk data is required");
        }

        final ReentrantLock lock = sessionLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
        lock.lock();
        try {
            final UploadSession session =
                    uploadSessions.computeIfAbsent(
                            uploadId, k -> new UploadSession(uploadId, totalChunks));

            // Set filename and content type from first chunk
            if (chunkIndex == 0) {
                session.filename = filename;
                session.contentType = contentType;
            }

            // Store chunk
            session.chunks.put(chunkIndex, chunkData);

            LOGGER.debug(
                    "Uploaded chunk {}/{} for uploadId: {}", chunkIndex + 1, totalChunks, uploadId);

            // Check if all chunks received
            return session.isComplete();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalizes upload and returns assembled file data
     *
     * @param uploadId Upload session ID
     * @return Assembled file data
     */
    public AssembledFile finalizeUpload(final String uploadId) throws IOException {
        if (uploadId == null || uploadId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Upload ID is required");
        }

        final ReentrantLock lock = sessionLocks.get(uploadId);
        if (lock == null) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Upload session not found: " + uploadId);
        }

        lock.lock();
        try {
            final UploadSession session = uploadSessions.get(uploadId);
            if (session == null) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "Upload session not found: " + uploadId);
            }

            if (!session.isComplete()) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        String.format(
                                "Upload incomplete: %d/%d chunks received",
                                session.chunks.size(), session.totalChunks));
            }

            // Assemble file
            final byte[] fileData = session.assemble();

            // Clean up session
            uploadSessions.remove(uploadId);
            sessionLocks.remove(uploadId);

            LOGGER.info(
                    "Finalized upload: {} ({} bytes, {} chunks)",
                    uploadId,
                    fileData.length,
                    session.totalChunks);

            return new AssembledFile(fileData, session.filename, session.contentType);
        } finally {
            lock.unlock();
        }
    }

    /** Cancels an upload session */
    public void cancelUpload(final String uploadId) {
        if (uploadId == null || uploadId.isEmpty()) {
            return;
        }

        final ReentrantLock lock = sessionLocks.get(uploadId);
        if (lock != null) {
            lock.lock();
            try {
                uploadSessions.remove(uploadId);
                LOGGER.info("Cancelled upload: {}", uploadId);
            } finally {
                lock.unlock();
                sessionLocks.remove(uploadId);
            }
        }
    }

    /** Cleans up old sessions (called periodically) */
    public void cleanupOldSessions() {
        final long now = System.currentTimeMillis();
        uploadSessions
                .entrySet()
                .removeIf(
                        entry -> {
                            final UploadSession session = entry.getValue();
                            if (now - session.createdAt > SESSION_TIMEOUT_MS) {
                                LOGGER.debug(
                                        "Cleaning up expired upload session: {}", entry.getKey());
                                sessionLocks.remove(entry.getKey());
                                return true;
                            }
                            return false;
                        });
    }

    /** Result of finalized upload */
    public static class AssembledFile {
        private final byte[] data;
        private final String filename;
        private final String contentType;

        public AssembledFile(final byte[] data, final String filename, final String contentType) {
            this.data = data;
            this.filename = filename;
            this.contentType = contentType;
        }

        public byte[] getData() {
            return data;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
