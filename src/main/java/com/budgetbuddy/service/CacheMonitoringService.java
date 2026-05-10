package com.budgetbuddy.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

/**
 * Service for monitoring cache performance and statistics Provides cache hit rates, miss rates, and
 * eviction statistics
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class CacheMonitoringService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheMonitoringService.class);
    private final CacheManager cacheManager;

    public CacheMonitoringService(final CacheManager cacheManager) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "CacheManager cannot be null");
    }

    /** Get cache statistics for all caches */
    public Map<String, CacheStatistics> getAllCacheStatistics() {
        final Map<String, CacheStatistics> stats = new HashMap<>();

        if (cacheManager == null) {
            return stats;
        }

        try {
            for (final String cacheName : cacheManager.getCacheNames()) {
                final org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache instanceof CaffeineCache) {
                    final CaffeineCache caffeineCache = (CaffeineCache) cache;
                    final Cache<?, ?> nativeCache = caffeineCache.getNativeCache();
                    final CacheStats cacheStats = nativeCache.stats();

                    stats.put(
                            cacheName,
                            new CacheStatistics(
                                    cacheStats.hitCount(),
                                    cacheStats.missCount(),
                                    cacheStats.evictionCount(),
                                    cacheStats.loadCount(),
                                    nativeCache.estimatedSize(),
                                    calculateHitRate(cacheStats),
                                    calculateMissRate(cacheStats)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error getting cache statistics: {}", e.getMessage());
        }

        return stats;
    }

    /** Get cache statistics for a specific cache */
    public CacheStatistics getCacheStatistics(final String cacheName) {
        if (cacheManager == null || cacheName == null) {
            return null;
        }

        try {
            final org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache instanceof CaffeineCache) {
                final CaffeineCache caffeineCache = (CaffeineCache) cache;
                final Cache<?, ?> nativeCache = caffeineCache.getNativeCache();
                final CacheStats cacheStats = nativeCache.stats();

                return new CacheStatistics(
                        cacheStats.hitCount(),
                        cacheStats.missCount(),
                        cacheStats.evictionCount(),
                        cacheStats.loadCount(),
                        nativeCache.estimatedSize(),
                        calculateHitRate(cacheStats),
                        calculateMissRate(cacheStats));
            }
        } catch (Exception e) {
            LOGGER.warn("Error getting cache statistics for {}: {}", cacheName, e.getMessage());
        }
        return null;
    }

    /** Log cache statistics (useful for monitoring) */
    public void logCacheStatistics() {
        final Map<String, CacheStatistics> stats = getAllCacheStatistics();
        LOGGER.info("=== Cache Statistics ===");
        for (final Map.Entry<String, CacheStatistics> entry : stats.entrySet()) {
            final CacheStatistics stat = entry.getValue();
            LOGGER.info(
                    "Cache: {} | Hits: {} | Misses: {} | Hit Rate: {:.2f}% | Size: {} | Evictions: {}",
                    entry.getKey(),
                    stat.getHitCount(),
                    stat.getMissCount(),
                    String.format("%.2f", stat.getHitRate() * 100),
                    stat.getSize(),
                    stat.getEvictionCount());
        }
        LOGGER.info("========================");
    }

    private double calculateHitRate(final CacheStats stats) {
        final long totalRequests = stats.hitCount() + stats.missCount();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) stats.hitCount() / totalRequests;
    }

    private double calculateMissRate(final CacheStats stats) {
        final long totalRequests = stats.hitCount() + stats.missCount();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) stats.missCount() / totalRequests;
    }

    /** Cache statistics data class */
    public static class CacheStatistics {
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final long loadCount;
        private final long size;
        private final double hitRate;
        private final double missRate;

        public CacheStatistics(
                final long hitCount,
                final long missCount,
                final long evictionCount,
                final long loadCount,
                final long size,
                final double hitRate,
                final double missRate) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.loadCount = loadCount;
            this.size = size;
            this.hitRate = hitRate;
            this.missRate = missRate;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getMissCount() {
            return missCount;
        }

        public long getEvictionCount() {
            return evictionCount;
        }

        public long getLoadCount() {
            return loadCount;
        }

        public long getSize() {
            return size;
        }

        public double getHitRate() {
            return hitRate;
        }

        public double getMissRate() {
            return missRate;
        }
    }
}
