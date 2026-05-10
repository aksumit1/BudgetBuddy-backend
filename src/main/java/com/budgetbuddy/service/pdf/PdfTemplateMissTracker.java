package com.budgetbuddy.service.pdf;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory rolling counter of PDF template-miss events.
 *
 * <h3>Why this exists</h3>
 *
 * Every time the structured template parsers (legacy Pattern 1-7 plus the YAML registry) fail to
 * match a PDF and we fall back to the loose regex extractor, we log a structured {@code
 * PDF_TEMPLATE_MISS} line. The log is useful but invisible — ops has to grep for it and manually
 * tally by bank to know which template to fix next.
 *
 * <p>This tracker aggregates those events into a ranked view so the team can see at a glance: "Citi
 * missed 120 times this week → write a better Citi template." It's the closing half of the "learn
 * from real failures" loop that was previously open-ended.
 *
 * <h3>Design</h3>
 *
 * - **Bounded in-memory state**: one {@link ConcurrentLinkedDeque} of events per (institution,
 * accountType) key, capped at {@link #MAX_EVENTS_PER_BUCKET} rows. Old events are evicted when the
 * deque fills or when they age out past the window. No unbounded growth; safe to leave running. -
 * **No persistence**: this is an operational signal, not audit data. If the process restarts,
 * counters reset. A separate observability pipeline (CloudWatch, Grafana, Datadog) is the right
 * home for long-term persistence — see the README for how to forward the events. - **No locking on
 * the hot path**: `record()` does a single atomic counter increment plus a deque append. Reads
 * (which walk the deque) take a snapshot — a live concurrent writer won't corrupt the ranking.
 *
 * <h3>What to do with the data</h3>
 *
 * GET {@code /api/admin/pdf-parse-health} returns the ranked misses. Hook it into ops dashboards;
 * set an SLA like "any institution with more than 20 misses in 7 days gets a template improvement
 * ticket within-sprint."
 */
@Service
public class PdfTemplateMissTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfTemplateMissTracker.class);

    /**
     * Cap per (institution, accountType) bucket — prevents one noisy bank from eating unbounded
     * memory if the miss rate spikes during an incident.
     */
    private static final int MAX_EVENTS_PER_BUCKET = 1_000;

    /** Default rolling window the admin endpoint reports on. */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /** Events older than this are pruned on every record() and every read. */
    private static final Duration MAX_RETENTION = Duration.ofDays(30);

    private final ConcurrentHashMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong totalMisses = new AtomicLong();

    /**
     * Record a miss. Called by PDFImportService when the loose-fallback path activates (all
     * structured templates produced zero rows).
     *
     * @param institution bank / card issuer detected from the PDF, or "unknown"
     * @param accountType "checking" / "credit" / "savings" / "investment" / "unknown"
     * @param pageCount total pages in the statement (diagnostic)
     * @param textLength characters of extracted text (helps tell scanned vs text PDFs)
     * @param fallbackRows rows the loose extractor managed to pull; 0 means total failure
     */
    public void record(
            final String institution,
            final String accountType,
            final int pageCount,
            final int textLength,
            final int fallbackRows) {
        final String inst = institution == null || institution.isBlank() ? "unknown" : institution;
        final String type = accountType == null || accountType.isBlank() ? "unknown" : accountType;
        final BucketKey key = new BucketKey(inst, type);
        final Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        bucket.record(new MissEvent(Instant.now(), pageCount, textLength, fallbackRows));
        totalMisses.incrementAndGet();
    }

    /**
     * Ranked misses over the last {@code window}. Institutions with the highest miss count come
     * first. Empty list when nothing has missed.
     */
    public List<InstitutionMissRanking> rankByMissesIn(final Duration window) {
        final Instant cutoff = Instant.now().minus(window);
        return buckets.entrySet().stream()
                .map(
                        entry -> {
                            final Bucket b = entry.getValue();
                            final long count = b.countSince(cutoff);
                            if (count == 0) {
                                return null;
                            }
                            final Instant latest = b.latest();
                            return new InstitutionMissRanking(
                                    entry.getKey().institution,
                                    entry.getKey().accountType,
                                    count,
                                    latest,
                                    b.averageFallbackRowsSince(cutoff));
                        })
                .filter(Objects::nonNull)
                .sorted(
                        Comparator.comparingLong(InstitutionMissRanking::missCount)
                                .reversed()
                                .thenComparing(InstitutionMissRanking::institution))
                .collect(Collectors.toList());
    }

    /** Default window ranking (7 days). */
    public List<InstitutionMissRanking> rank() {
        return rankByMissesIn(DEFAULT_WINDOW);
    }

    /** Total miss count since process start — useful for a single gauge metric. */
    public long totalMisses() {
        return totalMisses.get();
    }

    /** Explicitly clear state (tests only). */
    public void resetForTesting() {
        buckets.clear();
        totalMisses.set(0);
    }

    // ---- internals ----

    private static final class BucketKey {
        final String institution;
        final String accountType;

        BucketKey(final String institution, final String accountType) {
            this.institution = institution;
            this.accountType = accountType;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof BucketKey b)) {
                return false;
            }
            return institution.equals(b.institution) && accountType.equals(b.accountType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(institution, accountType);
        }
    }

    @SuppressWarnings("PMD.LawOfDemeter") // peeks/lasts on internal Deque — false positive
    private final class Bucket {
        private final ConcurrentLinkedDeque<MissEvent> events = new ConcurrentLinkedDeque<>();

        void record(final MissEvent event) {
            events.addLast(event);
            // Prune old + over-cap in one pass. Cheap because deque is a linked list.
            final Instant retentionCutoff = Instant.now().minus(MAX_RETENTION);
            while (!events.isEmpty()
                    && (events.peekFirst().at.isBefore(retentionCutoff)
                            || events.size() > MAX_EVENTS_PER_BUCKET)) {
                events.pollFirst();
            }
        }

        long countSince(final Instant cutoff) {
            return events.stream().filter(e -> !e.at.isBefore(cutoff)).count();
        }

        double averageFallbackRowsSince(final Instant cutoff) {
            return events.stream()
                    .filter(e -> !e.at.isBefore(cutoff))
                    .mapToInt(e -> e.fallbackRows)
                    .average()
                    .orElse(0.0);
        }

        Instant latest() {
            final MissEvent last = events.peekLast();
            return last != null ? last.at : Instant.EPOCH;
        }
    }

    private static final class MissEvent {
        final Instant at;
        final int pageCount;
        final int textLength;
        final int fallbackRows;

        MissEvent(final Instant at, final int pageCount, final int textLength, final int fallbackRows) {
            this.at = at;
            this.pageCount = pageCount;
            this.textLength = textLength;
            this.fallbackRows = fallbackRows;
        }
    }

    /** Public wire shape for the admin endpoint. */
    public record InstitutionMissRanking(
            String institution,
            String accountType,
            long missCount,
            Instant lastMissAt,
            double averageFallbackRows) {}
}
