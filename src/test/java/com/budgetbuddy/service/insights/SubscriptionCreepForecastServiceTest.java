package com.budgetbuddy.service.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class SubscriptionCreepForecastServiceTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void spikingWhenMoreThanFifteenPercentAddedSinceCutoff() {
        // Existing portfolio: $20/mo. Added 30 days ago: $10/mo Netflix + $5/mo Spotify.
        // Prior total: $20. Current: $35. Delta +75% → SPIKING.
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(
                        sub("Gym", "20", SubscriptionFrequency.MONTHLY,
                                NOW.minusDays(180).atStartOfDay()),
                        sub("Netflix", "10", SubscriptionFrequency.MONTHLY,
                                NOW.minusDays(10).atStartOfDay()),
                        sub("Spotify", "5", SubscriptionFrequency.MONTHLY,
                                NOW.minusDays(5).atStartOfDay())));

        final SubscriptionCreepForecastService svc = new SubscriptionCreepForecastService(subs);
        final SubscriptionCreepForecastService.CreepForecast f = svc.forecast(USER, NOW);
        assertEquals("SPIKING", f.status);
        assertEquals(3, f.activeSubscriptionCount);
        assertEquals(2, f.newSubscriptionsSinceCutoff.size());
        assertNotNull(f.message);
        assertTrue(f.message.contains("grew"));
    }

    @Test
    void stableWhenNoNewSubscriptions() {
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(
                        sub("Gym", "20", SubscriptionFrequency.MONTHLY,
                                NOW.minusDays(180).atStartOfDay()),
                        sub("Hulu", "10", SubscriptionFrequency.MONTHLY,
                                NOW.minusDays(180).atStartOfDay())));

        final SubscriptionCreepForecastService.CreepForecast f =
                new SubscriptionCreepForecastService(subs).forecast(USER, NOW);
        assertEquals("STABLE", f.status);
    }

    @Test
    void annualSubsNormaliseToMonthlyRate() {
        // $120/year = $10/month. Both old + only one in the prior period.
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(
                        sub("Domain", "120", SubscriptionFrequency.ANNUAL,
                                NOW.minusDays(180).atStartOfDay())));

        final SubscriptionCreepForecastService.CreepForecast f =
                new SubscriptionCreepForecastService(subs).forecast(USER, NOW);
        assertEquals(new BigDecimal("10.00"), f.currentMonthlyTotal);
    }

    @Test
    void emptyPortfolioReturnsStableWithMessage() {
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER)).thenReturn(List.of());
        final SubscriptionCreepForecastService.CreepForecast f =
                new SubscriptionCreepForecastService(subs).forecast(USER, NOW);
        assertEquals("STABLE", f.status);
        assertEquals(0, f.activeSubscriptionCount);
    }

    private static Subscription sub(
            final String merchant,
            final String amount,
            final SubscriptionFrequency freq,
            final LocalDateTime createdAt) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(java.util.UUID.randomUUID().toString());
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal(amount));
        s.setFrequency(freq);
        s.setActive(true);
        s.setCreatedAt(createdAt);
        return s;
    }
}
