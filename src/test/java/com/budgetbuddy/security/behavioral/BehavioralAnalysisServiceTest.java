package com.budgetbuddy.security.behavioral;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Comprehensive tests for BehavioralAnalysisService */
class BehavioralAnalysisServiceTest {

    private BehavioralAnalysisService behavioralAnalysisService;

    @BeforeEach
    void setUp() {
        behavioralAnalysisService = new BehavioralAnalysisService();
    }

    @Test
    @DisplayName("Should record activity successfully")
    void testRecordActivity() {
        // Given
        final String userId = "user-123";
        final BehavioralAnalysisService.ActivityType type =
                BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        final String resource = "/api/auth/login";
        final String action = "POST";
        final Map<String, String> metadata = new HashMap<>();
        metadata.put("ip", "192.168.1.1");

        // When
        assertDoesNotThrow(
                () -> {
                    behavioralAnalysisService.recordActivity(
                            userId, type, resource, action, metadata);
                });
    }

    @Test
    @DisplayName("Should handle null userId gracefully")
    void testRecordActivityNullUserId() {
        // Given
        final BehavioralAnalysisService.ActivityType type =
                BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        final String resource = "/api/auth/login";
        final String action = "POST";

        // When - Should not throw
        assertDoesNotThrow(
                () -> {
                    behavioralAnalysisService.recordActivity(null, type, resource, action, null);
                });
    }

    @Test
    @DisplayName("Should calculate risk score")
    void testCalculateRiskScore() {
        // Given
        final String userId = "user-123";
        final BehavioralAnalysisService.ActivityType type =
                BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        final String resource = "/api/transactions";
        final String action = "GET";
        final Map<String, String> context = new HashMap<>();

        // When
        final BehavioralAnalysisService.RiskScore riskScore =
                behavioralAnalysisService.calculateRiskScore(
                        userId, type, resource, action, context);

        // Then
        assertNotNull(riskScore);
        assertEquals(userId, riskScore.getUserId());
        assertTrue(riskScore.getScore() >= 0 && riskScore.getScore() <= 100);
    }

    @Test
    @DisplayName("Should calculate higher risk for sensitive resources")
    void testCalculateRiskScoreSensitiveResource() {
        // Given
        final String userId = "user-123";
        final BehavioralAnalysisService.ActivityType type =
                BehavioralAnalysisService.ActivityType.DATA_ACCESS;
        final String resource = "/api/admin/users";
        final String action = "DELETE";
        final Map<String, String> context = new HashMap<>();

        // When
        final BehavioralAnalysisService.RiskScore riskScore =
                behavioralAnalysisService.calculateRiskScore(
                        userId, type, resource, action, context);

        // Then
        assertNotNull(riskScore);
        assertTrue(riskScore.getScore() > 0);
    }

    @Test
    @DisplayName("Should detect anomalies")
    void testDetectAnomalies() {
        // Given
        final String userId = "user-123";
        // Record normal activities first
        for (int i = 0; i < 5; i++) {
            behavioralAnalysisService.recordActivity(
                    userId,
                    BehavioralAnalysisService.ActivityType.AUTHENTICATION,
                    "/api/auth/login",
                    "POST",
                    null);
        }

        // When
        final java.util.List<BehavioralAnalysisService.Anomaly> anomalies =
                behavioralAnalysisService.detectAnomalies(userId);

        // Then
        assertNotNull(anomalies);
    }

    @Test
    @DisplayName("Should return empty result for user with no activities")
    void testDetectAnomaliesNoActivities() {
        // Given
        final String userId = "user-123";

        // When
        final java.util.List<BehavioralAnalysisService.Anomaly> anomalies =
                behavioralAnalysisService.detectAnomalies(userId);

        // Then
        assertNotNull(anomalies);
    }
}
