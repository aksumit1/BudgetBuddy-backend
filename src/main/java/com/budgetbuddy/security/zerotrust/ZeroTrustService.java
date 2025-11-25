package com.budgetbuddy.security.zerotrust;

import com.budgetbuddy.security.zerotrust.device.DeviceAttestationService;
import com.budgetbuddy.security.zerotrust.identity.IdentityVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Zero Trust Security Service
 * Implements continuous verification and never trust, always verify principle
 * 
 * Zero Trust Principles:
 * 1. Verify explicitly - Always authenticate and authorize
 * 2. Use least privilege access - Limit user access
 * 3. Assume breach - Minimize blast radius
 */
@Service
public class ZeroTrustService {

    private static final Logger logger = LoggerFactory.getLogger(ZeroTrustService.class);

    @Autowired
    private DeviceAttestationService deviceAttestationService;

    @Autowired
    private IdentityVerificationService identityVerificationService;

    /**
     * Verify access request with zero trust principles
     * 
     * @param userId User ID
     * @param deviceId Device ID
     * @param resource Resource being accessed
     * @param action Action being performed
     * @return ZeroTrustResult with verification status
     */
    public ZeroTrustResult verifyAccess(String userId, String deviceId, String resource, String action) {
        ZeroTrustResult result = new ZeroTrustResult();

        // Principle 1: Verify explicitly
        if (!identityVerificationService.verifyIdentity(userId)) {
            result.setAllowed(false);
            result.setReason("Identity verification failed");
            logger.warn("Zero Trust: Identity verification failed for user: {}", userId);
            return result;
        }

        // Principle 2: Device attestation
        if (!deviceAttestationService.verifyDevice(deviceId, userId)) {
            result.setAllowed(false);
            result.setReason("Device attestation failed");
            logger.warn("Zero Trust: Device attestation failed for device: {} user: {}", deviceId, userId);
            return result;
        }

        // Principle 3: Continuous verification - check risk score
        RiskScore riskScore = calculateRiskScore(userId, deviceId, resource, action);
        if (riskScore.getScore() > 70) { // High risk threshold
            result.setAllowed(false);
            result.setReason("Risk score too high: " + riskScore.getScore());
            result.setRiskScore(riskScore);
            logger.warn("Zero Trust: Access denied due to high risk score: {} for user: {}", riskScore.getScore(), userId);
            return result;
        }

        // Principle 4: Least privilege - verify permissions
        if (!identityVerificationService.hasPermission(userId, resource, action)) {
            result.setAllowed(false);
            result.setReason("Insufficient permissions");
            logger.warn("Zero Trust: Insufficient permissions for user: {} resource: {} action: {}", userId, resource, action);
            return result;
        }

        result.setAllowed(true);
        result.setRiskScore(riskScore);
        logger.debug("Zero Trust: Access granted for user: {} resource: {} action: {}", userId, resource, action);
        return result;
    }

    /**
     * Calculate risk score based on multiple factors
     */
    private RiskScore calculateRiskScore(String userId, String deviceId, String resource, String action) {
        RiskScore riskScore = new RiskScore();

        // Factor 1: Device trust
        DeviceTrustLevel deviceTrust = deviceAttestationService.getDeviceTrustLevel(deviceId);
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

    private int checkTimeAnomaly(String userId, String action) {
        // Check if action is performed at unusual time
        // For now, return low risk
        return 10;
    }

    private int getResourceSensitivity(String resource) {
        // Classify resource sensitivity
        if (resource.contains("/admin") || resource.contains("/compliance")) {
            return 80; // High sensitivity
        } else if (resource.contains("/api/transactions") || resource.contains("/api/accounts")) {
            return 50; // Medium sensitivity
        } else {
            return 20; // Low sensitivity
        }
    }

    private int getActionSensitivity(String action) {
        // Classify action sensitivity
        if ("DELETE".equals(action) || "UPDATE".equals(action)) {
            return 60; // High sensitivity
        } else if ("CREATE".equals(action)) {
            return 40; // Medium sensitivity
        } else {
            return 10; // Low sensitivity (READ)
        }
    }

    /**
     * Zero Trust Result
     */
    public static class ZeroTrustResult {
        private boolean allowed;
        private String reason;
        private RiskScore riskScore;

        public boolean isAllowed() {
            return allowed;
        }

        public void setAllowed(boolean allowed) {
            this.allowed = allowed;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public RiskScore getRiskScore() {
            return riskScore;
        }

        public void setRiskScore(RiskScore riskScore) {
            this.riskScore = riskScore;
        }
    }

    /**
     * Risk Score calculation
     */
    public static class RiskScore {
        private final Map<String, Integer> factors = new java.util.HashMap<>();
        private int score = 0;

        public void addFactor(String name, int value) {
            factors.put(name, value);
            recalculate();
        }

        private void recalculate() {
            // Weighted average of factors
            score = (int) factors.values().stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0);
        }

        public int getScore() {
            return score;
        }

        public Map<String, Integer> getFactors() {
            return factors;
        }
    }
}

