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
     * Primary cache manager for all caching
     * Handles all caches: users, accounts, transactions, budgets, goals, transactionActions, fido2Credentials
     * Uses balanced TTL settings suitable for all cache types
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "users", "userProfiles",           // User caches
                "accounts", "accountBalances",      // Account caches
                "transactions", "transactionSummaries", // Transaction caches
                "budgets", "goals",                 // Budget/Goal caches
                "transactionActions",               // Transaction action cache
                "fido2Credentials",                // FIDO2 credential cache
                "subscriptions",                    // Subscription cache
                "analytics");                       // Analytics cache
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50_000) // Increased to handle all cache types
                .expireAfterWrite(15, TimeUnit.MINUTES) // Balanced TTL for all cache types
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats());
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
                .recordStats());
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
                .recordStats());
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
                .recordStats());
        return cacheManager;
    }
}
