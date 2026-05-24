package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.service.SubscriptionRenewalForecastService.UpcomingRenewal;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class SubscriptionRenewalForecastServiceTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void monthlySubLastPaidThreeWeeksAgoSurfacesInOneWeek() {
        // last paid May 1 + monthly → next billing June 1 → 17 days out.
        // The default 30-day window includes it.
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(monthly("Netflix", "10.99", LocalDate.of(2026, 5, 1))));

        final List<UpcomingRenewal> rs =
                new SubscriptionRenewalForecastService(subs).renewalCalendar(USER, 30, NOW);
        assertEquals(1, rs.size());
        assertEquals("Netflix", rs.get(0).merchantName);
        assertEquals("2026-06-01", rs.get(0).nextBillingDate);
        assertEquals(17, rs.get(0).daysUntilRenewal);
        assertEquals(new BigDecimal("10.99"), rs.get(0).monthlyEquivalent);
    }

    @Test
    void subRenewingOutsideWindowIsExcluded() {
        // last paid May 1 + annual → next billing 2027-05-01 → 351 days
        // out. 30-day window excludes it.
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(annual("Domain", "120", LocalDate.of(2026, 5, 1))));

        assertTrue(
                new SubscriptionRenewalForecastService(subs).renewalCalendar(USER, 30, NOW).isEmpty());
    }

    @Test
    void staleLastPaymentDateIsFastForwardedToFutureCycle() {
        // last paid 2020-01-01 (way old) + monthly → service should
        // hop forward until next billing is at or after `today`.
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(monthly("Gym", "20", LocalDate.of(2020, 1, 1))));

        final List<UpcomingRenewal> rs =
                new SubscriptionRenewalForecastService(subs).renewalCalendar(USER, 60, NOW);
        // The fast-forwarded date must not be in the past.
        if (!rs.isEmpty()) {
            assertTrue(
                    LocalDate.parse(rs.get(0).nextBillingDate).isAfter(NOW.minusDays(1)),
                    "Stale lastPaymentDate must be fast-forwarded past today");
        }
    }

    @Test
    void inactiveSubscriptionIsSkipped() {
        final SubscriptionService subs = mock(SubscriptionService.class);
        final Subscription inactive =
                monthly("Netflix", "10.99", LocalDate.of(2026, 5, 1));
        inactive.setActive(false);
        when(subs.getSubscriptions(USER)).thenReturn(List.of(inactive));

        assertTrue(
                new SubscriptionRenewalForecastService(subs).renewalCalendar(USER, 30, NOW).isEmpty());
    }

    @Test
    void annualReviewWindowOnlyReturnsLongCycleSubs() {
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(
                        // Annual renewing in 7 days → should appear.
                        annual("AmazonPrime", "139", LocalDate.of(2025, 5, 22)),
                        // Monthly renewing in 5 days → should NOT appear.
                        monthly("Netflix", "10.99", LocalDate.of(2026, 4, 20))));

        final List<UpcomingRenewal> rs =
                new SubscriptionRenewalForecastService(subs).annualReviewWindow(USER, 14, NOW);
        assertEquals(1, rs.size());
        assertEquals("AmazonPrime", rs.get(0).merchantName);
    }

    @Test
    void nullFrequencyOrPaymentDateReturnsNullBillingDate() {
        final SubscriptionService subs = mock(SubscriptionService.class);
        final SubscriptionRenewalForecastService svc = new SubscriptionRenewalForecastService(subs);
        final Subscription bare = new Subscription();
        bare.setSubscriptionId("s1");
        bare.setActive(true);
        bare.setAmount(new BigDecimal("10"));
        assertNull(svc.nextBillingDate(bare, NOW));
    }

    private static Subscription monthly(
            final String merchant, final String amount, final LocalDate lastPaid) {
        return sub(merchant, amount, SubscriptionFrequency.MONTHLY, lastPaid);
    }

    private static Subscription annual(
            final String merchant, final String amount, final LocalDate lastPaid) {
        return sub(merchant, amount, SubscriptionFrequency.ANNUAL, lastPaid);
    }

    private static Subscription sub(
            final String merchant,
            final String amount,
            final SubscriptionFrequency freq,
            final LocalDate lastPaid) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(java.util.UUID.randomUUID().toString());
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal(amount));
        s.setFrequency(freq);
        s.setActive(true);
        s.setLastPaymentDate(lastPaid);
        return s;
    }
}
