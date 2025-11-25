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
        return MeterFilter.deny(id -> {
            String name = id.getName();
            return name.startsWith("jvm.memory.pool") ||  // Detailed memory pool metrics
                   name.startsWith("jvm.gc.pause") ||     // GC pause details (keep summary)
                   name.startsWith("process.files") ||    // File descriptor metrics
                   name.startsWith("system.cpu.load") ||  // System CPU load (keep process CPU)
                   name.startsWith("http.server.requests.tag");  // Detailed HTTP tag metrics
        });
    }
}

