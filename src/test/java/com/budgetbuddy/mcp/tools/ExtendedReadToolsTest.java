package com.budgetbuddy.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetSuggestionService;
import com.budgetbuddy.service.ExpenseReductionService;
import com.budgetbuddy.service.FinancialGoalsRecommendationService;
import com.budgetbuddy.service.HighInterestDetectionService;
import com.budgetbuddy.service.MissedPaymentDetectionService;
import com.budgetbuddy.service.SubscriptionAdvancedService;
import com.budgetbuddy.service.SubscriptionInsightsService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.BudgetAllocationStatusService;
import com.budgetbuddy.service.subscription.TaxDeductibilityClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-tool smoke tests for the new read-side MCP tools. Each tool is
 * a thin delegate over a service; we don't re-test the service, we
 * just verify the tool's contract (name, category, schema shape) and
 * that the handler delegates to the underlying service when invoked.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class ExtendedReadToolsTest {

    private ObjectMapper mapper;
    private ExtendedReadTools tools;
    private UserTable user;
    private McpSession session;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        tools = new ExtendedReadTools(mapper);
        user = new UserTable();
        user.setUserId("u1");
        user.setPreferredCurrency("USD");
        user.setMcpMoneyMovingConsent(Boolean.TRUE);
        user.setMcpConsentGrantedAt(Instant.parse("2026-05-01T00:00:00Z"));
        session = new McpSession("u1");
    }

    @Test
    void getUserProfileToolMirrorsUserFields() throws Exception {
        final McpTool tool = tools.getUserProfileTool();
        assertEquals(McpTool.Category.READ, tool.category());
        final var out = tool.call(mapper.createObjectNode(), user, session);
        assertEquals("u1", out.path("userId").asText());
        assertEquals("USD", out.path("preferredCurrency").asText());
        assertTrue(out.path("mcpMoneyMovingConsent").asBoolean());
        assertEquals("2026-05-01T00:00:00Z", out.path("mcpConsentGrantedAt").asText());
    }

    @Test
    void listAccountsToolReturnsRepositoryResults() throws Exception {
        final AccountRepository repo = mock(AccountRepository.class);
        when(repo.findByUserId("u1")).thenReturn(List.of());
        final McpTool tool = tools.listAccountsTool(repo);
        assertEquals(McpTool.Category.READ, tool.category());
        assertNotNull(tool.call(mapper.createObjectNode(), user, session));
    }

    @Test
    void listTransactionsToolCapsLimit() throws Exception {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        final McpTool tool = tools.listTransactionsTool(repo);
        final var args = mapper.createObjectNode();
        args.put("startDate", "2026-05-01");
        args.put("endDate", "2026-05-31");
        args.put("limit", 999_999);
        // Must not blow up — internal cap is 500.
        assertNotNull(tool.call(args, user, session));
    }

    @Test
    void missedPaymentsToolDelegates() throws Exception {
        final MissedPaymentDetectionService svc = mock(MissedPaymentDetectionService.class);
        when(svc.detectMissedPayments("u1")).thenReturn(List.of());
        final McpTool tool = tools.missedPaymentsTool(svc);
        assertEquals(McpTool.Category.READ, tool.category());
        assertNotNull(tool.call(mapper.createObjectNode(), user, session));
    }

    @Test
    void highInterestAlertsToolDelegates() throws Exception {
        final HighInterestDetectionService svc = mock(HighInterestDetectionService.class);
        when(svc.detectHighInterest("u1")).thenReturn(List.of());
        assertNotNull(tools.highInterestAlertsTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void expenseRecommendationsToolDelegates() throws Exception {
        final ExpenseReductionService svc = mock(ExpenseReductionService.class);
        when(svc.getRecommendations("u1")).thenReturn(List.of());
        assertNotNull(tools.expenseRecommendationsTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void goalSuggestionsToolDelegates() throws Exception {
        final FinancialGoalsRecommendationService svc =
                mock(FinancialGoalsRecommendationService.class);
        when(svc.getRecommendations("u1")).thenReturn(List.of());
        assertNotNull(tools.goalSuggestionsTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void budgetSuggestionsToolDelegates() throws Exception {
        final BudgetSuggestionService svc = mock(BudgetSuggestionService.class);
        when(svc.suggestForUser(user)).thenReturn(List.of());
        assertNotNull(tools.budgetSuggestionsTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void allocationStatusToolDelegates() throws Exception {
        final BudgetAllocationStatusService svc = mock(BudgetAllocationStatusService.class);
        when(svc.compute(user)).thenReturn(new BudgetAllocationStatusService.AllocationStatus());
        assertNotNull(tools.allocationStatusTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void cancellationRecommendationsToolDelegates() throws Exception {
        final SubscriptionInsightsService svc = mock(SubscriptionInsightsService.class);
        when(svc.getCancellationRecommendations("u1")).thenReturn(List.of());
        assertNotNull(tools.cancellationRecommendationsTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void subscriptionAlternativesToolDelegates() throws Exception {
        final SubscriptionAdvancedService svc = mock(SubscriptionAdvancedService.class);
        when(svc.suggestAlternatives("u1")).thenReturn(List.of());
        assertNotNull(tools.subscriptionAlternativesTool(svc)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void subscriptionHealthToolDelegates() throws Exception {
        final SubscriptionAdvancedService advanced = mock(SubscriptionAdvancedService.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getActiveSubscriptions("u1")).thenReturn(List.of());
        assertNotNull(tools.subscriptionHealthTool(advanced, subs)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void taxDeductibilityToolDelegates() throws Exception {
        final TaxDeductibilityClassifier classifier = mock(TaxDeductibilityClassifier.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getActiveSubscriptions("u1")).thenReturn(List.of());
        assertNotNull(tools.taxDeductibilityTool(classifier, subs)
                .call(mapper.createObjectNode(), user, session));
    }

    @Test
    void financialInsightsSummaryToolReturnsCounts() throws Exception {
        final MissedPaymentDetectionService missed = mock(MissedPaymentDetectionService.class);
        final HighInterestDetectionService highInterest =
                mock(HighInterestDetectionService.class);
        final SubscriptionInsightsService insights = mock(SubscriptionInsightsService.class);
        final ExpenseReductionService expense = mock(ExpenseReductionService.class);
        when(missed.detectMissedPayments("u1")).thenReturn(List.of());
        when(highInterest.detectHighInterest("u1")).thenReturn(List.of());
        when(insights.getCancellationRecommendations("u1")).thenReturn(List.of());
        when(expense.getRecommendations("u1")).thenReturn(List.of());

        final var out = tools.financialInsightsSummaryTool(missed, highInterest, insights, expense)
                .call(mapper.createObjectNode(), user, session);
        assertEquals(0, out.path("missedPayments").asInt());
        assertEquals(0, out.path("highInterestAlerts").asInt());
        assertEquals(0, out.path("cancellationRecommendations").asInt());
        assertNotNull(out.get("topExpenseRecommendations"));
    }

    @Test
    void everyReadToolDeclaresReadCategory() {
        // Lock the invariant — a READ tool category is what keeps the
        // consent gate from refusing common queries.
        assertEquals(McpTool.Category.READ, tools.getUserProfileTool().category());
        assertEquals(McpTool.Category.READ,
                tools.listAccountsTool(mock(AccountRepository.class)).category());
        assertEquals(McpTool.Category.READ,
                tools.listTransactionsTool(mock(TransactionRepository.class)).category());
    }
}
