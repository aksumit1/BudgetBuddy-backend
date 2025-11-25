package com.budgetbuddy.metrics;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache Metrics Service
 * Monitors and logs cache performance metrics
 */
@Component
public class CacheMetrics {

    private static final Logger logger = LoggerFactory.getLogger(CacheMetrics.class);

    private final CacheManager cacheManager;

    public CacheMetrics(final CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Log cache statistics every hour
     */
    @Scheduled(cron = "0 0 * * * ?") // Every hour
    public void logCacheStats() {
        Map<String, CacheStatsInfo> stats = getCacheStats();
        
        logger.info("=== Cache Statistics ===");
        for (Map.Entry<String, CacheStatsInfo> entry : stats.entrySet()) {
            CacheStatsInfo info = entry.getValue();
            logger.info("Cache: {} | Hit Rate: {:.2f}% | Hits: {} | Misses: {} | Size: {}",
                    entry.getKey(),
                    info.getHitRate() * 100,
                    info.getHits(),
                    info.getMisses(),
                    info.getSize());
        }
        logger.info("=======================");
    }

    /**
     * Get cache statistics for all caches
     */
    public Map<String, CacheStatsInfo> getCacheStats() {
        Map<String, CacheStatsInfo> stats = new HashMap<>();

        for (String cacheName : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
                @SuppressWarnings("unchecked")
                com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeineCache =
                        (com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache.getNativeCache();
                
                CacheStats cacheStats = caffeineCache.stats();
                CacheStatsInfo info = new CacheStatsInfo(
                        cacheStats.hitCount(),
                        cacheStats.missCount(),
                        cacheStats.hitRate(),
                        caffeineCache.estimatedSize()
                );
                stats.put(cacheName, info);
            }
        }

        return stats;
    }

    /**
     * Get cache statistics for a specific cache
     */
    public CacheStatsInfo getCacheStats(final String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return null;
        }

        if (cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
            @SuppressWarnings("unchecked")
            com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeineCache =
                    (com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache.getNativeCache();
            
            CacheStats cacheStats = caffeineCache.stats();
            return new CacheStatsInfo(
                    cacheStats.hitCount(),
                    cacheStats.missCount(),
                    cacheStats.hitRate(),
                    caffeineCache.estimatedSize()
            );
        }

        return null;
    }

    /**
     * Cache statistics information
     */
    public static class CacheStatsInfo {
        private final long hits;
        private final long misses;
        private final double hitRate;
        private final long size;

        public CacheStatsInfo(final long hits, final long misses, final double hitRate, final long size) {
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.size = size;
        }

        public long getHits() {
            return hits;
        }

        public long getMisses() {
            return misses;
        }

        public double getHitRate() {
            return hitRate;
        }

        public long getSize() {
            return size;
        }
    }
}

