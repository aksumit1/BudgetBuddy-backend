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
            logger.warn("Failed to warm up Redis connection pool: {} (this is OK if Redis is optional)", e.getMessage());
            // Don't fail startup if Redis is unavailable - health check will handle it
        }
    }
}

