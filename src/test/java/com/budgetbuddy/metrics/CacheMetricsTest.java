package com.budgetbuddy.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
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
    private SimpleCacheManager simpleCacheManager;

    @BeforeEach
    void setUp() {
        cacheMetrics = new CacheMetrics(cacheManager);
        
        // Create a real cache manager for some tests
        simpleCacheManager = new SimpleCacheManager();
        Cache<Object, Object> caffeineCache = Caffeine.newBuilder()
                .maximumSize(100)
                .recordStats()
                .build();
        CaffeineCache springCache = new CaffeineCache("testCache", caffeineCache);
        simpleCacheManager.setCaches(Collections.singletonList(springCache));
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
        CacheMetrics metricsWithRealCache = new CacheMetrics(simpleCacheManager);
        
        // When
        Map<String, CacheMetrics.CacheStatsInfo> stats = metricsWithRealCache.getCacheStats();
        
        // Then
        assertNotNull(stats);
        // The cache should be found if it's a CaffeineCache
        if (!stats.isEmpty()) {
            assertTrue(stats.containsKey("testCache"), "Stats should contain testCache. Found: " + stats.keySet());
            CacheMetrics.CacheStatsInfo info = stats.get("testCache");
            assertNotNull(info);
            assertEquals(0, info.getHits());
            assertEquals(0, info.getMisses());
        } else {
            // If stats are empty, it means the cache wasn't recognized as CaffeineCache
            // This can happen if the cache manager doesn't return the cache properly
            fail("Cache stats should not be empty. Cache manager has caches: " + simpleCacheManager.getCacheNames());
        }
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
        CacheMetrics metricsWithRealCache = new CacheMetrics(simpleCacheManager);
        
        // When
        CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("testCache");
        
        // Then
        // The cache should be found and return stats since we're using CaffeineCache
        assertNotNull(stats, "Cache stats should not be null for CaffeineCache. " +
                "Cache exists: " + (simpleCacheManager.getCache("testCache") != null) +
                ", Cache type: " + (simpleCacheManager.getCache("testCache") != null ? 
                    simpleCacheManager.getCache("testCache").getClass().getName() : "null"));
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getMisses());
    }

    @Test
    void testGetCacheStats_WithNonExistentCache_ReturnsNull() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(simpleCacheManager);
        
        // When
        CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("nonExistent");
        
        // Then
        assertNull(stats);
    }

    @Test
    void testLogCacheStats_DoesNotThrowException() {
        // Given
        CacheMetrics metricsWithRealCache = new CacheMetrics(simpleCacheManager);
        
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

