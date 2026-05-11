package com.budgetbuddy.service;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service for pre-warming cache on app startup/login Improves initial app performance by loading
 * frequently accessed data into cache
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class CacheWarmingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheWarmingService.class);

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
     * Pre-warm cache for a user on app startup/login Loads all user data into cache asynchronously
     * to improve initial app performance
     *
     * @param userId The user ID to warm cache for
     * @return CompletableFuture that completes when cache warming is done
     */
    @Async
    public CompletableFuture<Void> warmCacheForUser(final String userId) {
        if (userId == null || userId.isEmpty()) {
            LOGGER.warn("Cannot warm cache: userId is null or empty");
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.info("Starting cache warm-up for user: {}", userId);
        final long startTime = System.currentTimeMillis();

        try {
            // Warm all caches in parallel for better performance
            final CompletableFuture<List<?>> accountsFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return accountRepository.findByUserId(userId);
                                } catch (Exception e) {
                                    if (LOGGER.isErrorEnabled()) {
                                        LOGGER.error(
                                                "Error warming accounts cache for user {}: {}",
                                                userId,
                                                e.getMessage());
                                    }
                                    return List.of();
                                }
                            });

            final CompletableFuture<List<?>> transactionsFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return transactionRepository.findByUserId(
                                            userId, 0, 50); // Load first 50 transactions
                                } catch (Exception e) {
                                    if (LOGGER.isErrorEnabled()) {
                                        LOGGER.error(
                                                "Error warming transactions cache for user {}: {}",
                                                userId,
                                                e.getMessage());
                                    }
                                    return List.of();
                                }
                            });

            final CompletableFuture<List<?>> budgetsFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return budgetRepository.findByUserId(userId);
                                } catch (Exception e) {
                                    if (LOGGER.isErrorEnabled()) {
                                        LOGGER.error(
                                                "Error warming budgets cache for user {}: {}",
                                                userId,
                                                e.getMessage());
                                    }
                                    return List.of();
                                }
                            });

            final CompletableFuture<List<?>> goalsFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return goalRepository.findByUserId(userId);
                                } catch (Exception e) {
                                    if (LOGGER.isErrorEnabled()) {
                                        LOGGER.error(
                                                "Error warming goals cache for user {}: {}",
                                                userId,
                                                e.getMessage());
                                    }
                                    return List.of();
                                }
                            });

            final CompletableFuture<List<?>> actionsFuture =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return transactionActionRepository.findByUserId(userId);
                                } catch (Exception e) {
                                    if (LOGGER.isErrorEnabled()) {
                                        LOGGER.error(
                                                "Error warming transaction actions cache for user {}: {}",
                                                userId,
                                                e.getMessage());
                                    }
                                    return List.of();
                                }
                            });

            // Wait for all cache warming operations to complete
            CompletableFuture.allOf(
                            accountsFuture,
                            transactionsFuture,
                            budgetsFuture,
                            goalsFuture,
                            actionsFuture)
                    .join();

            final long duration = System.currentTimeMillis() - startTime;
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Cache warm-up completed for user {} in {}ms", userId, duration);
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error during cache warm-up for user {}: {}", userId, e.getMessage(), e);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Pre-warm cache for multiple users (useful for batch operations)
     *
     * @param userIds List of user IDs to warm cache for
     */
    @Async
    public void warmCacheForUsers(final List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            LOGGER.warn("Cannot warm cache: userIds list is null or empty");
            return;
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting cache warm-up for {} users", userIds.size());
        }
        final long startTime = System.currentTimeMillis();

        // Warm cache for all users in parallel
        final List<CompletableFuture<Void>> futures =
                userIds.stream().map(this::warmCacheForUser).toList();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        final long duration = System.currentTimeMillis() - startTime;
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Cache warm-up completed for {} users in {}ms", userIds.size(), duration);
        }
    }
}
