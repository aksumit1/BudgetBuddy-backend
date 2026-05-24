package com.budgetbuddy.service.subscription;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Composite engagement score per subscription. The existing "unused"
 * detector uses a single signal (no charge in N days). This service
 * blends four:
 *
 * <ol>
 *   <li>Recency — days since the last related transaction, normalised
 *       against the subscription's frequency.
 *   <li>Frequency stability — count of charges in the last 90 days
 *       relative to what the cadence implies.
 *   <li>Price stability — coefficient of variation across the price
 *       history. Wild swings drop the score.
 *   <li>User-active flag — explicit signal from the user that the sub
 *       is/isn't relevant. Reduces score sharply when inactive.
 * </ol>
 *
 * <p>Each signal contributes 0..1 weighted scaled into 0..100. The
 * resulting score is bucketed into {@code ACTIVE} / {@code AT_RISK} /
 * {@code DORMANT}. iOS surfaces the score + tier on the subscription
 * card.
 *
 * <p>Replaces nothing — coexists with the simple unused-detector so
 * tests for it stay valid. The cancellation-recommendation flow uses
 * the new tier when present (DORMANT → high priority) instead of
 * deciding from absence-of-charge alone.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class SubscriptionEngagementScorer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SubscriptionEngagementScorer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;

    public SubscriptionEngagementScorer(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public EngagementScore score(final Subscription sub) {
        return score(sub, LocalDate.now());
    }

    public EngagementScore score(final Subscription sub, final LocalDate today) {
        final EngagementScore out = new EngagementScore();
        if (sub == null || sub.getUserId() == null || sub.getMerchantName() == null) {
            return out;
        }
        // Pull related transactions over the last 90 days for the user.
        final LocalDate windowStart = today.minusDays(90);
        final List<TransactionTable> related = relatedTransactions(sub, windowStart, today);

        final double recency = recencyScore(sub, related, today);
        final double frequency = frequencyScore(sub, related);
        final double priceStability = priceStabilityScore(sub, related);
        final double activeFlag = Boolean.TRUE.equals(sub.getActive()) ? 1.0 : 0.0;

        // Weights chosen so an actively-flagged but recently-unused sub
        // still drops to AT_RISK rather than staying ACTIVE because of
        // its explicit flag. Recency carries the most weight.
        final double composite =
                0.45 * recency + 0.25 * frequency + 0.15 * priceStability + 0.15 * activeFlag;
        final int finalScore = (int) Math.round(composite * 100);

        out.subscriptionId = sub.getSubscriptionId();
        out.score = finalScore;
        out.recencySignal = round01(recency);
        out.frequencySignal = round01(frequency);
        out.priceStabilitySignal = round01(priceStability);
        out.activeSignal = round01(activeFlag);
        out.tier = tierFor(finalScore, sub.getActive());
        out.reason = explain(out, sub);
        return out;
    }

    private List<TransactionTable> relatedTransactions(
            final Subscription sub, final LocalDate windowStart, final LocalDate today) {
        try {
            final List<TransactionTable> rows =
                    transactionRepository.findByUserIdAndDateRange(
                            sub.getUserId(), windowStart.format(ISO), today.format(ISO));
            final String normalisedMerchant = normalise(sub.getMerchantName());
            final List<TransactionTable> filtered = new ArrayList<>();
            for (final TransactionTable t : rows) {
                if (t == null || t.getMerchantName() == null || t.getDeletedAt() != null) continue;
                if (normalise(t.getMerchantName()).equals(normalisedMerchant)) {
                    filtered.add(t);
                }
            }
            return filtered;
        } catch (Exception e) {
            LOGGER.debug("EngagementScorer tx fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private double recencyScore(
            final Subscription sub,
            final List<TransactionTable> related,
            final LocalDate today) {
        if (related.isEmpty()) {
            // No charge in 90 days — recency is 0 regardless of cadence.
            return 0.0;
        }
        LocalDate latest = null;
        for (final TransactionTable t : related) {
            try {
                final LocalDate d = LocalDate.parse(t.getTransactionDate());
                if (latest == null || d.isAfter(latest)) latest = d;
            } catch (Exception ignored) {
                // skip
            }
        }
        if (latest == null) return 0.0;
        final long daysSince = ChronoUnit.DAYS.between(latest, today);
        final long expectedCadence = expectedCadenceDays(sub);
        // 0 days since → 1.0; cadence + buffer → 0.5; 2× cadence → 0.0.
        final double normalised = 1.0 - (daysSince / (2.0 * expectedCadence));
        return Math.max(0.0, Math.min(1.0, normalised));
    }

    private double frequencyScore(final Subscription sub, final List<TransactionTable> related) {
        final long expected = expectedChargesInLast90Days(sub);
        if (expected <= 0) return related.isEmpty() ? 0.0 : 1.0;
        final double ratio = (double) related.size() / expected;
        // Within ±25% of expected → 1.0; less than half expected → 0.0.
        if (ratio >= 0.75) return 1.0;
        if (ratio <= 0.5) return Math.max(0.0, ratio);
        return 0.5 + (ratio - 0.5) * 2; // linear ramp
    }

    private double priceStabilityScore(
            final Subscription sub, final List<TransactionTable> related) {
        if (sub == null || related.size() < 2) return 1.0; // not enough data → assume stable
        final Set<BigDecimal> uniquePrices = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (final TransactionTable t : related) {
            if (t.getAmount() == null) continue;
            final BigDecimal absAmount = t.getAmount().abs().setScale(2, RoundingMode.HALF_UP);
            uniquePrices.add(absAmount);
            sum = sum.add(absAmount);
            count++;
        }
        if (count == 0) return 1.0;
        if (uniquePrices.size() == 1) return 1.0;
        final BigDecimal mean = sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        if (mean.signum() == 0) return 1.0;
        double variance = 0.0;
        for (final BigDecimal p : uniquePrices) {
            final double diff = p.subtract(mean).doubleValue();
            variance += diff * diff;
        }
        final double stddev = Math.sqrt(variance / uniquePrices.size());
        final double cv = stddev / Math.max(0.01, mean.doubleValue());
        // CV <= 0.05 → 1.0; CV >= 0.50 → 0.0; linear in between.
        return Math.max(0.0, Math.min(1.0, 1.0 - (cv - 0.05) / 0.45));
    }

    private static long expectedCadenceDays(final Subscription sub) {
        if (sub == null || sub.getFrequency() == null) return 30;
        return switch (sub.getFrequency()) {
            case DAILY -> 1L;
            case WEEKLY -> 7L;
            case BI_WEEKLY -> 14L;
            case MONTHLY -> 30L;
            case QUARTERLY -> 90L;
            case SEMI_ANNUAL -> 180L;
            case ANNUAL -> 365L;
        };
    }

    private static long expectedChargesInLast90Days(final Subscription sub) {
        return 90 / Math.max(1, expectedCadenceDays(sub));
    }

    private static String tierFor(final int score, final Boolean active) {
        if (Boolean.FALSE.equals(active)) return "DORMANT";
        if (score >= 70) return "ACTIVE";
        if (score >= 40) return "AT_RISK";
        return "DORMANT";
    }

    private static String explain(final EngagementScore s, final Subscription sub) {
        final String merchant = sub.getMerchantName() == null ? "this subscription" : sub.getMerchantName();
        return switch (s.tier) {
            case "ACTIVE" -> "Recent charges and stable price — looks like " + merchant + " is in use.";
            case "AT_RISK" -> "Mixed signals on " + merchant + " — recent activity is thin or prices have moved.";
            default -> "No recent activity for " + merchant + " — strong candidate for review.";
        };
    }

    private static double round01(final double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String normalise(final String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class EngagementScore {
        public String subscriptionId;
        public int score;
        public double recencySignal;
        public double frequencySignal;
        public double priceStabilitySignal;
        public double activeSignal;
        /** ACTIVE | AT_RISK | DORMANT */
        public String tier = "DORMANT";
        public String reason = "";
    }
}
