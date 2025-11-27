package com.budgetbuddy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Performance Metrics Service
 * Tracks and records performance metrics for monitoring
 *
 * Metrics tracked:
 * - Request count by endpoint
 * - Response time by endpoint
 * - Error count by endpoint
 * - Throughput (requests per second)
 * - Active connections
 */
@Service
public class PerformanceMetricsService {

    @SuppressWarnings("unused") // Reserved for future logging
    private static final Logger logger =
            LoggerFactory.getLogger(PerformanceMetricsService.class);

    private final MeterRegistry meterRegistry;
    // JDK 25: Using ConcurrentHashMap for thread-safe operations
    private final ConcurrentHashMap<String, Timer.Sample> activeRequests =
            new ConcurrentHashMap<>();

    // Counters
    private final Counter totalRequests;
    private final Counter totalErrors;
    private final Counter totalSuccess;

    public PerformanceMetricsService(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize counters
        this.totalRequests = Counter.builder("http.requests.total")
                .description("Total number of HTTP requests")
                .register(meterRegistry);

        this.totalErrors = Counter.builder("http.errors.total")
                .description("Total number of HTTP errors")
                .register(meterRegistry);

        this.totalSuccess = Counter.builder("http.success.total")
                .description("Total number of successful HTTP requests")
                .register(meterRegistry);
    }

    /**
     * Start timing a request
     */
    public void startRequest(final String correlationId, final String endpoint,
                             final String method) {
        Timer.Sample sample = Timer.start(meterRegistry);
        activeRequests.put(correlationId, sample);

        // Increment total requests
        totalRequests.increment();

        // Record request by endpoint and method
        Counter.builder("http.requests")
                .tag("endpoint", sanitizeEndpoint(endpoint))
                .tag("method", method)
                .description("Number of requests by endpoint and method")
                .register(meterRegistry)
                .increment();
    }

    /**
     * End timing a request and record metrics
     */
    public void endRequest(final String correlationId, final String endpoint,
                           final String method, final int statusCode,
                           final boolean isError) {
        Timer.Sample sample = activeRequests.remove(correlationId);
        if (sample != null) {
            Timer timer = Timer.builder("http.request.duration")
                    .tag("endpoint", sanitizeEndpoint(endpoint))
                    .tag("method", method)
                    .tag("status", String.valueOf(statusCode))
                    .description("Request duration by endpoint, "
                            + "method, and status")
                    .register(meterRegistry);

            sample.stop(timer);
        }

        // Record success or error
        final int errorThreshold = 400;
        if (isError || statusCode >= errorThreshold) {
            totalErrors.increment();
            Counter.builder("http.errors")
                    .tag("endpoint", sanitizeEndpoint(endpoint))
                    .tag("method", method)
                    .tag("status", String.valueOf(statusCode))
                    .description("Number of errors by endpoint, "
                            + "method, and status")
                    .register(meterRegistry)
                    .increment();
        } else {
            totalSuccess.increment();
        }
    }

    /**
     * Record throughput (requests per second)
     */
    public void recordThroughput(final String endpoint,
                                 final double requestsPerSecond) {
        io.micrometer.core.instrument.Gauge.builder("http.throughput",
                        () -> requestsPerSecond)
                .tag("endpoint", sanitizeEndpoint(endpoint))
                .register(meterRegistry);
    }

    /**
     * Record active connections
     */
    public void recordActiveConnections(final int count) {
        io.micrometer.core.instrument.Gauge.builder("http.connections.active",
                        () -> count)
                .register(meterRegistry);
    }

    /**
     * Record database query time
     */
    public void recordDatabaseQuery(final String operation,
                                     final long durationMs) {
        Timer.builder("database.query.duration")
                .tag("operation", operation)
                .description("Database query duration by operation")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record external API call time
     */
    public void recordExternalApiCall(final String service,
                                      final String endpoint,
                                      final long durationMs,
                                      final boolean success) {
        Timer.builder("external.api.duration")
                .tag("service", service)
                .tag("endpoint", sanitizeEndpoint(endpoint))
                .tag("success", String.valueOf(success))
                .description("External API call duration")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        Counter.builder("external.api.calls")
                .tag("service", service)
                .tag("success", String.valueOf(success))
                .description("Number of external API calls")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record cache hit/miss
     */
    public void recordCacheOperation(final String cacheName,
                                     final boolean hit) {
        Counter.builder("cache.operations")
                .tag("cache", cacheName)
                .tag("result", hit ? "hit" : "miss")
                .description("Cache operations by cache name and result")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record queue size
     */
    public void recordQueueSize(final String queueName, final int size) {
        io.micrometer.core.instrument.Gauge.builder("queue.size", () -> size)
                .tag("queue", queueName)
                .register(meterRegistry);
    }

    /**
     * Sanitize endpoint for metrics (remove IDs, etc.)
     */
    private String sanitizeEndpoint(final String endpoint) {
        if (endpoint == null) {
            return "unknown";
        }

        // Replace UUIDs and IDs with placeholders
        String sanitized = endpoint.replaceAll(
                "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "/{id}");
        sanitized = sanitized.replaceAll("/\\d+", "/{id}");

        // Limit length
        final int maxEndpointLength = 100;
        if (sanitized.length() > maxEndpointLength) {
            sanitized = sanitized.substring(0, maxEndpointLength);
        }

        return sanitized;
    }
}

