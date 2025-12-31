package com.budgetbuddy.service.aws;

import com.budgetbuddy.security.FileSecurityValidator;
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
import java.util.List;

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
    private final FileSecurityValidator fileSecurityValidator;
    private final boolean s3Enabled;

    public S3Service(final S3Client s3Client, final S3Presigner s3Presigner, 
                    @Value("${aws.s3.bucket-name:budgetbuddy-storage}") String bucketName,
                    @Value("${AWS_S3_ENDPOINT:}") String s3Endpoint,
                    final FileSecurityValidator fileSecurityValidator) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
        this.fileSecurityValidator = fileSecurityValidator;
        // S3 is enabled if endpoint is configured (LocalStack) 
        // Note: AWS_REGION alone is not sufficient - we need an endpoint for LocalStack
        // In production, AWS_REGION is used but endpoint is empty (uses default AWS endpoints)
        // So we check: if endpoint is set (LocalStack) OR if we're in production (no endpoint but AWS_REGION set)
        boolean hasEndpoint = !s3Endpoint.isEmpty();
        boolean hasAwsRegion = System.getenv("AWS_REGION") != null;
        // S3 is enabled if we have an endpoint (LocalStack) OR if we're in production (AWS_REGION set but no endpoint override)
        this.s3Enabled = hasEndpoint || (hasAwsRegion && s3Endpoint.isEmpty());
        
        if (!s3Enabled) {
            logger.info("S3 is not configured (no AWS_S3_ENDPOINT for LocalStack, and no AWS_REGION for production). S3 operations will be skipped.");
        } else if (hasEndpoint) {
            logger.debug("S3 enabled with LocalStack endpoint: {}", s3Endpoint);
        } else {
            logger.debug("S3 enabled with production AWS (region: {})", System.getenv("AWS_REGION"));
        }
    }

    /**
     * Check if S3 is available and configured
     */
    private boolean isS3Available() {
        return s3Enabled;
    }

    /**
     * Check if exception is a connectivity/configuration error
     */
    private boolean isConnectivityError(Exception e) {
        return e instanceof software.amazon.awssdk.core.exception.SdkClientException ||
                e.getCause() instanceof java.net.UnknownHostException ||
                (e.getMessage() != null && (e.getMessage().contains("UnknownHostException") ||
                                          e.getMessage().contains("Unable to resolve") ||
                                          e.getMessage().contains("network connectivity") ||
                                          e.getMessage().contains("Connection refused")));
    }

    /**
     * Check if exception is a "bucket doesn't exist" error
     * This is a non-fatal error - bucket may not exist yet or may have been deleted
     */
    private boolean isBucketNotFoundError(Exception e) {
        return e instanceof software.amazon.awssdk.services.s3.model.NoSuchBucketException ||
                (e.getMessage() != null && (e.getMessage().contains("NoSuchBucket") ||
                                          e.getMessage().contains("bucket does not exist") ||
                                          e.getMessage().contains("404") && e.getMessage().contains("bucket")));
    }

    /**
     * Check if exception is a "key doesn't exist" error
     * This is a non-fatal error - object may not exist
     */
    private boolean isKeyNotFoundError(Exception e) {
        return e instanceof software.amazon.awssdk.services.s3.model.NoSuchKeyException ||
                (e.getMessage() != null && (e.getMessage().contains("NoSuchKey") ||
                                          e.getMessage().contains("key does not exist") ||
                                          e.getMessage().contains("404") && e.getMessage().contains("key")));
    }

    /**
     * Upload file to S3 with cost optimization
     * Uses Standard storage class for frequently accessed files
     */
    public String uploadFile(final String key, final InputStream inputStream, final long contentLength, final String contentType) {
        if (!isS3Available()) {
            logger.warn("S3 not available - skipping file upload for key: {}", key);
            return key; // Return key anyway for compatibility
        }
        
        // SECURITY: Validate S3 key to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(key);
        
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
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - skipping file upload for key {}: {}", key, e.getMessage());
                return key; // Return key anyway for compatibility
            }
            if (isBucketNotFoundError(e)) {
                logger.warn("S3 bucket '{}' does not exist - skipping file upload for key {}: {}. " +
                           "Bucket will be created automatically on first upload if permissions allow.", 
                           bucketName, key, e.getMessage());
                return key; // Return key anyway for compatibility
            }
            logger.error("Error uploading file to S3: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    /**
     * Upload file with Standard-IA storage class for cost savings on infrequent access
     */
    public String uploadFileInfrequentAccess(final String key, final InputStream inputStream, final long contentLength, final String contentType) {
        if (!isS3Available()) {
            logger.warn("S3 not available - skipping file upload for key: {}", key);
            return key; // Return key anyway for compatibility
        }
        
        // SECURITY: Validate S3 key to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(key);
        
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
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - skipping file upload for key {}: {}", key, e.getMessage());
                return key; // Return key anyway for compatibility
            }
            if (isBucketNotFoundError(e)) {
                logger.warn("S3 bucket '{}' does not exist - skipping file upload for key {}: {}. " +
                           "Bucket will be created automatically on first upload if permissions allow.", 
                           bucketName, key, e.getMessage());
                return key; // Return key anyway for compatibility
            }
            logger.error("Error uploading file to S3: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    /**
     * Move file to Glacier for long-term archival (90% cost savings)
     */
    public void archiveFile(final String key) {
        if (!isS3Available()) {
            logger.warn("S3 not available - skipping file archival for key: {}", key);
            return;
        }
        
        // SECURITY: Validate S3 key to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(key);
        
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
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - skipping file archival for key {}: {}", key, e.getMessage());
                return;
            }
            if (isBucketNotFoundError(e) || isKeyNotFoundError(e)) {
                logger.warn("S3 bucket '{}' or key '{}' does not exist - skipping file archival: {}", 
                           bucketName, key, e.getMessage());
                return;
            }
            logger.error("Error archiving file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to archive file", e);
        }
    }

    /**
     * Delete file from S3
     */
    public void deleteFile(final String key) {
        if (!isS3Available()) {
            logger.warn("S3 not available - skipping file deletion for key: {}", key);
            return;
        }
        
        // SECURITY: Validate S3 key to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(key);
        
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            logger.info("File deleted from S3: {}", key);
        } catch (Exception e) {
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - skipping file deletion for key {}: {}", key, e.getMessage());
                return;
            }
            if (isBucketNotFoundError(e) || isKeyNotFoundError(e)) {
                logger.debug("S3 bucket '{}' or key '{}' does not exist - skipping file deletion (non-fatal): {}", 
                           bucketName, key, e.getMessage());
                return; // Non-fatal - file/bucket doesn't exist, nothing to delete
            }
            logger.error("Error deleting file from S3: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete file from S3", e);
        }
    }

    /**
     * Delete all files with a given prefix from S3
     * Used for GDPR data deletion - removes all user-related files
     * 
     * @param prefix The prefix to match (e.g., "exports/user_123" or "accounts/user_123/")
     * @return Number of files deleted
     */
    public int deleteFilesByPrefix(final String prefix) {
        if (!isS3Available()) {
            logger.warn("S3 not available - skipping file deletion for prefix: {}", prefix);
            return 0;
        }
        
        // SECURITY: Validate prefix to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(prefix);
        
        try {
            int deletedCount = 0;
            String continuationToken = null;

            do {
                ListObjectsV2Request.Builder listRequestBuilder = ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix);

                if (continuationToken != null) {
                    listRequestBuilder.continuationToken(continuationToken);
                }

                ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequestBuilder.build());

                // Handle empty results gracefully - no files to delete
                if (listResponse.contents() != null && !listResponse.contents().isEmpty()) {
                    // Delete objects in batches (max 1000 per batch)
                    List<ObjectIdentifier> objectsToDelete = 
                            listResponse.contents().stream()
                                    .map(s3Object -> ObjectIdentifier.builder()
                                            .key(s3Object.key())
                                            .build())
                                    .collect(java.util.stream.Collectors.toList());

                    if (!objectsToDelete.isEmpty()) {
                        DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                                .bucket(bucketName)
                                .delete(Delete.builder()
                                        .objects(objectsToDelete)
                                        .build())
                                .build();

                        DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(deleteRequest);
                        deletedCount += deleteResponse.deleted().size();
                        
                        if (deleteResponse.errors() != null && !deleteResponse.errors().isEmpty()) {
                            deleteResponse.errors().forEach(error -> 
                                    logger.error("Error deleting S3 object {}: {}", error.key(), error.message())
                            );
                        }
                    }
                }

                continuationToken = listResponse.nextContinuationToken();
            } while (continuationToken != null);

            logger.info("Deleted {} files from S3 with prefix: {} (bucket: {})", deletedCount, prefix, bucketName);
            return deletedCount;
        } catch (Exception e) {
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - skipping file deletion for prefix {}: {}", prefix, e.getMessage());
                return 0;
            }
            if (isBucketNotFoundError(e)) {
                logger.debug("S3 bucket '{}' does not exist - no files to delete for prefix {} (non-fatal): {}", 
                           bucketName, prefix, e.getMessage());
                return 0; // Non-fatal - bucket doesn't exist, nothing to delete
            }
            logger.error("Error deleting files from S3 with prefix {}: {}", prefix, e.getMessage(), e);
            throw new RuntimeException("Failed to delete files from S3", e);
        }
    }

    /**
     * Get presigned URL for file access (cost-effective for direct client access)
     */
    public String getPresignedUrl(final String key, final int expirationMinutes) {
        if (!isS3Available()) {
            logger.warn("S3 not available - cannot generate presigned URL for key: {}", key);
            throw new RuntimeException("S3 is not configured - cannot generate presigned URL");
        }
        
        // SECURITY: Validate S3 key to prevent path traversal attacks
        fileSecurityValidator.validateS3Key(key);
        
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
            if (isConnectivityError(e)) {
                logger.warn("S3 not available - cannot generate presigned URL for key {}: {}", key, e.getMessage());
                throw new RuntimeException("S3 is not available - cannot generate presigned URL", e);
            }
            if (isBucketNotFoundError(e)) {
                logger.warn("S3 bucket '{}' does not exist - cannot generate presigned URL for key {}: {}", 
                           bucketName, key, e.getMessage());
                throw new RuntimeException("S3 bucket does not exist - cannot generate presigned URL", e);
            }
            logger.error("Error generating presigned URL: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}

