package com.budgetbuddy.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Service for monitoring cache performance and statistics
 * Provides cache hit rates, miss rates, and eviction statistics
 */
@Service
public class CacheMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(CacheMonitoringService.class);
    private final CacheManager cacheManager;

    public CacheMonitoringService(CacheManager cacheManager) {
        this.cacheManager = Objects.requireNonNull(cacheManager, "CacheManager cannot be null");
    }

    /**
     * Get cache statistics for all caches
     */
    public Map<String, CacheStatistics> getAllCacheStatistics() {
        Map<String, CacheStatistics> stats = new HashMap<>();
        
        if (cacheManager == null) {
            return stats;
        }
        
        try {
            for (String cacheName : cacheManager.getCacheNames()) {
                org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache instanceof CaffeineCache) {
                    CaffeineCache caffeineCache = (CaffeineCache) cache;
                    Cache<?, ?> nativeCache = caffeineCache.getNativeCache();
                    CacheStats cacheStats = nativeCache.stats();
                    
                    stats.put(cacheName, new CacheStatistics(
                            cacheStats.hitCount(),
                            cacheStats.missCount(),
                            cacheStats.evictionCount(),
                            cacheStats.loadCount(),
                            nativeCache.estimatedSize(),
                            calculateHitRate(cacheStats),
                            calculateMissRate(cacheStats)
                    ));
                }
            }
        } catch (Exception e) {
            logger.warn("Error getting cache statistics: {}", e.getMessage());
        }
        
        return stats;
    }

    /**
     * Get cache statistics for a specific cache
     */
    public CacheStatistics getCacheStatistics(String cacheName) {
        if (cacheManager == null || cacheName == null) {
            return null;
        }
        
        try {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache instanceof CaffeineCache) {
                CaffeineCache caffeineCache = (CaffeineCache) cache;
                Cache<?, ?> nativeCache = caffeineCache.getNativeCache();
                CacheStats cacheStats = nativeCache.stats();
                
                return new CacheStatistics(
                        cacheStats.hitCount(),
                        cacheStats.missCount(),
                        cacheStats.evictionCount(),
                        cacheStats.loadCount(),
                        nativeCache.estimatedSize(),
                        calculateHitRate(cacheStats),
                        calculateMissRate(cacheStats)
                );
            }
        } catch (Exception e) {
            logger.warn("Error getting cache statistics for {}: {}", cacheName, e.getMessage());
        }
        return null;
    }

    /**
     * Log cache statistics (useful for monitoring)
     */
    public void logCacheStatistics() {
        Map<String, CacheStatistics> stats = getAllCacheStatistics();
        logger.info("=== Cache Statistics ===");
        for (Map.Entry<String, CacheStatistics> entry : stats.entrySet()) {
            CacheStatistics stat = entry.getValue();
            logger.info("Cache: {} | Hits: {} | Misses: {} | Hit Rate: {:.2f}% | Size: {} | Evictions: {}",
                    entry.getKey(),
                    stat.getHitCount(),
                    stat.getMissCount(),
                    String.format("%.2f", stat.getHitRate() * 100),
                    stat.getSize(),
                    stat.getEvictionCount());
        }
        logger.info("========================");
    }

    private double calculateHitRate(CacheStats stats) {
        long totalRequests = stats.hitCount() + stats.missCount();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) stats.hitCount() / totalRequests;
    }

    private double calculateMissRate(CacheStats stats) {
        long totalRequests = stats.hitCount() + stats.missCount();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) stats.missCount() / totalRequests;
    }

    /**
     * Cache statistics data class
     */
    public static class CacheStatistics {
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final long loadCount;
        private final long size;
        private final double hitRate;
        private final double missRate;

        public CacheStatistics(long hitCount, long missCount, long evictionCount, 
                              long loadCount, long size, double hitRate, double missRate) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.loadCount = loadCount;
            this.size = size;
            this.hitRate = hitRate;
            this.missRate = missRate;
        }

        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getEvictionCount() { return evictionCount; }
        public long getLoadCount() { return loadCount; }
        public long getSize() { return size; }
        public double getHitRate() { return hitRate; }
        public double getMissRate() { return missRate; }
    }
}

