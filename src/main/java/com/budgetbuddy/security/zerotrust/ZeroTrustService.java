package com.budgetbuddy.security.zerotrust;

import com.budgetbuddy.security.zerotrust.device.DeviceAttestationService;
import com.budgetbuddy.security.zerotrust.identity.IdentityVerificationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Zero Trust Security Service Implements continuous verification and never trust, always verify
 * principle
 *
 * <p>Zero Trust Principles: 1. Verify explicitly - Always authenticate and authorize 2. Use least
 * privilege access - Limit user access 3. Assume breach - Minimize blast radius
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@Service
public class ZeroTrustService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZeroTrustService.class);

    private final DeviceAttestationService deviceAttestationService;
    private final IdentityVerificationService identityVerificationService;

    public ZeroTrustService(
            final DeviceAttestationService deviceAttestationService,
            final IdentityVerificationService identityVerificationService) {
        this.deviceAttestationService = deviceAttestationService;
        this.identityVerificationService = identityVerificationService;
    }

    /**
     * Verify access request with zero trust principles
     *
     * @param userId User ID
     * @param deviceId Device ID
     * @param resource Resource being accessed
     * @param action Action being performed
     * @return ZeroTrustResult with verification status
     */
    public ZeroTrustResult verifyAccess(
            final String userId,
            final String deviceId,
            final String resource,
            final String action) {
        final ZeroTrustResult result = new ZeroTrustResult();

        // Principle 1: Verify explicitly
        if (!identityVerificationService.verifyIdentity(userId)) {
            result.setAllowed(false);
            result.setReason("Identity verification failed");
            LOGGER.warn("Zero Trust: Identity verification failed for user: {}", userId);
            return result;
        }

        // Principle 2: Device attestation
        if (!deviceAttestationService.verifyDevice(deviceId, userId)) {
            result.setAllowed(false);
            result.setReason("Device attestation failed");
            LOGGER.warn(
                    "Zero Trust: Device attestation failed for device: {} user: {}",
                    deviceId,
                    userId);
            return result;
        }

        // Principle 3: Continuous verification - check risk score
        final RiskScore riskScore = calculateRiskScore(userId, deviceId, resource, action);
        if (riskScore.getScore() > 70) { // High risk threshold
            result.setAllowed(false);
            result.setReason("Risk score too high: " + riskScore.getScore());
            result.setRiskScore(riskScore);
            LOGGER.warn(
                    "Zero Trust: Access denied due to high risk score: {} for user: {}",
                    riskScore.getScore(),
                    userId);
            return result;
        }

        // Principle 4: Least privilege - verify permissions
        if (!identityVerificationService.hasPermission(userId, resource, action)) {
            result.setAllowed(false);
            result.setReason("Insufficient permissions");
            LOGGER.warn(
                    "Zero Trust: Insufficient permissions for user: {} resource: {} action: {}",
                    userId,
                    resource,
                    action);
            return result;
        }

        result.setAllowed(true);
        result.setRiskScore(riskScore);
        LOGGER.debug(
                "Zero Trust: Access granted for user: {} resource: {} action: {}",
                userId,
                resource,
                action);
        return result;
    }

    /** Calculate risk score based on multiple factors */
    private RiskScore calculateRiskScore(
            final String userId,
            final String deviceId,
            final String resource,
            final String action) {
        final RiskScore riskScore = new RiskScore();

        // Factor 1: Device trust
        final DeviceAttestationService.DeviceTrustLevel deviceTrust =
                deviceAttestationService.getDeviceTrustLevel(deviceId);
        riskScore.addFactor("deviceTrust", deviceTrust.getScore());

        // Factor 2: Location anomaly (if available)
        // riskScore.addFactor("locationAnomaly", locationService.checkAnomaly(userId, deviceId));

        // Factor 3: Time-based anomaly
        riskScore.addFactor("timeAnomaly", checkTimeAnomaly(userId, action));

        // Factor 4: Resource sensitivity
        riskScore.addFactor("resourceSensitivity", getResourceSensitivity(resource));

        // Factor 5: Action sensitivity
        riskScore.addFactor("actionSensitivity", getActionSensitivity(action));

        return riskScore;
    }

    private int checkTimeAnomaly(final String userId, final String action) {
        // Check if action is performed at unusual time
        // For now, return low risk
        return 10;
    }

    private int getResourceSensitivity(final String resource) {
        // Classify resource sensitivity
        if (resource.contains("/admin") || resource.contains("/compliance")) {
            return 80; // High sensitivity
        } else if (resource.contains("/api/transactions") || resource.contains("/api/accounts")) {
            return 50; // Medium sensitivity
        } else {
            return 20; // Low sensitivity
        }
    }

    private int getActionSensitivity(final String action) {
        // Classify action sensitivity
        if ("DELETE".equals(action) || "UPDATE".equals(action)) {
            return 60; // High sensitivity
        } else if ("CREATE".equals(action)) {
            return 40; // Medium sensitivity
        } else {
            return 10; // Low sensitivity (READ)
        }
    }

    /** Zero Trust Result */
    public static class ZeroTrustResult {
        private boolean allowed;
        private String reason;
        private RiskScore riskScore;

        public boolean isAllowed() {
            return allowed;
        }

        public void setAllowed(final boolean allowed) {
            this.allowed = allowed;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(final String reason) {
            this.reason = reason;
        }

        public RiskScore getRiskScore() {
            return riskScore;
        }

        public void setRiskScore(final RiskScore riskScore) {
            this.riskScore = riskScore;
        }
    }

    /** Risk Score calculation */
    public static class RiskScore {
        private final Map<String, Integer> factors = new java.util.HashMap<>();
        private int score = 0;

        public void addFactor(final String name, final int value) {
            factors.put(name, value);
            recalculate();
        }

        private void recalculate() {
            // Weighted average of factors
            score = (int) factors.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        public int getScore() {
            return score;
        }

        public Map<String, Integer> getFactors() {
            return factors;
        }
    }
}
