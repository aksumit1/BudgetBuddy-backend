package com.budgetbuddy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileIntegrityServiceTest {

    private FileIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new FileIntegrityService();
    }

    @Test
    void testCalculateChecksum_ShouldReturnConsistentHash() {
        // Given
        String content = "test content for checksum";
        InputStream inputStream1 = new ByteArrayInputStream(content.getBytes());
        InputStream inputStream2 = new ByteArrayInputStream(content.getBytes());
        
        // When
        String checksum1 = service.calculateChecksum(inputStream1);
        String checksum2 = service.calculateChecksum(inputStream2);
        
        // Then
        assertNotNull(checksum1);
        assertNotNull(checksum2);
        assertEquals(checksum1, checksum2); // Same content should produce same checksum
    }

    @Test
    void testCalculateChecksum_DifferentContent_ShouldReturnDifferentHash() {
        // Given
        String content1 = "test content 1";
        String content2 = "test content 2";
        InputStream inputStream1 = new ByteArrayInputStream(content1.getBytes());
        InputStream inputStream2 = new ByteArrayInputStream(content2.getBytes());
        
        // When
        String checksum1 = service.calculateChecksum(inputStream1);
        String checksum2 = service.calculateChecksum(inputStream2);
        
        // Then
        assertNotEquals(checksum1, checksum2); // Different content should produce different checksum
    }

    @Test
    void testCalculateChecksum_EmptyContent_ShouldReturnHash() {
        // Given
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        
        // When
        String checksum = service.calculateChecksum(inputStream);
        
        // Then
        assertNotNull(checksum);
        assertFalse(checksum.isEmpty());
    }

    @Test
    void testStoreChecksum_ShouldStore() {
        // Given
        String fileId = "test-file-123";
        String checksum = "test-checksum";
        
        // When
        service.storeChecksum(fileId, checksum, null);
        
        // Then
        FileIntegrityService.ChecksumRecord record = service.getChecksum(fileId);
        assertNotNull(record);
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
    }

    @Test
    void testStoreChecksum_WithMetadata_ShouldStore() {
        // Given
        String fileId = "test-file-456";
        String checksum = "test-checksum-2";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("size", 1024L);
        metadata.put("uploadTime", System.currentTimeMillis());
        
        // When
        service.storeChecksum(fileId, checksum, metadata);
        
        // Then
        FileIntegrityService.ChecksumRecord record = service.getChecksum(fileId);
        assertNotNull(record);
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
        assertNotNull(record.getMetadata());
    }

    @Test
    void testVerifyIntegrity_MatchingChecksum_ShouldReturnTrue() {
        // Given
        String fileId = "test-file-789";
        String content = "test content";
        InputStream inputStream1 = new ByteArrayInputStream(content.getBytes());
        String checksum = service.calculateChecksum(inputStream1);
        service.storeChecksum(fileId, checksum, null);
        
        // When - Verify with same content
        InputStream inputStream2 = new ByteArrayInputStream(content.getBytes());
        boolean verified = service.verifyIntegrity(fileId, inputStream2);
        
        // Then
        assertTrue(verified);
    }

    @Test
    void testVerifyIntegrity_NonMatchingChecksum_ShouldReturnFalse() {
        // Given
        String fileId = "test-file-abc";
        String content1 = "test content 1";
        InputStream inputStream1 = new ByteArrayInputStream(content1.getBytes());
        String checksum = service.calculateChecksum(inputStream1);
        service.storeChecksum(fileId, checksum, null);
        
        // When - Verify with different content
        String content2 = "test content 2";
        InputStream inputStream2 = new ByteArrayInputStream(content2.getBytes());
        boolean verified = service.verifyIntegrity(fileId, inputStream2);
        
        // Then
        assertFalse(verified);
    }

    @Test
    void testVerifyIntegrity_NoStoredChecksum_ShouldReturnFalse() {
        // Given
        String fileId = "non-existent-file";
        InputStream inputStream = new ByteArrayInputStream("content".getBytes());
        
        // When
        boolean verified = service.verifyIntegrity(fileId, inputStream);
        
        // Then
        assertFalse(verified);
    }

    @Test
    void testGetChecksum_NonExistent_ShouldReturnNull() {
        // When
        FileIntegrityService.ChecksumRecord record = service.getChecksum("non-existent");
        
        // Then
        assertNull(record);
    }

    @Test
    void testRemoveChecksum_ShouldRemove() {
        // Given
        String fileId = "test-file-remove";
        service.storeChecksum(fileId, "checksum", null);
        
        // When
        service.removeChecksum(fileId);
        
        // Then
        assertNull(service.getChecksum(fileId));
    }

    @Test
    void testChecksumRecord_Getters() {
        // Given
        String fileId = "test-id";
        String checksum = "test-checksum";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        long timestamp = System.currentTimeMillis();
        
        // When
        FileIntegrityService.ChecksumRecord record = 
                new FileIntegrityService.ChecksumRecord(fileId, checksum, metadata, timestamp);
        
        // Then
        assertEquals(fileId, record.getFileId());
        assertEquals(checksum, record.getChecksum());
        assertEquals(timestamp, record.getTimestamp());
        assertNotNull(record.getMetadata());
        assertEquals("value", record.getMetadata().get("key"));
    }
}

