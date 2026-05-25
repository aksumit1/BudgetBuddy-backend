package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ExpenseReductionService;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.FinancialGoalsRecommendationService;
import com.budgetbuddy.service.FinancialGoalsRecommendationService.FinancialGoalRecommendation;
import com.budgetbuddy.service.HighInterestDetectionService;
import com.budgetbuddy.service.HighInterestDetectionService.HighInterestAlert;
import com.budgetbuddy.service.MissedPaymentDetectionService;
import com.budgetbuddy.service.MissedPaymentDetectionService.MissedPaymentAlert;
import com.budgetbuddy.service.TransactionAnomalyService;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/** Tests for FinancialInsightsController */
@ExtendWith(MockitoExtension.class)
class FinancialInsightsControllerTest {

    private static final String USER_123 = "user-123";

    @Mock private TransactionAnomalyService anomalyService;

    @Mock private ExpenseReductionService expenseReductionService;

    @Mock private FinancialGoalsRecommendationService goalsService;

    @Mock private MissedPaymentDetectionService missedPaymentService;

    @Mock private HighInterestDetectionService highInterestService;

    @Mock private UserService userService;

    @Mock private com.budgetbuddy.service.CrossAccountAnomalyDetector crossAccountDetector;
    @Mock private com.budgetbuddy.service.CreditCardInsightsService creditCardInsightsService;
    @Mock private com.budgetbuddy.service.insights.InsightsContextFactory insightsContextFactory;

    @InjectMocks private FinancialInsightsController controller;

    private UserDetails userDetails;
    private UserTable user;

    @BeforeEach
    void setUp() {
        userDetails =
                User.withUsername("test@example.com")
                        .password("password")
                        .authorities("ROLE_USER")
                        .build();

        user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");
    }

    @Test
    void testGetAnomaliesSuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final List<TransactionAnomaly> anomalies = new ArrayList<>();
        final TransactionAnomaly anomaly =
                new TransactionAnomaly(
                        "tx-1",
                        BigDecimal.valueOf(-500),
                        "Test",
                        "Merchant",
                        "2024-01-01",
                        "Other",
                        TransactionAnomalyService.AnomalyType.STATISTICAL_OUTLIER,
                        TransactionAnomalyService.Severity.HIGH,
                        "Reason");
        anomalies.add(anomaly);

        when(anomalyService.detectAnomalies(USER_123)).thenReturn(anomalies);

