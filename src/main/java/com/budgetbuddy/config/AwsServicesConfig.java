package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * AWS Services Configuration
 * Configures all AWS service clients with IAM role authentication
 */
@Configuration
public class AwsServicesConfig {

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Credentials provider that uses IAM role in ECS/EKS
     */
    private AwsCredentialsProvider getCredentialsProvider() {
        try {
            return InstanceProfileCredentialsProvider.create();
        } catch (Exception e) {
            return DefaultCredentialsProvider.create();
        }
    }

    @Bean
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public CloudTrailClient cloudTrailClient() {
        return CloudTrailClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public CloudFormationClient cloudFormationClient() {
        return CloudFormationClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public CodePipelineClient codePipelineClient() {
        return CodePipelineClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public KmsClient kmsClient() {
        return KmsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }
}

