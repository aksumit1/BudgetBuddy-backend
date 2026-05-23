package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.BertEmbeddingService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Predicts which subscriptions the user is about to stop using based on
 * embedding-distance drift between recent transactions and the
 * subscription's prototype text.
 *
 * <h3>Signal</h3>
 *
 * If a user used to charge Netflix monthly AND also had nearby
 * "streaming"-like activity (Hulu watches, IMDb searches, popcorn
 * purchases), and the recent 30 days show no such adjacent activity, the
 * Netflix subscription is at risk of cancellation. Embedding distance
 * captures this without requiring a hand-coded "what's a streaming
 * adjacency" map.
 *
 * <h3>How to use</h3>
 *
 * Output is a probability [0, 1] that the user will cancel within the
 * next billing cycle. Surface at &gt;= 0.5 ("you might want to cancel
 * this") — anything lower is noise.
 *
 * <h3>Status</h3>
 *
 * Feature-flagged scaffold. Real implementation requires a calibration
 * step against historical cancellation events (we have user-deletion
 * telemetry from SubscriptionService now), which is its own data
 * collection arc. The interface ships so callers know the shape.
 */
@Service
@ConditionalOnProperty(
        name = "app.subscription.usage-prediction.enabled",
        havingValue = "true")
public class SubscriptionUsagePredictor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SubscriptionUsagePredictor.class);

    private final BertEmbeddingService embeddingService;
    private final int recentDays;
    private final double cancellationThreshold;

    public SubscriptionUsagePredictor(
            final BertEmbeddingService embeddingService,
            @Value("${app.subscription.usage-prediction.recent-days:30}") final int recentDays,
            @Value("${app.subscription.usage-prediction.cancellation-threshold:0.5}")
                    final double cancellationThreshold) {
        this.embeddingService = embeddingService;
        this.recentDays = recentDays;
        this.cancellationThreshold = cancellationThreshold;
    }

    /**
     * Probability [0, 1] that {@code subscription} will be cancelled in
     * the next billing cycle, given the user's transaction history.
     * Returns -1 sentinel if scoring couldn't run.
     */
    public double cancellationLikelihood(
            final Subscription subscription, final List<TransactionTable> userHistory) {
        if (!embeddingService.isAvailable() || subscription == null || userHistory == null) {
            return -1;
        }
        final String subText = subscription.getMerchantName() == null
                ? subscription.getCategory()
                : subscription.getMerchantName();
        if (subText == null || subText.isBlank()) return -1;
        final float[] subVec = embeddingService.embed(subText);
        if (subVec == null) return -1;

        final LocalDate cutoff = LocalDate.now().minusDays(recentDays);
        int counted = 0;
        double sumSimilarity = 0;
        for (final TransactionTable tx : userHistory) {
            if (tx == null || tx.getMerchantName() == null) continue;
            if (tx.getTransactionDate() != null) {
                try {
                    if (LocalDate.parse(tx.getTransactionDate()).isBefore(cutoff)) continue;
                } catch (Exception ignore) {
                    continue;
                }
            }
            final float[] v = embeddingService.embed(tx.getMerchantName());
            if (v == null) continue;
            sumSimilarity += BertEmbeddingService.cosineSimilarity(subVec, v);
            counted++;
        }
        if (counted == 0) return 1.0; // No recent activity at all → very likely to cancel
        final double avgSimilarity = sumSimilarity / counted;
        // Invert: high similarity → still engaged → low cancellation likelihood
        return Math.max(0, Math.min(1, 1 - avgSimilarity));
    }

    public boolean shouldFlagForCancellation(final double likelihood) {
        return likelihood >= cancellationThreshold;
    }
}
