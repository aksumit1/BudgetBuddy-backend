package com.budgetbuddy.service.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for S3Service
 * Tests S3 file operations including upload, archive, and presigned URLs
 */
@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @InjectMocks
    private S3Service s3Service;

    private String testBucketName = "test-bucket";
    private String testKey = "test-file.txt";
    private InputStream testInputStream;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(s3Client, s3Presigner, testBucketName);
        testInputStream = new ByteArrayInputStream("test content".getBytes());
    }

    @Test
    void testUploadFile_WithValidInput_UploadsSuccessfully() {
        // Given
        String contentType = "text/plain";
        long contentLength = 12L;
        PutObjectResponse response = PutObjectResponse.builder().build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(response);

        // When
        String result = s3Service.uploadFile(testKey, testInputStream, contentLength, contentType);

        // Then
        assertEquals(testKey, result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testUploadFile_WithException_ThrowsRuntimeException() {
        // Given
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            s3Service.uploadFile(testKey, testInputStream, 12L, "text/plain");
        });
    }

    @Test
    void testUploadFileInfrequentAccess_WithValidInput_UploadsWithIAStorage() {
        // Given
        String contentType = "text/plain";
        long contentLength = 12L;
        PutObjectResponse response = PutObjectResponse.builder().build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(response);

        // When
        String result = s3Service.uploadFileInfrequentAccess(testKey, testInputStream, contentLength, contentType);

        // Then
        assertEquals(testKey, result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testArchiveFile_WithValidKey_ArchivesToGlacier() {
        // Given
        CopyObjectResponse copyResponse = CopyObjectResponse.builder().build();
        DeleteObjectResponse deleteResponse = DeleteObjectResponse.builder().build();
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(copyResponse);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(deleteResponse);

        // When
        s3Service.archiveFile(testKey);

        // Then
        verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void testArchiveFile_WithException_ThrowsRuntimeException() {
        // Given
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            s3Service.archiveFile(testKey);
        });
    }

    @Test
    void testDeleteFile_WithValidKey_DeletesFile() {
        // Given
        DeleteObjectResponse response = DeleteObjectResponse.builder().build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(response);

        // When
        s3Service.deleteFile(testKey);

        // Then
        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void testDeleteFile_WithException_ThrowsRuntimeException() {
        // Given
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            s3Service.deleteFile(testKey);
        });
    }

    @Test
    void testGetPresignedUrl_WithValidInput_ReturnsUrl() throws Exception {
        // Given
        int expirationMinutes = 60;
        String expectedUrl = "https://test-bucket.s3.amazonaws.com/test-file.txt";
        
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignGetObject(any(java.util.function.Consumer.class))).thenReturn(presignedRequest);

        // When
        String result = s3Service.getPresignedUrl(testKey, expirationMinutes);

        // Then
        assertNotNull(result);
        assertEquals(expectedUrl, result);
        verify(s3Presigner, times(1)).presignGetObject(any(java.util.function.Consumer.class));
    }

    @Test
    void testGetPresignedUrl_WithException_ThrowsRuntimeException() {
        // Given
        when(s3Presigner.presignGetObject(any(java.util.function.Consumer.class)))
                .thenThrow(new RuntimeException("Presigner error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            s3Service.getPresignedUrl(testKey, 60);
        });
    }
}

