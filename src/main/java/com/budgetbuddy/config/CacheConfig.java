package com.budgetbuddy.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Cache Configuration
 * Enterprise-grade caching with Caffeine for high performance
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Primary cache manager for general caching
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .recordStats()
                .build());
        return cacheManager;
    }

    /**
     * User cache manager - longer TTL for user data
     */
    @Bean
    public CacheManager userCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("users", "userProfiles");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .recordStats()
                .build());
        return cacheManager;
    }

    /**
     * Transaction cache manager - shorter TTL for frequently changing data
     */
    @Bean
    public CacheManager transactionCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("transactions", "transactionSummaries");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .recordStats()
                .build());
        return cacheManager;
    }

    /**
     * Account cache manager - medium TTL for account data
     */
    @Bean
    public CacheManager accountCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("accounts", "accountBalances");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats()
                .build());
        return cacheManager;
    }
}
