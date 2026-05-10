package com.budgetbuddy.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit Tests for Performance Metrics Service */
class PerformanceMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private PerformanceMetricsService service;

    @BeforeEach
    void setUp() {
        // Use a real SimpleMeterRegistry instead of mocking
        // This allows Timer.start() and Counter.builder() to work properly
        meterRegistry = new SimpleMeterRegistry();
        service = new PerformanceMetricsService(meterRegistry);
    }

    @Test
    void testStartRequestRecordsRequest() {
        // When
        service.startRequest("correlation-123", "/api/test", "GET");

        // Then - Verify counters were created and incremented
        final Counter totalRequestsCounter = meterRegistry.find("http.requests.total").counter();
        assertNotNull(totalRequestsCounter, "Total requests counter should exist");
        assertEquals(1.0, totalRequestsCounter.count(), "Total requests should be 1");

        final Counter requestsCounter =
                meterRegistry
                        .find("http.requests")
                        .tag("endpoint", "/api/test")
                        .tag("method", "GET")
                        .counter();
        assertNotNull(requestsCounter, "Requests counter should exist");
        assertEquals(1.0, requestsCounter.count(), "Requests counter should be 1");
    }

    @Test
    void testEndRequestWithSuccessRecordsSuccess() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");

        // When
        service.endRequest("correlation-123", "/api/test", "GET", 200, false);

        // Then - Verify timer was recorded and success counter incremented
        final Timer durationTimer =
                meterRegistry
                        .find("http.request.duration")
                        .tag("endpoint", "/api/test")
                        .tag("method", "GET")
                        .timer();
        assertNotNull(durationTimer, "Duration timer should exist");
        assertEquals(1, durationTimer.count(), "Timer should have 1 measurement");

        final Counter successCounter = meterRegistry.find("http.success.total").counter();
        assertNotNull(successCounter, "Success counter should exist");
        assertEquals(1.0, successCounter.count(), "Success counter should be 1");
    }

    @Test
    void testEndRequestWithErrorRecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");

        // When
        service.endRequest("correlation-123", "/api/test", "GET", 500, true);

        // Then - Verify timer and error counter
        final Timer durationTimer =
                meterRegistry
                        .find("http.request.duration")
                        .tag("endpoint", "/api/test")
                        .tag("method", "GET")
                        .timer();
        assertNotNull(durationTimer, "Duration timer should exist");

        final Counter errorCounter =
                meterRegistry
                        .find("http.errors")
                        .tag("endpoint", "/api/test")
                        .tag("method", "GET")
                        .tag("status", "500")
                        .counter();
        assertNotNull(errorCounter, "Error counter should exist");
        assertEquals(1.0, errorCounter.count(), "Error counter should be 1");
    }

    @Test
    void testEndRequestWithHighStatusCodeRecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");

        // When
        service.endRequest("correlation-123", "/api/test", "GET", 404, false);

        // Then - Verify error counter for 4xx status
        final Counter errorCounter =
                meterRegistry
                        .find("http.errors")
                        .tag("endpoint", "/api/test")
                        .tag("method", "GET")
                        .tag("status", "404")
                        .counter();
        assertNotNull(errorCounter, "Error counter should exist for 404");
        assertEquals(1.0, errorCounter.count(), "Error counter should be 1");
    }

    @Test
    void testEndRequestWithMissingSampleHandlesGracefully() {
        // When - End request without starting it
        service.endRequest("missing-correlation", "/api/test", "GET", 200, false);

        // Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    service.endRequest("missing-correlation", "/api/test", "GET", 200, false);
                });
    }

    @Test
    void testRecordThroughputRecordsGauge() {
        // When
        service.recordThroughput("/api/test", 10.5);

        // Then - Verify gauge was registered
        // Gauges are harder to verify directly, but we can check the meter exists
        assertNotNull(
                meterRegistry.find("http.throughput").tag("endpoint", "/api/test").gauge(),
                "Throughput gauge should exist");
    }

    @Test
    void testRecordActiveConnectionsRecordsGauge() {
        // When
        service.recordActiveConnections(5);

        // Then - Verify gauge was registered
        assertNotNull(
                meterRegistry.find("http.connections.active").gauge(),
                "Active connections gauge should exist");
    }

    @Test
    void testRecordDatabaseQueryRecordsTimer() {
        // When
        service.recordDatabaseQuery("SELECT", 100);

        // Then - Verify timer was recorded (tag is "operation" not "query")
        final Timer queryTimer =
                meterRegistry.find("database.query.duration").tag("operation", "SELECT").timer();
        assertNotNull(queryTimer, "Query timer should exist");
        assertEquals(1, queryTimer.count(), "Timer should have 1 measurement");
    }

    @Test
    void testRecordExternalApiCallRecordsMetrics() {
        // When
        service.recordExternalApiCall("plaid", "/transactions", 200, true);

        // Then - Verify timer and counter (tag "success" is String "true", endpoint is sanitized)
        final Timer apiTimer =
                meterRegistry
                        .find("external.api.duration")
                        .tag("service", "plaid")
                        .tag("endpoint", "/transactions")
                        .tag("success", "true")
                        .timer();
        assertNotNull(apiTimer, "API timer should exist");
        assertEquals(1, apiTimer.count(), "Timer should have 1 measurement");

        final Counter apiCounter =
                meterRegistry
                        .find("external.api.calls")
                        .tag("service", "plaid")
                        .tag("success", "true")
                        .counter();
        assertNotNull(apiCounter, "API counter should exist");
        assertEquals(1.0, apiCounter.count(), "API counter should be 1");
    }

    @Test
    void testRecordCacheOperationRecordsCounter() {
        // When
        service.recordCacheOperation("userCache", true);

        // Then - Verify counter
        final Counter cacheCounter =
                meterRegistry
                        .find("cache.operations")
                        .tag("cache", "userCache")
                        .tag("result", "hit")
                        .counter();
        assertNotNull(cacheCounter, "Cache counter should exist");
        assertEquals(1.0, cacheCounter.count(), "Cache counter should be 1");
    }

    @Test
    void testRecordQueueSizeRecordsGauge() {
        // When
        service.recordQueueSize("transactionQueue", 10);

        // Then - Verify gauge was registered
        assertNotNull(
                meterRegistry.find("queue.size").tag("queue", "transactionQueue").gauge(),
                "Queue size gauge should exist");
    }

    @Test
    void testSanitizeEndpointRemovesUUIDs() {
        // Given
        final String endpoint = "/api/users/123e4567-e89b-12d3-a456-426614174000";

        // When - Use reflection to test private method, or test via public method
        service.startRequest("test", endpoint, "GET");

        // Then - Verify sanitization happens (endpoint should be sanitized to /api/users/{id})
        final Counter requestsCounter =
                meterRegistry
                        .find("http.requests")
                        .tag("endpoint", "/api/users/{id}")
                        .tag("method", "GET")
                        .counter();
        assertNotNull(requestsCounter, "Requests counter with sanitized endpoint should exist");
        assertEquals(1.0, requestsCounter.count(), "Counter should be 1");
    }

    @Test
    void testSanitizeEndpointWithNullReturnsUnknown() {
        // When
        service.startRequest("test", null, "GET");

        // Then - Should handle null gracefully by using "unknown" endpoint
        final Counter requestsCounter =
                meterRegistry
                        .find("http.requests")
                        .tag("endpoint", "unknown")
                        .tag("method", "GET")
                        .counter();
        assertNotNull(requestsCounter, "Requests counter with unknown endpoint should exist");
        assertEquals(1.0, requestsCounter.count(), "Counter should be 1");
    }
}
