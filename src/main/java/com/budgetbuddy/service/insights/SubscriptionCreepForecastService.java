package com.budgetbuddy.service.insights;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Subscription portfolio creep detector.
 *
 * <p>Per-subscription alerts (price changes, idle subs) already exist
 * — but no detector watches the <em>aggregate</em>. A user can add three
 * $9.99/month services in a quarter without any individual alert tripping,
 * yet their monthly spend on subs has grown 30%. This service surfaces the
 * portfolio-level trend.
 *
 * <p>Algorithm: monthly-normalise every active subscription's price
 * (weekly × 4.345, biweekly × 2.17, monthly × 1, etc.), then compare:
 * <ul>
 *   <li>{@code currentMonthlyTotal} — sum across all active subs today.
 *   <li>{@code priorMonthlyTotal} — same sum, but only over subs that
 *       existed 30+ days ago (the inverse: new subs subtracted).
 *   <li>{@code monthOverMonthDelta} = current - prior.
 * </ul>
 *
 * <p>Alert status:
 * <ul>
 *   <li>STABLE — delta within ±5% of prior
 *   <li>CREEPING — delta between 5% and 15%
 *   <li>SPIKING — delta >15% (the user-facing warning)
 *   <li>SHRINKING — delta below -5% (positive direction; same threshold)
 * </ul>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class SubscriptionCreepForecastService {

    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");
    private static final BigDecimal SPIKE_THRESHOLD = new BigDecimal("0.15");
    private static final BigDecimal CREEP_THRESHOLD = new BigDecimal("0.05");
    private static final int CUTOFF_DAYS = 30;

    private final SubscriptionService subscriptionService;

    public SubscriptionCreepForecastService(final SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    public CreepForecast forecast(final String userId) {
        return forecast(userId, LocalDate.now());
    }

    /**
     * RISK-1 context-aware overload: reads the subscriptions list from the
     * pre-fetched {@link InsightsContext} snapshot instead of issuing a
     * fresh {@code subscriptionService.getSubscriptions(...)} call on
     * every /summary request.
     */
    public CreepForecast forecast(final InsightsContext ctx) {
        if (ctx == null) return new CreepForecast();
        return computeFromSubs(ctx.subscriptions(), ctx.asOf());
    }

    public CreepForecast forecast(final String userId, final LocalDate today) {
        final CreepForecast f = new CreepForecast();
        if (userId == null || userId.isEmpty()) return f;
        final List<Subscription> subs;
        try {
            subs = subscriptionService.getSubscriptions(userId);
        } catch (Exception e) {
            f.status = "STABLE";
            f.message = "Subscription data unavailable.";
            return f;
        }
        return computeFromSubs(subs, today);
    }

    private static CreepForecast computeFromSubs(final List<Subscription> subs, final LocalDate today) {
        final CreepForecast f = new CreepForecast();
        if (subs == null || subs.isEmpty()) {
            f.status = "STABLE";
            f.message = "No active subscriptions tracked.";
            return f;
        }

        final LocalDateTime cutoff = today.minusDays(CUTOFF_DAYS).atStartOfDay();
        BigDecimal currentTotal = BigDecimal.ZERO;
        BigDecimal priorTotal = BigDecimal.ZERO;
        final List<NewSub> newSubs = new ArrayList<>();
        int activeCount = 0;

        for (final Subscription s : subs) {
            if (s == null) continue;
            if (!Boolean.TRUE.equals(s.getActive())) continue;
            if (s.getAmount() == null || s.getAmount().signum() <= 0) continue;
            activeCount++;
            final BigDecimal monthly = normaliseToMonthly(s.getAmount(), s.getFrequency());
            currentTotal = currentTotal.add(monthly);
            // Subscription is "prior" iff it existed at the cutoff. createdAt
            // is null on legacy rows — treat null as "existed already" to
            // avoid mislabelling old data as new creep.
            final LocalDateTime created = s.getCreatedAt();
            if (created == null || !created.isAfter(cutoff)) {
                priorTotal = priorTotal.add(monthly);
            } else {
                final NewSub ns = new NewSub();
                ns.merchantName = s.getMerchantName();
                ns.monthlyCost = monthly.setScale(2, RoundingMode.HALF_UP);
                ns.addedOn = created.toLocalDate().toString();
                newSubs.add(ns);
            }
        }

        f.activeSubscriptionCount = activeCount;
        f.currentMonthlyTotal = currentTotal.setScale(2, RoundingMode.HALF_UP);
        f.priorMonthlyTotal = priorTotal.setScale(2, RoundingMode.HALF_UP);
        f.monthOverMonthDelta =
                f.currentMonthlyTotal.subtract(f.priorMonthlyTotal).setScale(2, RoundingMode.HALF_UP);
        f.newSubscriptionsSinceCutoff = newSubs;

        final BigDecimal ratio =
                priorTotal.signum() == 0
                        ? (currentTotal.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE)
                        : f.monthOverMonthDelta.divide(priorTotal, 4, RoundingMode.HALF_UP);
        f.monthOverMonthRatio = ratio;

        if (ratio.compareTo(SPIKE_THRESHOLD) >= 0) {
            f.status = "SPIKING";
            f.message =
                    String.format(
                            "Subscription portfolio grew %.0f%% (+%s/month) in the last 30 days.",
                            ratio.doubleValue() * 100.0,
                            usd(f.monthOverMonthDelta));
        } else if (ratio.compareTo(CREEP_THRESHOLD) >= 0) {
            f.status = "CREEPING";
            f.message =
                    String.format(
                            "Subscription costs ticked up %.0f%% (+%s/month). Worth a review.",
                            ratio.doubleValue() * 100.0,
                            usd(f.monthOverMonthDelta));
        } else if (ratio.compareTo(CREEP_THRESHOLD.negate()) <= 0) {
            f.status = "SHRINKING";
            f.message =
                    String.format(
                            "Subscriptions trimmed %.0f%% (-%s/month). Nice.",
                            Math.abs(ratio.doubleValue()) * 100.0,
                            usd(f.monthOverMonthDelta.abs()));
        } else {
            f.status = "STABLE";
            f.message = "Subscription costs are roughly steady over the last 30 days.";
        }
        return f;
    }

    private static BigDecimal normaliseToMonthly(
            final BigDecimal amount, final Subscription.SubscriptionFrequency freq) {
        if (amount == null) return BigDecimal.ZERO;
        if (freq == null) return amount;
        return switch (freq) {
            case DAILY -> amount.multiply(DAYS_PER_MONTH);
            case WEEKLY -> amount.multiply(DAYS_PER_MONTH).divide(new BigDecimal("7"), 4, RoundingMode.HALF_UP);
            case BI_WEEKLY -> amount.multiply(DAYS_PER_MONTH).divide(new BigDecimal("14"), 4, RoundingMode.HALF_UP);
            case MONTHLY -> amount;
            case QUARTERLY -> amount.divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP);
            case SEMI_ANNUAL -> amount.divide(new BigDecimal("6"), 4, RoundingMode.HALF_UP);
            case ANNUAL -> amount.divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP);
        };
    }

    private static String usd(final BigDecimal v) {
        return "$" + v.setScale(0, RoundingMode.HALF_UP);
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class CreepForecast {
        public int activeSubscriptionCount;
        public BigDecimal currentMonthlyTotal = BigDecimal.ZERO;
        public BigDecimal priorMonthlyTotal = BigDecimal.ZERO;
        public BigDecimal monthOverMonthDelta = BigDecimal.ZERO;
        public BigDecimal monthOverMonthRatio = BigDecimal.ZERO;
        /** STABLE | CREEPING | SPIKING | SHRINKING */
        public String status = "STABLE";
        public String message = "";
        public List<NewSub> newSubscriptionsSinceCutoff = new ArrayList<>();
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class NewSub {
        public String merchantName;
        public BigDecimal monthlyCost = BigDecimal.ZERO;
        public String addedOn;
    }
}
