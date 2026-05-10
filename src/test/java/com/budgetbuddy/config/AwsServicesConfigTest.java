package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Unit Tests for AwsServicesConfig Tests AWS service client configuration including LocalStack
 * support
 */
class AwsServicesConfigTest {

    private static final String US_EAST_1 = "us-east-1";
    private static final String ACCESSKEYID = "accessKeyId";
    private static final String AWSREGION = "awsRegion";
    private static final String SECRETACCESSKEY = "secretAccessKey";

    private AwsServicesConfig awsServicesConfig = new AwsServicesConfig();

    @Test
    void testCloudWatchClientWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, "cloudWatchEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CloudWatchClient client = awsServicesConfig.cloudWatchClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCloudWatchClientWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(
                awsServicesConfig, "cloudWatchEndpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CloudWatchClient client = awsServicesConfig.cloudWatchClient();

        // Then
        assertNotNull(client);
        final URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testSecretsManagerClientWithDefaultRegion() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, "secretsManagerEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final SecretsManagerClient client = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testSecretsManagerClientWithLocalStackEndpoint() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(
                awsServicesConfig, "secretsManagerEndpoint", "http://localhost:4566");
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final SecretsManagerClient client = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(client);
        final URI endpoint = client.serviceClientConfiguration().endpointOverride().orElse(null);
        assertNotNull(endpoint);
        assertEquals("http://localhost:4566", endpoint.toString());
    }

    @Test
    void testCloudTrailClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CloudTrailClient client = awsServicesConfig.cloudTrailClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCloudFormationClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CloudFormationClient client = awsServicesConfig.cloudFormationClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCodePipelineClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CodePipelineClient client = awsServicesConfig.codePipelineClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testCognitoIdentityProviderClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final CognitoIdentityProviderClient client =
                awsServicesConfig.cognitoIdentityProviderClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testKmsClientIsCreated() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "");

        // When
        final KmsClient client = awsServicesConfig.kmsClient();

        // Then
        assertNotNull(client);
        assertEquals(US_EAST_1, client.serviceClientConfiguration().region().id());
    }

    @Test
    void testClientsWithStaticCredentials() {
        // Given
        ReflectionTestUtils.setField(awsServicesConfig, AWSREGION, US_EAST_1);
        ReflectionTestUtils.setField(awsServicesConfig, "cloudWatchEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, "secretsManagerEndpoint", "");
        ReflectionTestUtils.setField(awsServicesConfig, ACCESSKEYID, "test-key");
        ReflectionTestUtils.setField(awsServicesConfig, SECRETACCESSKEY, "test-secret");

        // When
        final CloudWatchClient cloudWatchClient = awsServicesConfig.cloudWatchClient();
        final SecretsManagerClient secretsManagerClient = awsServicesConfig.secretsManagerClient();

        // Then
        assertNotNull(cloudWatchClient);
        assertNotNull(secretsManagerClient);
    }
}
