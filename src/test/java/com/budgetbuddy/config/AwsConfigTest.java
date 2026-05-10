package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Unit Tests for AwsConfig Tests AWS client configuration including LocalStack support */
class AwsConfigTest {

    private AwsConfig awsConfig = new AwsConfig();

    @Test
    void testS3ClientWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        final S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testS3ClientWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        final S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        final URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testS3ClientWithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "test-secret");

        // When
        final S3Client client = awsConfig.s3Client();

        // Then
        assertNotNull(client);
        // Verify credentials provider is set (can't directly check, but client should be created)
    }

    @Test
    void testS3PresignerWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-west-2");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        final S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
        // S3Presigner doesn't expose serviceClientConfiguration, but we can verify it's created
    }

    @Test
    void testS3PresignerWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "");

        // When
        final S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
        // S3Presigner doesn't expose endpoint configuration directly, but we can verify it's
        // created
    }

    @Test
    void testS3PresignerWithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsConfig, "s3Endpoint", "");
        ReflectionTestUtils.setField(awsConfig, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(awsConfig, "secretAccessKey", "test-secret");

        // When
        final S3Presigner presigner = awsConfig.s3Presigner();

        // Then
        assertNotNull(presigner);
    }
}
