package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS Configuration
 * Optimized for cost: uses regional endpoints, connection pooling, and minimal resources
 * Supports LocalStack for local development (matches production architecture)
 * 
 * Note: SecretsManagerClient is defined in AwsServicesConfig to support LocalStack endpoints
 * Note: CloudWatchClient is defined in AwsServicesConfig to avoid duplicate bean definition
 */
@Configuration
@org.springframework.context.annotation.Profile("!test") // Don't load in tests - use AWSTestConfiguration instead
public class AwsConfig {

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${AWS_S3_ENDPOINT:}")
    private String s3Endpoint;

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
        // For production, use default credentials provider (IAM role or env vars)
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider());
        
        // Use LocalStack endpoint if configured
        if (!s3Endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(s3Endpoint));
        }
        
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(getCredentialsProvider());
        
        // Use LocalStack endpoint if configured
        if (!s3Endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(s3Endpoint));
        }
        
        return builder.build();
    }
}

