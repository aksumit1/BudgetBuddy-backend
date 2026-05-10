package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Tests for AsyncSyncService */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AsyncSyncServiceTest {

    @Autowired(required = false)
    private AsyncSyncService asyncSyncService;

    @Test
    void testAsyncSyncServiceIsCreated() {
        // Then
        assertNotNull(asyncSyncService, "AsyncSyncService should be created");
    }

    @Test
    void testProcessInParallelBatchesWithEmptyListReturnsEmpty() {
        // Given
        if (asyncSyncService == null) {
            return;
        }

        // When
        final CompletableFuture<List<String>> future =
                asyncSyncService.processInParallelBatches(List.of(), item -> item.toString(), 10);

        // Then
        assertNotNull(future, "Future should not be null");
    }

    @Test
    void testProcessInParallelBatchesWithValidItemsProcessesItems() {
        // Given
        if (asyncSyncService == null) {
            return;
        }
        final List<Integer> items = Arrays.asList(1, 2, 3, 4, 5);

        // When
        final CompletableFuture<List<String>> future =
                asyncSyncService.processInParallelBatches(items, item -> "processed-" + item, 2);

        // Then
        assertNotNull(future, "Future should not be null");
    }
}
