package com.budgetbuddy.compliance.soc2;

import com.budgetbuddy.compliance.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SOC 2 Type II Compliance Service
 *
 * SOC 2 Trust Service Criteria:
 * 1. Security - Protection against unauthorized access
 * 2. Availability - System is available for operation
 * 3. Processing Integrity - System processing is complete, valid, accurate, timely, and authorized
 * 4. Confidentiality - Information designated as confidential is protected
 * 5. Privacy - Personal information is collected, used, retained, disclosed, and disposed in conformity with commitments
 */
@Service
public class SOC2ComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(SOC2ComplianceService.class);
    private static final String NAMESPACE = "BudgetBuddy/SOC2";

    private final AuditLogService auditLogService;
    private final CloudWatchClient cloudWatchClient;

    public SOC2ComplianceService(
            final AuditLogService auditLogService,
            final CloudWatchClient cloudWatchClient) {
        this.auditLogService = auditLogService;
        this.cloudWatchClient = cloudWatchClient;
    }

    /**
     * CC1.1 - Control Environment
     * Log control activities
     */
    public void logControlActivity(final String controlId, final String activity, final String userId) {
        auditLogService.logControlActivity(controlId, activity, userId);

        // Send metric to CloudWatch
        putMetric("ControlActivity", 1.0, Map.of(
                "ControlId", controlId,
                "Activity", activity
        ));
    }

    /**
     * CC2.1 - Communication and Information
     * Log information system changes
     */
    public void logSystemChange(final String changeType, final String description, final String userId) {
        auditLogService.logSystemChange(changeType, description, userId);
        putMetric("SystemChange", 1.0, Map.of("ChangeType", changeType));
    }

    /**
     * CC3.1 - Risk Assessment
     * Assess and log risks
     */
    public RiskAssessment assessRisk(final String resource, final String action, final String userId) {
        RiskAssessment assessment = new RiskAssessment();
        assessment.setResource(resource);
        assessment.setAction(action);
        assessment.setUserId(userId);
        assessment.setTimestamp(Instant.now());

        // Calculate risk score
        int riskScore = calculateRiskScore(resource, action);
        assessment.setRiskScore(riskScore);
        assessment.setRiskLevel(riskScore > 70 ? "HIGH" : riskScore > 40 ? "MEDIUM" : "LOW");

        // Log risk assessment
        auditLogService.logRiskAssessment(assessment);
        putMetric("RiskAssessment", (double) riskScore, Map.of("RiskLevel", assessment.getRiskLevel()));

        return assessment;
    }

    /**
     * CC4.1 - Monitoring Activities
     * Monitor system activities for anomalies
     */
    public void monitorActivity(final String activityType, final String details) {
        // Check for anomalies
        if (isAnomalous(activityType, details)) {
            logger.warn("SOC2: Anomalous activity detected: {} - {}", activityType, details);
            putMetric("AnomalousActivity", 1.0, Map.of("ActivityType", activityType));
        }

        putMetric("Activity", 1.0, Map.of("ActivityType", activityType));
    }

    /**
     * CC5.1 - Control Activities
     * Log control activities with status
     */
    public void logControlActivityWithStatus(final String controlId, final String status, final String details) {
        auditLogService.logControlActivity(controlId, status, details);
        putMetric("ControlActivity", status.equals("PASS") ? 1.0 : 0.0, Map.of("ControlId", controlId));
    }

    /**
     * CC6.1 - Logical and Physical Access Controls
     * Log access control activities
     */
    public void logAccessControl(final String resource, final String action, final String userId, final boolean allowed) {
        auditLogService.logAccessControl(resource, action, userId, allowed);
        putMetric("AccessControl", allowed ? 1.0 : 0.0, Map.of(
                "Resource", resource,
                "Action", action
        ));
    }

    /**
     * CC7.1 - System Operations
     * Monitor system operations
     */
    public SystemHealth checkSystemHealth() {
        SystemHealth health = new SystemHealth();
        health.setTimestamp(Instant.now());
        health.setAvailability(calculateAvailability());
        health.setPerformance(calculatePerformance());
        health.setErrorRate(calculateErrorRate());

        // Log health check
        auditLogService.logSystemHealth(health);
        putMetric("SystemHealth", health.getAvailability(), Map.of());

        return health;
    }

    /**
     * CC8.1 - Change Management
     * Log system changes
     */
    public void logChangeManagement(final String changeId, final String changeType, final String description, final String userId) {
        auditLogService.logChangeManagement(changeId, changeType, description, userId);
        putMetric("ChangeManagement", 1.0, Map.of("ChangeType", changeType));
    }

    private int calculateRiskScore(final String resource, final String action) {
        // Simplified risk calculation
        int score = 0;
        if (resource.contains("/admin") || resource.contains("/compliance")) {
            score += 50;
        }
        if ("DELETE".equals(action) || "UPDATE".equals(action)) {
            score += 30;
        }
        return score;
    }

    private boolean isAnomalous(final String activityType, final String details) {
        // Simplified anomaly detection
        return details.contains("unauthorized") || details.contains("failed") || details.contains("error");
    }

    private double calculateAvailability() {
        // Calculate system availability percentage
        // In production, this would query CloudWatch metrics
        return 99.9; // Placeholder
    }

    private double calculatePerformance() {
        // Calculate system performance
        return 95.0; // Placeholder
    }

    private double calculateErrorRate() {
        // Calculate error rate
        return 0.1; // Placeholder
    }

    private void putMetric(final String metricName, final double value, final Map<String, String> dimensions) {
        try {
            List<Dimension> dims = dimensions.entrySet().stream()
                    .map(e -> Dimension.builder()
                            .name(e.getKey())
                            .value(e.getValue())
                            .build())
                    .toList();

            cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(MetricDatum.builder()
                            .metricName(metricName)
                            .value(value)
                            .timestamp(Instant.now())
                            .dimensions(dims)
                            .build())
                    .build());
        } catch (Exception e) {
            logger.error("Failed to put metric to CloudWatch: {}", e.getMessage());
        }
    }

    /**
     * Risk Assessment DTO
     */
    public static class RiskAssessment {
        private String resource;
        private String action;
        private String userId;
        private Instant timestamp;
        private int riskScore;
        private String riskLevel;

        // Getters and setters
        public String getResource() { return resource; }
        public void setResource(final String resource) { this.resource = resource; }
        public String getAction() { return action; }
        public void setAction(final String action) { this.action = action; }
        public String getUserId() { return userId; }
        public void setUserId(final String userId) { this.userId = userId; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
        public int getRiskScore() { return riskScore; }
        public void setRiskScore(final int riskScore) { this.riskScore = riskScore; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(final String riskLevel) { this.riskLevel = riskLevel; }
    }

    /**
     * System Health DTO
     */
    public static class SystemHealth {
        private Instant timestamp;
        private double availability;
        private double performance;
        private double errorRate;

        // Getters and setters
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
        public double getAvailability() { return availability; }
        public void setAvailability(final double availability) { this.availability = availability; }
        public double getPerformance() { return performance; }
        public void setPerformance(final double performance) { this.performance = performance; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(final double errorRate) { this.errorRate = errorRate; }
    }
}

