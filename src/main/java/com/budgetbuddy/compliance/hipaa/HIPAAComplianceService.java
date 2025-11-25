package com.budgetbuddy.compliance.hipaa;

import com.budgetbuddy.compliance.AuditLogService;
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
 * HIPAA Compliance Service
 * 
 * HIPAA Requirements:
 * - Administrative Safeguards (164.308)
 * - Physical Safeguards (164.310)
 * - Technical Safeguards (164.312)
 * - Breach Notification (164.400-414)
 */
@Service
public class HIPAAComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(HIPAAComplianceService.class);

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private CloudWatchClient cloudWatchClient;

    private static final String NAMESPACE = "BudgetBuddy/HIPAA";

    /**
     * §164.312(a)(1) - Access Control
     * Unique user identification and access controls
     */
    public void logPHIAccess(String userId, String phiType, String action, boolean authorized) {
        auditLogService.logPHIAccess(userId, phiType, action, authorized);
        
        if (!authorized) {
            logger.warn("HIPAA: Unauthorized PHI access attempt: User={}, Type={}, Action={}", userId, phiType, action);
            putMetric("UnauthorizedPHIAccess", 1.0, Map.of("PHIType", phiType));
        } else {
            putMetric("PHIAccess", 1.0, Map.of("PHIType", phiType, "Action", action));
        }
    }

    /**
     * §164.312(b) - Audit Controls
     * Log all PHI access and modifications
     */
    public void auditPHIActivity(String userId, String phiId, String activity, String details) {
        auditLogService.auditPHIActivity(userId, phiId, activity, details);
        putMetric("PHIActivity", 1.0, Map.of("Activity", activity));
    }

    /**
     * §164.312(c)(1) - Integrity
     * Ensure PHI is not improperly altered or destroyed
     */
    public void logPHIModification(String userId, String phiId, String modificationType, String beforeValue, String afterValue) {
        auditLogService.logPHIModification(userId, phiId, modificationType, beforeValue, afterValue);
        putMetric("PHIModification", 1.0, Map.of("ModificationType", modificationType));
    }

    /**
     * §164.312(e)(1) - Transmission Security
     * Encrypt PHI during transmission
     */
    public void logPHITransmission(String userId, String destination, boolean encrypted) {
        if (!encrypted) {
            logger.error("HIPAA VIOLATION: PHI transmitted without encryption: User={}, Destination={}", userId, destination);
            putMetric("UnencryptedPHITransmission", 1.0, Map.of());
        } else {
            putMetric("PHITransmission", 1.0, Map.of("Encrypted", "true"));
        }
    }

    /**
     * §164.312(a)(2)(iv) - Automatic Logoff
     * Implement automatic logoff after inactivity
     */
    public void checkSessionTimeout(String userId, long lastActivityTime) {
        long inactivityMinutes = (Instant.now().getEpochSecond() - lastActivityTime) / 60;
        if (inactivityMinutes > 15) { // 15 minute timeout
            logger.warn("HIPAA: Session timeout exceeded: User={}, Inactivity={} minutes", userId, inactivityMinutes);
            putMetric("SessionTimeout", 1.0, Map.of());
        }
    }

    /**
     * §164.400-414 - Breach Notification
     * Detect and report PHI breaches
     */
    public void reportBreach(String userId, String phiId, String breachType, String details) {
        BreachReport report = new BreachReport();
        report.setUserId(userId);
        report.setPhiId(phiId);
        report.setBreachType(breachType);
        report.setDetails(details);
        report.setTimestamp(Instant.now());
        report.setReported(false);

        // Log breach
        auditLogService.logBreach(report);
        putMetric("PHIBreach", 1.0, Map.of("BreachType", breachType));

        // In production, this would trigger:
        // 1. Immediate notification to security team
        // 2. Notification to affected individuals within 60 days
        // 3. Notification to HHS within 60 days (if >500 individuals affected)
        logger.error("HIPAA BREACH DETECTED: User={}, PHI={}, Type={}, Details={}", userId, phiId, breachType, details);
    }

    /**
     * §164.308(a)(3) - Workforce Security
     * Ensure workforce members have appropriate access
     */
    public void logWorkforceAccess(String userId, String role, String resource, boolean granted) {
        auditLogService.logWorkforceAccess(userId, role, resource, granted);
        putMetric("WorkforceAccess", granted ? 1.0 : 0.0, Map.of("Role", role));
    }

    /**
     * §164.308(a)(4) - Information Access Management
     * Implement policies for access to PHI
     */
    public boolean checkPHIAccessPolicy(String userId, String phiType) {
        // Check if user has access to specific PHI type
        // In production, this would check role-based access policies
        boolean hasAccess = true; // Placeholder
        
        logPHIAccess(userId, phiType, "READ", hasAccess);
        return hasAccess;
    }

    /**
     * §164.312(d) - Person or Entity Authentication
     * Implement procedures to verify identity
     */
    public void logAuthentication(String userId, String method, boolean success) {
        auditLogService.logAuthentication(userId, method, success);
        putMetric("Authentication", success ? 1.0 : 0.0, Map.of("Method", method));
        
        if (!success) {
            logger.warn("HIPAA: Authentication failed: User={}, Method={}", userId, method);
        }
    }

    private void putMetric(String metricName, double value, Map<String, String> dimensions) {
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
     * Breach Report DTO
     */
    public static class BreachReport {
        private String userId;
        private String phiId;
        private String breachType;
        private String details;
        private Instant timestamp;
        private boolean reported;

        // Getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getPhiId() { return phiId; }
        public void setPhiId(String phiId) { this.phiId = phiId; }
        public String getBreachType() { return breachType; }
        public void setBreachType(String breachType) { this.breachType = breachType; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public boolean isReported() { return reported; }
        public void setReported(boolean reported) { this.reported = reported; }
    }
}

