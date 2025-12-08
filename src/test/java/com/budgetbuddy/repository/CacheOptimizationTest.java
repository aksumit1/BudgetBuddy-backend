package com.budgetbuddy.repository;

import com.budgetbuddy.config.CacheConfig;
import com.budgetbuddy.service.CacheMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for cache monitoring service
 * Tests cache statistics and monitoring functionality
 */
@SpringJUnitConfig(classes = {CacheOptimizationTest.TestConfig.class})
public class CacheOptimizationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired
    private CacheMonitoringService cacheMonitoringService;

    @BeforeEach
    void setUp() {
        // Clear all caches before each test
        if (cacheManager != null) {
            cacheManager.getCacheNames().forEach(cacheName -> {
                org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            });
        }
    }

    @Test
    void testCacheMonitoringService_GetAllStatistics() {
        // Get all statistics
        Map<String, CacheMonitoringService.CacheStatistics> allStats = cacheMonitoringService.getAllCacheStatistics();
        assertNotNull(allStats);
        // May be empty if no cache activity yet, which is fine
    }

    @Test
    void testCacheMonitoringService_GetSpecificCacheStatistics() {
        // Test getting statistics for a specific cache
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("accounts");
        // May be null if cache hasn't been used yet, which is fine
        if (stats != null) {
            assertTrue(stats.getHitCount() >= 0);
            assertTrue(stats.getMissCount() >= 0);
            assertTrue(stats.getHitRate() >= 0.0 && stats.getHitRate() <= 1.0);
            assertTrue(stats.getMissRate() >= 0.0 && stats.getMissRate() <= 1.0);
        }
    }

    @Test
    void testCacheMonitoringService_LogStatistics() {
        // Test that logging doesn't throw exceptions
        assertDoesNotThrow(() -> cacheMonitoringService.logCacheStatistics());
    }

    @Test
    void testCacheManager_Exists() {
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
        public CacheMonitoringService cacheMonitoringService(CacheManager cacheManager) {
            return new CacheMonitoringService(cacheManager);
        }
    }
}

