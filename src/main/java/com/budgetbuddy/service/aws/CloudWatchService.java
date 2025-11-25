package com.budgetbuddy.service.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS CloudWatch Service for metrics
 * Batches metrics to minimize API calls and costs
 */
@Service
public class CloudWatchService {

    private static final Logger logger = LoggerFactory.getLogger(CloudWatchService.class);

    private final CloudWatchClient cloudWatchClient;
    private final String namespace = "BudgetBuddy";
    private final List<MetricDatum> metricBuffer = new ArrayList<>();
    private static final int BATCH_SIZE = 20; // CloudWatch allows up to 20 metrics per request

    public CloudWatchService(final CloudWatchClient cloudWatchClient) {
        this.cloudWatchClient = cloudWatchClient;
    }

    /**
     * Put metric with batching to reduce API calls
     */
    public void putMetric((final String metricName, final double value, final String unit) {
        MetricDatum datum = MetricDatum.builder()
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

    /**
     * Flush buffered metrics to CloudWatch
     */
    public void flushMetrics() {
        if (metricBuffer.isEmpty()) {
            return;
        }

        try {
            List<MetricDatum> batch = new ArrayList<>(metricBuffer);
            metricBuffer.clear();

            PutMetricDataRequest request = PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(batch)
                    .build();

            cloudWatchClient.putMetricData(request);
            logger.debug("Flushed {} metrics to CloudWatch", batch.size());
        } catch (Exception e) {
            logger.error("Error flushing metrics to CloudWatch: {}", e.getMessage());
        }
    }

    /**
     * Put custom metric with dimensions
     */
    public void putMetricWithDimensions((final String metricName, final double value, final String unit, final List<Dimension> dimensions) {
        MetricDatum datum = MetricDatum.builder()
                .metricName(metricName)
                .value(value)
                .unit(unit)
                .dimensions(dimensions)
                .timestamp(Instant.now())
                .build();

        synchronized (metricBuffer) {
            metricBuffer.add(datum);
            if (metricBuffer.size() >= BATCH_SIZE) {
                flushMetrics();
            }
        }
    }
}

