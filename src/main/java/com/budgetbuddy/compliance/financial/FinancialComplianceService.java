package com.budgetbuddy.compliance.financial;

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
 * Financial Compliance Service
 * Implements compliance with:
 * - PCI DSS (Payment Card Industry Data Security Standard)
 * - GLBA (Gramm-Leach-Bliley Act)
 * - SOX (Sarbanes-Oxley Act)
 * - FFIEC (Federal Financial Institutions Examination Council)
 * - FINRA (Financial Industry Regulatory Authority)
 */
@Service
public class FinancialComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(FinancialComplianceService.class);

    @Autowired
    private CloudWatchClient cloudWatchClient;

    @Autowired
    private com.budgetbuddy.compliance.AuditLogService auditLogService;

    private static final String NAMESPACE = "BudgetBuddy/FinancialCompliance";

    /**
     * PCI DSS Requirement 3.4 - Render PAN unreadable
     * Ensure card numbers are encrypted
     */
    public void logCardDataAccess((final String userId, final String cardLast4, final boolean encrypted) {
        if (!encrypted) {
            logger.error("PCI DSS VIOLATION: Card data accessed without encryption: User={}, Card={}", userId, cardLast4);
            putMetric("UnencryptedCardData", 1.0, Map.of());
        } else {
            putMetric("CardDataAccess", 1.0, Map.of("Encrypted", "true"));
        }
        auditLogService.logCardDataAccess(userId, cardLast4, encrypted);
    }

    /**
     * PCI DSS Requirement 7 - Restrict Access to Cardholder Data
     * Log access to cardholder data
     */
    public void logCardholderDataAccess((final String userId, final String resource, final boolean authorized) {
        auditLogService.logCardholderDataAccess(userId, resource, authorized);
        putMetric("CardholderDataAccess", authorized ? 1.0 : 0.0, Map.of());

        if (!authorized) {
            logger.warn("PCI DSS: Unauthorized cardholder data access: User={}, Resource={}", userId, resource);
        }
    }

    /**
     * GLBA - Safeguards Rule
     * Protect customer financial information
     */
    public void logFinancialDataAccess((final String userId, final String dataType, final String action) {
        auditLogService.logFinancialDataAccess(userId, dataType, action);
        putMetric("FinancialDataAccess", 1.0, Map.of("DataType", dataType, "Action", action));
    }

    /**
     * SOX Section 302 - Corporate Responsibility for Financial Reports
     * Log financial data modifications
     */
    public void logFinancialDataModification((final String userId, final String dataType, final String beforeValue, final String afterValue) {
        auditLogService.logFinancialDataModification(userId, dataType, beforeValue, afterValue);
        putMetric("FinancialDataModification", 1.0, Map.of("DataType", dataType));

        // Alert on significant changes
        if (isSignificantChange(dataType, beforeValue, afterValue)) {
            logger.warn("SOX: Significant financial data modification: User={}, Type={}", userId, dataType);
            putMetric("SignificantFinancialChange", 1.0, Map.of("DataType", dataType));
        }
    }

    /**
     * SOX Section 404 - Management Assessment of Internal Controls
     * Log internal control activities
     */
    public void logInternalControl((final String controlId, final String activity, final boolean effective) {
        auditLogService.logInternalControl(controlId, activity, effective);
        putMetric("InternalControl", effective ? 1.0 : 0.0, Map.of("ControlId", controlId));

        if (!effective) {
            logger.warn("SOX: Ineffective internal control: Control={}, Activity={}", controlId, activity);
        }
    }

    /**
     * FFIEC - Information Security
     * Log security controls for financial institutions
     */
    public void logSecurityControl((final String controlId, final String status) {
        auditLogService.logSecurityControl(controlId, status);
        putMetric("SecurityControl", "PASS".equals(status) ? 1.0 : 0.0, Map.of("ControlId", controlId));
    }

    /**
     * FINRA - Customer Protection Rule
     * Ensure customer assets are protected
     */
    public void logCustomerAssetAccess((final String userId, final String customerId, final String assetType, final String action) {
        auditLogService.logCustomerAssetAccess(userId, customerId, assetType, action);
        putMetric("CustomerAssetAccess", 1.0, Map.of("AssetType", assetType, "Action", action));
    }

    /**
     * Transaction Monitoring
     * Monitor transactions for suspicious activity
     */
    public void monitorTransaction((final String transactionId, final double amount, final String userId) {
        if (isSuspiciousTransaction(amount, userId)) {
            logger.warn("Financial Compliance: Suspicious transaction detected: ID={}, Amount={}, User={}",
                    transactionId, amount, userId);
            putMetric("SuspiciousTransaction", 1.0, Map.of());
            auditLogService.logSuspiciousTransaction(transactionId, amount, userId);
        } else {
            putMetric("Transaction", 1.0, Map.of());
        }
    }

    /**
     * Data Retention Compliance
     * Ensure data is retained according to regulations
     */
    public void logDataRetention((final String dataType, final Instant retentionUntil) {
        auditLogService.logDataRetention(dataType, retentionUntil);
        putMetric("DataRetention", 1.0, Map.of("DataType", dataType));
    }

    private boolean isSignificantChange((final String dataType, final String beforeValue, final String afterValue) {
        // Simplified check - in production, would compare actual values
        return !beforeValue.equals(afterValue) &&
               (dataType.contains("balance") || dataType.contains("amount"));
    }

    private boolean isSuspiciousTransaction((final double amount, final String userId) {
        // Simplified check - in production, would use ML models
        return amount > 10000; // Flag transactions over $10,000
    }

    private void putMetric((final String metricName, final double value, Map<String, final String> dimensions) {
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
}

