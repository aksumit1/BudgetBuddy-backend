package com.budgetbuddy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Performance Metrics Service
 */
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
    void testStartRequest_RecordsRequest() {
        // When
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // Then - Verify counters were created and incremented
        Counter totalRequestsCounter = meterRegistry.find("http.requests.total").counter();
        assertNotNull(totalRequestsCounter, "Total requests counter should exist");
        assertEquals(1.0, totalRequestsCounter.count(), "Total requests should be 1");
        
        Counter requestsCounter = meterRegistry.find("http.requests").tag("endpoint", "/api/test").tag("method", "GET").counter();
        assertNotNull(requestsCounter, "Requests counter should exist");
        assertEquals(1.0, requestsCounter.count(), "Requests counter should be 1");
    }

    @Test
    void testEndRequest_WithSuccess_RecordsSuccess() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 200, false);
        
        // Then - Verify timer was recorded and success counter incremented
        Timer durationTimer = meterRegistry.find("http.request.duration").tag("endpoint", "/api/test").tag("method", "GET").timer();
        assertNotNull(durationTimer, "Duration timer should exist");
        assertEquals(1, durationTimer.count(), "Timer should have 1 measurement");
        
        Counter successCounter = meterRegistry.find("http.success.total").counter();
        assertNotNull(successCounter, "Success counter should exist");
        assertEquals(1.0, successCounter.count(), "Success counter should be 1");
    }

    @Test
    void testEndRequest_WithError_RecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 500, true);
        
        // Then - Verify timer and error counter
        Timer durationTimer = meterRegistry.find("http.request.duration").tag("endpoint", "/api/test").tag("method", "GET").timer();
        assertNotNull(durationTimer, "Duration timer should exist");
        
        Counter errorCounter = meterRegistry.find("http.errors").tag("endpoint", "/api/test").tag("method", "GET").tag("status", "500").counter();
        assertNotNull(errorCounter, "Error counter should exist");
        assertEquals(1.0, errorCounter.count(), "Error counter should be 1");
    }

    @Test
    void testEndRequest_WithHighStatusCode_RecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 404, false);
        
        // Then - Verify error counter for 4xx status
        Counter errorCounter = meterRegistry.find("http.errors").tag("endpoint", "/api/test").tag("method", "GET").tag("status", "404").counter();
        assertNotNull(errorCounter, "Error counter should exist for 404");
        assertEquals(1.0, errorCounter.count(), "Error counter should be 1");
    }

    @Test
    void testEndRequest_WithMissingSample_HandlesGracefully() {
        // When - End request without starting it
        service.endRequest("missing-correlation", "/api/test", "GET", 200, false);
        
        // Then - Should not throw exception
        assertDoesNotThrow(() -> {
            service.endRequest("missing-correlation", "/api/test", "GET", 200, false);
        });
    }

    @Test
    void testRecordThroughput_RecordsGauge() {
        // When
        service.recordThroughput("/api/test", 10.5);
        
        // Then - Verify gauge was registered
        // Gauges are harder to verify directly, but we can check the meter exists
        assertNotNull(meterRegistry.find("http.throughput").tag("endpoint", "/api/test").gauge(),
                "Throughput gauge should exist");
    }

    @Test
    void testRecordActiveConnections_RecordsGauge() {
        // When
        service.recordActiveConnections(5);
        
        // Then - Verify gauge was registered
        assertNotNull(meterRegistry.find("http.connections.active").gauge(),
                "Active connections gauge should exist");
    }

    @Test
    void testRecordDatabaseQuery_RecordsTimer() {
        // When
        service.recordDatabaseQuery("SELECT", 100);
        
        // Then - Verify timer was recorded (tag is "operation" not "query")
        Timer queryTimer = meterRegistry.find("database.query.duration").tag("operation", "SELECT").timer();
        assertNotNull(queryTimer, "Query timer should exist");
        assertEquals(1, queryTimer.count(), "Timer should have 1 measurement");
    }

    @Test
    void testRecordExternalApiCall_RecordsMetrics() {
        // When
        service.recordExternalApiCall("plaid", "/transactions", 200, true);
        
        // Then - Verify timer and counter (tag "success" is String "true", endpoint is sanitized)
        Timer apiTimer = meterRegistry.find("external.api.duration").tag("service", "plaid").tag("endpoint", "/transactions").tag("success", "true").timer();
        assertNotNull(apiTimer, "API timer should exist");
        assertEquals(1, apiTimer.count(), "Timer should have 1 measurement");
        
        Counter apiCounter = meterRegistry.find("external.api.calls").tag("service", "plaid").tag("success", "true").counter();
        assertNotNull(apiCounter, "API counter should exist");
        assertEquals(1.0, apiCounter.count(), "API counter should be 1");
    }

    @Test
    void testRecordCacheOperation_RecordsCounter() {
        // When
        service.recordCacheOperation("userCache", true);
        
        // Then - Verify counter
        Counter cacheCounter = meterRegistry.find("cache.operations").tag("cache", "userCache").tag("result", "hit").counter();
        assertNotNull(cacheCounter, "Cache counter should exist");
        assertEquals(1.0, cacheCounter.count(), "Cache counter should be 1");
    }

    @Test
    void testRecordQueueSize_RecordsGauge() {
        // When
        service.recordQueueSize("transactionQueue", 10);
        
        // Then - Verify gauge was registered
        assertNotNull(meterRegistry.find("queue.size").tag("queue", "transactionQueue").gauge(),
                "Queue size gauge should exist");
    }

    @Test
    void testSanitizeEndpoint_RemovesUUIDs() {
        // Given
        String endpoint = "/api/users/123e4567-e89b-12d3-a456-426614174000";
        
        // When - Use reflection to test private method, or test via public method
        service.startRequest("test", endpoint, "GET");
        
        // Then - Verify sanitization happens (endpoint should be sanitized to /api/users/{id})
        Counter requestsCounter = meterRegistry.find("http.requests").tag("endpoint", "/api/users/{id}").tag("method", "GET").counter();
        assertNotNull(requestsCounter, "Requests counter with sanitized endpoint should exist");
        assertEquals(1.0, requestsCounter.count(), "Counter should be 1");
    }

    @Test
    void testSanitizeEndpoint_WithNull_ReturnsUnknown() {
        // When
        service.startRequest("test", null, "GET");
        
        // Then - Should handle null gracefully by using "unknown" endpoint
        Counter requestsCounter = meterRegistry.find("http.requests").tag("endpoint", "unknown").tag("method", "GET").counter();
        assertNotNull(requestsCounter, "Requests counter with unknown endpoint should exist");
        assertEquals(1.0, requestsCounter.count(), "Counter should be 1");
    }
}

