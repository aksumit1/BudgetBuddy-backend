package com.budgetbuddy.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Cache Configuration Enterprise-grade caching with Caffeine for high performance.
 *
 * <p>Implements {@link CachingConfigurer} to explicitly tell Spring which CacheManager resolves
 * {@code @Cacheable} lookups by default. Without this, {@code
 * CacheAspectSupport.afterSingletonsInstantiated} fails with "No qualifying bean... expected single
 * matching CacheManager but found 2" at startup — even though one bean is marked {@code @Primary},
 * because the cache-aspect path doesn't consult {@code @Primary} in every Spring version. The iOS
 * clients and controller layer all depend on caching working at startup, so misconfiguration here
 * crashes the whole app.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /**
     * Explicitly route default {@code @Cacheable} resolution to the long-TTL primary manager.
     * Dedicated managers (analytics) must be referenced by name via {@code @Cacheable(cacheManager
     * = "...")}.
     */
    // CachingConfigurer.cacheManager() is declared @Nullable in the interface,
    // so SpotBugs flags the call here as possibly-null. Our concrete override
    // never returns null (it always returns a fresh CaffeineCacheManager).
    @SuppressFBWarnings(
            value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
            justification =
                    "cacheManager() is overridden in this class and always returns non-null")
    @Override
    public CacheResolver cacheResolver() {
        return new SimpleCacheResolver(cacheManager());
    }

    /**
     * Primary cache manager for all caching Handles all caches: users, accounts, transactions,
     * budgets, goals, transactionActions, fido2Credentials Uses balanced TTL settings suitable for
     * all cache types
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        // Every cache EXCEPT analytics gets the long-TTL default. Analytics is handled
        // by the dedicated manager below (Flow 7 / O13) so budget/goal/transaction
        // ingest can evict it aggressively without nuking user profile caches.
        final CaffeineCacheManager cacheManager =
                new CaffeineCacheManager(
                        "users",
                        "userProfiles",
                        "accounts",
                        "accountBalances",
                        "transactions",
                        "transactionSummaries",
                        "budgets",
                        "goals",
                        "transactionActions",
                        "fido2Credentials",
                        "subscriptions",
                        "categoryDetermination",
                        "importCategoryParsing",
                        // Benchmarks refresh at most once a day (via BenchmarkAggregation
                        // cron), so client responses can be Caffeine-cached aggressively.
                        "benchmarks");
        cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterWrite(15, TimeUnit.MINUTES)
                        .expireAfterAccess(10, TimeUnit.MINUTES)
                        .recordStats());
        return cacheManager;
    }

    /**
     * Flow 7 / O13 — dedicated analytics cache with a tight TTL and tight size cap. 5 minutes
     * matches the rate at which transactions typically ingest — old enough to avoid recompute
     * storms, fresh enough that dashboards don't lag behind an edit. Size capped at 5k because
     * analytics keys include category + date range, so the cardinality grows faster than
     * user-indexed caches.
     */
    @Bean("analyticsCacheManager")
    public CacheManager analyticsCacheManager() {
        final CaffeineCacheManager mgr = new CaffeineCacheManager("analytics");
        mgr.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats());
        return mgr;
    }
}
