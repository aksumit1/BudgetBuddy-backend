package com.budgetbuddy.service.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * AWS S3 Service for file storage
 * Optimized for cost: uses S3 Standard-IA for infrequent access, lifecycle policies for archival
 */
@Service
public class S3Service {

    private static final Logger logger = LoggerFactory.getLogger(S3Service.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    public S3Service(final S3Client s3Client, final S3Presigner s3Presigner, @Value("${aws.s3.bucket-name:budgetbuddy-storage}") String bucketName) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
    }

    /**
     * Upload file to S3 with cost optimization
     * Uses Standard storage class for frequently accessed files
     */
    public String uploadFile(final String key, final InputStream inputStream, final long contentLength, final String contentType) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .storageClass(StorageClass.STANDARD) // Use Standard for active files
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, contentLength));
            logger.info("File uploaded to S3: {}", key);
            return key;
        } catch (Exception e) {
            logger.error("Error uploading file to S3: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    /**
     * Upload file with Standard-IA storage class for cost savings on infrequent access
     */
    public String uploadFileInfrequentAccess(final String key, final InputStream inputStream, final long contentLength, final String contentType) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .storageClass(StorageClass.STANDARD_IA) // 50% cost savings
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, contentLength));
            logger.info("File uploaded to S3 (IA): {}", key);
            return key;
        } catch (Exception e) {
            logger.error("Error uploading file to S3: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    /**
     * Move file to Glacier for long-term archival (90% cost savings)
     */
    public void archiveFile(final String key) {
        try {
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(key)
                    .destinationBucket(bucketName)
                    .destinationKey("archive/" + key)
                    .storageClass(StorageClass.GLACIER) // 90% cost savings for archival
                    .build();

            s3Client.copyObject(copyRequest);

            // Delete original
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteRequest);

            logger.info("File archived to Glacier: {}", key);
        } catch (Exception e) {
            logger.error("Error archiving file: {}", e.getMessage());
            throw new RuntimeException("Failed to archive file", e);
        }
    }

    /**
     * Delete file from S3
     */
    public void deleteFile(final String key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            logger.info("File deleted from S3: {}", key);
        } catch (Exception e) {
            logger.error("Error deleting file from S3: {}", e.getMessage());
            throw new RuntimeException("Failed to delete file from S3", e);
        }
    }

    /**
     * Get presigned URL for file access (cost-effective for direct client access)
     */
    public String getPresignedUrl(final String key, final int expirationMinutes) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(r -> r
                    .signatureDuration(java.time.Duration.ofMinutes(expirationMinutes))
                    .getObjectRequest(getObjectRequest));

            return presignedRequest.url().toString();
        } catch (Exception e) {
            logger.error("Error generating presigned URL: {}", e.getMessage());
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}

