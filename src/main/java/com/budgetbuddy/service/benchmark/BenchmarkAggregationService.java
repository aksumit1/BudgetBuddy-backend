package com.budgetbuddy.service.benchmark;

import com.budgetbuddy.model.dynamodb.BenchmarkTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserPreferencesTable;
import com.budgetbuddy.repository.dynamodb.BenchmarkRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserPreferencesRepository;
import com.budgetbuddy.service.DistributedLockService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Community spend-benchmarks aggregation — real implementation.
 *
 * <p>Pipeline per daily run:
 *
 * <ol>
 *   <li>Scan {@link UserPreferencesRepository} for opt-in users ({@code shareAnonymisedStats ==
 *       true}).
 *   <li>For each opt-in user, fetch 90 days of transactions and compute per-category monthly
 *       average (sum ÷ 3).
 *   <li>Bucket each user by {@code annualIncomeTier + householdSize}, both of which are
 *       pre-bucketed strings the user picked on opt-in — we never ingest raw income values into the
 *       pipeline.
 *   <li>Per (bucket, category): compute p25 / median / p75 across contributors. Only emit buckets
 *       with {@link #MIN_SAMPLES} contributors (k-anonymity floor); only emit categories with at
 *       least {@link #MIN_CATEGORY_CONTRIBUTORS} non-zero samples.
 *   <li>Persist via {@link BenchmarkRepository}.
 * </ol>
 *
 * <p>Falls back to BLS-seeded placeholder bands when a bucket has no real data.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Service
public class BenchmarkAggregationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkAggregationService.class);

    /** K-anonymity floor — no bucket emits bands with fewer contributors. */
    private static final int MIN_SAMPLES = 50;

    /** Sparse-category suppression. */
    private static final int MIN_CATEGORY_CONTRIBUTORS = 15;

    public static class BenchmarkRow {
        public final String category;
        public final BigDecimal medianMonthly;
        public final BigDecimal p25;
        public final BigDecimal p75;
        public final String bucketLabel;

        public BenchmarkRow(
                final String category,
                final BigDecimal median,
                final BigDecimal p25,
                final BigDecimal p75,
                final String bucket) {
            this.category = category;
            this.medianMonthly = median;
            this.p25 = p25;
            this.p75 = p75;
            this.bucketLabel = bucket;
        }
    }

    private static final String DEFAULT_BUCKET_ID = "inc-75-150k-hh-1-2";
    private static final String DEFAULT_BUCKET_LABEL = "Income $75–150K · 1–2 people";

    private final BenchmarkRepository repository;
    private final UserPreferencesRepository prefsRepo;
    private final TransactionRepository transactionRepository;
    private final DistributedLockService distributedLock;

    public BenchmarkAggregationService(
            final BenchmarkRepository repository,
            final UserPreferencesRepository prefsRepo,
            final TransactionRepository transactionRepository,
            final DistributedLockService distributedLock) {
        this.repository = repository;
        this.prefsRepo = prefsRepo;
        this.transactionRepository = transactionRepository;
        this.distributedLock = distributedLock;
    }

    /**
     * Seed the default-bucket placeholder rows if the table is empty. Deferred to
     * {@code @PostConstruct} so a DynamoDB outage at boot doesn't slow or fail app start — the
     * endpoint's in-memory fallback still returns the seed.
     */
    @jakarta.annotation.PostConstruct
    public void seedOnStartup() {
        try {
            seedDefaultBucketIfEmpty();
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Benchmark seed on startup skipped: {}", e.getMessage());
            }
        }
    }

    /** Runs daily at 02:00 UTC. */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void aggregateDaily() {
        // Distributed lock — when ECS autoscales to 2+ tasks, only one should
        // run the aggregation. 120-min TTL is comfortably longer than the job
        // itself so a crash doesn't prevent the next day's run.
        final String lockKey = "benchmarkAggregation:" + LocalDate.now(java.time.ZoneOffset.UTC);
        distributedLock.runOnce(lockKey, 120, this::aggregateDailyInner);
    }

    private void aggregateDailyInner() {
        LOGGER.info("Benchmark aggregation starting.");
        final List<UserPreferencesTable> optIns = prefsRepo.findAnonymisedStatsOptIns();
        if (optIns.isEmpty()) {
            LOGGER.info("No anonymised-stats opt-ins. Keeping seed placeholders.");
            return;
        }

        // bucketId -> category -> list of per-user monthly spend
        final Map<String, Map<String, List<Double>>> byBucketCategory = new HashMap<>();
        final Map<String, String> bucketLabels = new HashMap<>();
        final Map<String, Integer> bucketContributors = new HashMap<>();

        final LocalDate today = LocalDate.now();
        final LocalDate start = today.minusDays(90);

        for (final UserPreferencesTable pref : optIns) {
            final String bucketId =
                    bucketIdFromInputs(pref.getAnnualIncomeTier(), pref.getHouseholdSize());
            bucketLabels.putIfAbsent(
                    bucketId,
                    bucketLabelFromInputs(pref.getAnnualIncomeTier(), pref.getHouseholdSize()));

            final Map<String, Double> monthly =
                    monthlySpendByCategory(pref.getUserId(), start, today);
            if (monthly.isEmpty()) {
                continue;
            }

            bucketContributors.merge(bucketId, 1, Integer::sum);
            final Map<String, List<Double>> byCategory =
                    byBucketCategory.computeIfAbsent(bucketId, k -> new HashMap<>());
            for (final Map.Entry<String, Double> e : monthly.entrySet()) {
                byCategory.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }

        int emitted = 0;
        final Instant now = Instant.now();
        for (Map.Entry<String, Map<String, List<Double>>> bucketEntry :
                byBucketCategory.entrySet()) {
            final String bucketId = bucketEntry.getKey();
            final int contributors = bucketContributors.getOrDefault(bucketId, 0);
            if (contributors < MIN_SAMPLES) {
                LOGGER.debug(
                        "Bucket {} below anonymity floor ({} < {}) — suppressed.",
                        bucketId,
                        contributors,
                        MIN_SAMPLES);
                continue;
            }
            final String bucketLabel = bucketLabels.getOrDefault(bucketId, bucketId);

            for (final Map.Entry<String, List<Double>> categoryEntry :
                    bucketEntry.getValue().entrySet()) {
                final List<Double> values = categoryEntry.getValue();
                if (values.size() < MIN_CATEGORY_CONTRIBUTORS) {
                    continue;
                }

                Collections.sort(values);
                final BenchmarkTable row = new BenchmarkTable();
                row.setBucketId(bucketId);
                row.setCategory(categoryEntry.getKey());
                row.setMedianMonthly(BigDecimal.valueOf(percentile(values, 0.50)));
                row.setP25(BigDecimal.valueOf(percentile(values, 0.25)));
                row.setP75(BigDecimal.valueOf(percentile(values, 0.75)));
                row.setBucketLabel(bucketLabel);
                row.setSampleSize(values.size());
                row.setLastComputedAt(now);
                try {
                    repository.save(row);
                    emitted++;
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Persist failed for ({}, {}): {}",
                                bucketId,
                                categoryEntry.getKey(),
                                e.getMessage());
                    }
                }
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Benchmark aggregation complete — {} opt-ins, {} rows emitted.",
                    optIns.size(),
                    emitted);
        }
    }

    public List<BenchmarkRow> benchmarksFor(final String incomeTier, final Integer householdSize) {
        final String bucketId = bucketIdFromInputs(incomeTier, householdSize);
        final List<BenchmarkTable> stored = repository.findByBucket(bucketId);
        if (!stored.isEmpty()) {
            final List<BenchmarkRow> out = new ArrayList<>(stored.size());
            for (final BenchmarkTable t : stored) {
                out.add(
                        new BenchmarkRow(
                                t.getCategory(),
                                t.getMedianMonthly(),
                                t.getP25(),
                                t.getP75(),
                                t.getBucketLabel() != null
                                        ? t.getBucketLabel()
                                        : DEFAULT_BUCKET_LABEL));
            }
            return out;
        }
        // Fall back to default bucket data for unpopulated buckets.
        if (!DEFAULT_BUCKET_ID.equals(bucketId)) {
            return benchmarksFor(null, null);
        }
        return seedDefaultBucket();
    }

    // MARK - Bucketing

    private String bucketIdFromInputs(final String tier, final Integer householdSize) {
        final String tierPart = tier == null || tier.isBlank() ? "inc-75-150k" : tier;
        return tierPart + "-hh-" + sizeBucket(householdSize);
    }

    private String bucketLabelFromInputs(final String tier, final Integer householdSize) {
        final String incomeLabel;
        switch (tier == null ? "inc-75-150k" : tier) {
            case "inc-0-50k":
                incomeLabel = "Income under $50K";
                break;
            case "inc-50-75k":
                incomeLabel = "Income $50–75K";
                break;
            case "inc-150k-plus":
                incomeLabel = "Income $150K+";
                break;
            case "inc-75-150k":
            default:
                incomeLabel = "Income $75–150K";
                break;
        }
        final String sizeLabel;
        switch (sizeBucket(householdSize)) {
            case "3-4":
                sizeLabel = "3–4 people";
                break;
            case "5-plus":
                sizeLabel = "5+ people";
                break;
            case "1-2":
            default:
                sizeLabel = "1–2 people";
                break;
        }
        return incomeLabel + " · " + sizeLabel;
    }

    private String sizeBucket(final Integer size) {
        if (size == null) {
            return "1-2";
        }
        if (size >= 5) {
            return "5-plus";
        }
        if (size >= 3) {
            return "3-4";
        }
        return "1-2";
    }

    // MARK - Per-user monthly spend

    private Map<String, Double> monthlySpendByCategory(
            final String userId, final LocalDate start, final LocalDate end) {
        final Map<String, Double> sum = new HashMap<>();
        try {
            final List<TransactionTable> rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, start.toString(), end.toString());
            for (final TransactionTable t : rows) {
                if (t == null || t.getAmount() == null) {
                    continue;
                }
                if (t.getDeletedAt() != null) {
                    continue;
                }
                if (t.getAmount().signum() >= 0) {
                    continue;
                }
                final String cat = t.getCategoryPrimary();
                if (cat == null || cat.isBlank()) {
                    continue;
                }
                sum.merge(
                        cat.toLowerCase(Locale.ROOT),
                        t.getAmount().abs().doubleValue(),
                        Double::sum);
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Skipping user {} — transaction fetch failed: {}", userId, e.getMessage());
            }
            return Map.of();
        }
        final Map<String, Double> monthly = new HashMap<>(sum.size());
        for (final Map.Entry<String, Double> e : sum.entrySet()) {
            monthly.put(e.getKey(), e.getValue() / 3.0);
        }
        return monthly;
    }

    // MARK - Percentile + seed

    /**
     * R-7 linear-interpolation percentile — the "default" method in NumPy / Excel / R. Interpolates
     * between the two nearest ranks rather than rounding to one, which is materially more accurate
     * at the p25 / p75 band boundaries that drive the "below / typical / above" classification on
     * the iOS benchmarks view. Reference: Hyndman & Fan (1996), method #7.
     */
    private double percentile(final List<Double> sorted, final double p) {
        final int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return sorted.getFirst();
        }
        final double rank = p * (n - 1);
        final int lower = (int) Math.floor(rank);
        final int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        final double weight = rank - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private void seedDefaultBucketIfEmpty() {
        try {
            if (!repository.findByBucket(DEFAULT_BUCKET_ID).isEmpty()) {
                return;
            }
            final Instant now = Instant.now();
            for (final BenchmarkRow row : seedDefaultBucket()) {
                final BenchmarkTable t = new BenchmarkTable();
                t.setBucketId(DEFAULT_BUCKET_ID);
                t.setCategory(row.category);
                t.setMedianMonthly(row.medianMonthly);
                t.setP25(row.p25);
                t.setP75(row.p75);
                t.setBucketLabel(row.bucketLabel);
                t.setSampleSize(0);
                t.setLastComputedAt(now);
                repository.save(t);
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Seed skipped: {}", e.getMessage());
            }
        }
    }

    private List<BenchmarkRow> seedDefaultBucket() {
        final String bucket = DEFAULT_BUCKET_LABEL;
        final List<BenchmarkRow> rows = new ArrayList<>();
        rows.add(new BenchmarkRow("groceries", bd(475), bd(320), bd(640), bucket));
        rows.add(new BenchmarkRow("dining", bd(280), bd(160), bd(420), bucket));
        rows.add(new BenchmarkRow("transportation", bd(210), bd(110), bd(340), bucket));
        rows.add(new BenchmarkRow("utilities", bd(260), bd(180), bd(370), bucket));
        rows.add(new BenchmarkRow("entertainment", bd(125), bd(60), bd(220), bucket));
        rows.add(new BenchmarkRow("shopping", bd(260), bd(130), bd(440), bucket));
        rows.add(new BenchmarkRow("subscriptions", bd(95), bd(45), bd(175), bucket));
        rows.add(new BenchmarkRow("health", bd(95), bd(30), bd(180), bucket));
        return rows;
    }

    private BigDecimal bd(final double v) {
        return BigDecimal.valueOf(v);
    }
}
