package com.budgetbuddy;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.net.URI;

/**
 * Comprehensive AWS Test Configuration
 * Provides mock AWS clients for testing without requiring real AWS credentials
 * 
 * This configuration is automatically used when the "test" profile is active.
 * Uses @Primary to override production AWS client beans.
 */
@TestConfiguration
@Profile("test")
public class AWSTestConfiguration {

    private static final String LOCALSTACK_ENDPOINT = "http://localhost:4566";
    private static final Region TEST_REGION = Region.US_EAST_1;
    private static final AwsBasicCredentials TEST_CREDENTIALS = 
            AwsBasicCredentials.create("test", "test");

    /**
     * DynamoDB Client pointing to LocalStack
     */
    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * DynamoDB Enhanced Client
     */
    @Bean
    @Primary
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    /**
     * S3 Client pointing to LocalStack
     */
    @Bean
    @Primary
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * Secrets Manager Client pointing to LocalStack
     */
    @Bean
    @Primary
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * CloudWatch Client - Mocked since CloudWatch is disabled in tests
     */
    @Bean
    @Primary
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * S3 Presigner - Required by some services
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner() {
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * CloudTrail Client - Required by CloudTrailService
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cloudtrail.CloudTrailClient cloudTrailClient() {
        return software.amazon.awssdk.services.cloudtrail.CloudTrailClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * CloudFormation Client
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cloudformation.CloudFormationClient cloudFormationClient() {
        return software.amazon.awssdk.services.cloudformation.CloudFormationClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * CodePipeline Client
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.codepipeline.CodePipelineClient codePipelineClient() {
        return software.amazon.awssdk.services.codepipeline.CodePipelineClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * Cognito Identity Provider Client
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * KMS Client
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.kms.KmsClient kmsClient() {
        return software.amazon.awssdk.services.kms.KmsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * SNS Client - Required by NotificationService
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.sns.SnsClient snsClient() {
        return software.amazon.awssdk.services.sns.SnsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * SES Client - Required by EmailNotificationService
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.ses.SesClient sesClient() {
        return software.amazon.awssdk.services.ses.SesClient.builder()
                .endpointOverride(URI.create(LOCALSTACK_ENDPOINT))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * AppConfig Client - Not provided in test config
     * AppConfigIntegration will handle this via @ConditionalOnProperty
     */
    // Note: We don't provide AppConfigClient here because AppConfigIntegration
    // uses @ConditionalOnProperty to prevent bean creation when disabled

    /**
     * AppConfig Data Client - Not provided in test config
     */
    // Note: We don't provide AppConfigDataClient here because AppConfigIntegration
    // uses @ConditionalOnProperty to prevent bean creation when disabled

}

