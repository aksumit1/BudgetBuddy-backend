package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.security.Security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for DnsCacheConfig */
class DnsCacheConfigTest {

    private DnsCacheConfig config;
    private String originalTtl;
    private String originalNegativeTtl;

    @BeforeEach
    void setUp() {
        config = new DnsCacheConfig();
        // Store original values to restore after tests
        originalTtl = Security.getProperty("networkaddress.cache.ttl");
        originalNegativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
    }

    @Test
    void testConfigureDnsCacheWithDefaultValues() {
        // Given
        ReflectionTestUtils.setField(config, "dnsCacheTtlSeconds", 3600);
        ReflectionTestUtils.setField(config, "dnsCacheNegativeTtlSeconds", 1);

        // When
        config.configureDnsCache();

        // Then - verify properties are set
        final String ttl = Security.getProperty("networkaddress.cache.ttl");
        final String negativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        assertEquals("3600", ttl);
        assertEquals("1", negativeTtl);
    }

    @Test
    void testConfigureDnsCacheWithCustomValues() {
        // Given
        ReflectionTestUtils.setField(config, "dnsCacheTtlSeconds", 7200);
        ReflectionTestUtils.setField(config, "dnsCacheNegativeTtlSeconds", 5);

        // When
        config.configureDnsCache();

        // Then
        final String ttl = Security.getProperty("networkaddress.cache.ttl");
        final String negativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        assertEquals("7200", ttl);
        assertEquals("5", negativeTtl);
    }

    @Test
    void testClearDnsCacheSuccess() {
        // Given
        ReflectionTestUtils.setField(config, "dnsCacheTtlSeconds", 3600);
        ReflectionTestUtils.setField(config, "dnsCacheNegativeTtlSeconds", 1);
        config.configureDnsCache();

        // Set some values
        Security.setProperty("networkaddress.cache.ttl", "3600");
        Security.setProperty("networkaddress.cache.negative.ttl", "1");

        // When
        assertDoesNotThrow(() -> config.clearDnsCache());

        // Then - values should still be set (cleared and restored)
        final String ttl = Security.getProperty("networkaddress.cache.ttl");
        final String negativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        assertNotNull(ttl);
        assertNotNull(negativeTtl);
    }

    @Test
    void testClearDnsCachePreservesConfiguration() {
        // Given
        ReflectionTestUtils.setField(config, "dnsCacheTtlSeconds", 3600);
        ReflectionTestUtils.setField(config, "dnsCacheNegativeTtlSeconds", 1);
        config.configureDnsCache();

        // When - clear and restore
        assertDoesNotThrow(() -> config.clearDnsCache());

        // Then - values should be preserved after clearing
        final String ttl = Security.getProperty("networkaddress.cache.ttl");
        final String negativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        assertNotNull(ttl);
        assertNotNull(negativeTtl);
        // Values should match configured defaults
        assertEquals("3600", ttl);
        assertEquals("1", negativeTtl);
    }
}
