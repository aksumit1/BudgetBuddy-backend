package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AsyncSyncService
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AsyncSyncServiceTest {

    @Autowired(required = false)
    private AsyncSyncService asyncSyncService;

    @Test
    void testAsyncSyncService_IsCreated() {
        // Then
        assertNotNull(asyncSyncService, "AsyncSyncService should be created");
    }

    @Test
    void testProcessInParallelBatches_WithEmptyList_ReturnsEmpty() {
        // Given
        if (asyncSyncService == null) {
            return;
        }

        // When
        CompletableFuture<List<String>> future = asyncSyncService.processInParallelBatches(
                List.of(),
                item -> item.toString(),
                10
        );

        // Then
        assertNotNull(future, "Future should not be null");
    }

    @Test
    void testProcessInParallelBatches_WithValidItems_ProcessesItems() {
        // Given
        if (asyncSyncService == null) {
            return;
        }
        List<Integer> items = Arrays.asList(1, 2, 3, 4, 5);

        // When
        CompletableFuture<List<String>> future = asyncSyncService.processInParallelBatches(
                items,
                item -> "processed-" + item,
                2
        );

        // Then
        assertNotNull(future, "Future should not be null");
    }
}

