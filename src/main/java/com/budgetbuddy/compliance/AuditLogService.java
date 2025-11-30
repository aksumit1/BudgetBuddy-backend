package com.budgetbuddy.compliance;

import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced Audit Log Service for compliance
 * Supports SOC2, HIPAA, ISO27001, and financial compliance requirements
 * Uses DynamoDB for storage
 *
 * Thread-safe implementation with proper dependency injection
 */
@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(final AuditLogRepository auditLogRepository, final ObjectMapper objectMapper) {
        if (auditLogRepository == null) {
            throw new IllegalArgumentException("AuditLogRepository cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper cannot be null");
        }
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    // Standard audit logging
    public void logAction(final String userId, final String action, final String resourceType, final String resourceId, final Map<String, Object> details, final String ipAddress, final String userAgent) {
        if (action == null || action.isEmpty()) {
            logger.warn("Audit log action is null or empty, skipping");
            return;
        }

        try {
            AuditLogTable auditLog = new AuditLogTable();
            auditLog.setAuditLogId(UUID.randomUUID().toString());
            auditLog.setUserId(userId != null ? userId : "SYSTEM");
            auditLog.setAction(action);
            auditLog.setResourceType(resourceType != null ? resourceType : "UNKNOWN");
            auditLog.setResourceId(resourceId);
            auditLog.setDetails(convertToJson(details));
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setCreatedAt(Instant.now().getEpochSecond());

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            logger.error("Failed to save audit log: {}", e.getMessage(), e);
            // Don't throw - audit logging should not break the application
        }
    }

    // SOC2 Compliance Methods
    public void logControlActivity(final String controlId, final String activity, final String userId) {
        logAction(userId, "CONTROL_ACTIVITY", "CONTROL", controlId,
                Map.of("activity", activity != null ? activity : "", "controlId", controlId != null ? controlId : ""),
                null, null);
    }

    public void logSystemChange(final String changeType, final String description, final String userId) {
        logAction(userId, "SYSTEM_CHANGE", "SYSTEM", null,
                Map.of("changeType", changeType != null ? changeType : "",
                       "description", description != null ? description : ""), null, null);
    }

    public void logRiskAssessment(final com.budgetbuddy.compliance.soc2.SOC2ComplianceService.RiskAssessment assessment) {
        if (assessment == null) {
            return;
        }
        logAction(assessment.getUserId(), "RISK_ASSESSMENT", "RISK", assessment.getResource(),
                Map.of("riskScore", assessment.getRiskScore(), "riskLevel", assessment.getRiskLevel() != null ? assessment.getRiskLevel() : ""),
                null, null);
    }

    public void logAccessControl(final String resource, final String action, final String userId, final boolean allowed) {
        logAction(userId, "ACCESS_CONTROL", resource != null ? resource : "UNKNOWN", null,
                Map.of("action", action != null ? action : "", "allowed", allowed), null, null);
    }

    public void logSystemHealth(final com.budgetbuddy.compliance.soc2.SOC2ComplianceService.SystemHealth health) {
        if (health == null) {
            return;
        }
        logAction("SYSTEM", "HEALTH_CHECK", "SYSTEM", null,
                Map.of("availability", health.getAvailability(),
                       "performance", health.getPerformance(),
                       "errorRate", health.getErrorRate()), null, null);
    }

    public void logChangeManagement(final String changeId, final String changeType, final String description, final String userId) {
        logAction(userId, "CHANGE_MANAGEMENT", "CHANGE", changeId,
                Map.of("changeType", changeType != null ? changeType : "",
                       "description", description != null ? description : ""), null, null);
    }

    // HIPAA Compliance Methods
    public void logPHIAccess(final String userId, final String phiType, final String action, final boolean authorized) {
        logAction(userId, "PHI_ACCESS", phiType != null ? phiType : "UNKNOWN", null,
                Map.of("action", action != null ? action : "", "authorized", authorized), null, null);
    }

    public void auditPHIActivity(final String userId, final String phiId, final String activity, final String details) {
        logAction(userId, "PHI_ACTIVITY", "PHI", phiId,
                Map.of("activity", activity != null ? activity : "",
                       "details", details != null ? details : ""), null, null);
    }

    public void logPHIModification(final String userId, final String phiId, final String modificationType, final String beforeValue, final String afterValue) {
        logAction(userId, "PHI_MODIFICATION", "PHI", phiId,
                Map.of("modificationType", modificationType != null ? modificationType : "",
                       "before", beforeValue != null ? beforeValue : "",
                       "after", afterValue != null ? afterValue : ""), null, null);
    }

    public void logBreach(final com.budgetbuddy.compliance.hipaa.HIPAAComplianceService.BreachReport report) {
        if (report == null) {
            return;
        }
        logAction(report.getUserId(), "PHI_BREACH", "PHI", report.getPhiId(),
                Map.of("breachType", report.getBreachType() != null ? report.getBreachType() : "",
                       "details", report.getDetails() != null ? report.getDetails() : ""), null, null);
    }

    public void logWorkforceAccess(final String userId, final String role, final String resource, final boolean granted) {
        logAction(userId, "WORKFORCE_ACCESS", resource != null ? resource : "UNKNOWN", null,
                Map.of("role", role != null ? role : "", "granted", granted), null, null);
    }

    public void logAuthentication(final String userId, final String method, final boolean success) {
        logAction(userId, "AUTHENTICATION", "AUTH", null,
                Map.of("method", method != null ? method : "", "success", success), null, null);
    }

    // ISO27001 Compliance Methods
    public void logUserRegistration(final String userId, final String registrationType) {
        logAction(userId, "USER_REGISTRATION", "USER", userId,
                Map.of("registrationType", registrationType != null ? registrationType : ""), null, null);
    }

    public void logAccessProvisioning(final String userId, final String resource, final String accessLevel) {
        logAction(userId, "ACCESS_PROVISIONING", resource != null ? resource : "UNKNOWN", null,
                Map.of("accessLevel", accessLevel != null ? accessLevel : ""), null, null);
    }

    public void logPrivilegedAccess(final String userId, final String privilege, final String resource) {
        logAction(userId, "PRIVILEGED_ACCESS", resource != null ? resource : "UNKNOWN", null,
                Map.of("privilege", privilege != null ? privilege : ""), null, null);
    }

    public void logCredentialChange(final String userId, final String changeType) {
        logAction(userId, "CREDENTIAL_CHANGE", "CREDENTIAL", null,
                Map.of("changeType", changeType != null ? changeType : ""), null, null);
    }

    public void logAccessReview(final com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService.AccessReview review) {
        if (review == null) {
            return;
        }
        logAction(review.getUserId(), "ACCESS_REVIEW", "ACCESS", null,
                Map.of("status", review.getStatus() != null ? review.getStatus() : ""), null, null);
    }

    public void logAccessRemoval(final String userId, final String resource, final String reason) {
        logAction(userId, "ACCESS_REMOVAL", resource != null ? resource : "UNKNOWN", null,
                Map.of("reason", reason != null ? reason : ""), null, null);
    }

    public void logSecureLogon(final String userId, final String method, final boolean success) {
        logAction(userId, "SECURE_LOGON", "AUTH", null,
                Map.of("method", method != null ? method : "", "success", success), null, null);
    }

    public void logPasswordManagement(final String userId, final String activity) {
        logAction(userId, "PASSWORD_MANAGEMENT", "CREDENTIAL", null,
                Map.of("activity", activity != null ? activity : ""), null, null);
    }

    public void logSecurityEvent(final String eventType, final String severity, final String details) {
        logAction("SYSTEM", "SECURITY_EVENT", "SECURITY", null,
                Map.of("eventType", eventType != null ? eventType : "",
                       "severity", severity != null ? severity : "",
                       "details", details != null ? details : ""), null, null);
    }

    public void protectLogInformation(final String logId) {
        logAction("SYSTEM", "LOG_PROTECTION", "LOG", logId != null ? logId : "UNKNOWN",
                Map.of("action", "protect"), null, null);
    }

    public void logAdministratorActivity(final String adminId, final String activity, final String resource) {
        logAction(adminId, "ADMINISTRATOR_ACTIVITY", resource != null ? resource : "UNKNOWN", null,
                Map.of("activity", activity != null ? activity : ""), null, null);
    }

    public void logClockSynchronization(final long systemTime) {
        logAction("SYSTEM", "CLOCK_SYNCHRONIZATION", "SYSTEM", null,
                Map.of("systemTime", systemTime), null, null);
    }

    public void logSecurityIncident(final com.budgetbuddy.compliance.iso27001.ISO27001ComplianceService.SecurityIncident incident) {
        if (incident == null) {
            return;
        }
        logAction("SYSTEM", "SECURITY_INCIDENT", "SECURITY", null,
                Map.of("incidentType", incident.getIncidentType() != null ? incident.getIncidentType() : "",
                       "severity", incident.getSeverity() != null ? incident.getSeverity() : "",
                       "details", incident.getDetails() != null ? incident.getDetails() : "",
                       "status", incident.getStatus() != null ? incident.getStatus() : ""), null, null);
    }

    public void logComplianceCheck(final String legislation, final boolean compliant) {
        logAction("SYSTEM", "COMPLIANCE_CHECK", "COMPLIANCE", null,
                Map.of("legislation", legislation != null ? legislation : "", "compliant", compliant), null, null);
    }

    // Financial Compliance Methods
    public void logCardDataAccess(final String userId, final String cardLast4, final boolean encrypted) {
        logAction(userId, "CARD_DATA_ACCESS", "CARD", cardLast4 != null ? cardLast4 : "UNKNOWN",
                Map.of("encrypted", encrypted), null, null);
    }

    public void logCardholderDataAccess(final String userId, final String resource, final boolean authorized) {
        logAction(userId, "CARDHOLDER_DATA_ACCESS", resource != null ? resource : "UNKNOWN", null,
                Map.of("authorized", authorized), null, null);
    }

    public void logFinancialDataAccess(final String userId, final String dataType, final String action) {
        logAction(userId, "FINANCIAL_DATA_ACCESS", dataType != null ? dataType : "UNKNOWN", null,
                Map.of("action", action != null ? action : ""), null, null);
    }

    public void logFinancialDataModification(final String userId, final String dataType, final String beforeValue, final String afterValue) {
        logAction(userId, "FINANCIAL_DATA_MODIFICATION", dataType != null ? dataType : "UNKNOWN", null,
                Map.of("before", beforeValue != null ? beforeValue : "",
                       "after", afterValue != null ? afterValue : ""), null, null);
    }

    public void logInternalControl(final String controlId, final String activity, final boolean effective) {
        logAction("SYSTEM", "INTERNAL_CONTROL", "CONTROL", controlId != null ? controlId : "UNKNOWN",
                Map.of("activity", activity != null ? activity : "", "effective", effective), null, null);
    }

    public void logSecurityControl(final String controlId, final String status) {
        logAction("SYSTEM", "SECURITY_CONTROL", "CONTROL", controlId != null ? controlId : "UNKNOWN",
                Map.of("status", status != null ? status : ""), null, null);
    }

    public void logCustomerAssetAccess(final String userId, final String customerId, final String assetType, final String action) {
        logAction(userId, "CUSTOMER_ASSET_ACCESS", assetType != null ? assetType : "UNKNOWN", customerId,
                Map.of("action", action != null ? action : ""), null, null);
    }

    public void logSuspiciousTransaction(final String transactionId, final double amount, final String userId) {
        logAction(userId, "SUSPICIOUS_TRANSACTION", "TRANSACTION", transactionId != null ? transactionId : "UNKNOWN",
                Map.of("amount", amount), null, null);
    }

    public void logDataRetention(final String dataType, final Instant retentionUntil) {
        logAction("SYSTEM", "DATA_RETENTION", "DATA", dataType != null ? dataType : "UNKNOWN",
                Map.of("retentionUntil", retentionUntil != null ? retentionUntil.toString() : ""), null, null);
    }

    // GDPR Compliance Methods
    public void logDataExport(final String userId, final String exportId) {
        logAction(userId, "DATA_EXPORT", "DATA", exportId != null ? exportId : "UNKNOWN",
                Map.of("exportId", exportId != null ? exportId : ""), null, null);
    }

    public void logDataDeletion(final String userId) {
        logAction(userId, "DATA_DELETION", "DATA", userId, Collections.emptyMap(), null, null);
    }

    public void logDataUpdate(final String userId) {
        logAction(userId, "DATA_UPDATE", "DATA", userId, Collections.emptyMap(), null, null);
    }

    // FINRA Compliance Methods
    public void logRecordKeeping(final String recordType, final String recordId, final Instant retentionUntil) {
        logAction("SYSTEM", "RECORD_KEEPING", recordType != null ? recordType : "UNKNOWN", recordId,
                Map.of("retentionUntil", retentionUntil != null ? retentionUntil.toString() : ""), null, null);
    }

    public void logSupervision(final String supervisorId, final String supervisedUserId, final String activity, final boolean approved) {
        logAction(supervisorId, "SUPERVISION", "SUPERVISION", supervisedUserId,
                Map.of("activity", activity != null ? activity : "", "approved", approved), null, null);
    }

    public void logSuspiciousActivity(final String userId, final String activityType, final String details) {
        logAction(userId, "SUSPICIOUS_ACTIVITY", "COMPLIANCE", null,
                Map.of("activityType", activityType != null ? activityType : "",
                       "details", details != null ? details : ""), null, null);
    }

    public void logCommunication(final String userId, final String customerId, final String communicationType, final String content) {
        logAction(userId, "COMMUNICATION", communicationType != null ? communicationType : "UNKNOWN", customerId,
                Map.of("content", content != null ? content : ""), null, null);
    }

    // HIPAA Breach Notification
    public void logBreachNotification(final com.budgetbuddy.compliance.hipaa.HIPAAComplianceService.BreachReport report) {
        if (report == null) {
            return;
        }
        logAction(report.getUserId(), "BREACH_NOTIFICATION", "BREACH", report.getPhiId(),
                Map.of("breachType", report.getBreachType() != null ? report.getBreachType() : "",
                       "details", report.getDetails() != null ? report.getDetails() : "",
                       "reported", report.isReported()), null, null);
    }

    // GDPR Consent Management
    public void logConsent(final String userId, final String consentType, final boolean granted, final String purpose) {
        logAction(userId, "CONSENT", "CONSENT", null,
                Map.of("consentType", consentType != null ? consentType : "",
                       "granted", granted,
                       "purpose", purpose != null ? purpose : ""), null, null);
    }

    public void logConsentWithdrawal(final String userId, final String consentType) {
        logAction(userId, "CONSENT_WITHDRAWAL", "CONSENT", null,
                Map.of("consentType", consentType != null ? consentType : ""), null, null);
    }

    public void logDataProcessingNotification(final String userId, final String processingPurpose, final String legalBasis) {
        logAction(userId, "DATA_PROCESSING_NOTIFICATION", "GDPR", null,
                Map.of("processingPurpose", processingPurpose != null ? processingPurpose : "",
                       "legalBasis", legalBasis != null ? legalBasis : ""), null, null);
    }

    private String convertToJson(final Map<String, Object> details) {
        try {
            if (details == null || details.isEmpty()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            logger.error("Failed to serialize audit log details: {}", e.getMessage(), e);
            return "{\"error\": \"Failed to serialize details\"}";
        }
    }
}
