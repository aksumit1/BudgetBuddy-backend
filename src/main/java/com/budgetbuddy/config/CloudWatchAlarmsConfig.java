package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * CloudWatch Alarms Configuration
 * Defines alarm thresholds and configurations for monitoring
 *
 * Note: This class documents the alarm configurations that should be set up in AWS CloudWatch.
 * Actual alarms should be created via CloudFormation, Terraform, or AWS Console.
 *
 * Critical Alarms:
 * - Error rate spikes
 * - High latency (p95, p99)
 * - Database connection pool exhaustion
 * - Memory/CPU thresholds
 * - Failed health checks
 * - Rate limit violations
 */
@Configuration
public class CloudWatchAlarmsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CloudWatchAlarmsConfig.class);

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

    /**
     * Get error rate alarm configuration
     */
    public AlarmConfig getErrorRateAlarmConfig() {
        return new AlarmConfig(
                "ErrorRate",
                errorRateThreshold,
                errorRateEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Get latency alarm configuration
     */
    public AlarmConfig getLatencyP95AlarmConfig() {
        return new AlarmConfig(
                "LatencyP95",
                latencyP95ThresholdMs,
                latencyEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Get latency alarm configuration
     */
    public AlarmConfig getLatencyP99AlarmConfig() {
        return new AlarmConfig(
                "LatencyP99",
                latencyP99ThresholdMs,
                latencyEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Get database connection pool alarm configuration
     */
    public AlarmConfig getDatabaseConnectionPoolAlarmConfig() {
        return new AlarmConfig(
                "DatabaseConnectionPool",
                databaseConnectionPoolThreshold,
                resourceEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Get memory alarm configuration
     */
    public AlarmConfig getMemoryAlarmConfig() {
        return new AlarmConfig(
                "MemoryUsage",
                memoryThreshold,
                resourceEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Get CPU alarm configuration
     */
    public AlarmConfig getCpuAlarmConfig() {
        return new AlarmConfig(
                "CpuUsage",
                cpuThreshold,
                resourceEvaluationPeriods,
                "GreaterThanThreshold"
        );
    }

    /**
     * Alarm configuration data class
     */
    public static class AlarmConfig {
        private final String name;
        private final int threshold;
        private final int evaluationPeriods;
        private final String comparisonOperator;

        public AlarmConfig(String name, int threshold, int evaluationPeriods, String comparisonOperator) {
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

    /**
     * Log alarm configurations on startup
     */
    @jakarta.annotation.PostConstruct
    public void logAlarmConfigurations() {
        logger.info("CloudWatch Alarm Configurations:");
        logger.info("  Error Rate: {} errors/min ({} evaluation periods)", errorRateThreshold, errorRateEvaluationPeriods);
        logger.info("  Latency P95: {}ms ({} evaluation periods)", latencyP95ThresholdMs, latencyEvaluationPeriods);
        logger.info("  Latency P99: {}ms ({} evaluation periods)", latencyP99ThresholdMs, latencyEvaluationPeriods);
        logger.info("  Database Connection Pool: {}% ({} evaluation periods)", databaseConnectionPoolThreshold, resourceEvaluationPeriods);
        logger.info("  Memory: {}% ({} evaluation periods)", memoryThreshold, resourceEvaluationPeriods);
        logger.info("  CPU: {}% ({} evaluation periods)", cpuThreshold, resourceEvaluationPeriods);
        logger.info("  Health Check Failures: {} consecutive failures", healthCheckFailureThreshold);
        logger.info("  Rate Limit Violations: {} violations/hour", rateLimitViolationsThreshold);
        logger.info("Note: These alarms should be configured in AWS CloudWatch Console or via Infrastructure as Code");
    }
}

