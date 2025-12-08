package com.budgetbuddy.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for AwsServicesConfig
 * Tests AWS service client configuration including LocalStack support
 */
class AwsServicesConfigTest {

    private AwsServicesConfig awsServicesConfig = new AwsServicesConfig();

    @Test
    void testCloudWatchClient_WithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "cloudWatchEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CloudWatchClient client = awsServicesConfig.cloudWatchClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCloudWatchClient_WithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "cloudWatchEndpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CloudWatchClient client = awsServicesConfig.cloudWatchClient();

        // Then
        assertNotNull(client);
        URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testSecretsManagerClient_WithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "secretsManagerEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        SecretsManagerClient client = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSecretsManagerClient_WithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "secretsManagerEndpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        SecretsManagerClient client = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(client);
        URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testCloudTrailClient_IsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CloudTrailClient client = awsServicesConfig.cloudTrailClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCloudFormationClient_IsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CloudFormationClient client = awsServicesConfig.cloudFormationClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCodePipelineClient_IsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CodePipelineClient client = awsServicesConfig.codePipelineClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCognitoIdentityProviderClient_IsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        CognitoIdentityProviderClient client = awsServicesConfig.cognitoIdentityProviderClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testKmsClient_IsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "");

        // When
        KmsClient client = awsServicesConfig.kmsClient();

        // Then
        assertNotNull(client);
        assertEquals("us-east-1", client.serviceClientConfiguration().region().id());
    }

    @Test
    void testClients_WithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(awsServicesConfig, "cloudWatchEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretsManagerEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(awsServicesConfig, "secretAccessKey", "test-secret");

        // When
        CloudWatchClient cloudWatchClient = awsServicesConfig.cloudWatchClient();
        SecretsManagerClient secretsManagerClient = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(cloudWatchClient);
        assertNotNull(secretsManagerClient);
    }
}

