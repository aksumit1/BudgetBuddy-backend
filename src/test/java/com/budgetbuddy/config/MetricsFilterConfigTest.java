package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsFilterConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.metrics.enabled=true",
    "app.metrics.filter-unnecessary=true",
    "app.environment=test"
})
class MetricsFilterConfigTest {

    @Autowired(required = false)
    private MeterRegistryCustomizer<MeterRegistry> metricsCommonTags;

    @Autowired(required = false)
    private MeterFilter meterFilter;

    @Test
    void testMetricsCommonTags_IsCreated() {
        // Then
        assertNotNull(metricsCommonTags, "MetricsCommonTags should be created");
    }

    @Test
    void testMeterFilter_IsCreated() {
        // Then
        assertNotNull(meterFilter, "MeterFilter should be created");
    }

    @Test
    void testMeterFilter_IsConfigured() {
        // Then - Verify filter is configured
        assertNotNull(meterFilter, "MeterFilter should be configured");
        // The actual filtering logic is tested through integration tests
    }
}

