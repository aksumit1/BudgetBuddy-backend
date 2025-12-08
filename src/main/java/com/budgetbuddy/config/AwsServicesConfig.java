package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;

/**
 * AWS Services Configuration
 * Configures all AWS service clients with IAM role authentication
 * Supports LocalStack for local development (matches production architecture)
 */
@Configuration
@org.springframework.context.annotation.Profile("!test") // Don't load in tests - use AWSTestConfiguration instead
public class AwsServicesConfig {

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${AWS_CLOUDWATCH_ENDPOINT:}")
    private String cloudWatchEndpoint;

    @Value("${AWS_SECRETS_MANAGER_ENDPOINT:}")
    private String secretsManagerEndpoint;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;

    /**
     * Credentials provider that uses IAM role in ECS/EKS, or static credentials for LocalStack
     */
    private AwsCredentialsProvider getCredentialsProvider() {
        // For LocalStack, use static credentials if provided
        if (!accessKeyId.isEmpty() && !secretAccessKey.isEmpty()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }
        // For production, use IAM role
        try {
            return InstanceProfileCredentialsProvider.create();
        } catch (Exception e) {
            return DefaultCredentialsProvider.create();
        }
    }

    @Bean(destroyMethod = "close")
    public CloudWatchClient cloudWatchClient() {
        var builder = CloudWatchClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider());
        
        // Use LocalStack endpoint if configured
        if (!cloudWatchEndpoint.isEmpty()) {
            builder.endpointOverride(URI.create(cloudWatchEndpoint));
        }
        
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public CloudTrailClient cloudTrailClient() {
        return CloudTrailClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloudFormationClient cloudFormationClient() {
        return CloudFormationClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean(destroyMethod = "close")
    public CodePipelineClient codePipelineClient() {
        return CodePipelineClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean(destroyMethod = "close")
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean(destroyMethod = "close")
    public KmsClient kmsClient() {
        return KmsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean(destroyMethod = "close")
    public SecretsManagerClient secretsManagerClient() {
        var builder = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider());
        
        // Use LocalStack endpoint if configured
        if (!secretsManagerEndpoint.isEmpty()) {
            builder.endpointOverride(URI.create(secretsManagerEndpoint));
        }
        
        return builder.build();
    }
    
    // Note: AppConfigClient and AppConfigDataClient are defined in AppConfigIntegration
    // to avoid duplicate bean definitions. AppConfigIntegration handles AppConfig-specific
    // configuration including conditional enabling and LocalStack endpoint support.
}

