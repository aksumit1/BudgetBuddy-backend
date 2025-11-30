package com.budgetbuddy.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Async Sync Service
 * Provides enhanced async processing for large sync operations
 * Uses parallel processing for better performance
 */
@Service
public class AsyncSyncService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncSyncService.class);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final ExecutorService executorService;

    public AsyncSyncService() {
        this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
    }

    /**
     * Process items in parallel batches
     * Splits large lists into batches and processes them concurrently
     *
     * @param items List of items to process
     * @param processor Function to process each item
     * @param batchSize Size of each batch
     * @return CompletableFuture that completes when all batches are processed
     */
    @Async
    public <T, R> CompletableFuture<List<R>> processInParallelBatches(
            final List<T> items,
            final Function<T, R> processor,
            final int batchSize) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int actualBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;

        // Split into batches
        List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i += actualBatchSize) {
            batches.add(items.subList(i, Math.min(i + actualBatchSize, items.size())));
        }

        logger.debug("Processing {} items in {} batches of size {}", items.size(), batches.size(), actualBatchSize);

        // Process batches in parallel
        List<CompletableFuture<List<R>>> batchFutures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return batch.stream()
                                .map(processor)
                                .collect(Collectors.toList());
                    } catch (Exception e) {
                        logger.error("Error processing batch: {}", e.getMessage(), e);
                        return List.<R>of();
                    }
                }, executorService))
                .collect(Collectors.toList());

        // Combine all batch results
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> batchFutures.stream()
                        .flatMap(future -> {
                            try {
                                return future.join().stream();
                            } catch (Exception e) {
                                logger.error("Error joining batch future: {}", e.getMessage(), e);
                                return java.util.stream.Stream.empty();
                            }
                        })
                        .collect(Collectors.toList()));
    }

    /**
     * Process items in parallel batches with default batch size
     */
    @Async
    public <T, R> CompletableFuture<List<R>> processInParallelBatches(
            final List<T> items,
            final Function<T, R> processor) {
        return processInParallelBatches(items, processor, DEFAULT_BATCH_SIZE);
    }

    /**
     * Process items sequentially in batches (for rate-limited APIs)
     */
    @Async
    public <T, R> CompletableFuture<List<R>> processInSequentialBatches(
            final List<T> items,
            final Function<T, R> processor,
            final int batchSize) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        int actualBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;

        List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i += actualBatchSize) {
            batches.add(items.subList(i, Math.min(i + actualBatchSize, items.size())));
        }

        List<R> results = new java.util.ArrayList<>();
        for (List<T> batch : batches) {
            try {
                List<R> batchResults = batch.stream()
                        .map(processor)
                        .collect(Collectors.toList());
                results.addAll(batchResults);
            } catch (Exception e) {
                logger.error("Error processing sequential batch: {}", e.getMessage(), e);
            }
        }

        return CompletableFuture.completedFuture(results);
    }

    /**
     * Shutdown executor service (called on application shutdown)
     * Prevents thread pool leaks by ensuring proper cleanup
     */
    @PreDestroy
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            logger.info("Shutting down AsyncSyncService executor service...");
            executorService.shutdown();
            
            try {
                // Wait for tasks to complete
                if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("AsyncSyncService executor did not terminate within {} seconds, forcing shutdown", 
                            SHUTDOWN_TIMEOUT_SECONDS);
                    executorService.shutdownNow();
                    
                    // Wait again after shutdownNow
                    if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        logger.error("AsyncSyncService executor did not terminate after forced shutdown");
                    } else {
                        logger.info("AsyncSyncService executor terminated after forced shutdown");
                    }
                } else {
                    logger.info("AsyncSyncService executor service shut down successfully");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while shutting down AsyncSyncService executor", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

