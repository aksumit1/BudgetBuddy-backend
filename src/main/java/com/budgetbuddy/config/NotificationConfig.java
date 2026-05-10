package com.budgetbuddy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

/** Notification Services Configuration */
@Configuration
@org.springframework.context.annotation.Profile(
        "!test") // Don't load in tests - use AWSTestConfiguration instead
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
public class NotificationConfig {

    private AwsCredentialsProvider getCredentialsProvider() {
        try {
            return InstanceProfileCredentialsProvider.create();
        } catch (Exception e) {
            return DefaultCredentialsProvider.create();
        }
    }

    @Bean
    public SnsClient snsClient(
            @org.springframework.beans.factory.annotation.Value("${app.aws.region:us-east-1}")
                    final String region) {
        return SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public SesClient sesClient(
            @org.springframework.beans.factory.annotation.Value("${app.aws.region:us-east-1}")
                    final String region) {
        return SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }
}
