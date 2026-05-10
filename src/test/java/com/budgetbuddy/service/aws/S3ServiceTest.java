package com.budgetbuddy.service.aws;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Unit Tests for S3Service Tests S3 file operations including upload, archive, and presigned URLs
 */
@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    @Mock private S3Client s3Client;

    @Mock private S3Presigner s3Presigner;

    @Mock private com.budgetbuddy.security.FileSecurityValidator fileSecurityValidator;

    // CRITICAL: Remove @InjectMocks - manually instantiate in setUp() to avoid constructor issues
    private S3Service s3Service;

    private String testBucketName = "test-bucket";
    private String testKey = "test-file.txt";
    private InputStream testInputStream;

    @BeforeEach
    void setUp() {
        // CRITICAL: Manually create S3Service to avoid @InjectMocks constructor issues with @Value
        // parameters
        // The constructor requires s3Endpoint which is null when using @InjectMocks
        // Set a dummy endpoint to enable S3 for testing (or set AWS_REGION env var)
        // Using "http://localhost:4566" (LocalStack default) to enable S3
        s3Service =
                new S3Service(
                        s3Client,
                        s3Presigner,
                        testBucketName,
                        "http://localhost:4566",
                        fileSecurityValidator);
        testInputStream = new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testUploadFileWithValidInputUploadsSuccessfully() {
        // Given
        final String contentType = "text/plain";
        final long contentLength = 12L;
        final PutObjectResponse response = PutObjectResponse.builder().build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(response);

        // When
        final String result = s3Service.uploadFile(testKey, testInputStream, contentLength, contentType);

        // Then
        assertEquals(testKey, result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testUploadFileWithExceptionThrowsRuntimeException() {
        // Given
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    s3Service.uploadFile(testKey, testInputStream, 12L, "text/plain");
                });
    }

    @Test
    void testUploadFileInfrequentAccessWithValidInputUploadsWithIAStorage() {
        // Given
        final String contentType = "text/plain";
        final long contentLength = 12L;
        final PutObjectResponse response = PutObjectResponse.builder().build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(response);

        // When
        final String result =
                s3Service.uploadFileInfrequentAccess(
                        testKey, testInputStream, contentLength, contentType);

        // Then
        assertEquals(testKey, result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testArchiveFileWithValidKeyArchivesToGlacier() {
        // Given
        final CopyObjectResponse copyResponse = CopyObjectResponse.builder().build();
        final DeleteObjectResponse deleteResponse = DeleteObjectResponse.builder().build();
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(copyResponse);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(deleteResponse);

        // When
        s3Service.archiveFile(testKey);

        // Then
        verify(s3Client, times(1)).copyObject(any(CopyObjectRequest.class));
        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void testArchiveFileWithExceptionThrowsRuntimeException() {
        // Given
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    s3Service.archiveFile(testKey);
                });
    }

    @Test
    void testDeleteFileWithValidKeyDeletesFile() {
        // Given
        final DeleteObjectResponse response = DeleteObjectResponse.builder().build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(response);

        // When
        s3Service.deleteFile(testKey);

        // Then
        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void testDeleteFileWithExceptionThrowsRuntimeException() {
        // Given
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    s3Service.deleteFile(testKey);
                });
    }

    @Test
    void testGetPresignedUrlWithValidInputReturnsUrl() throws Exception {
        // Given
        final int expirationMinutes = 60;
        final String expectedUrl = "https://test-bucket.s3.amazonaws.com/test-file.txt";

        final PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignGetObject(any(java.util.function.Consumer.class)))
                .thenReturn(presignedRequest);

        // When
        final String result = s3Service.getPresignedUrl(testKey, expirationMinutes);

        // Then
        assertNotNull(result);
        assertEquals(expectedUrl, result);
        verify(s3Presigner, times(1)).presignGetObject(any(java.util.function.Consumer.class));
    }

    @Test
    void testGetPresignedUrlWithExceptionThrowsRuntimeException() {
        // Given
        when(s3Presigner.presignGetObject(any(java.util.function.Consumer.class)))
                .thenThrow(new RuntimeException("Presigner error"));

        // When/Then
        assertThrows(
                RuntimeException.class,
                () -> {
                    s3Service.getPresignedUrl(testKey, 60);
                });
    }
}
