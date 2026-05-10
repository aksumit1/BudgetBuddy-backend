package com.budgetbuddy.service.aws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

/**
 * AWS CloudWatch Service for metrics Batches metrics to minimize API calls and costs Merged with
 * com.budgetbuddy.aws.cloudwatch.CloudWatchService to resolve duplicate bean conflict
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service("cloudWatchService")
public class CloudWatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudWatchService.class);

    private final CloudWatchClient cloudWatchClient;
    private final String namespace;
    private final List<MetricDatum> metricBuffer = new ArrayList<>();
    private static final int BATCH_SIZE = 20; // CloudWatch allows up to 20 metrics per request

    public CloudWatchService(
            final CloudWatchClient cloudWatchClient,
            @Value("${app.aws.cloudwatch.namespace:BudgetBuddy}") final String namespace) {
        this.cloudWatchClient = cloudWatchClient;
        this.namespace = namespace;
    }

    /** Put metric with batching to reduce API calls */
    public void putMetric(final String metricName, final double value, final String unit) {
        final MetricDatum datum =
                MetricDatum.builder()
                        .metricName(metricName)
                        .value(value)
                        .unit(unit)
                        .timestamp(Instant.now())
                        .build();

        synchronized (metricBuffer) {
            metricBuffer.add(datum);
            if (metricBuffer.size() >= BATCH_SIZE) {
                flushMetrics();
            }
        }
    }

    /** Put metric with dimensions (Map version for compatibility) */
    public void putMetric(
            final String metricName, final double value, final Map<String, String> dimensions) {
        final List<Dimension> dims =
                dimensions != null
                        ? dimensions.entrySet().stream()
                        .map(
                                e ->
                                        Dimension.builder()
                                                .name(e.getKey())
                                                .value(e.getValue())
                                                .build())
                        .collect(Collectors.toList())
                        : List.of();

        putMetricWithDimensions(metricName, value, StandardUnit.COUNT.toString(), dims);
    }

    /** Put metric with unit and dimensions */
    public void putMetric(
            final String metricName,
            final double value,
            final StandardUnit unit,
            final Map<String, String> dimensions) {
        final List<Dimension> dims =
                dimensions != null
                        ? dimensions.entrySet().stream()
                        .map(
                                e ->
                                        Dimension.builder()
                                                .name(e.getKey())
                                                .value(e.getValue())
                                                .build())
                        .collect(Collectors.toList())
                        : List.of();

        putMetricWithDimensions(metricName, value, unit.toString(), dims);
    }

    /** Flush buffered metrics to CloudWatch */
    public void flushMetrics() {
        if (metricBuffer.isEmpty()) {
            return;
        }

        try {
            final List<MetricDatum> batch = new ArrayList<>(metricBuffer);
            metricBuffer.clear();

            final PutMetricDataRequest request =
                    PutMetricDataRequest.builder().namespace(namespace).metricData(batch).build();

            cloudWatchClient.putMetricData(request);
            LOGGER.debug("Flushed {} metrics to CloudWatch", batch.size());
        } catch (Exception e) {
            LOGGER.error("Error flushing metrics to CloudWatch: {}", e.getMessage());
        }
    }

    /** Put custom metric with dimensions */
    public void putMetricWithDimensions(
            final String metricName,
            final double value,
            final String unit,
            final List<Dimension> dimensions) {
        final MetricDatum datum =
                MetricDatum.builder()
                        .metricName(metricName)
                        .value(value)
                        .unit(unit)
                        .dimensions(dimensions != null ? dimensions : List.of())
                        .timestamp(Instant.now())
                        .build();

        synchronized (metricBuffer) {
            metricBuffer.add(datum);
            if (metricBuffer.size() >= BATCH_SIZE) {
                flushMetrics();
            }
        }
    }

    /** Put log event (simplified - logs to application LOGGER) */
    public void putLogEvent(final String logGroup, final String logStream, final String message) {
        try {
            // Note: CloudWatch Logs requires different API (PutLogEvents)
            // This is a simplified version - in production, use CloudWatch Logs API
            LOGGER.info("CloudWatch Log: [{}] {}", logGroup, message);
        } catch (Exception e) {
            LOGGER.error("Failed to put log event to CloudWatch: {}", e.getMessage());
        }
    }

    /** Create CloudWatch alarm */
    public void createAlarm(
            final String alarmName,
            final String metricName,
            final double threshold,
            final String comparisonOperator) {
        try {
            cloudWatchClient.putMetricAlarm(
                    PutMetricAlarmRequest.builder()
                            .alarmName(alarmName)
                            .metricName(metricName)
                            .namespace(namespace)
                            .statistic(Statistic.AVERAGE)
                            .period(300) // 5 minutes
                            .evaluationPeriods(1)
                            .threshold(threshold)
                            .comparisonOperator(ComparisonOperator.fromValue(comparisonOperator))
                            .alarmActions(List.of()) // Add SNS topic ARN for notifications
                            .build());
            LOGGER.info("CloudWatch alarm created: {}", alarmName);
        } catch (Exception e) {
            LOGGER.error("Failed to create CloudWatch alarm: {}", e.getMessage());
        }
    }

    /** Get metric statistics */
    public GetMetricStatisticsResponse getMetricStatistics(
            final String metricName, final Instant startTime, final Instant endTime) {
        try {
            return cloudWatchClient.getMetricStatistics(
                    GetMetricStatisticsRequest.builder()
                            .namespace(namespace)
                            .metricName(metricName)
                            .startTime(startTime)
                            .endTime(endTime)
                            .period(3600) // 1 hour
                            .statistics(
                                    Statistic.AVERAGE,
                                    Statistic.MAXIMUM,
                                    Statistic.MINIMUM,
                                    Statistic.SUM)
                            .build());
        } catch (Exception e) {
            LOGGER.error("Failed to get metric statistics: {}", e.getMessage());
            return null;
        }
    }
}
