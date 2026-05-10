package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AnomalyFeedbackTable;
import com.budgetbuddy.repository.dynamodb.AnomalyFeedbackRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 7 / O1 — business layer around {@link AnomalyFeedbackRepository}.
 *
 * <p>Two jobs: 1. Persist a verdict (NORMAL / CONFIRMED / DISMISSED) with a stable per-pattern
 * fingerprint, so "this is my weekly Costco run" silences the whole pattern. 2. Expose a per-user
 * suppression set that the anomaly detector consults to filter out already-dismissed fingerprints.
 *
 * <p>The fingerprint intentionally rounds the amount into $20 buckets. Real-world repeat spending
 * never lands on the same dollar amount (sales tax, incidentals), but it does cluster inside a
 * bucket. Rounded further and we'd swallow genuine anomalies; rounded finer and we never match. $20
 * is the compromise.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Service
public class AnomalyFeedbackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyFeedbackService.class);

    public enum Verdict {
        NORMAL,
        CONFIRMED,
        DISMISSED;

        public static Verdict parse(final String raw) {
            if (raw == null) {
                return NORMAL;
            }
            try {
                return Verdict.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    private final AnomalyFeedbackRepository repository;

    public AnomalyFeedbackService(final AnomalyFeedbackRepository repository) {
        this.repository = repository;
    }

    public AnomalyFeedbackTable record(
            final String userId,
            final String anomalyId,
            final String merchant,
            final String category,
            final BigDecimal amount,
            final Verdict verdict) {
        final String fp = fingerprintOf(merchant, category, amount);
        final String feedbackId =
                UUID.nameUUIDFromBytes(
                                (userId + "|" + fp + "|" + verdict.name())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();

        final AnomalyFeedbackTable row =
                repository.findById(feedbackId).orElseGet(AnomalyFeedbackTable::new);
        row.setFeedbackId(feedbackId);
        row.setUserId(userId);
        row.setFingerprint(fp);
        row.setVerdict(verdict.name());
        row.setAnomalyId(anomalyId);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(Instant.now());
        }
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        LOGGER.info(
                "Recorded anomaly feedback user={} fingerprint={} verdict={}", userId, fp, verdict);
        return row;
    }

    /**
     * Returns the set of fingerprints the user has already DISMISSED. Used by the detector to skip
     * suppressed patterns. CONFIRMED verdicts aren't suppression — they're explicit
     * acknowledgements, which the UI can surface differently.
     */
    public Set<String> dismissedFingerprintsFor(final String userId) {
        final List<AnomalyFeedbackTable> all = repository.findByUserId(userId);
        return all.stream()
                .filter(f -> Verdict.DISMISSED.name().equalsIgnoreCase(f.getVerdict()))
                .map(AnomalyFeedbackTable::getFingerprint)
                .collect(Collectors.toSet());
    }

    /**
     * Build the suppression fingerprint. Amount is rounded to a $20 bucket so "$180 at Costco" and
     * "$194 at Costco" collapse to the same pattern — otherwise a dismissal would be permanently
     * invalidated by every cent of noise.
     */
    public static String fingerprintOf(
            final String merchant, final String category, final BigDecimal amount) {
        final String m = merchant == null ? "" : merchant.trim().toLowerCase(Locale.ROOT);
        final String c = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String a = "0";
        if (amount != null) {
            final BigDecimal abs = amount.abs();
            final BigDecimal bucket =
                    abs.divide(new BigDecimal("20"), 0, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("20"));
            a = bucket.toPlainString();
        }
        return m + "|" + c + "|" + a;
    }
}
