package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.GoalAnalyticsService.ContributionInsights;
import com.budgetbuddy.service.GoalAnalyticsService.GoalProjection;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * G-OPP-1 + G-OPP-2: forecast bands, EMA-weighted rate, trend label,
 * and contribution-by-source breakdown.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class GoalAnalyticsServiceForecastBandsTest {

    private static final String USER = "u1";
    private static final String GOAL_ID = "g1";

    @Test
    void projectionAttachesEmaP50P90AndTrendWhenEnoughHistory() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        // Strongly-accelerating curve: 50 → 150 → 300. Flat-avg lags the EMA
        // by >10 % so the trend classifier labels it ACCELERATING rather than
        // STEADY.
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID)))
                .thenReturn(List.of(
                        contribution("50", LocalDate.now().minusMonths(2)),
                        contribution("150", LocalDate.now().minusMonths(1)),
                        contribution("300", LocalDate.now())));

        final GoalAnalyticsService svc = new GoalAnalyticsService(txRepo);
        final GoalProjection p = svc.calculateProjection(goal("1000"), USER);
        assertNotNull(p, "Projection must compute when contributions exist");
        assertNotNull(p.getEmaMonthlyContribution(), "EMA rate must be set with >=2 months of data");
        assertNotNull(p.getProjectedCompletionDateP50());
        assertNotNull(p.getProjectedCompletionDateP90(), "p90 must always >= p50");
        assertTrue(
                !p.getProjectedCompletionDateP90().isBefore(p.getProjectedCompletionDateP50()),
                "p90 (pessimistic) cannot be sooner than p50");
        assertEquals("ACCELERATING", p.getTrend(),
                "Contributions trending 50 → 150 → 300 must label as ACCELERATING");
    }

    @Test
    void projectionLeavesForecastBandsNullWhenLessThanTwoMonths() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID)))
                .thenReturn(List.of(contribution("100", LocalDate.now().minusDays(5))));

        final GoalAnalyticsService svc = new GoalAnalyticsService(txRepo);
        final GoalProjection p = svc.calculateProjection(goal("1000"), USER);
        assertNotNull(p);
        assertNull(p.getEmaMonthlyContribution(),
                "EMA requires >=2 months of distinct contribution months");
    }

    @Test
    void contributionsBySourceSeparatesRoundUpAndManual() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID)))
                .thenReturn(List.of(
                        roundUp("0.35", LocalDate.now().minusDays(3)),
                        roundUp("0.50", LocalDate.now().minusDays(5)),
                        manual("100.00", LocalDate.now().minusDays(7)),
                        salary("500.00", LocalDate.now().minusDays(10))));

        final GoalAnalyticsService svc = new GoalAnalyticsService(txRepo);
        final ContributionInsights c = svc.getContributionInsights(goal("1000"), USER);
        final Map<String, BigDecimal> bySrc = c.getContributionsBySource();
        assertNotNull(bySrc);
        assertEquals(new BigDecimal("0.85"), bySrc.get("ROUND_UP"));
        assertEquals(new BigDecimal("100.00"), bySrc.get("MANUAL"));
        assertEquals(new BigDecimal("500.00"), bySrc.get("INCOME"));
        // Best source is INCOME ($500 > others).
        assertEquals("INCOME", c.getBestContributionSource());
    }

    private static TransactionTable contribution(final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date.toString());
        return t;
    }

    private static TransactionTable roundUp(final String amount, final LocalDate date) {
        final TransactionTable t = contribution(amount, date);
        t.setRoundUpSourceTransactionId("src-" + amount);
        return t;
    }

    private static TransactionTable manual(final String amount, final LocalDate date) {
        return contribution(amount, date);
    }

    private static TransactionTable salary(final String amount, final LocalDate date) {
        final TransactionTable t = contribution(amount, date);
        t.setCategoryPrimary("salary");
        return t;
    }

    private static GoalTable goal(final String target) {
        final GoalTable g = new GoalTable();
        g.setGoalId(GOAL_ID);
        g.setTargetAmount(new BigDecimal(target));
        g.setTargetDate(LocalDate.now().plusMonths(12).toString());
        g.setCurrentAmount(BigDecimal.ZERO);
        return g;
    }
}
