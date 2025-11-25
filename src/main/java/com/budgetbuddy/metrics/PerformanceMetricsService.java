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

    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetricsService.class);

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timer.Sample> activeRequests = new ConcurrentHashMap<>();

    // Counters
    private final Counter totalRequests;
    private final Counter totalErrors;
    private final Counter totalSuccess;

    public PerformanceMetricsService(MeterRegistry meterRegistry) {
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
    public void startRequest(String correlationId, String endpoint, String method) {
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
    public void endRequest(String correlationId, String endpoint, String method, int statusCode, boolean isError) {
        Timer.Sample sample = activeRequests.remove(correlationId);
        if (sample != null) {
            Timer timer = Timer.builder("http.request.duration")
                    .tag("endpoint", sanitizeEndpoint(endpoint))
                    .tag("method", method)
                    .tag("status", String.valueOf(statusCode))
                    .description("Request duration by endpoint, method, and status")
                    .register(meterRegistry);

            sample.stop(timer);
        }

        // Record success or error
        if (isError || statusCode >= 400) {
            totalErrors.increment();
            Counter.builder("http.errors")
                    .tag("endpoint", sanitizeEndpoint(endpoint))
                    .tag("method", method)
                    .tag("status", String.valueOf(statusCode))
                    .description("Number of errors by endpoint, method, and status")
                    .register(meterRegistry)
                    .increment();
        } else {
            totalSuccess.increment();
        }
    }

    /**
     * Record throughput (requests per second)
     */
    public void recordThroughput(String endpoint, double requestsPerSecond) {
        meterRegistry.gauge("http.throughput", 
                "endpoint", sanitizeEndpoint(endpoint),
                requestsPerSecond);
    }

    /**
     * Record active connections
     */
    public void recordActiveConnections(int count) {
        meterRegistry.gauge("http.connections.active", count);
    }

    /**
     * Record database query time
     */
    public void recordDatabaseQuery(String operation, long durationMs) {
        Timer.builder("database.query.duration")
                .tag("operation", operation)
                .description("Database query duration by operation")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record external API call time
     */
    public void recordExternalApiCall(String service, String endpoint, long durationMs, boolean success) {
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
    public void recordCacheOperation(String cacheName, boolean hit) {
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
    public void recordQueueSize(String queueName, int size) {
        meterRegistry.gauge("queue.size",
                "queue", queueName,
                size);
    }

    /**
     * Sanitize endpoint for metrics (remove IDs, etc.)
     */
    private String sanitizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "unknown";
        }

        // Replace UUIDs and IDs with placeholders
        endpoint = endpoint.replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/{id}");
        endpoint = endpoint.replaceAll("/\\d+", "/{id}");

        // Limit length
        if (endpoint.length() > 100) {
            endpoint = endpoint.substring(0, 100);
        }

        return endpoint;
    }
}

