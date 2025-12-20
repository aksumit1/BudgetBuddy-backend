package com.budgetbuddy.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for CacheMonitoringService
 */
class CacheMonitoringServiceTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private CaffeineCache caffeineCache;

    @Mock
    private Cache<Object, Object> nativeCache;

    @Mock
    private CacheStats cacheStats;

    private CacheMonitoringService cacheMonitoringService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cacheMonitoringService = new CacheMonitoringService(cacheManager);
    }

    @Test
    @DisplayName("Should get statistics for all caches")
    void testGetAllCacheStatistics_Success() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Arrays.asList("userCache", "transactionCache"));
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(cacheManager.getCache("transactionCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        Map<String, CacheMonitoringService.CacheStatistics> stats = cacheMonitoringService.getAllCacheStatistics();

        // Then
        assertNotNull(stats);
        assertEquals(2, stats.size());
        assertTrue(stats.containsKey("userCache"));
        assertTrue(stats.containsKey("transactionCache"));
        
        CacheMonitoringService.CacheStatistics userStats = stats.get("userCache");
        assertEquals(100L, userStats.getHitCount());
        assertEquals(50L, userStats.getMissCount());
        assertEquals(10L, userStats.getEvictionCount());
        assertEquals(5L, userStats.getLoadCount());
        assertEquals(200L, userStats.getSize());
    }

    @Test
    @DisplayName("Should throw exception when cache manager is null")
    void testGetAllCacheStatistics_NullCacheManager() {
        // Given/When/Then - Constructor should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            new CacheMonitoringService(null);
        }, "Should throw NullPointerException when CacheManager is null");
    }

    @Test
    @DisplayName("Should handle exceptions gracefully")
    void testGetAllCacheStatistics_ExceptionHandling() {
        // Given
        when(cacheManager.getCacheNames()).thenThrow(new RuntimeException("Cache error"));

        // When
        Map<String, CacheMonitoringService.CacheStatistics> stats = cacheMonitoringService.getAllCacheStatistics();

        // Then - Should return empty map on error
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
    }

    @Test
    @DisplayName("Should get statistics for specific cache")
    void testGetCacheStatistics_Success() {
        // Given
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("userCache");

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
    void testGetCacheStatistics_NonExistentCache() {
        // Given
        when(cacheManager.getCache("nonExistent")).thenReturn(null);

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("nonExistent");

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should return null for null cache name")
    void testGetCacheStatistics_NullCacheName() {
        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics(null);

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should throw exception when cache manager is null")
    void testGetCacheStatistics_NullCacheManager() {
        // Given/When/Then - Constructor should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            new CacheMonitoringService(null);
        }, "Should throw NullPointerException when CacheManager is null");
    }

    @Test
    @DisplayName("Should handle non-CaffeineCache gracefully")
    void testGetCacheStatistics_NonCaffeineCache() {
        // Given
        org.springframework.cache.Cache nonCaffeineCache = mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("otherCache")).thenReturn(nonCaffeineCache);

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("otherCache");

        // Then
        assertNull(stats);
    }

    @Test
    @DisplayName("Should calculate hit rate correctly")
    void testCalculateHitRate() {
        // Given
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("userCache");

        // Then
        assertNotNull(stats);
        assertEquals(100.0 / 150.0, stats.getHitRate(), 0.001);
        assertEquals(50.0 / 150.0, stats.getMissRate(), 0.001);
    }

    @Test
    @DisplayName("Should return zero hit rate when no requests")
    void testCalculateHitRate_NoRequests() {
        // Given
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(0L);
        when(cacheStats.missCount()).thenReturn(0L);
        when(cacheStats.evictionCount()).thenReturn(0L);
        when(cacheStats.loadCount()).thenReturn(0L);
        when(nativeCache.estimatedSize()).thenReturn(0L);

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("userCache");

        // Then
        assertNotNull(stats);
        assertEquals(0.0, stats.getHitRate());
        assertEquals(0.0, stats.getMissRate());
    }

    @Test
    @DisplayName("Should log cache statistics")
    void testLogCacheStatistics() {
        // Given
        when(cacheManager.getCacheNames()).thenReturn(Arrays.asList("userCache"));
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenReturn(nativeCache);
        when(nativeCache.stats()).thenReturn(cacheStats);
        when(cacheStats.hitCount()).thenReturn(100L);
        when(cacheStats.missCount()).thenReturn(50L);
        when(cacheStats.evictionCount()).thenReturn(10L);
        when(cacheStats.loadCount()).thenReturn(5L);
        when(nativeCache.estimatedSize()).thenReturn(200L);

        // When - Should not throw exception
        assertDoesNotThrow(() -> {
            cacheMonitoringService.logCacheStatistics();
        });
    }

    @Test
    @DisplayName("Should handle exception during statistics retrieval")
    void testGetCacheStatistics_Exception() {
        // Given
        when(cacheManager.getCache("userCache")).thenReturn(caffeineCache);
        when(caffeineCache.getNativeCache()).thenThrow(new RuntimeException("Cache error"));

        // When
        CacheMonitoringService.CacheStatistics stats = cacheMonitoringService.getCacheStatistics("userCache");

        // Then - Should return null on error
        assertNull(stats);
    }
}

