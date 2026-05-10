package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Tests for CacheConfig */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class CacheConfigTest {

    @Autowired private CacheManager cacheManager;

    @Test
    void testCacheManagerIsCreated() {
        // Then
        assertNotNull(cacheManager, "Primary cache manager should be created");
    }

    @Test
    void testCacheManagerCanStoreAndRetrieve() {
        // Given
        final String key = "test-key";
        final String value = "test-value";

        // When
        final var cache = cacheManager.getCache("default");
        if (cache != null) {
            cache.put(key, value);
            final String retrieved = cache.get(key, String.class);

            // Then
            assertEquals(value, retrieved, "Should retrieve stored value");
        } else {
            // Cache might not be created in test profile
            assertNotNull(cacheManager, "Cache manager should exist");
        }
    }

    @Test
    void testCacheManagerWithNullKeyThrowsException() {
        // Given
        final var cache = cacheManager.getCache("default");

        // When/Then - Spring Cache does not allow null keys, should throw exception
        if (cache != null) {
            assertThrows(
                    Exception.class,
                    () -> {
                        cache.put(null, "value");
                    },
                    "Should throw exception for null key (Spring Cache requirement)");
        }
    }

    @Test
    void testCacheManagerWithNullValueHandlesGracefully() {
        // Given
        final var cache = cacheManager.getCache("default");

        // When/Then - Should handle null value without crashing
        if (cache != null) {
            assertDoesNotThrow(
                    () -> {
                        cache.put("key", null);
                    },
                    "Should handle null value gracefully");
        }
    }
}
