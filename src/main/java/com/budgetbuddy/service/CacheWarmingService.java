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
            // Fetch list of active users (logged in within last 30 days)
            List<String> activeUserIds = userRepository.findActiveUserIds(30, 1000);
            logger.info("Found {} active users to warm cache", activeUserIds.size());
            
            int warmedCount = 0;
            for (String userId : activeUserIds) {
                try {
                    userRepository.findById(userId).ifPresent(user -> {
                        logger.debug("Warmed cache for user: {}", userId);
                    });
                    warmedCount++;
                } catch (Exception e) {
                    logger.warn("Failed to warm cache for user {}: {}", userId, e.getMessage());
                }
            }
            
            logger.info("User cache warming completed: {} users warmed", warmedCount);
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
            // Fetch list of active users (logged in within last 7 days)
            List<String> activeUserIds = userRepository.findActiveUserIds(7, 500);
            logger.info("Found {} active users for account cache warming", activeUserIds.size());
            
            int warmedCount = 0;
            int totalAccounts = 0;
            for (String userId : activeUserIds) {
                try {
                    List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = 
                            accountRepository.findByUserId(userId);
                    totalAccounts += accounts.size();
                    warmedCount++;
                    logger.debug("Warmed cache for {} accounts of user: {}", accounts.size(), userId);
                } catch (Exception e) {
                    logger.warn("Failed to warm account cache for user {}: {}", userId, e.getMessage());
                }
            }
            
            logger.info("Account cache warming completed: {} users, {} accounts warmed", 
                    warmedCount, totalAccounts);
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
            // Fetch list of active users (logged in within last 3 days)
            List<String> activeUserIds = userRepository.findActiveUserIds(3, 200);
            logger.info("Found {} active users for transaction cache warming", activeUserIds.size());
            
            int warmedCount = 0;
            int totalTransactions = 0;
            for (String userId : activeUserIds) {
                try {
                    // Warm recent transactions cache (last 100 transactions per user)
                    List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions = 
                            transactionRepository.findByUserId(userId, 0, 100);
                    totalTransactions += transactions.size();
                    warmedCount++;
                    logger.debug("Warmed cache for {} transactions of user: {}", transactions.size(), userId);
                } catch (Exception e) {
                    logger.warn("Failed to warm transaction cache for user {}: {}", userId, e.getMessage());
                }
            }
            
            logger.info("Transaction cache warming completed: {} users, {} transactions warmed", 
                    warmedCount, totalTransactions);
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

