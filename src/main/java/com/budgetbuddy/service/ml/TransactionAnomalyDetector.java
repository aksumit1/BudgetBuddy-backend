package com.budgetbuddy.service.ml;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Per-user anomaly detector. Given a new transaction, returns an
 * "unusualness" score by comparing its BERT embedding against the user's
 * own last 90 days of transactions. High score = "this charge is unlike
 * anything you usually buy" — surfaces unfamiliar / fraudulent charges
 * before the user has to spot them.
 *
 * <h3>Approach</h3>
 *
 * For each new transaction:
 *   1. embed (merchantName + description) -> vector
 *   2. fetch user's 90d history (already in cache via TransactionRepository)
 *   3. compute max-cosine-similarity against history
 *   4. score = 1 - max_similarity (so high score = unfamiliar)
 *
 * <h3>Why not k-means / autoencoder</h3>
 *
 * Per-user data is small (median user has ~500 txs / 90d). Embeddings
 * + nearest-neighbor is the right tool at this scale. The base model is
 * shared, the per-user state is just the recent history (already
 * persisted in DynamoDB).
 *
 * <h3>Thresholds</h3>
 *
 * Surface in iOS at score &gt;= 0.5 (familiar threshold during eval). UNDER
 * 0.5 means "we've seen something like this from you before"; OVER 0.5
 * means worth a glance.
 *
 * <h3>Implementation status</h3>
 *
 * Scaffolding only — opt-in via {@code app.anomaly-detection.enabled=true}.
 * Wire-up into the transaction-creation path (TransactionService.save) is
 * deferred so we can soak the embedding cost (one embed per save) without
 * affecting prod latency.
 */
@Service
@ConditionalOnProperty(name = "app.anomaly-detection.enabled", havingValue = "true")
public class TransactionAnomalyDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionAnomalyDetector.class);

    /** History window — last N days of the user's own transactions. */
    private final int historyDays;
    /** Score above this is "anomalous" enough to flag in the UI. */
    private final double anomalyThreshold;

    private final BertEmbeddingService embeddingService;

    public TransactionAnomalyDetector(
            final BertEmbeddingService embeddingService,
            @Value("${app.anomaly-detection.history-days:90}") final int historyDays,
            @Value("${app.anomaly-detection.threshold:0.5}") final double anomalyThreshold) {
        this.embeddingService = embeddingService;
        this.historyDays = historyDays;
        this.anomalyThreshold = anomalyThreshold;
    }

    /**
     * Score a single transaction against the user's recent history.
     * Returns a value in [0, 1]; 0 = exactly like prior transactions,
     * 1 = nothing like them. Returns -1 (sentinel) if scoring couldn't run
     * (embedding service unavailable, empty history, etc.) — callers
     * should treat that as "no signal".
     */
    public double scoreUnusualness(
            final TransactionTable candidate, final List<TransactionTable> userHistory) {
        if (!embeddingService.isAvailable()
                || candidate == null
                || userHistory == null
                || userHistory.isEmpty()) {
            return -1;
        }
        final String candidateText = textFor(candidate);
        if (candidateText.isBlank()) return -1;
        final float[] q = embeddingService.embed(candidateText);
        if (q == null) return -1;

        final LocalDate cutoff = LocalDate.now().minusDays(historyDays);
        double maxSim = -1;
        for (final TransactionTable past : userHistory) {
            if (past == null) continue;
            // Skip the candidate itself if it's in the history
            if (past.getTransactionId() != null
                    && past.getTransactionId().equals(candidate.getTransactionId())) {
                continue;
            }
            if (past.getTransactionDate() != null) {
                try {
                    final LocalDate d = LocalDate.parse(past.getTransactionDate());
                    if (d.isBefore(cutoff)) continue;
                } catch (Exception ignore) {
                    // Skip unparseable dates rather than crash
                    continue;
                }
            }
            final String pastText = textFor(past);
            if (pastText.isBlank()) continue;
            final float[] v = embeddingService.embed(pastText);
            if (v == null) continue;
            final double sim = BertEmbeddingService.cosineSimilarity(q, v);
            if (sim > maxSim) maxSim = sim;
        }
        if (maxSim < 0) return -1;
        return Math.max(0, 1 - maxSim);
    }

    public boolean isAnomalous(final double score) {
        return score >= anomalyThreshold;
    }

    private static String textFor(final TransactionTable tx) {
        if (tx == null) return "";
        final StringBuilder sb = new StringBuilder(128);
        if (tx.getMerchantName() != null) sb.append(tx.getMerchantName()).append(' ');
        if (tx.getDescription() != null) sb.append(tx.getDescription());
        return sb.toString().trim();
    }

    /** Convenience: filter user history to the analysis window. */
    public List<TransactionTable> filterToWindow(final List<TransactionTable> history) {
        final LocalDate cutoff = LocalDate.now().minusDays(historyDays);
        final List<TransactionTable> out = new ArrayList<>();
        for (final TransactionTable t : history) {
            if (t == null || t.getTransactionDate() == null) continue;
            try {
                if (LocalDate.parse(t.getTransactionDate()).isAfter(cutoff)) out.add(t);
            } catch (Exception ignore) {
                // skip
            }
        }
        return out;
    }
}
