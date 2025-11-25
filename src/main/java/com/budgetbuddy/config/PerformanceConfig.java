package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Performance Configuration
 * Optimizes thread pools and async processing for enterprise performance
 *
 * Features:
 * - Proper thread pool management
 * - Graceful shutdown
 * - Resource cleanup
 * - Thread safety
 * - Deadlock prevention
 */
@Configuration
@EnableAsync
public class PerformanceConfig {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceConfig.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    /**
     * Async task executor for non-blocking operations
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_TIMEOUT_SECONDS);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        synchronized (executors) {
            executors.add(executor);
        }

        return executor;
    }

    /**
     * High-priority executor for critical operations
     */
    @Bean(name = "highPriorityExecutor")
    public Executor highPriorityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("high-priority-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_TIMEOUT_SECONDS);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        synchronized (executors) {
            executors.add(executor);
        }

        return executor;
    }

    /**
     * Cleanup thread pools on shutdown
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down thread pools...");
        List<ThreadPoolTaskExecutor> executorsToShutdown;

        synchronized (executors) {
            executorsToShutdown = new ArrayList<>(executors);
        }

        for (ThreadPoolTaskExecutor executor : executorsToShutdown) {
            shutdownExecutor(executor, executor.getThreadNamePrefix());
        }
    }

    private void shutdownExecutor(final ThreadPoolTaskExecutor executor, final String name) {
        if (executor == null) {
            return;
        }

        try {
            logger.info("Shutting down {}...", name);
            ExecutorService threadPoolExecutor = executor.getThreadPoolExecutor();

            if (threadPoolExecutor != null) {
                threadPoolExecutor.shutdown();

                if (!threadPoolExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("{} did not terminate within {} seconds, forcing shutdown", name, SHUTDOWN_TIMEOUT_SECONDS);
                    threadPoolExecutor.shutdownNow();

                    if (!threadPoolExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        logger.error("{} did not terminate after forced shutdown", name);
                    }
                } else {
                    logger.info("{} shut down successfully", name);
                }
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while shutting down {}", name, e);
            ExecutorService threadPoolExecutor = executor.getThreadPoolExecutor();
            if (threadPoolExecutor != null) {
                threadPoolExecutor.shutdownNow();
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error shutting down {}", name, e);
        }
    }
}
