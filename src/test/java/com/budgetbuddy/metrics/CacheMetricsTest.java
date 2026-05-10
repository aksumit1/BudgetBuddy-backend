package com.budgetbuddy.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;

/** Unit Tests for Cache Metrics */
@ExtendWith(MockitoExtension.class)
class CacheMetricsTest {

    @Mock private CacheManager cacheManager;

    private CacheMetrics cacheMetrics;
    private CaffeineCacheManager caffeineCacheManager;

    @BeforeEach
    void setUp() {
        cacheMetrics = new CacheMetrics(cacheManager);

        // Create a real CaffeineCacheManager for some tests
        caffeineCacheManager = new CaffeineCacheManager("testCache");
        caffeineCacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(100).recordStats());
    }

    @Test
    void testGetCacheStatsWithNoCachesReturnsEmptyMap() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Collections.emptyList());

        // When
        final Map<String, CacheMetrics.CacheStatsInfo> stats = cacheMetrics.getCacheStats();

        // Then
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
    }

    @Test
    void testGetCacheStatsWithCaffeineCacheReturnsStats() {
        // Given
        final CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);

        // When
        final Map<String, CacheMetrics.CacheStatsInfo> stats = metricsWithRealCache.getCacheStats();

        // Then
        assertNotNull(stats);
        assertFalse(
                stats.isEmpty(),
                "Cache stats should not be empty. Cache manager has caches: "
                        + caffeineCacheManager.getCacheNames());
        assertTrue(
                stats.containsKey("testCache"),
                "Stats should contain testCache. Found: " + stats.keySet());
        final CacheMetrics.CacheStatsInfo info = stats.get("testCache");
        assertNotNull(info);
        assertEquals(0, info.getHits());
        assertEquals(0, info.getMisses());
    }

    @Test
    void testGetCacheStatsWithNonCaffeineCacheReturnsEmptyMap() {
        // Given
        final org.springframework.cache.support.SimpleCacheManager manager = new SimpleCacheManager();
        final org.springframework.cache.concurrent.ConcurrentMapCache simpleCache =
                new org.springframework.cache.concurrent.ConcurrentMapCache("simpleCache");
        manager.setCaches(Collections.singletonList(simpleCache));
        final CacheMetrics metrics = new CacheMetrics(manager);

        // When
        final Map<String, CacheMetrics.CacheStatsInfo> stats = metrics.getCacheStats();

        // Then
        assertNotNull(stats);
        assertTrue(stats.isEmpty()); // Non-Caffeine caches are not tracked
    }

    @Test
    void testGetCacheStatsWithSpecificCacheReturnsStats() {
        // Given
        final CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);

        // When
        final CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("testCache");

        // Then
        // The cache should be found and return stats since we're using CaffeineCacheManager
        assertNotNull(
                stats,
                "Cache stats should not be null for CaffeineCache. "
                        + "Cache exists: "
                        + (caffeineCacheManager.getCache("testCache") != null)
                        + ", Cache type: "
                        + (caffeineCacheManager.getCache("testCache") != null
                                ? caffeineCacheManager.getCache("testCache").getClass().getName()
                                : "null"));
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getMisses());
    }

    @Test
    void testGetCacheStatsWithNonExistentCacheReturnsNull() {
        // Given
        final CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);

        // When
        final CacheMetrics.CacheStatsInfo stats = metricsWithRealCache.getCacheStats("nonExistent");

        // Then
        assertNull(stats);
    }

    @Test
    void testLogCacheStatsDoesNotThrowException() {
        // Given
        final CacheMetrics metricsWithRealCache = new CacheMetrics(caffeineCacheManager);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    metricsWithRealCache.logCacheStats();
                });
    }

    @Test
    void testCacheStatsInfoGetters() {
        // Given
        final CacheMetrics.CacheStatsInfo info = new CacheMetrics.CacheStatsInfo(10, 5, 0.67, 100);

        // When/Then
        assertEquals(10, info.getHits());
        assertEquals(5, info.getMisses());
        assertEquals(0.67, info.getHitRate(), 0.01);
        assertEquals(100, info.getSize());
    }
}
