package com.budgetbuddy.service;

import com.budgetbuddy.repository.dynamodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for pre-warming cache on app startup/login
 * Improves initial app performance by loading frequently accessed data into cache
 */
@Service
public class CacheWarmingService {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmingService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final TransactionActionRepository transactionActionRepository;

    public CacheWarmingService(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final TransactionActionRepository transactionActionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.transactionActionRepository = transactionActionRepository;
    }

    /**
     * Pre-warm cache for a user on app startup/login
     * Loads all user data into cache asynchronously to improve initial app performance
     * 
     * @param userId The user ID to warm cache for
     * @return CompletableFuture that completes when cache warming is done
     */
    @Async
    public CompletableFuture<Void> warmCacheForUser(String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("Cannot warm cache: userId is null or empty");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Starting cache warm-up for user: {}", userId);
        long startTime = System.currentTimeMillis();

        try {
            // Warm all caches in parallel for better performance
            CompletableFuture<List<?>> accountsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return accountRepository.findByUserId(userId);
                } catch (Exception e) {
                    logger.error("Error warming accounts cache for user {}: {}", userId, e.getMessage());
                    return List.of();
                }
            });

            CompletableFuture<List<?>> transactionsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return transactionRepository.findByUserId(userId, 0, 50); // Load first 50 transactions
                } catch (Exception e) {
                    logger.error("Error warming transactions cache for user {}: {}", userId, e.getMessage());
                    return List.of();
                }
            });

            CompletableFuture<List<?>> budgetsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return budgetRepository.findByUserId(userId);
                } catch (Exception e) {
                    logger.error("Error warming budgets cache for user {}: {}", userId, e.getMessage());
                    return List.of();
                }
            });

            CompletableFuture<List<?>> goalsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return goalRepository.findByUserId(userId);
                } catch (Exception e) {
                    logger.error("Error warming goals cache for user {}: {}", userId, e.getMessage());
                    return List.of();
                }
            });

            CompletableFuture<List<?>> actionsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return transactionActionRepository.findByUserId(userId);
                } catch (Exception e) {
                    logger.error("Error warming transaction actions cache for user {}: {}", userId, e.getMessage());
                    return List.of();
                }
            });

            // Wait for all cache warming operations to complete
            CompletableFuture.allOf(accountsFuture, transactionsFuture, budgetsFuture, goalsFuture, actionsFuture).join();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Cache warm-up completed for user {} in {}ms", userId, duration);

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            logger.error("Error during cache warm-up for user {}: {}", userId, e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Pre-warm cache for multiple users (useful for batch operations)
     * 
     * @param userIds List of user IDs to warm cache for
     */
    @Async
    public void warmCacheForUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            logger.warn("Cannot warm cache: userIds list is null or empty");
            return;
        }

        logger.info("Starting cache warm-up for {} users", userIds.size());
        long startTime = System.currentTimeMillis();

        // Warm cache for all users in parallel
        List<CompletableFuture<Void>> futures = userIds.stream()
                .map(this::warmCacheForUser)
                .toList();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Cache warm-up completed for {} users in {}ms", userIds.size(), duration);
    }
}
