package com.budgetbuddy.service.aws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmResponse;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

/** Unit Tests for CloudWatchService Tests metric batching, flushing, and CloudWatch operations */
@ExtendWith(MockitoExtension.class)
class CloudWatchServiceTest {

    private static final String TESTMETRIC = "TestMetric";

    @Mock private CloudWatchClient cloudWatchClient;

    @InjectMocks private CloudWatchService cloudWatchService;

    private String testNamespace = "BudgetBuddy";

    @BeforeEach
    void setUp() {
        cloudWatchService = new CloudWatchService(cloudWatchClient, testNamespace);
    }

    @Test
    void testPutMetricWithValidInputAddsToBuffer() {
        // Given
        final String metricName = TESTMETRIC;
        final double value = 10.0;
        final String unit = "Count";

        // When
        cloudWatchService.putMetric(metricName, value, unit);

        // Then - Metric should be buffered (no immediate flush for single metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetricWithBatchSizeFlushesMetrics() {
        // Given
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When - Add 20 metrics (batch size)
        for (int i = 0; i < 20; i++) {
            cloudWatchService.putMetric("Metric" + i, i, "Count");
        }

        // Then - Should flush once
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetricWithDimensionsAddsDimensions() {
        // Given
        final String metricName = TESTMETRIC;
        final double value = 10.0;
        final Map<String, String> dimensions = new HashMap<>();
        dimensions.put("Environment", "Test");
        dimensions.put("Service", "BudgetBuddy");

        // When
        cloudWatchService.putMetric(metricName, value, dimensions);

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutMetricWithStandardUnitAddsMetric() {
        // Given
        final String metricName = TESTMETRIC;
        final double value = 10.0;
        final StandardUnit unit = StandardUnit.COUNT;
        final Map<String, String> dimensions = new HashMap<>();

        // When
        cloudWatchService.putMetric(metricName, value, unit, dimensions);

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetricsWithEmptyBufferDoesNothing() {
        // When
        cloudWatchService.flushMetrics();

        // Then
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetricsWithBufferedMetricsFlushesToCloudWatch() {
        // Given
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);
        cloudWatchService.putMetric("Metric1", 1.0, "Count");
        cloudWatchService.putMetric("Metric2", 2.0, "Count");

        // When
        cloudWatchService.flushMetrics();

        // Then
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testFlushMetricsWithExceptionHandlesGracefully() {
        // Given
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));
        cloudWatchService.putMetric("Metric1", 1.0, "Count");

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> cloudWatchService.flushMetrics());
    }

    @Test
    void testPutMetricWithDimensionsWithValidInputAddsToBuffer() {
        // Given
        final String metricName = TESTMETRIC;
        final double value = 10.0;
        final String unit = "Count";
        final Dimension dimension = Dimension.builder().name("Environment").value("Test").build();

        // When
        cloudWatchService.putMetricWithDimensions(
                metricName, value, unit, java.util.List.of(dimension));

        // Then - Should not flush immediately (only 1 metric)
        verify(cloudWatchClient, never()).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testPutLogEventWithValidInputLogsMessage() {
        // Given
        final String logGroup = "TestLogGroup";
        final String logStream = "TestLogStream";
        final String message = "Test log message";

        // When/Then - Should not throw exception
        assertDoesNotThrow(() -> cloudWatchService.putLogEvent(logGroup, logStream, message));
    }

    @Test
    void testCreateAlarmWithValidInputCreatesAlarm() {
        // Given
        final String alarmName = "TestAlarm";
        final String metricName = TESTMETRIC;
        final double threshold = 100.0;
        final String comparisonOperator = "GreaterThanThreshold";
        final PutMetricAlarmResponse response = PutMetricAlarmResponse.builder().build();
        when(cloudWatchClient.putMetricAlarm(any(PutMetricAlarmRequest.class)))
                .thenReturn(response);

        // When
        cloudWatchService.createAlarm(alarmName, metricName, threshold, comparisonOperator);

        // Then
        verify(cloudWatchClient, times(1)).putMetricAlarm(any(PutMetricAlarmRequest.class));
    }

    @Test
    void testCreateAlarmWithExceptionHandlesGracefully() {
        // Given
        when(cloudWatchClient.putMetricAlarm(any(PutMetricAlarmRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));

        // When/Then - Should not throw exception
        assertDoesNotThrow(
                () ->
                        cloudWatchService.createAlarm(
                                "Alarm", "Metric", 100.0, "GreaterThanThreshold"));
    }

    @Test
    void testGetMetricStatisticsWithValidInputReturnsStatistics() {
        // Given
        final String metricName = TESTMETRIC;
        final Instant startTime = Instant.now().minusSeconds(3600);
        final Instant endTime = Instant.now();

        final GetMetricStatisticsResponse response =
                GetMetricStatisticsResponse.builder()
                        .label(metricName)
                        .datapoints(List.of())
                        .build();
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(response);

        // When
        final GetMetricStatisticsResponse result =
                cloudWatchService.getMetricStatistics(metricName, startTime, endTime);

        // Then
        assertNotNull(result);
        verify(cloudWatchClient, times(1))
                .getMetricStatistics(any(GetMetricStatisticsRequest.class));
    }

    @Test
    void testGetMetricStatisticsWithExceptionReturnsNull() {
        // Given
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch error"));

        // When
        final GetMetricStatisticsResponse result =
                cloudWatchService.getMetricStatistics(
                        "Metric", Instant.now().minusSeconds(3600), Instant.now());

        // Then
        assertNull(result);
    }
}
