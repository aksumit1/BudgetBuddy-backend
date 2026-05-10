package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

/** Comprehensive tests for CacheMonitoringService */
class CacheMonitoringServiceTest {

    private static final String USERCACHE = "userCache";

    @Mock private CacheManager cacheManager;

    @Mock private CaffeineCache caffeineCache;

    @Mock private Cache<Object, Object> nativeCache;

    @Mock private CacheStats cacheStats;

    private CacheMonitoringService cacheMonitoringService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cacheMonitoringService = new CacheMonitoringService(cacheManager);
    }

    @Test
    @DisplayName("Should get statistics for all caches")
    void testGetAllCacheStatisticsSuccess() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Arrays.asList(USERCACHE, "transactionCache"));
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(cacheManager.getCache("transactionCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        final Map<String, CacheMonitoringService.CacheStatistics> stats =
                cacheMonitoringService.getAllCacheStatistics();

        // Then
        assertNotNull(stats);
        assertEquals(2, stats.size());
        assertTrue(stats.containsKey(USERCACHE));
        assertTrue(stats.containsKey("transactionCache"));

        final CacheMonitoringService.CacheStatistics userStats = stats.get(USERCACHE);
        assertEquals(100L, userStats.getHitCount());
        assertEquals(50L, userStats.getMissCount());
        assertEquals(10L, userStats.getEvictionCount());
        assertEquals(5L, userStats.getLoadCount());
        assertEquals(200L, userStats.getSize());
    }

    @Test
    @DisplayName("Should throw exception when cache manager is null")
    void testGetAllCacheStatisticsNullCacheManager() {
        // Given/When/Then - Constructor should throw NullPointerException
        assertThrows(
                NullPointerException.class,
                () -> {
                    new CacheMonitoringService(null);
                },
                "Should throw NullPointerException when CacheManager is null");
    }

    @Test
    @DisplayName("Should handle exceptions gracefully")
    void testGetAllCacheStatisticsExceptionHandling() {
        // Given
        when(cacheManager.getCacheNames()).thenThrow(new RuntimeException("Cache error"));

        // When
        final Map<String, CacheMonitoringService.CacheStatistics> stats =
                cacheMonitoringService.getAllCacheStatistics();

        // Then - Should return empty map on error
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
    }

    @Test
    @DisplayName("Should get statistics for specific cache")
    void testGetCacheStatisticsSuccess() {
        // Given
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics(USERCACHE);

        // Then
        assertNotNull(stats);
        assertEquals(100L, stats.getHitCount());
        assertEquals(50L, stats.getMissCount());
        assertEquals(10L, stats.getEvictionCount());
        assertEquals(5L, stats.getLoadCount());
        assertEquals(200L, stats.getSize());
    }

    @Test
    @DisplayName("Should return null for non-existent cache")
    void testGetCacheStatisticsNonExistentCache() {
        // Given
        when(cacheManager.getCache("nonExistent")).thenReturn(null);

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics("nonExistent");

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should return null for null cache name")
    void testGetCacheStatisticsNullCacheName() {
        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics(null);

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should throw exception when cache manager is null")
    void testGetCacheStatisticsNullCacheManager() {
        // Given/When/Then - Constructor should throw NullPointerException
        assertThrows(
                NullPointerException.class,
                () -> {
                    new CacheMonitoringService(null);
                },
                "Should throw NullPointerException when CacheManager is null");
    }

    @Test
    @DisplayName("Should handle non-CaffeineCache gracefully")
    void testGetCacheStatisticsNonCaffeineCache() {
        // Given
        final org.springframework.cache.Cache nonCaffeineCache =
                mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("otherCache")).thenReturn(nonCaffeineCache);

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics("otherCache");

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should calculate hit rate correctly")
    void testCalculateHitRate() {
        // Given
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics(USERCACHE);

        // Then
        assertNotNull(stats);
        assertEquals(100.0 / 150.0, stats.getHitRate(), 0.001);
        assertEquals(50.0 / 150.0, stats.getMissRate(), 0.001);
    }

    @Test
    @DisplayName("Should return zero hit rate when no requests")
    void testCalculateHitRateNoRequests() {
        // Given
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(0L);
        when(cacheStats.missCount()).thenReturn(0L);
        when(cacheStats.evictionCount()).thenReturn(0L);
        when(cacheStats.loadCount()).thenReturn(0L);
        when(nativeCache.estimatedSize()).thenReturn(0L);

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics(USERCACHE);

        // Then
        assertNotNull(stats);
        assertEquals(0.0, stats.getHitRate());
        assertEquals(0.0, stats.getMissRate());
    }

    @Test
    @DisplayName("Should log cache statistics")
    void testLogCacheStatistics() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Arrays.asList(USERCACHE));
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    cacheMonitoringService.logCacheStatistics();
                });
    }

    @Test
    @DisplayName("Should handle exception during statistics retrieval")
    void testGetCacheStatisticsException() {
        // Given
        when(cacheManager.getCache(USERCACHE)).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenThrow(new RuntimeException("Cache error"));

        // When
        final CacheMonitoringService.CacheStatistics stats =
                cacheMonitoringService.getCacheStatistics(USERCACHE);

        // Then - Should return null on error
        assertNull(stats);
    }
}
