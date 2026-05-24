package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Forward-looking renewal calendar for active subscriptions. Deep-review
 * audit confirmed the rest of the subscription stack is reactive (idle
 * subs, post-charge price-change alerts, churn prediction). This service
 * fills the only forward-looking gap users actually feel: "what's
 * billing me next, and is anything renewing in time for me to cancel?"
 *
 * <p>Algorithm: every active subscription has a {@code lastPaymentDate}
 * and a {@code frequency}; their sum is the expected next billing date.
 * For annual subs that landed an unexpectedly early charge (mid-cycle
 * top-up, refund-then-rebill), we clamp the result to "today or later"
 * rather than report a date in the past.
 *
 * <p>The deterministic output feeds two surfaces:
 *
 * <ul>
 *   <li>{@link #renewalCalendar} returns the upcoming N-day window
 *       (default 30), sorted by date. iOS renders this as a
 *       calendar/timeline list on the subscriptions tab.
 *   <li>{@link #annualReviewWindow} returns annual + semi-annual subs
 *       renewing within the next M days (default 14) so the user has
 *       time to cancel before the renewal hits their card — long-cycle
 *       subs are the ones users actually forget about.
 * </ul>
 *
 * <p>No LLM hook here on purpose — renewal dates must be deterministic
 * and accountant-grade. The narrative wrapper above (FinancialNarrativeAdvisor)
 * is where personality goes; this service is the source of truth.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class SubscriptionRenewalForecastService {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int DEFAULT_ANNUAL_WINDOW_DAYS = 14;
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");

    private final SubscriptionService subscriptionService;

    public SubscriptionRenewalForecastService(final SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    public List<UpcomingRenewal> renewalCalendar(final String userId) {
        return renewalCalendar(userId, DEFAULT_WINDOW_DAYS, LocalDate.now());
    }

    public List<UpcomingRenewal> renewalCalendar(
            final String userId, final int windowDays, final LocalDate today) {
        if (userId == null || userId.isEmpty()) return List.of();
        final int window = Math.max(1, windowDays);
        final List<Subscription> subs;
        try {
            subs = subscriptionService.getSubscriptions(userId);
        } catch (Exception ignored) {
            return List.of();
        }

        final List<UpcomingRenewal> out = new ArrayList<>();
        for (final Subscription s : subs) {
            if (s == null || !Boolean.TRUE.equals(s.getActive())) continue;
            if (s.getAmount() == null || s.getAmount().signum() <= 0) continue;
            final LocalDate next = nextBillingDate(s, today);
            if (next == null) continue;
            final long daysUntil = ChronoUnit.DAYS.between(today, next);
            if (daysUntil < 0 || daysUntil > window) continue;

            final UpcomingRenewal r = new UpcomingRenewal();
            r.subscriptionId = s.getSubscriptionId();
            r.merchantName = s.getMerchantName();
            r.amount = s.getAmount().setScale(2, RoundingMode.HALF_UP);
            r.frequency = s.getFrequency() == null ? null : s.getFrequency().name();
            r.nextBillingDate = next.toString();
            r.daysUntilRenewal = (int) daysUntil;
            r.monthlyEquivalent = monthlyEquivalent(s.getAmount(), s.getFrequency());
            out.add(r);
        }
        out.sort(Comparator.comparingInt(a -> a.daysUntilRenewal));
        return out;
    }

    /**
     * Long-cycle subs (annual + semi-annual) that will renew within the
     * configured window. These are the ones users forget about — a
     * monthly Netflix charge stays visible, an annual $99 charge hits
     * once and surprises them. Surfaced as "review-before-renewal" so
     * the user has time to cancel.
     */
    public List<UpcomingRenewal> annualReviewWindow(final String userId) {
        return annualReviewWindow(userId, DEFAULT_ANNUAL_WINDOW_DAYS, LocalDate.now());
    }

    public List<UpcomingRenewal> annualReviewWindow(
            final String userId, final int windowDays, final LocalDate today) {
        return renewalCalendar(userId, windowDays, today).stream()
                .filter(
                        r -> r.frequency != null
                                && ("ANNUAL".equals(r.frequency) || "SEMI_ANNUAL".equals(r.frequency)))
                .toList();
    }

    /**
     * Compute the next billing date from lastPaymentDate + frequency.
     * Clamps to today-or-later: if a payment somehow landed and the
     * stored lastPaymentDate is already past the expected next cycle
     * (e.g. user manually re-entered an old payment), step forward
     * until we land in the future. Returns null when frequency is
     * unknown or no payment history exists yet.
     */
    public LocalDate nextBillingDate(final Subscription sub, final LocalDate today) {
        if (sub == null || sub.getFrequency() == null || sub.getLastPaymentDate() == null) {
            return null;
        }
        LocalDate next = addCycle(sub.getLastPaymentDate(), sub.getFrequency());
        // Safety net: if the user's lastPaymentDate is very stale (e.g.
        // they paused + resumed), step forward through cycles until
        // we're either at today or in the future. Capped at 60 iterations
        // so a malformed frequency can't infinite-loop.
        int hops = 0;
        while (next != null && next.isBefore(today) && hops < 60) {
            next = addCycle(next, sub.getFrequency());
            hops++;
        }
        return next;
    }

    private static LocalDate addCycle(final LocalDate from, final SubscriptionFrequency freq) {
        if (from == null || freq == null) return null;
        return switch (freq) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case BI_WEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case SEMI_ANNUAL -> from.plusMonths(6);
            case ANNUAL -> from.plusYears(1);
        };
    }

    private static BigDecimal monthlyEquivalent(
            final BigDecimal amount, final SubscriptionFrequency freq) {
        if (amount == null || freq == null) return BigDecimal.ZERO.setScale(2);
        return switch (freq) {
            case DAILY -> amount.multiply(DAYS_PER_MONTH).setScale(2, RoundingMode.HALF_UP);
            case WEEKLY ->
                    amount.multiply(DAYS_PER_MONTH).divide(new BigDecimal("7"), 2, RoundingMode.HALF_UP);
            case BI_WEEKLY ->
                    amount.multiply(DAYS_PER_MONTH).divide(new BigDecimal("14"), 2, RoundingMode.HALF_UP);
            case MONTHLY -> amount.setScale(2, RoundingMode.HALF_UP);
            case QUARTERLY -> amount.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
            case SEMI_ANNUAL -> amount.divide(new BigDecimal("6"), 2, RoundingMode.HALF_UP);
            case ANNUAL -> amount.divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
        };
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class UpcomingRenewal {
        public String subscriptionId;
        public String merchantName;
        public BigDecimal amount = BigDecimal.ZERO;
        public BigDecimal monthlyEquivalent = BigDecimal.ZERO;
        public String frequency; // "MONTHLY" | "ANNUAL" | …
        public String nextBillingDate;
        public int daysUntilRenewal;
    }
}
