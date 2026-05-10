package com.budgetbuddy.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Connection Warmup Pre-warms the connection pool on application startup to avoid cold start
 * delays This ensures health checks are fast from the first request
 *
 * <p>Only runs if Redis is configured and available
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisConnectionWarmup implements CommandLineRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConnectionWarmup.class);

    private final StringRedisTemplate redisTemplate;

    public RedisConnectionWarmup(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(final String... args) {
        try {
            // Pre-warm connection pool by performing a simple operation
            // This establishes connections in the pool (up to min-idle: 2)
            // Subsequent health checks will reuse these connections (fast!)
            final long startTime = System.currentTimeMillis();
            redisTemplate.getConnectionFactory().getConnection().ping();
            final long duration = System.currentTimeMillis() - startTime;

            if (duration > 100) {
                LOGGER.warn("Redis connection warmup took {}ms (expected < 100ms)", duration);
            } else {
                LOGGER.debug("Redis connection pool warmed up in {}ms", duration);
            }
        } catch (Exception e) {
            // Check if this is a DNS resolution failure
            final Throwable cause = e.getCause();
            final boolean isDnsFailure =
                    e.getMessage() != null
                            && (e.getMessage().contains("Name does not resolve")
                            || e.getMessage().contains("UnknownHostException")
                            || (cause != null
                            && "UnknownHostException"
                            .equals(cause.getClass()
                            .getSimpleName())));

            if (isDnsFailure) {
                // DNS resolution failure - clear DNS cache and retry once
                LOGGER.debug(
                        "Redis DNS resolution failed, clearing DNS cache and retrying: {}",
                        e.getMessage());
                clearDnsCache();
                try {
                    // Retry after clearing DNS cache
                    Thread.sleep(100); // Brief delay to allow DNS cache to clear
                    redisTemplate.getConnectionFactory().getConnection().ping();
                    LOGGER.debug("Redis connection warmup succeeded after DNS cache clear");
                } catch (Exception retryException) {
                    LOGGER.debug(
                            "Redis connection warmup failed after DNS cache clear: {} (this is OK if Redis is optional)",
                            retryException.getMessage());
                }
            } else {
                LOGGER.warn(
                        "Failed to warm up Redis connection pool: {} (this is OK if Redis is optional)",
                        e.getMessage());
            }
            // Don't fail startup if Redis is unavailable - health check will handle it
        }
    }

    /**
     * Clear DNS cache to allow retry of failed DNS lookups This helps when DNS resolution fails
     * temporarily (e.g., during container startup)
     */
    private void clearDnsCache() {
        try {
            // Clear DNS cache by setting TTL to 0 temporarily
            final String originalTtl = java.security.Security.getProperty("networkaddress.cache.ttl");
            final String originalNegativeTtl =
                    java.security.Security.getProperty("networkaddress.cache.negative.ttl");

            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");

            // Restore original TTL values
            java.security.Security.setProperty(
                    "networkaddress.cache.ttl", originalTtl != null ? originalTtl : "3600");
            java.security.Security.setProperty(
                    "networkaddress.cache.negative.ttl",
                    originalNegativeTtl != null ? originalNegativeTtl : "1");
        } catch (Exception e) {
            LOGGER.debug("Failed to clear DNS cache: {}", e.getMessage());
        }
    }
}
