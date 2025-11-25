package com.budgetbuddy.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics Filter Configuration
 * Filters unnecessary metrics to reduce CloudWatch costs
 */
@Configuration
public class MetricsFilterConfig {

    @Value("${app.metrics.enabled:true}")
    private boolean metricsEnabled;

    @Value("${app.metrics.filter-unnecessary:true}")
    private boolean filterUnnecessary;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags(
            @Value("${app.environment:production}") String environment) {
        return registry -> {
            registry.config().commonTags(
                    "environment", environment,
                    "service", "budgetbuddy-backend",
                    "application", "BudgetBuddy"
            );
        };
    }

    @Bean
    public MeterFilter meterFilter() {
        if (!filterUnnecessary) {
            return MeterFilter.accept();
        }

        // Filter out unnecessary metrics to reduce CloudWatch costs
        return MeterFilter.denyNameStartsWith(
                "jvm.memory.pool",  // Detailed memory pool metrics
                "jvm.gc.pause",     // GC pause details (keep summary)
                "process.files",    // File descriptor metrics
                "system.cpu.load",  // System CPU load (keep process CPU)
                "http.server.requests.tag"  // Detailed HTTP tag metrics
        ).and(MeterFilter.accept());
    }
}

