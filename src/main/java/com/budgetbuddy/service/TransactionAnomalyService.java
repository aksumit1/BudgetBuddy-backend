package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Transaction Anomaly Detection Service
 *
 * <p>Identifies suspicious, abnormal, or unusually high expenses that require user attention. Uses
 * statistical analysis, pattern recognition, and financial best practices.
 *
 * <p>Flow 7 / O1 + O12 — two feedback loops now feed this detector: - Per-user <em>sensitivity</em>
 * (loose / normal / strict) scales every threshold so users can dial in their own noise floor
 * instead of fighting hardcoded numbers. - Per-user <em>suppression</em> (dismissed-fingerprint
 * set) hides patterns the user has already marked "that's normal for me". Still computed, just
 * filtered out of the response.
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class TransactionAnomalyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionAnomalyService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final com.budgetbuddy.config.InsightsThresholds thresholds;

    // All values multiplied by per-request sensitivity at runtime.
    private double zScoreThreshold() { return thresholds.getAnomaly().getZScoreThreshold(); }
    private double categorySpikeMultiplier() {
        return thresholds.getAnomaly().getCategorySpikeMultiplier();
    }
    private double amountThresholdMultiplier() {
        return thresholds.getAnomaly().getAmountThresholdMultiplier();
    }
    private int minTransactionsForAnalysis() {
        return thresholds.getAnomaly().getMinTransactionsForAnalysis();
    }
    private int analysisWindowDays() {
        return thresholds.getAnomaly().getAnalysisWindowDays();
    }
    private int historicalWindowDays() {
        return thresholds.getAnomaly().getHistoricalWindowDays();
    }

    // Feedback + user services injected as Optional to keep tests that build the
    // detector with only the transaction repo (the majority) working. {@link
    // #warnIfDependenciesMissing} surfaces a clear WARN on startup if these are
    // null in a real Spring context so missing prod wiring doesn't degrade
    // silently to "every user gets NORMAL sensitivity and no dismiss-suppression".
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnomalyFeedbackService feedbackService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserService userService;

    /**
     * AI-3: optional LLM-driven personaliser for the static anomaly reason
     * strings. Null in unit-test contexts AND when the Anthropic advisor
     * is feature-flagged off — the detector still produces the
     * deterministic reason in both cases.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.service.insights.AnomalyMessageAdvisor messageAdvisor;

    @jakarta.annotation.PostConstruct
    void warnIfDependenciesMissing() {
        if (feedbackService == null) {
            LOGGER.warn(
                    "TransactionAnomalyService: AnomalyFeedbackService not wired — "
                            + "dismissed anomalies will reappear on every detection pass. "
                            + "Expected only in unit-test contexts.");
        }
        if (userService == null) {
            LOGGER.warn(
                    "TransactionAnomalyService: UserService not wired — every user will "
                            + "be treated as NORMAL sensitivity regardless of their stored "
                            + "preference. Expected only in unit-test contexts.");
        }
    }

    /**
     * Per-detection-pass sensitivity multiplier. ThreadLocal keeps concurrent web requests from
     * stomping each other since Spring MVC handlers run on different threads.
     *
     * <p>DRIFT-1 hardening: every public entrypoint calls {@link #beginPass(double)} which clears
     * the slot before setting it, and pairs with {@link #endPass()} in a finally block. The "clear
     * first" step defends against a prior request that crashed after {@code set()} but before
     * {@code remove()} — without it, the next request on the same Spring MVC worker thread would
     * inherit the leftover multiplier. The {@code withInitial(() -> 1.0)} default still applies
     * for any code path that hits {@link #currentSensitivity()} outside a pass.
     */
    private final ThreadLocal<Double> activeSensitivity = ThreadLocal.withInitial(() -> 1.0);

    /** Read the current sensitivity multiplier with a defensive default. */
    private double currentSensitivity() {
        final Double v = activeSensitivity.get();
        return v == null ? 1.0 : v;
    }

    private void beginPass(final double multiplier) {
        // Defensive remove() so a leaked value from a previous (crashed) pass
        // on this thread can't bleed into the new one.
        activeSensitivity.remove();
        activeSensitivity.set(multiplier);
    }

    private void endPass() {
        activeSensitivity.remove();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionAnomalyService(
            final TransactionRepository transactionRepository,
            final com.budgetbuddy.config.InsightsThresholds thresholds) {
        this.transactionRepository = transactionRepository;
        // Defensive default lets Mockito @InjectMocks call this with a
        // null thresholds (the common pattern in pre-existing tests)
        // without each test having to add @Mock InsightsThresholds.
        this.thresholds = thresholds != null
                ? thresholds
                : new com.budgetbuddy.config.InsightsThresholds();
    }

    /**
     * Backwards-compat constructor for tests that don't wire the
     * thresholds bean. Uses defaults that match the previously
     * hardcoded constants exactly.
     */
    public TransactionAnomalyService(final TransactionRepository transactionRepository) {
        this(transactionRepository, new com.budgetbuddy.config.InsightsThresholds());
    }

    /** Sensitivity knob used to scale the three headline thresholds. */
    private enum Sensitivity {
        LOOSE(1.5), // higher thresholds → fewer anomalies
        NORMAL(1.0),
        STRICT(0.7); // lower thresholds → more anomalies
        /* default */ final double multiplier;

        Sensitivity(final double m) {
            this.multiplier = m;
        }

        /* default */ static Sensitivity parse(final String raw) {
            if (raw == null) {
                return NORMAL;
            }
            try {
                return Sensitivity.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    /** Resolve the active sensitivity for {@code userId}. "normal" when nothing is set. */
    private Sensitivity sensitivityFor(final String userId) {
        if (userService == null) {
            return Sensitivity.NORMAL;
        }
        return userService
                .findById(userId)
                .map(u -> Sensitivity.parse(u.getAnomalySensitivity()))
                .orElse(Sensitivity.NORMAL);
    }

    /**
     * Detect anomalies for a user
     *
     * @param userId User ID
     * @return List of detected anomalies
     */
    /**
     * Context-aware overload for the /summary path. Eliminates the
     * service's two transaction-repo fetches by reading both windows
     * from the pre-fetched context.
     */
    public List<TransactionAnomaly> detectAnomalies(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        if (ctx == null) {
            return java.util.Collections.emptyList();
        }
        final Sensitivity sens = sensitivityFor(ctx.userId());
        beginPass(sens.multiplier);
        try {
            return detectAnomaliesFromContext(ctx);
        } finally {
            endPass();
        }
    }

    private List<TransactionAnomaly> detectAnomaliesFromContext(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        final LocalDate today = ctx.asOf();
        final LocalDate analysisStart = today.minusDays(analysisWindowDays());
        final LocalDate historicalStart = today.minusDays(historicalWindowDays());

        // Two windowed views over the same shared snapshot — no repo hits.
        final List<TransactionTable> recent = ctx.transactions().stream()
                .filter(tx -> tx.getTransactionDate() != null
                        && tx.getTransactionDate().compareTo(analysisStart.toString()) >= 0
                        && tx.getTransactionDate().compareTo(today.toString()) <= 0)
                .toList();
        final List<TransactionTable> historical = ctx.transactions().stream()
                .filter(tx -> tx.getTransactionDate() != null
                        && tx.getTransactionDate().compareTo(historicalStart.toString()) >= 0
                        && tx.getTransactionDate().compareTo(analysisStart.toString()) < 0)
                .toList();

        return runDetection(ctx.userId(), recent, historical);
    }

    public List<TransactionAnomaly> detectAnomalies(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        // O12 + cross-flow audit fix: apply the user's chosen sensitivity for this
        // pass, then ALWAYS clear the ThreadLocal in `finally` below. Without the
        // clear, a thread reused by Spring MVC for a different user's request would
        // inherit the previous user's sensitivity — a state leak.
        final Sensitivity sens = sensitivityFor(userId);
        beginPass(sens.multiplier);
        LOGGER.info("Detecting transaction anomalies for user: {} (sensitivity={})", userId, sens);

        try {
            return detectAnomaliesInternal(userId);
        } finally {
            endPass();
        }
    }

    /** Kept private so callers can't bypass the ThreadLocal cleanup in the public method. */
    private List<TransactionAnomaly> detectAnomaliesInternal(final String userId) {
        // Get transactions for analysis
        final LocalDate endDate = LocalDate.now();
        final LocalDate analysisStartDate = endDate.minusDays(analysisWindowDays());
        final LocalDate historicalStartDate = endDate.minusDays(historicalWindowDays());

        final String analysisStartStr = analysisStartDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);
        final String historicalStartStr = historicalStartDate.format(DATE_FORMATTER);

        final List<TransactionTable> recentTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        userId, analysisStartStr, endDateStr);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        userId, historicalStartStr, analysisStartStr);

        return runDetection(userId, recentTransactions, historicalTransactions);
    }

    /**
     * Run the full detection pipeline on already-fetched transaction
     * windows. Used by both the legacy {@code detectAnomalies(userId)}
     * path (which fetches its own data) and the {@code detectAnomalies
     * (ctx)} path (which slices windows from the shared snapshot). All
     * the original filtering/scoring/suppression logic moved here
     * verbatim so behaviour is identical.
     */
    private List<TransactionAnomaly> runDetection(
            final String userId,
            final List<TransactionTable> recentTransactions,
            final List<TransactionTable> historicalTransactions) {

        if (recentTransactions.size() < minTransactionsForAnalysis()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Insufficient transactions for anomaly detection: {}",
                        recentTransactions.size());
            }
            return Collections.emptyList();
        }

        // Filter to expense transactions only (negative amounts). Cross-flow audit fix:
        // also drop soft-deleted rows (Flow 4 / O9) — previously they could still
        // trigger anomaly alerts after the user had explicitly deleted them.
        final List<TransactionTable> recentExpenses =
                recentTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getDeletedAt() == null)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .toList();

        final List<TransactionTable> historicalExpenses =
                historicalTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getDeletedAt() == null)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .toList();

        if (recentExpenses.isEmpty()) {
            return Collections.emptyList();
        }

        List<TransactionAnomaly> anomalies = new ArrayList<>();

        // 1. Statistical Outliers (Z-score analysis)
        anomalies.addAll(detectStatisticalOutliers(recentExpenses, historicalExpenses));

        // 2. Category Anomalies
        anomalies.addAll(detectCategoryAnomalies(recentExpenses, historicalExpenses));

        // 3. Merchant Anomalies
        anomalies.addAll(detectMerchantAnomalies(recentExpenses, historicalExpenses));

        // 4. Duplicate Detection
        anomalies.addAll(detectDuplicates(recentExpenses));

        // 5. Amount Threshold Anomalies
        anomalies.addAll(detectAmountThresholdAnomalies(recentExpenses, historicalExpenses));

        // O1: suppress anomalies whose fingerprint the user already dismissed.
        if (feedbackService != null) {
            final Set<String> suppressed = feedbackService.dismissedFingerprintsFor(userId);
            if (!suppressed.isEmpty()) {
                final int before = anomalies.size();
                anomalies =
                        anomalies.stream()
                                .filter(
                                        a ->
                                                !suppressed.contains(
                                                        AnomalyFeedbackService.fingerprintOf(
                                                                a.getMerchantName(),
                                                                a.getCategory(),
                                                                a.getAmount())))
                                .toList();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Suppressed {}/{} anomalies via user dismiss list",
                            before - anomalies.size(),
                            before);
                }
            }
        }

        // Location-based anomalies (e.g. transaction from a country the user hasn't visited)
        // require geo data we don't yet collect: Plaid's transaction location fields are sparse
        // and the iOS client doesn't ship device-location telemetry to the backend. Until that
        // pipeline lands, this detector relies on the amount/merchant/category signals above.

        // Remove duplicates and sort by severity
        final List<TransactionAnomaly> deduped = anomalies.stream()
                .collect(
                        Collectors.toMap(
                                TransactionAnomaly::getTransactionId,
                                anomaly -> anomaly,
                                (a1, a2) ->
                                        a1.getSeverity().ordinal() > a2.getSeverity().ordinal()
                                                ? a1
                                                : a2))
                .values()
                .stream()
                .sorted(
                        Comparator.comparing((TransactionAnomaly a) -> a.getSeverity().ordinal())
                                .reversed()
                                .thenComparing(
                                        (TransactionAnomaly a) -> a.getAmount().abs(),
                                        Comparator.reverseOrder()))
                .toList();
        // AI-3: optionally let the LLM advisor personalise reason strings.
        // Wrapped in try/catch — any failure leaves the deterministic reason
        // unchanged so the user always sees *something*.
        if (messageAdvisor != null && !deduped.isEmpty()) {
            try {
                final List<com.budgetbuddy.service.insights.AnomalyMessageAdvisor.AnomalyContext>
                        ctxList = new ArrayList<>(deduped.size());
                for (final TransactionAnomaly a : deduped) {
                    final com.budgetbuddy.service.insights.AnomalyMessageAdvisor.AnomalyContext c =
                            new com.budgetbuddy.service.insights.AnomalyMessageAdvisor.AnomalyContext();
                    c.anomalyId = a.getTransactionId();
                    c.type = a.getType() == null ? null : a.getType().name();
                    c.severity = a.getSeverity() == null ? null : a.getSeverity().name();
                    c.category = a.getCategory();
                    c.merchantName = a.getMerchantName();
                    c.amount = a.getAmount();
                    c.deterministicReason = a.getReason();
                    if (a.getTransactionDate() != null) {
                        try {
                            c.transactionDate =
                                    java.time.LocalDate.parse(a.getTransactionDate());
                        } catch (java.time.format.DateTimeParseException e) {
                            // leave null
                        }
                    }
                    ctxList.add(c);
                }
                final List<com.budgetbuddy.service.insights.AnomalyMessageAdvisor.AnomalyContext>
                        annotated = messageAdvisor.annotate(ctxList);
                if (annotated != null) {
                    final java.util.Map<String, String> byId = new java.util.HashMap<>();
                    for (final com.budgetbuddy.service.insights.AnomalyMessageAdvisor.AnomalyContext
                            c : annotated) {
                        if (c.humanMessage != null) byId.put(c.anomalyId, c.humanMessage);
                    }
                    for (final TransactionAnomaly a : deduped) {
                        final String m = byId.get(a.getTransactionId());
                        if (m != null) a.setHumanMessage(m);
                    }
                }
            } catch (Exception ignored) {
                // Never let the personaliser break the response.
            }
        }
        return deduped;
    }

    /** Detect statistical outliers using Z-score */
    private List<TransactionAnomaly> detectStatisticalOutliers(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.size() < minTransactionsForAnalysis()) {
            return anomalies;
        }

        // Calculate mean and standard deviation from historical data
        final List<BigDecimal> amounts =
                historicalExpenses.stream()
                        .map(tx -> tx.getAmount().abs())
                        .toList();

        final BigDecimal mean = calculateMean(amounts);
        final BigDecimal stdDev = calculateStandardDeviation(amounts, mean);

        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return anomalies; // No variation, can't detect outliers
        }

        // Check recent transactions for outliers
        for (final TransactionTable tx : recentExpenses) {
            final BigDecimal amount = tx.getAmount().abs();
            final BigDecimal zScore = amount.subtract(mean).divide(stdDev, 4, RoundingMode.HALF_UP);

            // O12: scale the z-score threshold by the user's sensitivity multiplier.
            final double scaledZ = zScoreThreshold() * currentSensitivity();
            if (zScore.abs().compareTo(BigDecimal.valueOf(scaledZ)) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(500)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.STATISTICAL_OUTLIER,
                                severity,
                                String.format(
                                        "Transaction amount (%.2f) is %.2f standard deviations from your average (%.2f)",
                                        amount.doubleValue(),
                                        zScore.doubleValue(),
                                        mean.doubleValue())));
            }
        }

        return anomalies;
    }

    /** Detect category spending spikes */
    private List<TransactionAnomaly> detectCategoryAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate average spending per category in historical period
        final Map<String, List<BigDecimal>> historicalByCategory =
                historicalExpenses.stream()
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(
                                Collectors.groupingBy(
                                        TransactionTable::getCategoryPrimary,
                                        Collectors.mapping(
                                                tx -> tx.getAmount().abs(), Collectors.toList())));

        final Map<String, BigDecimal> categoryAverages =
                historicalByCategory.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> calculateMean(entry.getValue())));

        // Group recent expenses by category
        final Map<String, List<TransactionTable>> recentByCategory =
                recentExpenses.stream()
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Check for category spikes
        for (final Map.Entry<String, List<TransactionTable>> entry : recentByCategory.entrySet()) {
            final String category = entry.getKey();
            final List<TransactionTable> categoryTransactions = entry.getValue();

            final BigDecimal historicalAverage = categoryAverages.get(category);
            if (historicalAverage == null || historicalAverage.compareTo(BigDecimal.ZERO) == 0) {
                continue; // No historical data for this category
            }

            // Calculate total spending in this category recently
            final BigDecimal recentTotal =
                    categoryTransactions.stream()
                            .map(tx -> tx.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            final BigDecimal recentAverage =
                    recentTotal.divide(
                            BigDecimal.valueOf(categoryTransactions.size()),
                            2,
                            RoundingMode.HALF_UP);

            // Check if recent average is significantly higher
            final double scaledSpike = categorySpikeMultiplier() * currentSensitivity();
            if (recentAverage.compareTo(historicalAverage.multiply(BigDecimal.valueOf(scaledSpike)))
                    > 0) {
                // Find the largest transaction in this category
                final TransactionTable largestTx =
                        categoryTransactions.stream()
                                .max(Comparator.comparing(tx -> tx.getAmount().abs()))
                                .orElse(null);

                if (largestTx != null) {
                    final Severity severity =
                            recentTotal.compareTo(BigDecimal.valueOf(500)) > 0
                                    ? Severity.HIGH
                                    : Severity.MEDIUM;

                    anomalies.add(
                            new TransactionAnomaly(
                                    largestTx.getTransactionId(),
                                    largestTx.getAmount(),
                                    largestTx.getDescription(),
                                    largestTx.getMerchantName(),
                                    largestTx.getTransactionDate(),
                                    category,
                                    AnomalyType.CATEGORY_SPIKE,
                                    severity,
                                    String.format(
                                            "Spending in %s category is %.1fx higher than your historical average",
                                            category,
                                            recentAverage
                                                    .divide(
                                                            historicalAverage,
                                                            2,
                                                            RoundingMode.HALF_UP)
                                                    .doubleValue())));
                }
            }
        }

        return anomalies;
    }

    /**
     * Categories where "first-time merchant" is a meaningless signal. Rent,
     * mortgage, utilities, loan payments, insurance — by definition these
     * are recurring and any single occurrence is expected. Flagging them
     * generates 12-14 false anomalies per persona (every monthly rent
     * payment with a slightly different amount).
     */
    private static final Set<String> RECURRING_CATEGORIES = Set.of(
            "rent",
            "rent_and_utilities",
            "mortgage",
            "loan",
            "loan_payment",
            "utilities",
            "insurance",
            "tax",
            // Interest charges are surfaced by HighInterestDetectionService
            // and aren't really "anomalies" — they're expected on any
            // carried balance. Showing them in both surfaces double-counts
            // and clutters the anomaly list.
            "interest_charged",
            "interest");

    /** Detect first-time or rare merchant transactions with high amounts */
    private List<TransactionAnomaly> detectMerchantAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Build set of known merchants from historical data. We mutate this set
        // as we iterate recent transactions in chronological order — so the
        // SECOND occurrence of a merchant in recent is no longer "first-time".
        // Without this, a user with 12 monthly rent transactions in the recent
        // window gets 12 "first-time" alerts because none of them are in the
        // (often-empty for new users) historical window.
        final Set<String> knownMerchants = new java.util.HashSet<>(
                historicalExpenses.stream()
                        .map(tx -> normalizeMerchantName(tx.getMerchantName(), tx.getDescription()))
                        .filter(Objects::nonNull)
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toSet()));

        // Same-merchant velocity / size-spike flags (e.g. "Costco $50 → Costco $850" or "5x
        // Starbucks in an hour") are handled by the merchant-spend baseline detector earlier in
        // this class — see {@code detectMerchantSpendingAnomalies}. This method is the unknown-
        // merchant pass; keeping it focused avoids double-firing on the same transaction.

        // Sort by date ascending so we credit "known" status to the earliest
        // occurrence and never re-flag the same merchant twice.
        final List<TransactionTable> sorted = new ArrayList<>(recentExpenses);
        sorted.sort(Comparator.comparing(
                TransactionTable::getTransactionDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (final TransactionTable tx : sorted) {
            final String merchant =
                    normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            final String categoryLower = tx.getCategoryPrimary() == null
                    ? ""
                    : tx.getCategoryPrimary().toLowerCase(Locale.ROOT);
            if (RECURRING_CATEGORIES.contains(categoryLower)) {
                // Still credit the merchant as "seen" so future categories
                // matching this same merchant name don't re-fire either.
                knownMerchants.add(merchant);
                continue;
            }

            final boolean isFirstTime = !knownMerchants.contains(merchant);
            final BigDecimal amount = tx.getAmount().abs();

            if (isFirstTime && amount.compareTo(BigDecimal.valueOf(100)) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(500)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.FIRST_TIME_MERCHANT,
                                severity,
                                String.format(
                                        "First-time transaction with %s for $%.2f",
                                        merchant, amount.doubleValue())));
            }

            // Whether we flagged or not, the merchant is now known for
            // subsequent transactions in this pass.
            knownMerchants.add(merchant);
        }

        return anomalies;
    }

    /**
     * Minimum amount for the duplicate-transaction detector to fire. Two
     * $9 healthcare copays or $19 dining items on the same day are not a
     * "duplicate charge" worth alerting the user about — they're just
     * normal life. Real duplicate-charge fraud or accidental double-tap
     * concerns kick in at noticeable amounts. Threshold of $50 cuts the
     * persona-validation noise (saver: 4 of 7 anomalies were $9-$46
     * "similar" rows) without losing legitimate signals.
     */
    private static final BigDecimal DUPLICATE_MIN_AMOUNT = BigDecimal.valueOf(50);

    /** Detect duplicate or similar transactions */
    private List<TransactionAnomaly> detectDuplicates(final List<TransactionTable> recentExpenses) {
        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Group by merchant and amount (within $5 tolerance)
        final Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (final TransactionTable tx : recentExpenses) {
            final String merchant =
                    normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            final BigDecimal amount = tx.getAmount().abs();
            if (amount.compareTo(DUPLICATE_MIN_AMOUNT) < 0) {
                continue;
            }
            // Round to nearest $5 for grouping
            final BigDecimal roundedAmount =
                    amount.divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(5));

            final String key = merchant.toLowerCase(Locale.ROOT) + "|" + roundedAmount;

            byMerchantAndAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        // Find groups with multiple transactions within 24 hours
        for (final List<TransactionTable> group : byMerchantAndAmount.values()) {
            if (group.size() < 2) {
                continue;
            }

            // Sort by date
            group.sort(Comparator.comparing(TransactionTable::getTransactionDate));

            // Check for transactions within 24 hours
            for (int i = 0; i < group.size() - 1; i++) {
                final TransactionTable tx1 = group.get(i);
                final TransactionTable tx2 = group.get(i + 1);

                final LocalDate date1 = LocalDate.parse(tx1.getTransactionDate(), DATE_FORMATTER);
                final LocalDate date2 = LocalDate.parse(tx2.getTransactionDate(), DATE_FORMATTER);

                final long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(date1, date2);

                if (daysBetween <= 1) {
                    final BigDecimal amount = tx1.getAmount().abs();
                    final Severity severity =
                            amount.compareTo(BigDecimal.valueOf(200)) > 0
                                    ? Severity.HIGH
                                    : Severity.MEDIUM;

                    anomalies.add(
                            new TransactionAnomaly(
                                    tx1.getTransactionId(),
                                    tx1.getAmount(),
                                    tx1.getDescription(),
                                    tx1.getMerchantName(),
                                    tx1.getTransactionDate(),
                                    tx1.getCategoryPrimary(),
                                    AnomalyType.DUPLICATE_TRANSACTION,
                                    severity,
                                    String.format(
                                            "Similar transaction detected: $%.2f at %s within 24 hours",
                                            amount.doubleValue(),
                                            normalizeMerchantName(
                                                    tx1.getMerchantName(), tx1.getDescription()))));
                }
            }
        }

        return anomalies;
    }

    /** Detect transactions exceeding typical amount thresholds */
    private List<TransactionAnomaly> detectAmountThresholdAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate typical transaction amount (median)
        final List<BigDecimal> amounts =
                historicalExpenses.stream()
                        .map(tx -> tx.getAmount().abs())
                        .sorted()
                        .toList();

        final BigDecimal median = calculateMedian(amounts);
        final double scaledAmount = amountThresholdMultiplier() * currentSensitivity();
        final BigDecimal threshold = median.multiply(BigDecimal.valueOf(scaledAmount));

        // Check recent transactions
        for (final TransactionTable tx : recentExpenses) {
            final BigDecimal amount = tx.getAmount().abs();
            if (amount.compareTo(threshold) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(1000)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.AMOUNT_THRESHOLD,
                                severity,
                                String.format(
                                        "Transaction amount (%.2f) is %.1fx your typical transaction size (%.2f)",
                                        amount.doubleValue(),
                                        amount.divide(median, 2, RoundingMode.HALF_UP)
                                                .doubleValue(),
                                        median.doubleValue())));
            }
        }

        return anomalies;
    }

    // Helper methods

    private BigDecimal calculateMean(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStandardDeviation(
            final List<BigDecimal> values, final BigDecimal mean) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        final BigDecimal variance =
                values.stream()
                        .map(value -> value.subtract(mean).pow(2))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
        // Use BigDecimal.sqrt (Java 9+) to avoid the double round-trip; the
        // previous Math.sqrt(variance.doubleValue()) path introduced two
        // float-precision hops AND the `new BigDecimal(double)` tail —
        // enough to shift a z-score across the anomaly threshold.
        return variance.sqrt(new java.math.MathContext(16, RoundingMode.HALF_UP))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMedian(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final int size = values.size();
        if (size % 2 == 0) {
            return values.get(size / 2 - 1)
                    .add(values.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        } else {
            return values.get(size / 2);
        }
    }

    private String normalizeMerchantName(final String merchantName, final String description) {
        if (merchantName != null && !merchantName.isBlank()) {
            return merchantName.trim();
        }
        if (description != null && !description.isBlank()) {
            // Extract merchant from description (first part before common separators)
            final String desc = description.trim();
            final String[] separators = {" - ", " | ", " @ ", " # "};
            for (final String sep : separators) {
                if (desc.contains(sep)) {
                    return desc.substring(0, desc.indexOf(sep)).trim();
                }
            }
            return desc;
        }
        return null;
    }

    // Model classes

    public static class TransactionAnomaly {
        private final String transactionId;
        private final BigDecimal amount;
        private final String description;
        private final String merchantName;
        private final String transactionDate;
        private final String category;
        private final AnomalyType type;
        private final Severity severity;
        private final String reason;
        /**
         * AI-3: optional LLM-personalised message. Mutable so the
         * advisor can populate it after construction; null when the
         * advisor is disabled — clients fall back to `reason`.
         */
        private String humanMessage;

        public TransactionAnomaly(
                final String transactionId,
                final BigDecimal amount,
                final String description,
                final String merchantName,
                final String transactionDate,
                final String category,
                final AnomalyType type,
                final Severity severity,
                final String reason) {
            this.transactionId = transactionId;
            this.amount = amount;
            this.description = description;
            this.merchantName = merchantName;
            this.transactionDate = transactionDate;
            this.category = category;
            this.type = type;
            this.severity = severity;
            this.reason = reason;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getDescription() {
            return description;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public String getTransactionDate() {
            return transactionDate;
        }

        public String getCategory() {
            return category;
        }

        public AnomalyType getType() {
            return type;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getReason() {
            return reason;
        }

        public String getHumanMessage() {
            return humanMessage;
        }

        public void setHumanMessage(final String humanMessage) {
            this.humanMessage = humanMessage;
        }
    }

    public enum AnomalyType {
        STATISTICAL_OUTLIER,
        CATEGORY_SPIKE,
        FIRST_TIME_MERCHANT,
        DUPLICATE_TRANSACTION,
        AMOUNT_THRESHOLD
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
