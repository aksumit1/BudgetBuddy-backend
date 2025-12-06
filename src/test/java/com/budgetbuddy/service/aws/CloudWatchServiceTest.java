package com.budgetbuddy.service.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for CloudWatchService
 * Tests metric batching, flushing, and CloudWatch operations
 */
@ExtendWith(MockitoExtension.class)
class CloudWatchServiceTest {

    @Mock
    private CloudWatchClient cloudWatchClient;

    @InjectMocks
    private CloudWatchService cloudWatchService;

    private String testNamespace = "BudgetBuddy";

    @BeforeEach
    void setUp() {
        cloudWatchService = new CloudWatchService(cloudWatchClient, testNamespace);
    }

    @Test
    void testPutMetric_WithValidInput_AddsToBuffer() {
        // Given
        String metricName = "TestMetric";
        double value = 10.0;
        String unit = "Count";

        // When
        cloudWatchService.putMetric(metricName, value, unit);

        // Then - Metric should be buffered (no immediate flush for single metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetric_WithBatchSize_FlushesMetrics() {
        // Given
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When - Add 20 metrics (batch size)
        for (int i = 0; i < 20; i++) {
            cloudWatchService.putMetric("Metric" + i, i, "Count");
        }

        // Then - Should flush once
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetric_WithDimensions_AddsDimensions() {
        // Given
        String metricName = "TestMetric";
        double value = 10.0;
        Map<String, String> dimensions = new HashMap<>();
        dimensions.put("Environment", "Test");
        dimensions.put("Service", "BudgetBuddy");

        // When
        cloudWatchService.putMetric(metricName, value, dimensions);

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetric_WithStandardUnit_AddsMetric() {
        // Given
        String metricName = "TestMetric";
        double value = 10.0;
        StandardUnit unit = StandardUnit.COUNT;
        Map<String, String> dimensions = new HashMap<>();

        // When
        cloudWatchService.putMetric(metricName, value, unit, dimensions);

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetrics_WithEmptyBuffer_DoesNothing() {
        // When
        cloudWatchService.flushMetrics();

        // Then
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetrics_WithBufferedMetrics_FlushesToCloudWatch() {
        // Given
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);
        cloudWatchService.putMetric("Metric1", 1.0, "Count");
        cloudWatchService.putMetric("Metric2", 2.0, "Count");

        // When
        cloudWatchService.flushMetrics();

        // Then
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetrics_WithException_HandlesGracefully() {
        // Given
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));
        cloudWatchService.putMetric("Metric1", 1.0, "Count");

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> cloudWatchService.flushMetrics());
    }

    @Test
    void testPutMetricWithDimensions_WithValidInput_AddsToBuffer() {
        // Given
        String metricName = "TestMetric";
        double value = 10.0;
        String unit = "Count";
        Dimension dimension = Dimension.builder()
                .name("Environment")
                .value("Test")
                .build();

        // When
        cloudWatchService.putMetricWithDimensions(metricName, value, unit, java.util.List.of(dimension));

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutLogEvent_WithValidInput_LogsMessage() {
        // Given
        String logGroup = "TestLogGroup";
        String logStream = "TestLogStream";
        String message = "Test log message";

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> cloudWatchService.putLogEvent(logGroup, logStream, message));
    }

    @Test
    void testCreateAlarm_WithValidInput_CreatesAlarm() {
        // Given
        String alarmName = "TestAlarm";
        String metricName = "TestMetric";
        double threshold = 100.0;
        String comparisonOperator = "GreaterThanThreshold";
        PutMetricAlarmResponse response = PutMetricAlarmResponse.builder().build();
        when(cloudWatchClient.putMetricAlarm(any(PutMetricAlarmRequest.class))).thenReturn(response);

        // When
        cloudWatchService.createAlarm(alarmName, metricName, threshold, comparisonOperator);

        // Then
        verify(cloudWatchClient, times(1)).putMetricAlarm(any(PutMetricAlarmRequest.class));
    }

    @Test
    void testCreateAlarm_WithException_HandlesGracefully() {
        // Given
        when(cloudWatchClient.putMetricAlarm(any(PutMetricAlarmRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> cloudWatchService.createAlarm("Alarm", "Metric", 100.0, "GreaterThanThreshold"));
    }

    @Test
    void testGetMetricStatistics_WithValidInput_ReturnsStatistics() {
        // Given
        String metricName = "TestMetric";
        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = Instant.now();
        
        GetMetricStatisticsResponse response = GetMetricStatisticsResponse.builder()
                .label(metricName)
                .datapoints(List.of())
                .build();
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(response);

        // When
        GetMetricStatisticsResponse result = cloudWatchService.getMetricStatistics(metricName, startTime, endTime);

        // Then
        assertNotNull(result);
        verify(cloudWatchClient, times(1)).getMetricStatistics(any(GetMetricStatisticsRequest.class));
    }

    @Test
    void testGetMetricStatistics_WithException_ReturnsNull() {
        // Given
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));

        // When
        GetMetricStatisticsResponse result = cloudWatchService.getMetricStatistics("Metric", Instant.now().minusSeconds(3600), Instant.now());

        // Then
        assertNull(result);
    }
}

