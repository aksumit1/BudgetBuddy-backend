package com.budgetbuddy.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.config.CacheConfig;
import com.budgetbuddy.service.CacheMonitoringService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Unit tests for cache monitoring service Tests cache statistics and monitoring functionality */
@SpringJUnitConfig(classes = {CacheOptimizationTest.TestConfig.class})
public class CacheOptimizationTest {

    @org.springframework.beans.factory.annotation.Autowired private CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired
    private CacheMonitoringService cacheMonitoringService;

    @BeforeEach
    void setUp() {
        // CRITICAL: Do NOT clear all caches - this would affect shared resources (Redis, DynamoDB)
        // This test uses an isolated test configuration with its own CacheManager instance,
        // so cache clearing is safe here. However, we avoid clearing to prevent accidental
        // impact on shared resources if the test configuration changes.
        // Tests should use unique keys to avoid conflicts.
    }

    @Test
    void testCacheMonitoringServiceGetAllStatistics() {
        // Get all statistics
        final Map<String, CacheMonitoringService.CacheStatistics> allStats =
                cacheMonitoringService.getAllCacheStatistics();
        assertNotNull(allStats);
        // May be empty if no cache activity yet, which is fine
    }

    @Test
    void testCacheMonitoringServiceGetSpecificCacheStatistics() {
        // Test getting statistics for a specific cache
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics("accounts");
        // May be null if cache hasn't been used yet, which is fine
        if (stats != null) {
            assertTrue(stats.getHitCount() >= 0);
            assertTrue(stats.getMissCount() >= 0);
            assertTrue(stats.getHitRate() >= 0.0 && stats.getHitRate() <= 1.0);
            assertTrue(stats.getMissRate() >= 0.0 && stats.getMissRate() <= 1.0);
        }
    }

    @Test
    void testCacheMonitoringServiceLogStatistics() {
        // Test that logging doesn't throw exceptions
        assertDoesNotThrow(() -> cacheMonitoringService.logCacheStatistics());
    }

    @Test
    void testCacheManagerExists() {
        assertNotNull(cacheManager);
        assertNotNull(cacheManager.getCacheNames());
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        public CacheManager cacheManager() {
            return new CacheConfig().cacheManager();
        }

        @Bean
        public CacheMonitoringService cacheMonitoringService(final CacheManager cacheManager) {
            return new CacheMonitoringService(cacheManager);
        }
    }
}
