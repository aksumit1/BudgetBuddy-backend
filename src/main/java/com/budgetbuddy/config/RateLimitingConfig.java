package com.budgetbuddy.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Rate Limiting Configuration
 * Prevents DDoS and minimizes API costs
 */
@Configuration
public class RateLimitingConfig {

    @Value("${app.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${app.rate-limit.requests-per-hour:1000}")
    private int requestsPerHour;

    @Bean
    public Bucket defaultBucket() {
        // Per-minute limit
        Bandwidth perMinuteLimit = Bandwidth.classic(
                requestsPerMinute,
                Refill.intervally(requestsPerMinute, Duration.ofMinutes(1))
        );

        // Per-hour limit
        Bandwidth perHourLimit = Bandwidth.classic(
                requestsPerHour,
                Refill.intervally(requestsPerHour, Duration.ofHours(1))
        );

        return Bucket.builder()
                .addLimit(perMinuteLimit)
                .addLimit(perHourLimit)
                .build();
    }
}

