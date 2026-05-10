package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * CloudWatch Alarms Configuration Defines alarm thresholds and configurations for monitoring
 *
 * <p>Note: This class documents the alarm configurations that should be set up in AWS CloudWatch.
 * Actual alarms should be created via CloudFormation, Terraform, or AWS Console.
 *
 * <p>Critical Alarms: - Error rate spikes - High latency (p95, p99) - Database connection pool
 * exhaustion - Memory/CPU thresholds - Failed health checks - Rate limit violations
 */
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings("PMD.DataClass")
@Configuration
public class CloudWatchAlarmsConfig {

    private static final String GREATER_THAN_THRESHOLD = "GreaterThanThreshold";

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudWatchAlarmsConfig.class);

    // Error Rate Alarms
    @Value("${app.alarms.error-rate.threshold:10}")
    private int errorRateThreshold; // errors per minute

    @Value("${app.alarms.error-rate.evaluation-periods:2}")
    private int errorRateEvaluationPeriods;

    // Latency Alarms
    @Value("${app.alarms.latency.p95.threshold-ms:1000}")
    private int latencyP95ThresholdMs;

    @Value("${app.alarms.latency.p99.threshold-ms:2000}")
    private int latencyP99ThresholdMs;

    @Value("${app.alarms.latency.evaluation-periods:3}")
    private int latencyEvaluationPeriods;

    // Database Alarms
    @Value("${app.alarms.database.connection-pool-usage.threshold:80}")
    private int databaseConnectionPoolThreshold; // percentage

    @Value("${app.alarms.database.throttled-requests.threshold:5}")
    private int databaseThrottledRequestsThreshold;

    // Resource Alarms
    @Value("${app.alarms.resource.memory.threshold:85}")
    private int memoryThreshold; // percentage

    @Value("${app.alarms.resource.cpu.threshold:80}")
    private int cpuThreshold; // percentage

    @Value("${app.alarms.resource.evaluation-periods:2}")
    private int resourceEvaluationPeriods;

    // Health Check Alarms
    @Value("${app.alarms.health-check.failure-threshold:3}")
    private int healthCheckFailureThreshold;

    // Rate Limiting Alarms
    @Value("${app.alarms.rate-limit.violations.threshold:100}")
    private int rateLimitViolationsThreshold;

    /** Get error rate alarm configuration */
    public AlarmConfig getErrorRateAlarmConfig() {
        return new AlarmConfig(
                "ErrorRate",
                errorRateThreshold,
                errorRateEvaluationPeriods,
                GREATER_THAN_THRESHOLD);
    }

    /** Get latency alarm configuration */
    public AlarmConfig getLatencyP95AlarmConfig() {
        return new AlarmConfig(
                "LatencyP95",
                latencyP95ThresholdMs,
                latencyEvaluationPeriods,
                GREATER_THAN_THRESHOLD);
    }

    /** Get latency alarm configuration */
    public AlarmConfig getLatencyP99AlarmConfig() {
        return new AlarmConfig(
                "LatencyP99",
                latencyP99ThresholdMs,
                latencyEvaluationPeriods,
                GREATER_THAN_THRESHOLD);
    }

    /** Get database connection pool alarm configuration */
    public AlarmConfig getDatabaseConnectionPoolAlarmConfig() {
        return new AlarmConfig(
                "DatabaseConnectionPool",
                databaseConnectionPoolThreshold,
                resourceEvaluationPeriods,
                GREATER_THAN_THRESHOLD);
    }

    /** Get memory alarm configuration */
    public AlarmConfig getMemoryAlarmConfig() {
        return new AlarmConfig(
                "MemoryUsage", memoryThreshold, resourceEvaluationPeriods, GREATER_THAN_THRESHOLD);
    }

    /** Get CPU alarm configuration */
    public AlarmConfig getCpuAlarmConfig() {
        return new AlarmConfig(
                "CpuUsage", cpuThreshold, resourceEvaluationPeriods, GREATER_THAN_THRESHOLD);
    }

    /** Alarm configuration data class */
    public static class AlarmConfig {
        private final String name;
        private final int threshold;
        private final int evaluationPeriods;
        private final String comparisonOperator;

        public AlarmConfig(
                final String name,
                final int threshold,
                final int evaluationPeriods,
                final String comparisonOperator) {
            this.name = name;
            this.threshold = threshold;
            this.evaluationPeriods = evaluationPeriods;
            this.comparisonOperator = comparisonOperator;
        }

        public String getName() {
            return name;
        }

        public int getThreshold() {
            return threshold;
        }

        public int getEvaluationPeriods() {
            return evaluationPeriods;
        }

        public String getComparisonOperator() {
            return comparisonOperator;
        }
    }

    /** Log alarm configurations on startup */
    @jakarta.annotation.PostConstruct
    public void logAlarmConfigurations() {
        LOGGER.info("CloudWatch Alarm Configurations:");
        LOGGER.info(
                "  Error Rate: {} errors/min ({} evaluation periods)",
                errorRateThreshold,
                errorRateEvaluationPeriods);
        LOGGER.info(
                "  Latency P95: {}ms ({} evaluation periods)",
                latencyP95ThresholdMs,
                latencyEvaluationPeriods);
        LOGGER.info(
                "  Latency P99: {}ms ({} evaluation periods)",
                latencyP99ThresholdMs,
                latencyEvaluationPeriods);
        LOGGER.info(
                "  Database Connection Pool: {}% ({} evaluation periods)",
                databaseConnectionPoolThreshold, resourceEvaluationPeriods);
        LOGGER.info(
                "  Memory: {}% ({} evaluation periods)",
                memoryThreshold, resourceEvaluationPeriods);
        LOGGER.info("  CPU: {}% ({} evaluation periods)", cpuThreshold, resourceEvaluationPeriods);
        LOGGER.info(
                "  Health Check Failures: {} consecutive failures", healthCheckFailureThreshold);
        LOGGER.info("  Rate Limit Violations: {} violations/hour", rateLimitViolationsThreshold);
        LOGGER.info(
                "Note: These alarms should be configured in AWS CloudWatch Console or via Infrastructure as Code");
    }
}
