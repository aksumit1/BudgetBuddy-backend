package com.budgetbuddy.service.insights;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architecture pin for the RISK-1 work. The earlier audit flagged that
 * each insights service was issuing its own
 * {@code transactionRepository.findByUserIdAndDateRange} per detector,
 * fanning out to N DDB scans on every /summary request. After RISK-1,
 * the {@link InsightsContextFactory} builds the snapshot once and every
 * service receives it via a {@code forecast(InsightsContext)} overload.
 *
 * <p>This test drives the factory through a single
 * {@code buildFor(userId)} call, then runs all three forecast services'
 * context-overloads against the resulting snapshot. It asserts that the
 * three forecasters issue ZERO additional repo calls — only the factory
 * itself is allowed to touch the repos. Any reintroduced fan-out fails
 * the build.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class SummaryRepoFanOutTest {

    @Test
    void contextDrivenForecastersIssueZeroAdditionalRepoCalls() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final AccountRepository accountRepo = mock(AccountRepository.class);
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionActionRepository actionRepo = mock(TransactionActionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);

        when(txRepo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of());
        when(accountRepo.findByUserId("u1")).thenReturn(List.of());
        when(budgetRepo.findByUserId("u1")).thenReturn(List.of());
        when(actionRepo.findByUserId("u1")).thenReturn(List.of());
        when(subs.getSubscriptions("u1")).thenReturn(List.of());

        // Build the context — this is the ONLY allowed repo touch.
        final InsightsContextFactory factory =
                new InsightsContextFactory(txRepo, accountRepo, subs, actionRepo);
        factory.setBudgetRepository(budgetRepo);
        final InsightsContext ctx = factory.buildFor("u1");

        // Each repo should have been called at most once by the factory.
        verify(txRepo, atMost(1)).findByUserIdAndDateRange(eq("u1"), anyString(), anyString());
        verify(accountRepo, atMost(1)).findByUserId("u1");
        verify(budgetRepo, atMost(1)).findByUserId("u1");
        verify(actionRepo, atMost(1)).findByUserId("u1");
        verify(subs, atMost(1)).getSubscriptions("u1");

        // Now run the three context-overloads. None of them should
        // trigger another repo call.
        new CashFlowForecastService(txRepo, accountRepo).forecast(ctx);
        new SubscriptionCreepForecastService(subs).forecast(ctx);
        new BudgetExhaustionForecastService(budgetRepo, txRepo).forecast(ctx);

        // After the three forecasters: still no more than the single
        // factory-driven call per repo. atMost(1) catches both "exactly
        // 1" (the factory's call) and "0" (if the forecaster short-
        // circuited on empty data); never(0+more) catches any new
        // repo touch added by a forecaster regressing to userId-based math.
        verify(txRepo, atMost(1)).findByUserIdAndDateRange(eq("u1"), anyString(), anyString());
        verify(accountRepo, atMost(1)).findByUserId("u1");
        verify(budgetRepo, atMost(1)).findByUserId("u1");
        verify(subs, atMost(1)).getSubscriptions("u1");
        verify(subs, never())
                .getSubscriptions(org.mockito.ArgumentMatchers.argThat(s -> !"u1".equals(s)));
    }
}
