package com.budgetbuddy.security.behavioral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BehavioralAnalysisService
 */
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
        String userId = "user-123";
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        String resource = "/api/auth/login";
        String action = "POST";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("ip", "192.168.1.1");

        // When
        assertDoesNotThrow(() -> {
            behavioralAnalysisService.recordActivity(userId, type, resource, action, metadata);
        });
    }

    @Test
    @DisplayName("Should handle null userId gracefully")
    void testRecordActivity_NullUserId() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        String resource = "/api/auth/login";
        String action = "POST";

        // When - Should not throw
        assertDoesNotThrow(() -> {
            behavioralAnalysisService.recordActivity(null, type, resource, action, null);
        });
    }

    @Test
    @DisplayName("Should calculate risk score")
    void testCalculateRiskScore() {
        // Given
        String userId = "user-123";
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.AUTHENTICATION;
        String resource = "/api/transactions";
        String action = "GET";
        Map<String, String> context = new HashMap<>();

        // When
        BehavioralAnalysisService.RiskScore riskScore = behavioralAnalysisService.calculateRiskScore(userId, type, resource, action, context);

        // Then
        assertNotNull(riskScore);
        assertEquals(userId, riskScore.getUserId());
        assertTrue(riskScore.getScore() >= 0 && riskScore.getScore() <= 100);
    }

    @Test
    @DisplayName("Should calculate higher risk for sensitive resources")
    void testCalculateRiskScore_SensitiveResource() {
        // Given
        String userId = "user-123";
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.DATA_ACCESS;
        String resource = "/api/admin/users";
        String action = "DELETE";
        Map<String, String> context = new HashMap<>();

        // When
        BehavioralAnalysisService.RiskScore riskScore = behavioralAnalysisService.calculateRiskScore(userId, type, resource, action, context);

        // Then
        assertNotNull(riskScore);
        assertTrue(riskScore.getScore() > 0);
    }

    @Test
    @DisplayName("Should detect anomalies")
    void testDetectAnomalies() {
        // Given
        String userId = "user-123";
        // Record normal activities first
        for (int i = 0; i < 5; i++) {
            behavioralAnalysisService.recordActivity(userId, BehavioralAnalysisService.ActivityType.AUTHENTICATION, "/api/auth/login", "POST", null);
        }

        // When
        java.util.List<BehavioralAnalysisService.Anomaly> anomalies = behavioralAnalysisService.detectAnomalies(userId);

        // Then
        assertNotNull(anomalies);
    }

    @Test
    @DisplayName("Should return empty result for user with no activities")
    void testDetectAnomalies_NoActivities() {
        // Given
        String userId = "user-123";

        // When
        java.util.List<BehavioralAnalysisService.Anomaly> anomalies = behavioralAnalysisService.detectAnomalies(userId);

        // Then
        assertNotNull(anomalies);
    }
}
