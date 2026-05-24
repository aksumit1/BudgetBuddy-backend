package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.subscription.SubscriptionEngagementScorer.EngagementScore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class SubscriptionEngagementScorerTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void monthlySubChargedOnScheduleScoresActive() {
        // 3 charges in last 90 days at consistent $10 → ACTIVE.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        charge("Netflix", "10", NOW.minusDays(2)),
                        charge("Netflix", "10", NOW.minusDays(32)),
                        charge("Netflix", "10", NOW.minusDays(62))));

        final EngagementScore s =
                new SubscriptionEngagementScorer(repo)
                        .score(monthly("Netflix", "10", NOW.minusDays(2)), NOW);
        assertEquals("ACTIVE", s.tier);
        assertTrue(s.score >= 70, "ACTIVE tier requires >= 70 score; got " + s.score);
    }

    @Test
    void monthlySubWithNoChargesIn90DaysIsDormant() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString())).thenReturn(List.of());

        final EngagementScore s =
                new SubscriptionEngagementScorer(repo)
                        .score(monthly("Audible", "15", NOW.minusDays(120)), NOW);
        assertEquals("DORMANT", s.tier);
        assertTrue(s.score < 40, "DORMANT requires < 40 score; got " + s.score);
    }

    @Test
    void inactiveSubAlwaysDormantEvenWithRecentCharges() {
        // User explicitly marked sub inactive — engagement tier drops to
        // DORMANT regardless of charge recency.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(charge("Gym", "30", NOW.minusDays(1))));
        final Subscription s = monthly("Gym", "30", NOW.minusDays(1));
        s.setActive(false);

        assertEquals(
                "DORMANT",
                new SubscriptionEngagementScorer(repo).score(s, NOW).tier);
    }

    @Test
    void priceInstabilityDropsScore() {
        // Prices oscillating wildly between charges → reduced price-stability signal.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        charge("CableCo", "50", NOW.minusDays(5)),
                        charge("CableCo", "120", NOW.minusDays(35)),
                        charge("CableCo", "75", NOW.minusDays(65))));

        final EngagementScore s =
                new SubscriptionEngagementScorer(repo)
                        .score(monthly("CableCo", "50", NOW.minusDays(5)), NOW);
        assertTrue(s.priceStabilitySignal < 1.0,
                "Wild price swings must drop price-stability signal");
    }

    @Test
    void scoreBreakdownIsAlwaysWithinUnitInterval() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(charge("Spotify", "9.99", NOW.minusDays(10))));
        final EngagementScore s =
                new SubscriptionEngagementScorer(repo)
                        .score(monthly("Spotify", "9.99", NOW.minusDays(10)), NOW);
        for (final double signal :
                List.of(s.recencySignal, s.frequencySignal, s.priceStabilitySignal, s.activeSignal)) {
            assertTrue(signal >= 0.0 && signal <= 1.0,
                    "All signals must be normalised to [0,1]; saw " + signal);
        }
    }

    private static Subscription monthly(
            final String merchant, final String amount, final LocalDate lastPaid) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(java.util.UUID.randomUUID().toString());
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal(amount));
        s.setFrequency(SubscriptionFrequency.MONTHLY);
        s.setActive(true);
        s.setLastPaymentDate(lastPaid);
        return s;
    }

    private static TransactionTable charge(
            final String merchant, final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setUserId(USER);
        t.setMerchantName(merchant);
        t.setAmount(new BigDecimal(amount).negate());
        t.setTransactionDate(date.toString());
        return t;
    }
}
