package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.insights.InsightsContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the context-aware overload of {@link ExpenseReductionService}
 * against the legacy fetch path. Both paths must produce the same
 * recommendations from the same data — that's the whole point of the
 * #182 refactor. Also verifies the context path doesn't issue any
 * transaction-repo calls (the perf win).
 */
@ExtendWith(MockitoExtension.class)
class ExpenseReductionContextParityTest {

    private static final String USER = "u1";

    @Mock private TransactionRepository transactionRepository;
    @Mock private SubscriptionService subscriptionService;

    private ExpenseReductionService svc;

    @BeforeEach
    void setUp() {
        svc = new ExpenseReductionService(transactionRepository, subscriptionService);
        // Subscriptions are not in context yet — the analyzeSubscriptions
        // branch is identical in both paths. lenient because some tests
        // don't exercise it.
        lenient().when(subscriptionService.getSubscriptions(anyString()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void contextPath_doesNotHitTransactionRepository() {
        // The whole point of #182 — verify it.
        final InsightsContext ctx = new InsightsContext(
                USER, LocalDate.now(), buildSpendyHistory(), List.of(), List.of());
        svc.getRecommendations(ctx);
        verify(transactionRepository, never())
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
    }

    @Test
    void legacyPath_stillHitsTransactionRepository() {
        // Belt-and-suspenders: legacy callers must still get fetched data.
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
        svc.getRecommendations(USER);
        // 3 fetches: categoryOverspending, lowValueHighCost,
        // lifestyleInflation has 2 internal fetches (recent + historical).
        verify(transactionRepository, times(4))
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
    }

    @Test
    void bothPaths_produceSameRecommendationsForSameData() {
        // Same transactions → same recommendations. If the refactor
        // accidentally changed the filter/grouping logic, this fails.
        // Mock simulates real date-range filtering so the legacy path
        // gets the right subset for each window (the production
        // DynamoDB repo does this; a naive any-range-returns-all mock
        // would feed garbage to the legacy path).
        final List<TransactionTable> data = buildSpendyHistory();
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    final String start = inv.getArgument(1, String.class);
                    final String end = inv.getArgument(2, String.class);
                    return data.stream()
                            .filter(tx -> tx.getTransactionDate() != null
                                    && tx.getTransactionDate().compareTo(start) >= 0
                                    && tx.getTransactionDate().compareTo(end) <= 0)
                            .toList();
                });

        final List<ExpenseRecommendation> legacyOutput = svc.getRecommendations(USER);

        final InsightsContext ctx = new InsightsContext(
                USER, LocalDate.now(), data, List.of(), List.of());
        final List<ExpenseRecommendation> contextOutput = svc.getRecommendations(ctx);

        assertEquals(legacyOutput.size(), contextOutput.size(),
                "Context path must produce same number of recommendations as legacy");
        for (int i = 0; i < legacyOutput.size(); i++) {
            assertEquals(legacyOutput.get(i).getType(), contextOutput.get(i).getType());
            assertEquals(legacyOutput.get(i).getTitle(), contextOutput.get(i).getTitle());
            assertEquals(0, legacyOutput.get(i).getMonthlySavings()
                    .compareTo(contextOutput.get(i).getMonthlySavings()),
                    "Monthly savings must match for recommendation " + i);
        }
    }

    @Test
    void contextPath_returnsEmptyForNullContext() {
        assertTrue(svc.getRecommendations((InsightsContext) null).isEmpty());
    }

    @Test
    void contextPath_returnsEmptyWhenNoTransactionsInWindow() {
        // Empty snapshot → no recommendations.
        final InsightsContext ctx = new InsightsContext(
                USER, LocalDate.now(), List.of(), List.of(), List.of());
        // Subscriptions returns empty too (set up in @BeforeEach).
        assertTrue(svc.getRecommendations(ctx).isEmpty());
    }

    /**
     * Build a transaction history rich enough to trigger
     * category-overspending (Dining > $200/month) and lifestyle
     * inflation (recent month much higher than prior 60 days).
     */
    private List<TransactionTable> buildSpendyHistory() {
        final List<TransactionTable> txs = new ArrayList<>();
        // 10 dining transactions in the last 30 days — heavy
        for (int i = 0; i < 10; i++) {
            txs.add(tx("Dining", "-100.00", LocalDate.now().minusDays(i + 1).toString()));
        }
        // 5 dining transactions in days 30-90 — lighter (creates inflation signal)
        for (int i = 0; i < 5; i++) {
            txs.add(tx("Dining", "-50.00", LocalDate.now().minusDays(i + 40).toString()));
        }
        return txs;
    }

    private static TransactionTable tx(
            final String category, final String amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(new BigDecimal(amount));
        t.setCategoryPrimary(category);
        t.setDescription(category + " purchase");
        t.setMerchantName(category + " Co");
        t.setTransactionDate(date);
        return t;
    }
}
