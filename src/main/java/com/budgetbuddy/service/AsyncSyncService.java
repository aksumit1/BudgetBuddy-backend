package com.budgetbuddy.service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async Sync Service Provides enhanced async processing for large sync operations Uses parallel
 * processing for better performance
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class AsyncSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncSyncService.class);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Executor executor;
    private ExecutorService fallbackExecutorService; // Only used if Spring executor not available

    public AsyncSyncService(
            @Autowired(required = false) @Qualifier("taskExecutor") final Executor springExecutor) {
        if (springExecutor != null) {
            // Use Spring-managed thread pool executor
            this.executor = springExecutor;
            this.fallbackExecutorService = null;
            LOGGER.debug("AsyncSyncService using Spring-managed thread pool executor");
        } else {
            // Fallback to local executor (for testing or when Spring executor not available)
            this.fallbackExecutorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
            this.executor = fallbackExecutorService;
            LOGGER.debug("AsyncSyncService using fallback thread pool executor");
        }
    }

    /**
     * Process items in parallel batches Splits large lists into batches and processes them
     * concurrently
     *
     * @param items List of items to process
     * @param processor Function to process each item
     * @param batchSize Size of each batch
     * @return CompletableFuture that completes when all batches are processed
     */
    @Async
    public <T, R> CompletableFuture<List<R>> processInParallelBatches(
            final List<T> items, final Function<T, R> processor, final int batchSize) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        final int actualBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;

        // Split into batches
        final List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i += actualBatchSize) {
            batches.add(items.subList(i, Math.min(i + actualBatchSize, items.size())));
        }

        LOGGER.debug(
                "Processing {} items in {} batches of size {}",
                items.size(),
                batches.size(),
                actualBatchSize);

        // Process batches in parallel
        final List<CompletableFuture<List<R>>> batchFutures =
                batches.stream()
                        .map(
                                batch ->
                                        CompletableFuture.supplyAsync(
                                                () -> {
                                                    try {
                                                        return batch.stream()
                                                                .map(processor)
                                                                .collect(Collectors.toList());
                                                    } catch (Exception e) {
                                                        LOGGER.error(
                                                                "Error processing batch: {}",
                                                                e.getMessage(),
                                                                e);
                                                        return List.<R>of();
                                                    }
                                                },
                                                executor))
                        .collect(Collectors.toList());

        // Combine all batch results
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture<?>[0]))
                .thenApply(
                        v ->
                                batchFutures.stream()
                                        .flatMap(
                                                future -> {
                                                    try {
                                                        return future.join().stream();
                                                    } catch (Exception e) {
                                                        LOGGER.error(
                                                                "Error joining batch future: {}",
                                                                e.getMessage(),
                                                                e);
                                                        return java.util.stream.Stream.empty();
                                                    }
                                                })
                                        .collect(Collectors.toList()));
    }

    /** Process items in parallel batches with default batch size */
    @Async
    public <T, R> CompletableFuture<List<R>> processInParallelBatches(
            final List<T> items, final Function<T, R> processor) {
        return processInParallelBatches(items, processor, DEFAULT_BATCH_SIZE);
    }

    /** Process items sequentially in batches (for rate-limited APIs) */
    @Async
    public <T, R> CompletableFuture<List<R>> processInSequentialBatches(
            final List<T> items, final Function<T, R> processor, final int batchSize) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        final int actualBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;

        final List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i += actualBatchSize) {
            batches.add(items.subList(i, Math.min(i + actualBatchSize, items.size())));
        }

        final List<R> results = new java.util.ArrayList<>();
        for (final List<T> batch : batches) {
            try {
                final List<R> batchResults = batch.stream().map(processor).collect(Collectors.toList());
                results.addAll(batchResults);
            } catch (Exception e) {
                LOGGER.error("Error processing sequential batch: {}", e.getMessage(), e);
            }
        }

        return CompletableFuture.completedFuture(results);
    }

    /**
     * Shutdown executor service (called on application shutdown) Prevents thread pool leaks by
     * ensuring proper cleanup Only shuts down fallback executor - Spring-managed executor is
     * handled by Spring
     */
    @PreDestroy
    public void shutdown() {
        // Only shutdown fallback executor - Spring-managed executor is handled by Spring
        if (fallbackExecutorService != null && !fallbackExecutorService.isShutdown()) {
            LOGGER.info("Shutting down AsyncSyncService fallback executor service...");
            fallbackExecutorService.shutdown();

            try {
                // Wait for tasks to complete
                if (!fallbackExecutorService.awaitTermination(
                        SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.warn(
                            "AsyncSyncService executor did not terminate within {} seconds, forcing shutdown",
                            SHUTDOWN_TIMEOUT_SECONDS);
                    fallbackExecutorService.shutdownNow();

                    // Wait again after shutdownNow
                    if (!fallbackExecutorService.awaitTermination(
                            SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        LOGGER.error(
                                "AsyncSyncService executor did not terminate after forced shutdown");
                    } else {
                        LOGGER.info("AsyncSyncService executor terminated after forced shutdown");
                    }
                } else {
                    LOGGER.info("AsyncSyncService executor service shut down successfully");
                }
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted while shutting down AsyncSyncService executor", e);
                fallbackExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } else {
            LOGGER.debug("AsyncSyncService using Spring-managed executor - no shutdown needed");
        }
    }
}
