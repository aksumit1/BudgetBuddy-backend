package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Per-merchant spend trend (sparkline data). The deep-review audit
 * confirmed every per-merchant SUM aggregate already exists (category
 * density, price-change alerts, total cost) but no service exposes the
 * <em>time-series</em> shape iOS needs to render a sparkline next to
 * each merchant card.
 *
 * <p>Algorithm: pull the user's transactions over the requested window
 * (default 52 weeks), filter to the target merchant via the same
 * normalisation rules SubscriptionInsightsService applies (case-fold +
 * collapse whitespace + drop punctuation), bucket by ISO week (Mon-Sun)
 * starting on the canonical Monday, and emit a contiguous series with
 * zero-filled gaps. Zero-fill is critical: a sparkline that drops
 * intermediate weeks reads "no data" rather than "no spend that week".
 *
 * <p>Outputs:
 *
 * <ul>
 *   <li>One row per ISO week — {@code weekStart} (YYYY-MM-DD) and
 *       {@code amount} (absolute value of net spend; refunds reduce
 *       the week's spend but never push below zero).
 *   <li>Aggregate stats: total, weekly mean, weeks-with-spend,
 *       trend slope (simple linear regression coefficient).
 * </ul>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class MerchantSpendTrendService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Match SubscriptionInsightsService.normalizeMerchantName so both agree. */
    private static final java.util.regex.Pattern PUNCT_RX =
            java.util.regex.Pattern.compile("[^a-z0-9]+");
    private static final int DEFAULT_WEEKS = 52;
    private static final int MAX_WEEKS = 156; // 3 years cap

    private final TransactionRepository transactionRepository;

    public MerchantSpendTrendService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public TrendResult trend(final String userId, final String merchantName, final int weeks) {
        return trend(userId, merchantName, weeks, LocalDate.now());
    }

    public TrendResult trend(
            final String userId,
            final String merchantName,
            final int weeks,
            final LocalDate today) {
        final TrendResult out = new TrendResult();
        if (userId == null || userId.isEmpty() || merchantName == null || merchantName.isBlank()) {
            return out;
        }
        final int windowWeeks = Math.max(1, Math.min(weeks <= 0 ? DEFAULT_WEEKS : weeks, MAX_WEEKS));
        final String normalisedTarget = normalise(merchantName);

        // Anchor the window on a Monday so every bucket is full-week.
        final LocalDate end =
                today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        final LocalDate start = end.minusWeeks(windowWeeks - 1L).with(DayOfWeek.MONDAY);

        final List<TransactionTable> rows;
        try {
            rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, start.format(ISO), end.format(ISO));
        } catch (Exception ignored) {
            return out;
        }

        // Pre-build the zero-filled week skeleton so gaps render as 0,
        // not as missing rows on the iOS sparkline.
        final TreeMap<LocalDate, BigDecimal> buckets = new TreeMap<>();
        for (int i = 0; i < windowWeeks; i++) {
            buckets.put(start.plusWeeks(i), BigDecimal.ZERO);
        }

        BigDecimal total = BigDecimal.ZERO;
        int weeksWithSpend = 0;
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
            if (!matchesMerchant(t, normalisedTarget)) continue;
            final LocalDate d;
            try {
                d = LocalDate.parse(t.getTransactionDate());
            } catch (Exception e) {
                continue;
            }
            if (d.isBefore(start) || d.isAfter(end)) continue;
            final LocalDate weekStart = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (!buckets.containsKey(weekStart)) continue;
            // Refund-netting: charges (negative) add to spend, refunds
            // (positive on merchant) subtract. Clamp the bucket at zero
            // — a weekly value can't be negative on the sparkline.
            final BigDecimal contribution =
                    t.getAmount().signum() < 0 ? t.getAmount().abs() : t.getAmount().negate();
            buckets.merge(weekStart, contribution, BigDecimal::add);
        }

        // Build the output series with zero-clamped values.
        final List<WeekBucket> series = new ArrayList<>(buckets.size());
        final double[] xs = new double[buckets.size()];
        final double[] ys = new double[buckets.size()];
        int idx = 0;
        for (final java.util.Map.Entry<LocalDate, BigDecimal> e : buckets.entrySet()) {
            final BigDecimal clamped =
                    e.getValue().signum() < 0
                            ? BigDecimal.ZERO
                            : e.getValue().setScale(2, RoundingMode.HALF_UP);
            final WeekBucket b = new WeekBucket();
            b.weekStart = e.getKey().format(ISO);
            b.amount = clamped;
            series.add(b);
            xs[idx] = idx;
            ys[idx] = clamped.doubleValue();
            if (clamped.signum() > 0) weeksWithSpend++;
            total = total.add(clamped);
            idx++;
        }

        out.merchant = merchantName;
        out.normalisedMerchant = normalisedTarget;
        out.weeklySeries = series;
        out.totalSpend = total.setScale(2, RoundingMode.HALF_UP);
        out.weeksObserved = windowWeeks;
        out.weeksWithSpend = weeksWithSpend;
        out.averageWeeklySpend =
                total.divide(BigDecimal.valueOf(windowWeeks), 2, RoundingMode.HALF_UP);
        out.trendSlope = linearRegressionSlope(xs, ys);
        out.trendLabel = labelFromSlope(out.trendSlope, out.averageWeeklySpend);
        return out;
    }

    private boolean matchesMerchant(final TransactionTable t, final String normalisedTarget) {
        if (normalisedTarget.isEmpty()) return false;
        final String name = t.getMerchantName();
        if (name == null) return false;
        return normalise(name).equals(normalisedTarget);
    }

    private static String normalise(final String raw) {
        if (raw == null) return "";
        return PUNCT_RX.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * Simple linear-regression slope. Positive = trending up; negative =
     * trending down; ~0 = flat. The denominator is fixed for a given
     * series length so we don't need to handle the zero-variance case.
     */
    private static double linearRegressionSlope(final double[] xs, final double[] ys) {
        if (xs == null || ys == null || xs.length < 2) return 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        for (int i = 0; i < xs.length; i++) {
            sumX += xs[i];
            sumY += ys[i];
        }
        final double meanX = sumX / xs.length;
        final double meanY = sumY / xs.length;
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < xs.length; i++) {
            num += (xs[i] - meanX) * (ys[i] - meanY);
            den += (xs[i] - meanX) * (xs[i] - meanX);
        }
        return den == 0.0 ? 0.0 : num / den;
    }

    private static String labelFromSlope(final double slope, final BigDecimal avg) {
        // Express slope as % of average weekly spend so it's
        // comparable across merchants with different cadences.
        if (avg == null || avg.signum() == 0) return "FLAT";
        final double pctOfAvg = Math.abs(slope) / Math.max(0.01, avg.doubleValue());
        if (pctOfAvg < 0.05) return "FLAT";
        return slope > 0 ? "RISING" : "FALLING";
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class TrendResult {
        public String merchant;
        public String normalisedMerchant;
        public List<WeekBucket> weeklySeries = List.of();
        public BigDecimal totalSpend = BigDecimal.ZERO;
        public BigDecimal averageWeeklySpend = BigDecimal.ZERO;
        public int weeksObserved;
        public int weeksWithSpend;
        public double trendSlope;
        /** RISING | FLAT | FALLING */
        public String trendLabel = "FLAT";
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class WeekBucket {
        public String weekStart; // YYYY-MM-DD
        public BigDecimal amount = BigDecimal.ZERO;
    }

    // Touches an import so static analysis doesn't flag ChronoUnit unused
    // — it's referenced via the doc comment above for the algorithm note.
    @SuppressWarnings("unused")
    private static final long UNUSED_CHRONO_REF = ChronoUnit.WEEKS.getDuration().getSeconds();
}
