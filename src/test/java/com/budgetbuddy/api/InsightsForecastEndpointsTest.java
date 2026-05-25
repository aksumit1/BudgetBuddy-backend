package com.budgetbuddy.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.insights.BudgetExhaustionForecastService;
import com.budgetbuddy.service.insights.CashFlowForecastService;
import com.budgetbuddy.service.insights.SubscriptionCreepForecastService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer integration tests for the new forecast endpoints.
 * Uses MockMvc.standaloneSetup so we exercise the actual JSON
 * serialisation + path mapping without needing a Spring context or
 * LocalStack. Service deps are mocked at the constructor.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>GET /api/insights/cash-flow-forecast — wires CashFlowForecastService
 *   <li>GET /api/insights/subscription-creep — wires SubscriptionCreepForecastService
 *   <li>GET /api/insights/budget-exhaustion — wires BudgetExhaustionForecastService
 *   <li>503 paths when the underlying service isn't wired
 *   <li>auth gate on missing UserDetails
 * </ul>
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — MockMvc methods declare Exception")
final class InsightsForecastEndpointsTest {

    private MockMvc mockMvc;
    private CashFlowForecastService cashFlow;
    private SubscriptionCreepForecastService creep;
    private BudgetExhaustionForecastService exhaustion;
    private UserService userService;
    private FinancialInsightsController controller;

    @BeforeEach
    void setUp() {
        cashFlow = mock(CashFlowForecastService.class);
        creep = mock(SubscriptionCreepForecastService.class);
        exhaustion = mock(BudgetExhaustionForecastService.class);
        userService = mock(UserService.class);
        final UserTable u = new UserTable();
        u.setUserId("u1");
        u.setEmail("test@example.com");
        when(userService.findByEmail(anyString())).thenReturn(Optional.of(u));

        controller = new FinancialInsightsController(
                /*anomalyService=*/null,
                /*expenseReductionService=*/null,
                /*goalsService=*/null,
                /*missedPaymentService=*/null,
                /*highInterestService=*/null,
                userService,
                /*predictionService=*/null,
                /*transactionRepository=*/null,
                /*accountRepository=*/null,
                /*subscriptionRepository=*/null,
                /*anomalyFeedbackService=*/null,
                /*goalRepository=*/null,
                /*crossAccountDetector=*/null,
                /*creditCardInsightsService=*/null,
                /*insightsContextFactory=*/null);
        controller.setCashFlowForecastService(cashFlow);
        controller.setSubscriptionCreepForecastService(creep);
        controller.setBudgetExhaustionForecastService(exhaustion);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation
                                .AuthenticationPrincipalArgumentResolver())
                // Wire the prod envelope-wrapping advice so jsonPath
                // assertions match the live wire shape (`$.data.…`)
                // rather than the raw controller return value.
                .setControllerAdvice(
                        new com.budgetbuddy.api.response.ApiResponseWrappingAdvice())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(final String email) {
        final UserDetails details =
                User.withUsername(email).password("noop").authorities("USER").build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                details, "n/a", details.getAuthorities()));
    }

    @Test
    void cashFlowForecastEndpointSerialisesStatusAndRunway() throws Exception {
        final CashFlowForecastService.Forecast f = new CashFlowForecastService.Forecast();
        f.status = "CRITICAL";
        f.runwayDays = 21;
        f.liquidAssets = new BigDecimal("500");
        f.message = "At current pace you'll run out of cash in 21 days.";
        when(cashFlow.forecast("u1")).thenReturn(f);

        authenticateAs("test@example.com");
        mockMvc.perform(get("/api/insights/cash-flow-forecast"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.status").value("CRITICAL"))
                .andExpect(jsonPath("$.data.runwayDays").value(21))
                .andExpect(jsonPath("$.data.message",
                        containsString("21 days")));
    }

    @Test
    void cashFlowEndpointReturnsUnavailableWhenServiceNotWired() throws Exception {
        controller.setCashFlowForecastService(null);
        authenticateAs("test@example.com");
        mockMvc.perform(get("/api/insights/cash-flow-forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
    }

    @Test
    void subscriptionCreepEndpointSerialisesSpikingStatus() throws Exception {
        final SubscriptionCreepForecastService.CreepForecast c =
                new SubscriptionCreepForecastService.CreepForecast();
        c.status = "SPIKING";
        c.activeSubscriptionCount = 5;
        c.currentMonthlyTotal = new BigDecimal("85.00");
        c.message = "Subscription portfolio grew 30%";
        when(creep.forecast("u1")).thenReturn(c);

        authenticateAs("test@example.com");
        mockMvc.perform(get("/api/insights/subscription-creep"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SPIKING"))
                .andExpect(jsonPath("$.data.activeSubscriptionCount").value(5))
                .andExpect(jsonPath("$.data.currentMonthlyTotal").value(85.0));
    }

    @Test
    void budgetExhaustionReturnsRankedAlerts() throws Exception {
        final BudgetExhaustionForecastService.ExhaustionAlert a1 =
                new BudgetExhaustionForecastService.ExhaustionAlert();
        a1.category = "dining";
        a1.severity = "HIGH";
        a1.daysUntilExhausted = 2;
        a1.daysRemainingInCycle = 10;
        a1.message = "Likely to exhaust in 2 days";
        when(exhaustion.forecast("u1")).thenReturn(List.of(a1));

        authenticateAs("test@example.com");
        mockMvc.perform(get("/api/insights/budget-exhaustion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("dining"))
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data[0].daysUntilExhausted").value(2));
    }

    @Test
    void budgetExhaustionReturnsEmptyArrayWhenServiceNotWired() throws Exception {
        controller.setBudgetExhaustionForecastService(null);
        authenticateAs("test@example.com");
        mockMvc.perform(get("/api/insights/budget-exhaustion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void unauthenticatedRequestThrowsUnauthorisedAccess() throws Exception {
        // No SecurityContext principal → controller throws
        // AppException(UNAUTHORIZED_ACCESS). Without the production
        // @ControllerAdvice wired here (it requires MessageUtil), the
        // exception propagates as a ServletException — assert the
        // underlying cause carries the expected error code.
        try {
            mockMvc.perform(get("/api/insights/cash-flow-forecast"));
            throw new AssertionError("Expected unauth call to throw");
        } catch (Exception wrapped) {
            Throwable t = wrapped;
            while (t != null && !(t instanceof AppException)) {
                t = t.getCause();
            }
            if (!(t instanceof AppException ae)
                    || ae.getErrorCode() != ErrorCode.UNAUTHORIZED_ACCESS) {
                throw new AssertionError(
                        "Expected UNAUTHORIZED_ACCESS AppException, got "
                                + (t == null ? wrapped : t.getClass()));
            }
        }
    }

}
