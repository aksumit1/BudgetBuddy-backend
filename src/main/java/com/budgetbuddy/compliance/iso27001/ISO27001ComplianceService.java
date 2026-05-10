package com.budgetbuddy.compliance.iso27001;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.compliance.AuditLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;

/**
 * ISO/IEC 27001 Compliance Service
 *
 * <p>ISO 27001 Controls: - A.5 Information Security Policies - A.6 Organization of Information
 * Security - A.7 Human Resource Security - A.8 Asset Management - A.9 Access Control - A.10
 * Cryptography - A.11 Physical and Environmental Security - A.12 Operations Security - A.13
 * Communications Security - A.14 System Acquisition, Development and Maintenance - A.15 Supplier
 * Relationships - A.16 Information Security Incident Management - A.17 Information Security Aspects
 * of Business Continuity Management - A.18 Compliance
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class ISO27001ComplianceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ISO27001ComplianceService.class);
    private static final String NAMESPACE = "BudgetBuddy/ISO27001";

    private final AuditLogService auditLogService;
    private final CloudWatchClient cloudWatchClient;

    public ISO27001ComplianceService(
            final AuditLogService auditLogService, final CloudWatchClient cloudWatchClient) {
        this.auditLogService = auditLogService;
        this.cloudWatchClient = cloudWatchClient;
    }

    /** A.9.2.1 - User Registration and De-registration Log user registration and de-registration */
    public void logUserRegistration(final String userId, final String registrationType) {
        auditLogService.logUserRegistration(userId, registrationType);
        putMetric("UserRegistration", 1.0, Map.of("Type", registrationType));
    }

    /** A.9.2.2 - User Access Provisioning Log access provisioning */
    public void logAccessProvisioning(
            final String userId, final String resource, final String accessLevel) {
        auditLogService.logAccessProvisioning(userId, resource, accessLevel);
        putMetric("AccessProvisioning", 1.0, Map.of("AccessLevel", accessLevel));
    }

    /** A.9.2.3 - Management of Privileged Access Rights Log privileged access */
    public void logPrivilegedAccess(
            final String userId, final String privilege, final String resource) {
        auditLogService.logPrivilegedAccess(userId, privilege, resource);
        putMetric("PrivilegedAccess", 1.0, Map.of("Privilege", privilege));
    }

    /**
     * A.9.2.4 - Management of Secret Authentication Information Log authentication credential
     * changes
     */
    public void logCredentialChange(final String userId, final String changeType) {
        auditLogService.logCredentialChange(userId, changeType);
        putMetric("CredentialChange", 1.0, Map.of("ChangeType", changeType));
    }

    /** A.9.2.5 - Review of User Access Rights Review and log user access rights */
    public void reviewUserAccessRights(final String userId) {
        final AccessReview review = new AccessReview();
        review.setUserId(userId);
        review.setTimestamp(Instant.now());
        review.setStatus("PENDING");

        auditLogService.logAccessReview(review);
        putMetric("AccessReview", 1.0, Map.of());
    }

    /** A.9.2.6 - Removal or Adjustment of Access Rights Log access rights removal */
    public void logAccessRemoval(final String userId, final String resource, final String reason) {
        auditLogService.logAccessRemoval(userId, resource, reason);
        putMetric("AccessRemoval", 1.0, Map.of());
    }

    /** A.9.4.2 - Secure Log-on Procedures Log secure log-on procedures */
    public void logSecureLogon(final String userId, final String method, final boolean success) {
        auditLogService.logSecureLogon(userId, method, success);
        putMetric("SecureLogon", success ? 1.0 : 0.0, Map.of("Method", method));
    }

    /** A.9.4.3 - Password Management System Log password management activities */
    public void logPasswordManagement(final String userId, final String activity) {
        auditLogService.logPasswordManagement(userId, activity);
        putMetric("PasswordManagement", 1.0, Map.of("Activity", activity));
    }

    /** A.12.4.1 - Event Logging Log security events */
    public void logSecurityEvent(
            final String eventType, final String severity, final String details) {
        auditLogService.logSecurityEvent(eventType, severity, details);
        putMetric("SecurityEvent", 1.0, Map.of("EventType", eventType, "Severity", severity));
    }

    /** A.12.4.2 - Protection of Log Information Ensure log information is protected */
    public void protectLogInformation(final String logId) {
        // In production, this would encrypt and backup logs
        auditLogService.protectLogInformation(logId);
        putMetric("LogProtection", 1.0, Map.of());
    }

    /** A.12.4.3 - Administrator and Operator Logs Log administrator activities */
    public void logAdministratorActivity(
            final String adminId, final String activity, final String resource) {
        auditLogService.logAdministratorActivity(adminId, activity, resource);
        putMetric("AdministratorActivity", 1.0, Map.of("Activity", activity));
    }

    /** A.12.4.4 - Clock Synchronization Ensure system clocks are synchronized */
    public void logClockSynchronization() {
        final long systemTime = Instant.now().getEpochSecond();
        // In production, compare with NTP server
        auditLogService.logClockSynchronization(systemTime);
        putMetric("ClockSynchronization", 1.0, Map.of());
    }

    /** A.16.1.2 - Reporting Information Security Events Report security incidents */
    public void reportSecurityIncident(
            final String incidentType, final String severity, final String details) {
        final SecurityIncident incident = new SecurityIncident();
        incident.setIncidentType(incidentType);
        incident.setSeverity(severity);
        incident.setDetails(details);
        incident.setTimestamp(Instant.now());
        incident.setStatus("OPEN");

        auditLogService.logSecurityIncident(incident);
        putMetric(
                "SecurityIncident",
                1.0,
                Map.of("IncidentType", incidentType, "Severity", severity));

        if ("CRITICAL".equals(severity)) {
            LOGGER.error(
                    "ISO27001: Critical security incident: Type={}, Details={}",
                    incidentType,
                    details);
        }
    }

    /**
     * A.18.1.1 - Identification of Applicable Legislation Log compliance with applicable
     * legislation
     */
    public void logComplianceCheck(final String legislation, final boolean compliant) {
        auditLogService.logComplianceCheck(legislation, compliant);
        putMetric("ComplianceCheck", compliant ? 1.0 : 0.0, Map.of("Legislation", legislation));

        if (!compliant) {
            LOGGER.warn("ISO27001: Non-compliance detected: Legislation={}", legislation);
        }
    }

    private void putMetric(
            final String metricName, final double value, final Map<String, String> dimensions) {
        try {
            final List<Dimension> dims =
                    dimensions.entrySet().stream()
                            .map(
                                    e ->
                                            Dimension.builder()
                                                    .name(e.getKey())
                                                    .value(e.getValue())
                                                    .build())
                            .toList();

            cloudWatchClient.putMetricData(
                    PutMetricDataRequest.builder()
                            .namespace(NAMESPACE)
                            .metricData(
                                    MetricDatum.builder()
                                            .metricName(metricName)
                                            .value(value)
                                            .timestamp(Instant.now())
                                            .dimensions(dims)
                                            .build())
                            .build());
        } catch (Exception e) {
            LOGGER.error("Failed to put metric to CloudWatch: {}", e.getMessage());
        }
    }

    /** Access Review DTO */
    public static class AccessReview {
        private String userId;
        private Instant timestamp;
        private String status;

        // Getters and setters
        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(final Instant timestamp) {
            this.timestamp = timestamp;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(final String status) {
            this.status = status;
        }
    }

    /** Security Incident DTO */
    public static class SecurityIncident {
        private String incidentType;
        private String severity;
        private String details;
        private Instant timestamp;
        private String status;

        // Getters and setters
        public String getIncidentType() {
            return incidentType;
        }

        public void setIncidentType(final String incidentType) {
            this.incidentType = incidentType;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(final String severity) {
            this.severity = severity;
        }

        public String getDetails() {
            return details;
        }

        public void setDetails(final String details) {
            this.details = details;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(final Instant timestamp) {
            this.timestamp = timestamp;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(final String status) {
            this.status = status;
        }
    }
}
