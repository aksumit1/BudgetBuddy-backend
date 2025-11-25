package com.budgetbuddy.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
        // Per-minute limit (using new API without deprecated Refill)
        Bandwidth perMinuteLimit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
                .build();

        // Per-hour limit (using new API without deprecated Refill)
        Bandwidth perHourLimit = Bandwidth.builder()
                .capacity(requestsPerHour)
                .refillIntervally(requestsPerHour, Duration.ofHours(1))
                .build();

        return Bucket.builder()
                .addLimit(perMinuteLimit)
                .addLimit(perHourLimit)
                .build();
    }
}

