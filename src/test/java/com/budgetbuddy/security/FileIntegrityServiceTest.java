package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileIntegrityServiceTest {

    private FileIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new FileIntegrityService();
    }

    @Test
    void testCalculateChecksumShouldReturnConsistentHash() {
        // Given
        final String content = "test content for checksum";
        final InputStream inputStream1 =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        final InputStream inputStream2 =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final String checksum1 = service.calculateChecksum(inputStream1);
        final String checksum2 = service.calculateChecksum(inputStream2);

        // Then
        assertNotNull(checksum1);
        assertNotNull(checksum2);
        assertEquals(checksum1, checksum2); // Same content should produce same checksum
    }

    @Test
    void testCalculateChecksumDifferentContentShouldReturnDifferentHash() {
        // Given
        final String content1 = "test content 1";
        final String content2 = "test content 2";
        final InputStream inputStream1 =
                new ByteArrayInputStream(content1.getBytes(StandardCharsets.UTF_8));
        final InputStream inputStream2 =
                new ByteArrayInputStream(content2.getBytes(StandardCharsets.UTF_8));

        // When
        final String checksum1 = service.calculateChecksum(inputStream1);
        final String checksum2 = service.calculateChecksum(inputStream2);

        // Then
        assertNotEquals(
                checksum1, checksum2); // Different content should produce different checksum
    }

    @Test
    void testCalculateChecksumEmptyContentShouldReturnHash() {
        // Given
        final InputStream inputStream = new ByteArrayInputStream(new byte[0]);

        // When
        final String checksum = service.calculateChecksum(inputStream);

        // Then
        assertNotNull(checksum);
        assertFalse(checksum.isEmpty());
    }

    @Test
    void testStoreChecksumShouldStore() {
        // Given
        final String fileId = "test-file-123";
        final String checksum = "test-checksum";

        // When
        service.storeChecksum(fileId, checksum, null);

        // Then
        final FileIntegrityService.ChecksumRecord record = service.getChecksum(fileId);
        assertNotNull(record);
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
    }

    @Test
    void testStoreChecksumWithMetadataShouldStore() {
        // Given
        final String fileId = "test-file-456";
        final String checksum = "test-checksum-2";
        final Map<String, Object> metadata = new HashMap<>();
        metadata.put("size", 1024L);
        metadata.put("uploadTime", System.currentTimeMillis());

        // When
        service.storeChecksum(fileId, checksum, metadata);

        // Then
        final FileIntegrityService.ChecksumRecord record = service.getChecksum(fileId);
        assertNotNull(record);
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
        assertNotNull(record.getMetadata());
    }

    @Test
    void testVerifyIntegrityMatchingChecksumShouldReturnTrue() {
        // Given
        final String fileId = "test-file-789";
        final String content = "test content";
        final InputStream inputStream1 =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        final String checksum = service.calculateChecksum(inputStream1);
        service.storeChecksum(fileId, checksum, null);

        // When - Verify with same content
        final InputStream inputStream2 =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        final boolean verified = service.verifyIntegrity(fileId, inputStream2);

        // Then
        assertTrue(verified);
    }

    @Test
    void testVerifyIntegrityNonMatchingChecksumShouldReturnFalse() {
        // Given
        final String fileId = "test-file-abc";
        final String content1 = "test content 1";
        final InputStream inputStream1 =
                new ByteArrayInputStream(content1.getBytes(StandardCharsets.UTF_8));
        final String checksum = service.calculateChecksum(inputStream1);
        service.storeChecksum(fileId, checksum, null);

        // When - Verify with different content
        final String content2 = "test content 2";
        final InputStream inputStream2 =
                new ByteArrayInputStream(content2.getBytes(StandardCharsets.UTF_8));
        final boolean verified = service.verifyIntegrity(fileId, inputStream2);

        // Then
        assertFalse(verified);
    }

    @Test
    void testVerifyIntegrityNoStoredChecksumShouldReturnFalse() {
        // Given
        final String fileId = "non-existent-file";
        final InputStream inputStream =
                new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));

        // When
        final boolean verified = service.verifyIntegrity(fileId, inputStream);

        // Then
        assertFalse(verified);
    }

    @Test
    void testGetChecksumNonExistentShouldReturnNull() {
        // When
        final FileIntegrityService.ChecksumRecord record = service.getChecksum("non-existent");

        // Then
        assertNull(record);
    }

    @Test
    void testRemoveChecksumShouldRemove() {
        // Given
        final String fileId = "test-file-remove";
        service.storeChecksum(fileId, "checksum", null);

        // When
        service.removeChecksum(fileId);

        // Then
        assertNull(service.getChecksum(fileId));
    }

    @Test
    void testChecksumRecordGetters() {
        // Given
        final String fileId = "test-id";
        final String checksum = "test-checksum";
        final Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        final long timestamp = System.currentTimeMillis();

        // When
        final FileIntegrityService.ChecksumRecord record =
                new FileIntegrityService.ChecksumRecord(fileId, checksum, metadata, timestamp);

        // Then
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
        assertEquals(timestamp, record.getTimestamp());
        assertNotNull(record.getMetadata());
        assertEquals("value", record.getMetadata().get("key"));
    }
}
