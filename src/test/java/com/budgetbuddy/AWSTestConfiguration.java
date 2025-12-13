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

    private static final Region TEST_REGION = Region.US_EAST_1;
    private static final AwsBasicCredentials TEST_CREDENTIALS = 
            AwsBasicCredentials.create("test", "test");

    /**
     * Get DynamoDB endpoint from environment variable, system property, or default
     * Called at runtime (not class load time) to ensure environment variables are available
     */
    private static String getDynamoDbEndpoint() {
        // Check system property first (can be set via -Daws.dynamodb.endpoint=...)
        String endpoint = System.getProperty("aws.dynamodb.endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Check environment variable
        endpoint = System.getenv("DYNAMODB_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Default to localhost for LocalStack
        return "http://localhost:4566";
    }

    /**
     * DynamoDB Client pointing to LocalStack (or configured endpoint)
     */
    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        String endpoint = getDynamoDbEndpoint();
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
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
     * S3 Client pointing to LocalStack (or configured endpoint)
     */
    @Bean
    @Primary
    public S3Client s3Client() {
        String endpoint = getDynamoDbEndpoint();
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * Secrets Manager Client pointing to LocalStack (or configured endpoint)
     */
    @Bean
    @Primary
    public SecretsManagerClient secretsManagerClient() {
        String endpoint = getDynamoDbEndpoint();
        return SecretsManagerClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return CloudWatchClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cloudtrail.CloudTrailClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cloudformation.CloudFormationClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.codepipeline.CodePipelineClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.kms.KmsClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.sns.SnsClient.builder()
                .endpointOverride(URI.create(endpoint))
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
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.ses.SesClient.builder()
                .endpointOverride(URI.create(endpoint))
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

    /**
     * IAM Client - For testing IAM roles and policies
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.iam.IamClient iamClient() {
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.iam.IamClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * ACM Client - For testing SSL certificates
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.acm.AcmClient acmClient() {
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.acm.AcmClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * CodeBuild Client - For testing CI/CD build projects
     */
    @Bean
    @Primary
    public software.amazon.awssdk.services.codebuild.CodeBuildClient codeBuildClient() {
        String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.codebuild.CodeBuildClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

}

