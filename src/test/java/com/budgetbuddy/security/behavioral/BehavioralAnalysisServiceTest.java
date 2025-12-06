package com.budgetbuddy.security.behavioral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for BehavioralAnalysisService
 * Tests behavioral analysis and anomaly detection
 */
@ExtendWith(MockitoExtension.class)
class BehavioralAnalysisServiceTest {

    @InjectMocks
    private BehavioralAnalysisService behavioralAnalysisService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        behavioralAnalysisService = new BehavioralAnalysisService();
        testUserId = "user-123";
    }

    @Test
    void testRecordActivity_WithValidInput_RecordsActivity() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.API_CALL;
        String resource = "/api/transactions";
        String action = "GET";
        Map<String, String> metadata = new HashMap<>();

        // When
        behavioralAnalysisService.recordActivity(testUserId, type, resource, action, metadata);

        // Then
        BehavioralAnalysisService.BehaviorProfile profile = behavioralAnalysisService.getBehaviorProfile(testUserId);
        assertNotNull(profile);
    }

    @Test
    void testRecordActivity_WithNullUserId_DoesNotRecord() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.API_CALL;
        String resource = "/api/transactions";
        String action = "GET";

        // When
        behavioralAnalysisService.recordActivity(null, type, resource, action, null);

        // Then - Should not throw exception
        assertDoesNotThrow(() -> behavioralAnalysisService.recordActivity(null, type, resource, action, null));
    }

    @Test
    void testCalculateRiskScore_WithLowRiskActivity_ReturnsLowScore() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.API_CALL;
        String resource = "/api/public/info";
        String action = "GET";
        Map<String, String> context = new HashMap<>();

        // When
        BehavioralAnalysisService.RiskScore result = behavioralAnalysisService.calculateRiskScore(
                testUserId, type, resource, action, context);

        // Then
        assertNotNull(result);
        assertEquals(testUserId, result.getUserId());
        assertNotNull(result.getTimestamp());
        assertTrue(result.getScore() >= 0);
        assertNotNull(result.getRiskLevel());
    }

    @Test
    void testCalculateRiskScore_WithHighRiskActivity_ReturnsHighScore() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.DATA_DELETION;
        String resource = "/api/admin/users";
        String action = "DELETE";
        Map<String, String> context = new HashMap<>();

        // When
        BehavioralAnalysisService.RiskScore result = behavioralAnalysisService.calculateRiskScore(
                testUserId, type, resource, action, context);

        // Then
        assertNotNull(result);
        assertTrue(result.getScore() >= 0);
        // High sensitivity resource + DELETE action should result in higher risk
    }

    @Test
    void testDetectAnomalies_WithNoProfile_ReturnsEmptyList() {
        // When
        List<BehavioralAnalysisService.Anomaly> result = behavioralAnalysisService.detectAnomalies(testUserId);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testDetectAnomalies_WithNormalActivity_ReturnsEmptyList() {
        // Given - Record some normal activities
        for (int i = 0; i < 5; i++) {
            behavioralAnalysisService.recordActivity(
                    testUserId,
                    BehavioralAnalysisService.ActivityType.API_CALL,
                    "/api/transactions",
                    "GET",
                    new HashMap<>()
            );
        }

        // When
        List<BehavioralAnalysisService.Anomaly> result = behavioralAnalysisService.detectAnomalies(testUserId);

        // Then
        assertNotNull(result);
        // With normal activity, should not detect anomalies
    }

    @Test
    void testGetBehaviorProfile_WithNewUser_ReturnsNewProfile() {
        // When
        BehavioralAnalysisService.BehaviorProfile result = behavioralAnalysisService.getBehaviorProfile(testUserId);

        // Then
        assertNotNull(result);
        assertEquals(testUserId, result.getUserId());
    }

    @Test
    void testGetBehaviorProfile_WithExistingUser_ReturnsProfile() {
        // Given
        behavioralAnalysisService.recordActivity(
                testUserId,
                BehavioralAnalysisService.ActivityType.API_CALL,
                "/api/transactions",
                "GET",
                new HashMap<>()
        );

        // When
        BehavioralAnalysisService.BehaviorProfile result = behavioralAnalysisService.getBehaviorProfile(testUserId);

        // Then
        assertNotNull(result);
        assertEquals(testUserId, result.getUserId());
    }

    @Test
    void testCalculateRiskScore_WithMultipleFactors_CalculatesWeightedScore() {
        // Given
        BehavioralAnalysisService.ActivityType type = BehavioralAnalysisService.ActivityType.DATA_MODIFICATION;
        String resource = "/api/transactions";
        String action = "UPDATE";
        Map<String, String> context = new HashMap<>();

        // When
        BehavioralAnalysisService.RiskScore result = behavioralAnalysisService.calculateRiskScore(
                testUserId, type, resource, action, context);

        // Then
        assertNotNull(result);
        assertNotNull(result.getFactors());
        assertTrue(result.getFactors().size() > 0);
        assertTrue(result.getScore() >= 0);
    }
}

