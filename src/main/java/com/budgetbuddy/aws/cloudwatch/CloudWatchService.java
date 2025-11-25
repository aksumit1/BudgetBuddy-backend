package com.budgetbuddy.aws.cloudwatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AWS CloudWatch Integration Service
 * Provides comprehensive monitoring and logging
 */
@Service
public class CloudWatchService {

    private static final Logger logger = LoggerFactory.getLogger(CloudWatchService.class);

    private final CloudWatchClient cloudWatchClient;
    private final String namespace;

    public CloudWatchService(
            CloudWatchClient cloudWatchClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.cloudwatch.namespace:BudgetBuddy}") String namespace) {
        this.cloudWatchClient = cloudWatchClient;
        this.namespace = namespace;
    }

    /**
     * Put custom metric
     */
    public void putMetric(String metricName, double value, Map<String, String> dimensions) {
        try {
            List<Dimension> dims = dimensions != null ? dimensions.entrySet().stream()
                    .map(e -> Dimension.builder()
                            .name(e.getKey())
                            .value(e.getValue())
                            .build())
                    .toList() : List.of();

            cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(MetricDatum.builder()
                            .metricName(metricName)
                            .value(value)
                            .timestamp(Instant.now())
                            .dimensions(dims)
                            .unit(StandardUnit.COUNT)
                            .build())
                    .build());
        } catch (Exception e) {
            logger.error("Failed to put metric to CloudWatch: {}", e.getMessage());
        }
    }

    /**
     * Put metric with unit
     */
    public void putMetric(String metricName, double value, StandardUnit unit, Map<String, String> dimensions) {
        try {
            List<Dimension> dims = dimensions != null ? dimensions.entrySet().stream()
                    .map(e -> Dimension.builder()
                            .name(e.getKey())
                            .value(e.getValue())
                            .build())
                    .toList() : List.of();

            cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(MetricDatum.builder()
                            .metricName(metricName)
                            .value(value)
                            .timestamp(Instant.now())
                            .dimensions(dims)
                            .unit(unit)
                            .build())
                    .build());
        } catch (Exception e) {
            logger.error("Failed to put metric to CloudWatch: {}", e.getMessage());
        }
    }

    /**
     * Put log event
     */
    public void putLogEvent(String logGroup, String logStream, String message) {
        try {
            // Note: CloudWatch Logs requires different API (PutLogEvents)
            // This is a simplified version - in production, use CloudWatch Logs API
            logger.info("CloudWatch Log: [{}] {}", logGroup, message);
        } catch (Exception e) {
            logger.error("Failed to put log event to CloudWatch: {}", e.getMessage());
        }
    }

    /**
     * Create CloudWatch alarm
     */
    public void createAlarm(String alarmName, String metricName, double threshold, String comparisonOperator) {
        try {
            cloudWatchClient.putMetricAlarm(PutMetricAlarmRequest.builder()
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
            logger.info("CloudWatch alarm created: {}", alarmName);
        } catch (Exception e) {
            logger.error("Failed to create CloudWatch alarm: {}", e.getMessage());
        }
    }

    /**
     * Get metric statistics
     */
    public GetMetricStatisticsResponse getMetricStatistics(String metricName, Instant startTime, Instant endTime) {
        try {
            return cloudWatchClient.getMetricStatistics(GetMetricStatisticsRequest.builder()
                    .namespace(namespace)
                    .metricName(metricName)
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(3600) // 1 hour
                    .statistics(Statistic.AVERAGE, Statistic.MAXIMUM, Statistic.MINIMUM, Statistic.SUM)
                    .build());
        } catch (Exception e) {
            logger.error("Failed to get metric statistics: {}", e.getMessage());
            return null;
        }
    }
}

