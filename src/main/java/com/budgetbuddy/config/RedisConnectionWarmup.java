package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Connection Warmup
 * Pre-warms the connection pool on application startup to avoid cold start delays
 * This ensures health checks are fast from the first request
 * 
 * Only runs if Redis is configured and available
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisConnectionWarmup implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(RedisConnectionWarmup.class);

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
            long startTime = System.currentTimeMillis();
            redisTemplate.getConnectionFactory().getConnection().ping();
            long duration = System.currentTimeMillis() - startTime;
            
            if (duration > 100) {
                logger.warn("Redis connection warmup took {}ms (expected < 100ms)", duration);
            } else {
                logger.debug("Redis connection pool warmed up in {}ms", duration);
            }
        } catch (Exception e) {
            // Check if this is a DNS resolution failure
            Throwable cause = e.getCause();
            boolean isDnsFailure = e.getMessage() != null && 
                                   (e.getMessage().contains("Name does not resolve") ||
                                    e.getMessage().contains("UnknownHostException") ||
                                    (cause != null && cause.getClass().getSimpleName().equals("UnknownHostException")));
            
            if (isDnsFailure) {
                // DNS resolution failure - clear DNS cache and retry once
                logger.debug("Redis DNS resolution failed, clearing DNS cache and retrying: {}", e.getMessage());
                clearDnsCache();
                try {
                    // Retry after clearing DNS cache
                    Thread.sleep(100); // Brief delay to allow DNS cache to clear
                    redisTemplate.getConnectionFactory().getConnection().ping();
                    logger.debug("Redis connection warmup succeeded after DNS cache clear");
                } catch (Exception retryException) {
                    logger.debug("Redis connection warmup failed after DNS cache clear: {} (this is OK if Redis is optional)", retryException.getMessage());
                }
            } else {
                logger.warn("Failed to warm up Redis connection pool: {} (this is OK if Redis is optional)", e.getMessage());
            }
            // Don't fail startup if Redis is unavailable - health check will handle it
        }
    }

    /**
     * Clear DNS cache to allow retry of failed DNS lookups
     * This helps when DNS resolution fails temporarily (e.g., during container startup)
     */
    private void clearDnsCache() {
        try {
            // Clear DNS cache by setting TTL to 0 temporarily
            String originalTtl = java.security.Security.getProperty("networkaddress.cache.ttl");
            String originalNegativeTtl = java.security.Security.getProperty("networkaddress.cache.negative.ttl");
            
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
            
            // Restore original TTL values
            java.security.Security.setProperty("networkaddress.cache.ttl", originalTtl != null ? originalTtl : "3600");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", originalNegativeTtl != null ? originalNegativeTtl : "1");
        } catch (Exception e) {
            logger.debug("Failed to clear DNS cache: {}", e.getMessage());
        }
    }
}

