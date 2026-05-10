package com.budgetbuddy.metrics;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cache Metrics Service Monitors and logs cache performance metrics */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Component
public class CacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheMetrics.class);

    private final CacheManager cacheManager;

    public CacheMetrics(final CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Log cache statistics every hour */
    @Scheduled(cron = "0 0 * * * ?") // Every hour
    public void logCacheStats() {
        final Map<String, CacheStatsInfo> stats = getCacheStats();

        LOGGER.info("=== Cache Statistics ===");
        for (final Map.Entry<String, CacheStatsInfo> entry : stats.entrySet()) {
            final CacheStatsInfo info = entry.getValue();
            LOGGER.info(
                    "Cache: {} | Hit Rate: {:.2f}% | Hits: {} | Misses: {} | Size: {}",
                    entry.getKey(),
                    info.getHitRate() * 100,
                    info.getHits(),
                    info.getMisses(),
                    info.getSize());
        }
        LOGGER.info("=======================");
    }

    /** Get cache statistics for all caches */
    public Map<String, CacheStatsInfo> getCacheStats() {
        final Map<String, CacheStatsInfo> stats = new HashMap<>();

        for (final String cacheName : cacheManager.getCacheNames()) {
            final Cache cache = cacheManager.getCache(cacheName);
            if (cache != null
                    && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
                @SuppressWarnings("unchecked") final
                        com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeineCache =
                        (com.github.benmanes.caffeine.cache.Cache<Object, Object>)
                                cache.getNativeCache();

                final CacheStats cacheStats = caffeineCache.stats();
                final CacheStatsInfo info =
                        new CacheStatsInfo(
                                cacheStats.hitCount(),
                                cacheStats.missCount(),
                                cacheStats.hitRate(),
                                caffeineCache.estimatedSize());
                stats.put(cacheName, info);
            }
        }

        return stats;
    }

    /** Get cache statistics for a specific cache */
    public CacheStatsInfo getCacheStats(final String cacheName) {
        final Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return null;
        }

        if (cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
            @SuppressWarnings("unchecked") final
                    com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeineCache =
                    (com.github.benmanes.caffeine.cache.Cache<Object, Object>)
                            cache.getNativeCache();

            final CacheStats cacheStats = caffeineCache.stats();
            return new CacheStatsInfo(
                    cacheStats.hitCount(),
                    cacheStats.missCount(),
                    cacheStats.hitRate(),
                    caffeineCache.estimatedSize());
        }

        return null;
    }

    /** Cache statistics information */
    public static class CacheStatsInfo {
        private final long hits;
        private final long misses;
        private final double hitRate;
        private final long size;

        public CacheStatsInfo(
                final long hits, final long misses, final double hitRate, final long size) {
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