        // Act
        final ResponseEntity<List<Map<String, Object>>> response =
                controller.getAnomalies(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetExpenseReductionsSuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final List<ExpenseRecommendation> recommendations = new ArrayList<>();
        final ExpenseRecommendation rec =
                new ExpenseRecommendation(
                        ExpenseReductionService.RecommendationType.CANCEL,
                        "Test Subscription",
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(120),
                        "Description",
                        ExpenseReductionService.Priority.HIGH,
                        "subscription",
                        "sub-1");
        recommendations.add(rec);

        when(expenseReductionService.getRecommendations(USER_123)).thenReturn(recommendations);

        // Act
        final ResponseEntity<List<Map<String, Object>>> response =
                controller.getExpenseReductions(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetGoalRecommendationsSuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final List<FinancialGoalRecommendation> recommendations = new ArrayList<>();
        final FinancialGoalRecommendation rec =
                new FinancialGoalRecommendation(
                        FinancialGoalsRecommendationService.GoalType.EMERGENCY_FUND,
                        "Emergency Fund",
                        "Description",
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(10_000),
                        LocalDate.now().plusMonths(12),
                        FinancialGoalsRecommendationService.Priority.HIGH,
                        "Action plan",
                        BigDecimal.valueOf(10_000));
        recommendations.add(rec);

        when(goalsService.getRecommendations(USER_123)).thenReturn(recommendations);

        // Act
        final ResponseEntity<List<Map<String, Object>>> response =
                controller.getGoalRecommendations(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetMissedPaymentsSuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final List<MissedPaymentAlert> alerts = new ArrayList<>();
        final MissedPaymentAlert alert =
                new MissedPaymentAlert(
                        "action-1",
                        "Test Payment",
                        "Description",
                        LocalDate.now().minusDays(5),
                        5,
                        MissedPaymentDetectionService.AlertType.OVERDUE,
                        MissedPaymentDetectionService.Severity.HIGH,
                        "Message",
                        null);
        alerts.add(alert);

        when(missedPaymentService.detectMissedPayments(USER_123)).thenReturn(alerts);

        // Act
        final ResponseEntity<List<Map<String, Object>>> response =
                controller.getMissedPayments(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetHighInterestSuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final List<HighInterestAlert> alerts = new ArrayList<>();
        final HighInterestAlert alert =
                new HighInterestAlert(
                        "acc-1",
                        "Credit Card",
                        "Bank",
                        "creditCard",
                        BigDecimal.valueOf(5000),
                        0.25,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(1200),
                        HighInterestDetectionService.Severity.HIGH,
                        "Recommendation");
        alerts.add(alert);

        when(highInterestService.detectHighInterest(USER_123)).thenReturn(alerts);

        // Act
        final ResponseEntity<List<Map<String, Object>>> response =
                controller.getHighInterest(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetSummarySuccess() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        // The /summary endpoint now builds a shared InsightsContext
        // once and passes it to every detector — that's the whole point
        // of the refactor. Stub the context overloads, not the legacy
        // userId overloads.
        final com.budgetbuddy.service.insights.InsightsContext ctx =
                new com.budgetbuddy.service.insights.InsightsContext(
                        USER_123, LocalDate.now(),
                        new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        when(insightsContextFactory.buildFor(USER_123)).thenReturn(ctx);
        when(anomalyService.detectAnomalies(ctx)).thenReturn(new ArrayList<>());
        when(expenseReductionService.getRecommendations(ctx)).thenReturn(new ArrayList<>());
        when(goalsService.getRecommendations(ctx)).thenReturn(new ArrayList<>());
        when(missedPaymentService.detectMissedPayments(ctx)).thenReturn(new ArrayList<>());
        when(highInterestService.detectHighInterest(ctx)).thenReturn(new ArrayList<>());

        // Act
        final ResponseEntity<Map<String, Object>> response =
                controller.getInsightsSummary(userDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void summaryEndpoint_buildsContextExactlyOnce_andNeverHitsLegacyUserIdOverloads() {
        // The whole point of #182: /summary used to issue 9+ DDB scans
        // (one per detector). Now the context factory makes one bundle
        // of fetches; detectors operate on that snapshot. Verify both:
        //   1. buildFor is called exactly once
        //   2. None of the legacy userId-only overloads are touched
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        final com.budgetbuddy.service.insights.InsightsContext ctx =
                new com.budgetbuddy.service.insights.InsightsContext(
                        USER_123, LocalDate.now(),
                        new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        when(insightsContextFactory.buildFor(USER_123)).thenReturn(ctx);
        when(anomalyService.detectAnomalies(ctx)).thenReturn(new ArrayList<>());
        when(expenseReductionService.getRecommendations(ctx)).thenReturn(new ArrayList<>());
        when(goalsService.getRecommendations(ctx)).thenReturn(new ArrayList<>());
        when(missedPaymentService.detectMissedPayments(ctx)).thenReturn(new ArrayList<>());
        when(highInterestService.detectHighInterest(ctx)).thenReturn(new ArrayList<>());

        controller.getInsightsSummary(userDetails);

        org.mockito.Mockito.verify(insightsContextFactory, org.mockito.Mockito.times(1))
                .buildFor(USER_123);
        // Legacy overloads must NOT be called from /summary — they fetch
        // their own data and would defeat the purpose of the context.
        org.mockito.Mockito.verify(anomalyService, org.mockito.Mockito.never())
                .detectAnomalies(USER_123);
        org.mockito.Mockito.verify(expenseReductionService, org.mockito.Mockito.never())
                .getRecommendations(USER_123);
        org.mockito.Mockito.verify(goalsService, org.mockito.Mockito.never())
                .getRecommendations(USER_123);
        org.mockito.Mockito.verify(missedPaymentService, org.mockito.Mockito.never())
                .detectMissedPayments(USER_123);
        org.mockito.Mockito.verify(highInterestService, org.mockito.Mockito.never())
                .detectHighInterest(USER_123);
    }

    @Test
    void testGetAnomaliesUnauthorized() {
        // Act & Assert
        assertThrows(Exception.class, () -> controller.getAnomalies(null));
    }

    @Test
    void testGetAnomaliesUserNotFound() {
        // Arrange
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(Exception.class, () -> controller.getAnomalies(userDetails));
    }
}
