package com.budgetbuddy;

import com.budgetbuddy.plaid.PlaidService;
import com.plaid.client.model.LinkTokenCreateResponse;
import java.net.URI;
import java.time.OffsetDateTime;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Comprehensive AWS Test Configuration Provides mock AWS clients for testing without requiring real
 * AWS credentials
 *
 * <p>This configuration is automatically used when the "test" profile is active. Uses @Primary to
 * override production AWS client beans.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@TestConfiguration
@Profile("test")
public class AWSTestConfiguration {

    private static final Region TEST_REGION = Region.US_EAST_1;
    private static final AwsBasicCredentials TEST_CREDENTIALS =
            AwsBasicCredentials.create("test", "test");

    /**
     * Get DynamoDB endpoint from environment variable, system property, or default Called at
     * runtime (not class load time) to ensure environment variables are available
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
        // Default to LocalStack instance on port 4566 (can be overridden via environment variable
        // or system property)
        // For tests, use the same LocalStack instance as the main app to avoid needing separate
        // instances
        return "http://localhost:4566";
    }

    /**
     * Get S3 endpoint from environment variable, system property, or fallback to DynamoDB endpoint
     * Called at runtime (not class load time) to ensure environment variables are available
     */
    private static String getS3Endpoint() {
        // Check system property first (can be set via -Daws.s3.endpoint=...)
        String endpoint = System.getProperty("aws.s3.endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Check AWS_S3_ENDPOINT environment variable (used in docker-compose)
        endpoint = System.getenv("AWS_S3_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            return endpoint;
        }
        // Fallback to DynamoDB endpoint (usually same for LocalStack)
        return getDynamoDbEndpoint();
    }

    /** DynamoDB Client pointing to LocalStack (or configured endpoint) */
    @Bean
    @Primary
    public DynamoDbClient dynamoDbClient() {
        final String endpoint = getDynamoDbEndpoint();
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** DynamoDB Enhanced Client */
    @Bean
    @Primary
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(final DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }

    /** S3 Client pointing to LocalStack (or configured endpoint) */
    @Bean
    @Primary
    public S3Client s3Client() {
        final String endpoint = getS3Endpoint();
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** Secrets Manager Client pointing to LocalStack (or configured endpoint) */
    @Bean
    @Primary
    public SecretsManagerClient secretsManagerClient() {
        final String endpoint = getDynamoDbEndpoint();
        return SecretsManagerClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** CloudWatch Client - Mocked since CloudWatch is disabled in tests */
    @Bean
    @Primary
    public CloudWatchClient cloudWatchClient() {
        final String endpoint = getDynamoDbEndpoint();
        return CloudWatchClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** S3 Presigner - Required by some services */
    @Bean
    @Primary
    public software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner() {
        final String endpoint = getS3Endpoint();
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** CloudTrail Client - Required by CloudTrailService */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cloudtrail.CloudTrailClient cloudTrailClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cloudtrail.CloudTrailClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** CloudFormation Client */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cloudformation.CloudFormationClient
            cloudFormationClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cloudformation.CloudFormationClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** CodePipeline Client */
    @Bean
    @Primary
    public software.amazon.awssdk.services.codepipeline.CodePipelineClient codePipelineClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.codepipeline.CodePipelineClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** Cognito Identity Provider Client */
    @Bean
    @Primary
    public software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
            cognitoIdentityProviderClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
                .builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** KMS Client */
    @Bean
    @Primary
    public software.amazon.awssdk.services.kms.KmsClient kmsClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.kms.KmsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** SNS Client - Required by NotificationService */
    @Bean
    @Primary
    public software.amazon.awssdk.services.sns.SnsClient snsClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.sns.SnsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** SES Client - Required by EmailNotificationService */
    @Bean
    @Primary
    public software.amazon.awssdk.services.ses.SesClient sesClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.ses.SesClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /*
     * AppConfig Client - Not provided in test config AppConfigIntegration will handle this
     * via @ConditionalOnProperty
     */
    // Note: We don't provide AppConfigClient here because AppConfigIntegration
    // uses @ConditionalOnProperty to prevent bean creation when disabled

    /* AppConfig Data Client - Not provided in test config */
    // Note: We don't provide AppConfigDataClient here because AppConfigIntegration
    // uses @ConditionalOnProperty to prevent bean creation when disabled

    /** IAM Client - For testing IAM roles and policies */
    @Bean
    @Primary
    public software.amazon.awssdk.services.iam.IamClient iamClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.iam.IamClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** ACM Client - For testing SSL certificates */
    @Bean
    @Primary
    public software.amazon.awssdk.services.acm.AcmClient acmClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.acm.AcmClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /** CodeBuild Client - For testing CI/CD build projects */
    @Bean
    @Primary
    public software.amazon.awssdk.services.codebuild.CodeBuildClient codeBuildClient() {
        final String endpoint = getDynamoDbEndpoint();
        return software.amazon.awssdk.services.codebuild.CodeBuildClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(TEST_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(TEST_CREDENTIALS))
                .build();
    }

    /**
     * Mock PlaidService - Prevents ApplicationContext failures when Plaid credentials are not
     * configured This bean overrides the real PlaidService during tests to avoid initialization
     * errors.
     *
     * <p>Note: The real PlaidService is still created but this @Primary bean takes precedence. To
     * prevent the real service from being created, we rely on PlaidService's constructor handling
     * missing credentials gracefully (it uses placeholders instead of throwing).
     */
    @Bean
    @Primary
    public PlaidService plaidService(
            final com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService pciDSSComplianceService) {
        // Create a mock that will be used instead of the real PlaidService
        final PlaidService mockPlaidService = Mockito.mock(PlaidService.class);
        // Mock createLinkToken to return a valid response
        final LinkTokenCreateResponse mockResponse = new LinkTokenCreateResponse();
        mockResponse.setLinkToken("test-link-token-" + System.currentTimeMillis());
        mockResponse.setExpiration(OffsetDateTime.now().plusHours(1));
        try {
            Mockito.when(mockPlaidService.createLinkToken(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(mockResponse);
        } catch (Exception e) {
            // Ignore - this is just for mocking
        }
        return mockPlaidService;
    }

    /**
     * Test DynamoDBTableManager - No-op bean to satisfy @DependsOn dependency
     *
     * <p>CRITICAL: UserRepository depends on "dynamoDBTableManager" via @DependsOn. Since
     * DynamoDBTableManager is disabled in test profile, we provide a no-op bean here to satisfy the
     * dependency. Tests use TableInitializer instead for table initialization.
     */
    @Bean
    @Primary
    public com.budgetbuddy.service.dynamodb.DynamoDBTableManager dynamoDBTableManager(
            final DynamoDbClient dynamoDbClient) {
        // Return a no-op implementation that does nothing
        // Tests use TableInitializer for table initialization instead
        return Mockito.mock(com.budgetbuddy.service.dynamodb.DynamoDBTableManager.class);
    }
}
