package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CacheWarmingService
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.performance.cache.warming.enabled=true"
})
class CacheWarmingServiceTest {

    @Autowired(required = false)
    private CacheWarmingService cacheWarmingService;

    @Test
    void testCacheWarmingService_IsCreated() {
        // Then
        assertNotNull(cacheWarmingService, "CacheWarmingService should be created");
    }

    @Test
    void testWarmCacheForUser_WithValidUserId_DoesNotThrow() {
        // Given
        if (cacheWarmingService == null) {
            return;
        }
        String userId = "test-user-id";

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            cacheWarmingService.warmCacheForUser(userId);
        }, "Should not throw for valid user ID");
    }

    @Test
    void testWarmCacheForUser_WithNullUserId_DoesNotThrow() {
        // Given
        if (cacheWarmingService == null) {
            return;
        }

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            cacheWarmingService.warmCacheForUser(null);
        }, "Should not throw for null user ID");
    }

    @Test
    void testClearAllCaches_DoesNotThrow() {
        // Given
        if (cacheWarmingService == null) {
            return;
        }

        // When/Then - Should not throw
        assertDoesNotThrow(() -> {
            cacheWarmingService.clearAllCaches();
        }, "Should not throw when clearing caches");
    }
}

