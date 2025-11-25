package com.budgetbuddy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Notification Services Configuration
 */
@Configuration
public class NotificationConfig {

    private DefaultCredentialsProvider getCredentialsProvider() {
        try {
            return InstanceProfileCredentialsProvider.create();
        } catch (Exception e) {
            return DefaultCredentialsProvider.create();
        }
    }

    @Bean
    public SnsClient snsClient(@org.springframework.beans.factory.annotation.Value("${app.aws.region:us-east-1}") String region) {
        return SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    @Bean
    public SesClient sesClient(@org.springframework.beans.factory.annotation.Value("${app.aws.region:us-east-1}") String region) {
        return SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }
}

