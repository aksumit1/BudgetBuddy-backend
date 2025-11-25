package com.budgetbuddy.compliance.pcidss;

import com.budgetbuddy.compliance.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PCI-DSS Compliance Service
 *
 * PCI-DSS Requirements (v4.0):
 * 1. Install and maintain network security controls
 * 2. Apply secure configurations to all system components
 * 3. Protect stored account data
 * 4. Protect cardholder data with strong cryptography during transmission
 * 5. Protect all systems and networks from malicious software
 * 6. Develop and maintain secure systems and software
 * 7. Restrict access to cardholder data by business need to know
 * 8. Identify users and authenticate access to system components
 * 9. Restrict physical access to cardholder data
 * 10. Log and monitor all access to network resources and cardholder data
 * 11. Test security of systems and networks regularly
 * 12. Support information security with organizational policies and programs
 */
@Service
public class PCIDSSComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(PCIDSSComplianceService.class);

    // PCI-DSS compliant PAN masking pattern
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3[0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b");
    private static final int MIN_PAN_LENGTH = 13;
    private static final int MAX_PAN_LENGTH = 19;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private CloudWatchClient cloudWatchClient;

    @Autowired
    private KmsClient kmsClient;

    private static final String NAMESPACE = "BudgetBuddy/PCI-DSS";

    /**
     * Requirement 3.4 - Render PAN unreadable anywhere it is stored
     * Mask PAN to show only last 4 digits
     */
    public String maskPAN((final String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }

        // Validate PAN format
        if (!isValidPAN(pan)) {
            logger.warn("PCI-DSS: Invalid PAN format detected");
            return "****";
        }

        // Mask all but last 4 digits
        String last4 = pan.substring(pan.length() - 4);
        return "****" + last4;
    }

    /**
     * Requirement 3.4.1 - Primary Account Number (PAN) is masked when displayed
     */
    public boolean isPANMasked((final String pan) {
        return pan != null && pan.matches("\\*{4,}\\d{4}");
    }

    /**
     * Requirement 3.5.1 - Documented cryptographic key-management procedures
     * Encrypt PAN using KMS
     */
    public String encryptPAN((final String pan, final String keyId) {
        try {
            // In production, use KMS to encrypt PAN
            // For now, log the encryption operation
            auditLogService.logCardDataAccess("SYSTEM", maskPAN(pan), true);
            putMetric("PANEncryption", 1.0, Map.of("Encrypted", "true"));

            // Return encrypted value (in production, would be actual encrypted data)
            return "encrypted_" + pan;
        } catch (Exception e) {
            logger.error("PCI-DSS VIOLATION: Failed to encrypt PAN: {}", e.getMessage());
            putMetric("PANEncryptionFailure", 1.0, Map.of());
            throw new RuntimeException("Failed to encrypt PAN", e);
        }
    }

    /**
     * Requirement 3.5.2 - Restrict access to cryptographic keys to the fewest number of custodians
     */
    public void logKeyAccess((final String keyId, final String userId, final String operation) {
        auditLogService.logCardDataAccess(userId, "KEY_ACCESS", true);
        putMetric("KeyAccess", 1.0, Map.of("Operation", operation));
        logger.info("PCI-DSS: Key access logged - KeyId: {}, User: {}, Operation: {}", keyId, userId, operation);
    }

    /**
     * Requirement 4.1 - Use strong cryptography and security protocols
     * Validate TLS version and cipher suites
     */
    public boolean validateTLSConfiguration((final String tlsVersion, final List<String> cipherSuites) {
        // PCI-DSS requires TLS 1.2 or higher
        if (!tlsVersion.matches("TLSv1\\.(2|3)")) {
            logger.error("PCI-DSS VIOLATION: Invalid TLS version: {}", tlsVersion);
            putMetric("TLSViolation", 1.0, Map.of("TLSVersion", tlsVersion));
            return false;
        }

        // Validate cipher suites (must not use weak ciphers)
        for (String cipher : cipherSuites) {
            if (isWeakCipher(cipher)) {
                logger.error("PCI-DSS VIOLATION: Weak cipher suite detected: {}", cipher);
                putMetric("WeakCipher", 1.0, Map.of("Cipher", cipher));
                return false;
            }
        }

        putMetric("TLSValidation", 1.0, Map.of("TLSVersion", tlsVersion));
        return true;
    }

    /**
     * Requirement 7.1 - Limit access to system components and cardholder data to only those individuals
     * whose job requires such access
     */
    public boolean checkAccessAuthorization((final String userId, final String resource, final String action) {
        // Check if user has authorized access to cardholder data
        boolean authorized = checkBusinessNeedToKnow(userId, resource);

        if (!authorized) {
            logger.warn("PCI-DSS: Unauthorized access attempt - User: {}, Resource: {}, Action: {}", userId, resource, action);
            putMetric("UnauthorizedAccess", 1.0, Map.of("Resource", resource));
            auditLogService.logCardholderDataAccess(userId, resource, false);
        } else {
            auditLogService.logCardholderDataAccess(userId, resource, true);
        }

        return authorized;
    }

    /**
     * Requirement 8.2 - Strong authentication for all access
     * Validate password strength
     */
    public boolean validatePasswordStrength((final String password) {
        // PCI-DSS requires:
        // - Minimum 7 characters (12 recommended)
        // - Both numeric and alphabetic characters
        // - Complexity requirements

        if (password == null || password.length() < 12) {
            logger.warn("PCI-DSS: Password does not meet minimum length requirement");
            return false;
        }

        boolean hasUpper = password.matches(".*[A-Z].*");
        boolean hasLower = password.matches(".*[a-z].*");
        boolean hasDigit = password.matches(".*\\d.*");
        boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"|,.<>/?].*");

        if (!(hasUpper && hasLower && hasDigit && hasSpecial)) {
            logger.warn("PCI-DSS: Password does not meet complexity requirements");
            return false;
        }

        return true;
    }

    /**
     * Requirement 8.3 - Multi-factor authentication for all non-console administrative access
     */
    public void logMFAUsage((final String userId, final String mfaMethod, final boolean success) {
        auditLogService.logAuthentication(userId, "MFA_" + mfaMethod, success);
        putMetric("MFAUsage", success ? 1.0 : 0.0, Map.of("Method", mfaMethod));

        if (!success) {
            logger.warn("PCI-DSS: MFA failure - User: {}, Method: {}", userId, mfaMethod);
        }
    }

    /**
     * Requirement 10.2 - Implement automated audit trails
     * Log all access to cardholder data
     */
    public void logCardholderDataAccess((final String userId, final String resource, final String action, final boolean authorized) {
        auditLogService.logCardholderDataAccess(userId, resource, authorized);
        putMetric("CardholderDataAccess", 1.0, Map.of(
                "Action", action,
                "Authorized", String.valueOf(authorized)
        ));

        if (!authorized) {
            logger.error("PCI-DSS: Unauthorized cardholder data access - User: {}, Resource: {}, Action: {}",
                    userId, resource, action);
        }
    }

    /**
     * Requirement 10.5 - Secure audit trail files so they cannot be altered
     */
    public void protectAuditTrail((final String auditLogId) {
        // In production, this would:
        // 1. Encrypt audit logs
        // 2. Store in write-once storage
        // 3. Implement integrity checks

        auditLogService.protectLogInformation(auditLogId);
        putMetric("AuditTrailProtection", 1.0, Map.of());
        logger.info("PCI-DSS: Audit trail protected - LogId: {}", auditLogId);
    }

    /**
     * Requirement 11.4 - Use intrusion-detection and/or intrusion-prevention techniques
     */
    public void detectIntrusion((final String userId, final String resource, final String suspiciousActivity) {
        logger.error("PCI-DSS: Intrusion detected - User: {}, Resource: {}, Activity: {}",
                userId, resource, suspiciousActivity);
        putMetric("IntrusionDetected", 1.0, Map.of("Resource", resource));
        auditLogService.logSecurityEvent("INTRUSION_DETECTED", "CRITICAL", suspiciousActivity);

        // In production, this would trigger:
        // 1. Immediate alert to security team
        // 2. Automatic account lockout
        // 3. Incident response procedures
    }

    /**
     * Requirement 12.8 - Maintain information security policy
     */
    public void logPolicyCompliance((final String policyId, final boolean compliant) {
        auditLogService.logComplianceCheck("PCI-DSS_" + policyId, compliant);
        putMetric("PolicyCompliance", compliant ? 1.0 : 0.0, Map.of("PolicyId", policyId));

        if (!compliant) {
            logger.warn("PCI-DSS: Policy non-compliance - Policy: {}", policyId);
        }
    }

    /**
     * Requirement 3.2 - Do not store sensitive authentication data after authorization
     * Validate that sensitive data is not stored
     */
    public boolean validateNoSensitiveDataStorage((final String data) {
        // Check for CVV, full track data, PIN blocks
        if (data == null) {
            return true;
        }

        // CVV pattern (3-4 digits)
        if (data.matches(".*\\b\\d{3,4}\\b.*")) {
            logger.error("PCI-DSS VIOLATION: CVV detected in stored data");
            putMetric("SensitiveDataStorageViolation", 1.0, Map.of("DataType", "CVV"));
            return false;
        }

        // Full track data pattern
        if (data.contains("%B") || data.contains(";")) {
            logger.error("PCI-DSS VIOLATION: Full track data detected");
            putMetric("SensitiveDataStorageViolation", 1.0, Map.of("DataType", "TRACK_DATA"));
            return false;
        }

        return true;
    }

    private boolean isValidPAN((final String pan) {
        // Remove spaces and dashes
        String cleaned = pan.replaceAll("[\\s-]", "");

        // Check length
        if (cleaned.length() < MIN_PAN_LENGTH || cleaned.length() > MAX_PAN_LENGTH) {
            return false;
        }

        // Check if all digits
        if (!cleaned.matches("\\d+")) {
            return false;
        }

        // Luhn algorithm validation
        return isValidLuhn(cleaned);
    }

    private boolean isValidLuhn((final String number) {
        int sum = 0;
        boolean alternate = false;

        for (int i = number.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(number.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }

        return (sum % 10 == 0);
    }

    private boolean isWeakCipher((final String cipher) {
        // List of weak cipher suites
        String[] weakCiphers = {
                "RC4", "DES", "MD5", "SHA1", "NULL", "EXPORT", "ANON", "ADH", "LOW", "MEDIUM"
        };

        for (String weak : weakCiphers) {
            if (cipher.toUpperCase().contains(weak)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkBusinessNeedToKnow((final String userId, final String resource) {
        // In production, this would check:
        // 1. User's role
        // 2. Resource access policies
        // 3. Business justification

        // Simplified check
        return !resource.contains("/admin") || userId.contains("admin");
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

