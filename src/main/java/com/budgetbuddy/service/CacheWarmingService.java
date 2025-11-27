package com.budgetbuddy.service;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cache Warming Service
 * Pre-loads frequently accessed data into cache to improve performance
 */
@Service
public class CacheWarmingService {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmingService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CacheManager cacheManager;

    public CacheWarmingService(
            final UserRepository userRepository,
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * Warm cache for active users
     * Runs daily at 2 AM to pre-load frequently accessed user data
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void warmUserCache() {
        logger.info("Starting user cache warming");
        try {
            // In production, fetch list of active users from database
            // For now, this is a placeholder that demonstrates the pattern
            // TODO: Implement active user list retrieval
            
            // Example: Warm cache for top 1000 active users
            // List<String> activeUserIds = getActiveUserIds(1000);
            // for (String userId : activeUserIds) {
            //     userRepository.findById(userId); // This will cache the result
            // }
            
            logger.info("User cache warming completed");
        } catch (Exception e) {
            logger.error("Error warming user cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Warm cache for frequently accessed accounts
     * Runs every 6 hours
     */
    @Scheduled(cron = "0 0 */6 * * ?") // Every 6 hours
    public void warmAccountCache() {
        logger.info("Starting account cache warming");
        try {
            // In production, fetch list of users with active accounts
            // For now, this is a placeholder
            // TODO: Implement account cache warming logic
            
            logger.info("Account cache warming completed");
        } catch (Exception e) {
            logger.error("Error warming account cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Warm cache for recent transactions
     * Runs every 4 hours
     */
    @Scheduled(cron = "0 0 */4 * * ?") // Every 4 hours
    public void warmTransactionCache() {
        logger.info("Starting transaction cache warming");
        try {
            // In production, fetch recent transactions for active users
            // For now, this is a placeholder
            // TODO: Implement transaction cache warming logic
            
            logger.info("Transaction cache warming completed");
        } catch (Exception e) {
            logger.error("Error warming transaction cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Manually warm cache for a specific user
     * Useful for warming cache after user login
     */
    public void warmCacheForUser(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }

        try {
            // Warm user cache
            userRepository.findById(userId).ifPresent(user -> {
                logger.debug("Warmed cache for user: {}", userId);
            });

            // Warm account cache
            List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByUserId(userId);
            logger.debug("Warmed cache for {} accounts of user: {}", accounts.size(), userId);

            // Warm recent transactions cache (last 30 days)
            List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions = 
                    transactionRepository.findByUserId(userId, 0, 100);
            logger.debug("Warmed cache for {} transactions of user: {}", transactions.size(), userId);

        } catch (Exception e) {
            logger.error("Error warming cache for user {}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * Clear all caches
     * Useful for cache invalidation
     */
    public void clearAllCaches() {
        logger.info("Clearing all caches");
        cacheManager.getCacheNames().forEach(cacheName -> {
            if (cacheManager.getCache(cacheName) != null) {
                cacheManager.getCache(cacheName).clear();
            }
        });
        logger.info("All caches cleared");
    }
}

