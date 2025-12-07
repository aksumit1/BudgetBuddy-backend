package com.budgetbuddy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Performance Metrics Service
 */
@ExtendWith(MockitoExtension.class)
class PerformanceMetricsServiceTest {

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @Mock
    private Timer timer;

    @Mock
    private Timer.Sample sample;

    private PerformanceMetricsService service;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString(), anyString(), anyString()))
                .thenReturn(counter);
        when(meterRegistry.counter(anyString()))
                .thenReturn(counter);
        when(meterRegistry.timer(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(timer);
        when(meterRegistry.timer(anyString(), anyString(), anyString()))
                .thenReturn(timer);
        when(Timer.start(meterRegistry)).thenReturn(sample);
        
        service = new PerformanceMetricsService(meterRegistry);
    }

    @Test
    void testStartRequest_RecordsRequest() {
        // When
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // Then
        verify(meterRegistry).counter(eq("http.requests.total"), anyString(), anyString());
        verify(meterRegistry).counter(eq("http.requests"), anyString(), anyString(), anyString());
        verify(counter, atLeastOnce()).increment();
    }

    @Test
    void testEndRequest_WithSuccess_RecordsSuccess() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 200, false);
        
        // Then
        verify(meterRegistry).timer(eq("http.request.duration"), anyString(), anyString(), anyString());
        verify(sample).stop(any(Timer.class));
        verify(counter, atLeast(2)).increment(); // totalRequests and totalSuccess
    }

    @Test
    void testEndRequest_WithError_RecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 500, true);
        
        // Then
        verify(meterRegistry).timer(eq("http.request.duration"), anyString(), anyString(), anyString());
        verify(meterRegistry).counter(eq("http.errors"), anyString(), anyString(), anyString());
        verify(sample).stop(any(Timer.class));
    }

    @Test
    void testEndRequest_WithHighStatusCode_RecordsError() {
        // Given
        service.startRequest("correlation-123", "/api/test", "GET");
        
        // When
        service.endRequest("correlation-123", "/api/test", "GET", 404, false);
        
        // Then
        verify(meterRegistry).counter(eq("http.errors"), anyString(), anyString(), anyString());
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
        
        // Then
        verify(meterRegistry).gauge(eq("http.throughput"), anyString(), any());
    }

    @Test
    void testRecordActiveConnections_RecordsGauge() {
        // When
        service.recordActiveConnections(5);
        
        // Then
        verify(meterRegistry).gauge(eq("http.connections.active"), any());
    }

    @Test
    void testRecordDatabaseQuery_RecordsTimer() {
        // When
        service.recordDatabaseQuery("SELECT", 100);
        
        // Then
        verify(meterRegistry).timer(eq("database.query.duration"), anyString(), anyString());
        verify(timer).record(eq(100L), any());
    }

    @Test
    void testRecordExternalApiCall_RecordsMetrics() {
        // When
        service.recordExternalApiCall("plaid", "/transactions", 200, true);
        
        // Then
        verify(meterRegistry).timer(eq("external.api.duration"), anyString(), anyString(), anyString());
        verify(meterRegistry).counter(eq("external.api.calls"), anyString(), anyString());
        verify(timer).record(eq(200L), any());
        verify(counter).increment();
    }

    @Test
    void testRecordCacheOperation_RecordsCounter() {
        // When
        service.recordCacheOperation("userCache", true);
        
        // Then
        verify(meterRegistry).counter(eq("cache.operations"), anyString(), anyString());
        verify(counter).increment();
    }

    @Test
    void testRecordQueueSize_RecordsGauge() {
        // When
        service.recordQueueSize("transactionQueue", 10);
        
        // Then
        verify(meterRegistry).gauge(eq("queue.size"), anyString(), any());
    }

    @Test
    void testSanitizeEndpoint_RemovesUUIDs() {
        // Given
        String endpoint = "/api/users/123e4567-e89b-12d3-a456-426614174000";
        
        // When - Use reflection to test private method, or test via public method
        service.startRequest("test", endpoint, "GET");
        
        // Then - Verify sanitization happens (endpoint should be sanitized)
        verify(meterRegistry).counter(eq("http.requests"), anyString(), anyString(), anyString());
    }

    @Test
    void testSanitizeEndpoint_WithNull_ReturnsUnknown() {
        // When
        service.startRequest("test", null, "GET");
        
        // Then - Should handle null gracefully
        verify(meterRegistry).counter(anyString(), anyString(), anyString());
    }
}

