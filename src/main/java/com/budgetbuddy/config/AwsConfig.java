package com.budgetbuddy.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * AWS Configuration Optimized for cost: uses regional endpoints, connection pooling, and minimal
 * resources Supports LocalStack for local development (matches production architecture)
 *
 * <p>Note: SecretsManagerClient is defined in AwsServicesConfig to support LocalStack endpoints
 * Note: CloudWatchClient is defined in AwsServicesConfig to avoid duplicate bean definition
 */
@Configuration
@org.springframework.context.annotation.Profile(
        "!test") // Don't load in tests - use AWSTestConfiguration instead
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
public class AwsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AwsConfig.class);

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${AWS_S3_ENDPOINT:}")
    private String s3Endpoint;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;

    /** Credentials provider that uses IAM role in ECS/EKS, or static credentials for LocalStack */
    private AwsCredentialsProvider getCredentialsProvider() {
        // For LocalStack, use static credentials if provided
        if (!accessKeyId.isEmpty() && !secretAccessKey.isEmpty()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        // For production, use default credentials provider (IAM role or env vars)
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        final var builder =
                S3Client.builder()
                        .region(Region.of(awsRegion))
                        .credentialsProvider(getCredentialsProvider());

        // Use LocalStack endpoint if configured
        // Also check environment variable directly as fallback (Spring @Value might not always map
        // env vars correctly)
        String endpoint = s3Endpoint;
        if (endpoint.isEmpty()) {
            endpoint = System.getenv("AWS_S3_ENDPOINT");
            if (endpoint == null) {
                endpoint = "";
            }
        }

        if (!endpoint.isEmpty()) {
            try {
                final URI endpointUri = URI.create(endpoint);
                builder.endpointOverride(endpointUri);
                // LocalStack requires path-based access style (not virtual-hosted)
                builder.serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "✅ S3Client configured with LocalStack endpoint: {} (path-style access enabled)",
                            endpoint);
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "⚠️ Failed to configure S3 endpoint override: {} - {}",
                            endpoint,
                            e.getMessage());
                }
            }
        }

        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        final var builder =
                S3Presigner.builder()
                        .region(Region.of(awsRegion))
                        .credentialsProvider(getCredentialsProvider());

        // Use LocalStack endpoint if configured
        // Also check environment variable directly as fallback (Spring @Value might not always map
        // env vars correctly)
        String endpoint = s3Endpoint;
        if (endpoint.isEmpty()) {
            endpoint = System.getenv("AWS_S3_ENDPOINT");
            if (endpoint == null) {
                endpoint = "";
            }
        }

        if (!endpoint.isEmpty()) {
            try {
                final URI endpointUri = URI.create(endpoint);
                builder.endpointOverride(endpointUri);
                // LocalStack requires path-based access style (not virtual-hosted)
                builder.serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "✅ S3Presigner configured with LocalStack endpoint: {} (path-style access enabled)",
                            endpoint);
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "⚠️ Failed to configure S3Presigner endpoint override: {} - {}",
                            endpoint,
                            e.getMessage());
                }
            }
        }

        return builder.build();
    }
}
