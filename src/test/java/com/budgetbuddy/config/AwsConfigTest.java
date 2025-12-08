package com.budgetbuddy.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for AwsConfig
 * Tests AWS client configuration including LocalStack support
 */
class AwsConfigTest {

    private AwsConfig awsConfig = new AwsConfig();

    @Test
    void testS3Client_WithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testS3Client_WithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testS3Client_WithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "test-secret");

        // When
        S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        // Verify credentials provider is set (can't directly check, but client should be created)
    }

    @Test
    void testS3Presigner_WithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-west-2");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
        // S3Presigner doesn't expose serviceClientConfiguration, but we can verify it's created
    }

    @Test
    void testS3Presigner_WithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
        // S3Presigner doesn't expose endpoint configuration directly, but we can verify it's created
    }

    @Test
    void testS3Presigner_WithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "test-secret");

        // When
        S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
    }
}

