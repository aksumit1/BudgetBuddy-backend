package com.budgetbuddy.metrics;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit Tests for Cache Metrics
 */
@ExtendWith(MockitoExtension.class)
class CacheMetricsTest {

    @Mock
    private CacheManager cacheManager;

    private CacheMetrics cacheMetrics;
    private CaffeineCacheManager caffeineCacheManager;

    @BeforeEach
    void setUp() {
        cacheMetrics = new CacheMetrics(cacheManager);
        
        // Create a real CaffeineCacheManager for some tests
        caffeineCacheManager = new CaffeineCacheManager("testCache");
        caffeineCacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .recordStats());
    }

    @Test
    void testGetCacheStats_WithNoCaches_ReturnsEmptyMap() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Collections.emptyList());
        
        // When
        Map<String, CacheMetrics.CacheStatsInfo> stats = cacheMetrics.getCacheStats();
        
        // Then
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
    }

    @Test
    void testGetCacheStats_WithCaffeineCache_ReturnsStats() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);
        
        // When
        Map<String, CacheMetrics.CacheStatsInfo> stats = metricsWithRealCache.getCacheStats();
        
        // Then
        assertNotNull(stats);
        assertFalse(stats.isEmpty(), "Cache stats should not be empty. Cache manager has caches: " + caffeineCacheManager.getCacheNames());
        assertTrue(stats.containsKey("testCache"), "Stats should contain testCache. Found: " + stats.keySet());
        CacheMetrics.CacheStatsInfo info = stats.get("testCache");
        assertNotNull(info);
        assertEquals(0, info.getHits());
        assertEquals(0, info.getMisses());
    }

    @Test
    void testGetCacheStats_WithNonCaffeineCache_ReturnsEmptyMap() {
        // Given
        org.springframework.cache.support.SimpleCacheManager manager = new SimpleCacheManager();
        org.springframework.cache.concurrent.ConcurrentMapCache simpleCache = new org.springframework.cache.concurrent.ConcurrentMapCache("simpleCache");
        manager.setCaches(Collections.singletonList(simpleCache));
        CacheMetrics metrics = new CacheMetrics(manager);
        
        // When
        Map<String, CacheMetrics.CacheStatsInfo> stats = metrics.getCacheStats();
        
        // Then
        assertNotNull(stats);
        assertTrue(stats.isEmpty()); // Non-Caffeine caches are not tracked
    }

    @Test
    void testGetCacheStats_WithSpecificCache_ReturnsStats() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);
        
        // When
        CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("testCache");
        
        // Then
        // The cache should be found and return stats since we're using CaffeineCacheManager
        assertNotNull(stats, "Cache stats should not be null for CaffeineCache. " +
                "Cache exists: " + (caffeineCacheManager.getCache("testCache") != null) +
                ", Cache type: " + (caffeineCacheManager.getCache("testCache") != null ? 
                    caffeineCacheManager.getCache("testCache").getClass().getName() : "null"));
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getMisses());
    }

    @Test
    void testGetCacheStats_WithNonExistentCache_ReturnsNull() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);
        
        // When
        CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("nonExistent");
        
        // Then
        assertNull(stats);
    }

    @Test
    void testLogCacheStats_DoesNotThrowException() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);
        
        // When/Then
        assertDoesNotThrow(() -> {
            metricsWithRealCache.logCacheStats();
        });
    }

    @Test
    void testCacheStatsInfo_Getters() {
        // Given
        CacheMetrics.CacheStatsInfo info = new CacheMetrics.CacheStatsInfo(10, 5, 0.67, 100);
        
        // When/Then
        assertEquals(10, info.getHits());
        assertEquals(5, info.getMisses());
        assertEquals(0.67, info.getHitRate(), 0.01);
        assertEquals(100, info.getSize());
    }
}

