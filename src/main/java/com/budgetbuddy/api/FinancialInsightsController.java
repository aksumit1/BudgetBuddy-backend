package com.budgetbuddy.api;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ExpenseReductionService;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.FinancialGoalsRecommendationService;
import com.budgetbuddy.service.FinancialGoalsRecommendationService.FinancialGoalRecommendation;
import com.budgetbuddy.service.HighInterestDetectionService;
import com.budgetbuddy.service.HighInterestDetectionService.HighInterestAlert;
import com.budgetbuddy.service.MissedPaymentDetectionService;
import com.budgetbuddy.service.MissedPaymentDetectionService.MissedPaymentAlert;
import com.budgetbuddy.service.TransactionAnomalyService;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financial Insights REST Controller
 *
 * <p>Provides advanced financial intelligence including: - Transaction anomaly detection - Expense
 * reduction recommendations - Financial goals recommendations - Missed payment detection - High
 * interest detection
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@RestController
@RequestMapping("/api/insights")
public class FinancialInsightsController {

    private static final String UNKNOWN = "Unknown";

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final String AMOUNT = "amount";

    private static final String CATEGORY = "category";

    private static final String DESCRIPTION = "description";

    private static final String INTEREST = "interest";

    private static final String MONTHLY_SAVINGS = "monthlySavings";

    private static final String PREDICTED_DATE = "predictedDate";

    private static final String REASON = "reason";

    private static final String SEVERITY = "severity";

    private static final String TITLE = "title";

    private final TransactionAnomalyService anomalyService;
    private final ExpenseReductionService expenseReductionService;
    private final FinancialGoalsRecommendationService goalsService;
    private final MissedPaymentDetectionService missedPaymentService;
    private final HighInterestDetectionService highInterestService;
    private final UserService userService;
    private final FinancialInsightsPredictionService predictionService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final SubscriptionRepository subscriptionRepository;
    // Flow 7 / O1 — persistent user feedback on anomalies.
    private final com.budgetbuddy.service.AnomalyFeedbackService anomalyFeedbackService;
    // Flow 7 / O3 — the ML controller needs live goals + subscriptions to finish its
    // predictions; previously the controller had TODOs feeding empty maps.
    private final com.budgetbuddy.repository.dynamodb.GoalRepository goalRepository;

    // JTBD #4 — cross-account anomaly patterns (same merchant on 2 cards, rapid burst, etc.).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.service.CrossAccountAnomalyDetector crossAccountDetector;

    public FinancialInsightsController(
            final TransactionAnomalyService anomalyService,
            final ExpenseReductionService expenseReductionService,
            final FinancialGoalsRecommendationService goalsService,
            final MissedPaymentDetectionService missedPaymentService,
            final HighInterestDetectionService highInterestService,
            final UserService userService,
            final FinancialInsightsPredictionService predictionService,
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final SubscriptionRepository subscriptionRepository,
            final com.budgetbuddy.service.AnomalyFeedbackService anomalyFeedbackService,
            final com.budgetbuddy.repository.dynamodb.GoalRepository goalRepository) {
        this.anomalyService = anomalyService;
        this.expenseReductionService = expenseReductionService;
        this.goalsService = goalsService;
        this.missedPaymentService = missedPaymentService;
        this.highInterestService = highInterestService;
        this.userService = userService;
        this.predictionService = predictionService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.anomalyFeedbackService = anomalyFeedbackService;
        this.goalRepository = goalRepository;
    }

    /**
     * Flow 7 / O3 — derive {merchant → PaymentPattern} from the user's recent transactions and
     * active subscriptions. A pattern counts if the merchant had at least three expense rows with
     * roughly-regular spacing (monthly: 25–35 days). Anything looser gets dropped; the predictor
     * only works on rhythmic series.
     */
    private Map<String, FinancialInsightsPredictionService.PaymentPattern> extractPaymentPatterns(
            final String userId, final List<TransactionTable> rows) {
        final Map<String, FinancialInsightsPredictionService.PaymentPattern> out = new HashMap<>();
        // Bucket by merchant + amount (to the dollar) to collapse minor variation.
        final Map<String, List<TransactionTable>> byKey = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getAmount().signum() >= 0) {
                continue;
            }
            final String merchant =
                    t.getMerchantName() == null ? "" : t.getMerchantName().trim().toLowerCase(Locale.ROOT);
            if (merchant.isEmpty()) {
                continue;
            }
            final String key =
                    merchant
                            + "|"
                            + t.getAmount().abs().setScale(0, java.math.RoundingMode.HALF_UP);
            byKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(t);
        }
        for (final var entry : byKey.entrySet()) {
            final var txs = entry.getValue();
            if (txs.size() < 3) {
                continue;
            }
            // Compute cadence (days between charges). Require mean 25–35 to count as monthly.
            txs.sort(java.util.Comparator.comparing(TransactionTable::getTransactionDate));
            long totalDays = 0;
            int gaps = 0;
            for (int i = 1; i < txs.size(); i++) {
                try {
                    final LocalDate a = LocalDate.parse(txs.get(i - 1).getTransactionDate());
                    final LocalDate b = LocalDate.parse(txs.get(i).getTransactionDate());
                    totalDays += java.time.temporal.ChronoUnit.DAYS.between(a, b);
                    gaps++;
                } catch (DateTimeException ignored) {
                    // skip pairs whose transaction date string can't be parsed
                }
            }
            if (gaps == 0) {
                continue;
            }
            final double avg = (double) totalDays / gaps;
            if (avg < 25 || avg > 35) {
                continue;
            }
            out.put(
                    entry.getKey(),
                    new FinancialInsightsPredictionService.PaymentPattern(
                            txs.get(txs.size() - 1).getAmount().abs(), (int) Math.round(avg)));
        }
        return out;
    }

    /**
     * Flow 7 / O1 — user feedback on an anomaly detection.
     *
     * <p>{@code verdict} is one of "NORMAL" | "CONFIRMED" | "DISMISSED". Dismissals suppress the
     * pattern across future detections; CONFIRMED is an explicit "yes, flag this stuff" that leaves
     * the detector unaffected. NORMAL clears any prior verdict (rare but useful).
     */
    @PostMapping("/anomalies/{anomalyId}/feedback")
    public ResponseEntity<Map<String, Object>> recordAnomalyFeedback(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String anomalyId,
            @RequestBody final AnomalyFeedbackRequest request) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        final var saved =
                anomalyFeedbackService.record(
                        user.getUserId(),
                        anomalyId,
                        request.getMerchantName(),
                        request.getCategory(),
                        request.getAmount(),
                        com.budgetbuddy.service.AnomalyFeedbackService.Verdict.parse(
                                request.getVerdict()));
        return ResponseEntity.ok(
                Map.of(
                        "feedbackId", saved.getFeedbackId(),
                        "fingerprint", saved.getFingerprint(),
                        "verdict", saved.getVerdict()));
    }

    /**
     * JTBD #4 — "Is something weird happening across my accounts?"
     *
     * <p>Returns cross-account anomaly patterns: same merchant hitting two cards on the same day,
     * rapid successive charges, amount near-duplicates across accounts. Complements the
     * per-transaction {@code /anomalies} endpoint (that's for single outliers) — this one surfaces
     * patterns that individual rows don't reveal.
     */
    @GetMapping("/anomalies/cross-account")
    public ResponseEntity<List<com.budgetbuddy.service.CrossAccountAnomalyDetector.Pattern>>
            getCrossAccountAnomalies(@AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        if (crossAccountDetector == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(crossAccountDetector.detect(user.getUserId()));
    }

    /** Flow 7 / O12 — update the current user's anomaly sensitivity knob. */
    @PostMapping("/anomalies/sensitivity")
    public ResponseEntity<Map<String, String>> setAnomalySensitivity(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final Map<String, String> body) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));
        final String raw = body == null ? null : body.get("sensitivity");
        final String normalized = raw == null ? "normal" : raw.toLowerCase(Locale.ROOT);
        if (!"loose".equals(normalized)
                && !"normal".equals(normalized)
                && !"strict".equals(normalized)) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "sensitivity must be loose | normal | strict");
        }
        user.setAnomalySensitivity(normalized);
        user.setUpdatedAt(java.time.Instant.now());
        userService.updateUser(user);
        return ResponseEntity.ok(Map.of("sensitivity", normalized));
    }

    /** Request DTO for the anomaly-feedback endpoint. */
    public static class AnomalyFeedbackRequest {
        private String merchantName;
        private String category;
        private java.math.BigDecimal amount;
        private String verdict; // NORMAL | CONFIRMED | DISMISSED

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(final String merchantName) {
            this.merchantName = merchantName;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(final String category) {
            this.category = category;
        }

        public java.math.BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final java.math.BigDecimal amount) {
            this.amount = amount;
        }

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(final String verdict) {
            this.verdict = verdict;
        }
    }

    /** Get transaction anomalies GET /api/insights/anomalies */
    @GetMapping("/anomalies")
    public ResponseEntity<List<Map<String, Object>>> getAnomalies(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(user.getUserId());

        final List<Map<String, Object>> response =
                anomalies.stream().map(this::toAnomalyMap).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get expense reduction recommendations GET /api/insights/expense-reductions */
    @GetMapping("/expense-reductions")
    public ResponseEntity<List<Map<String, Object>>> getExpenseReductions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final List<ExpenseRecommendation> recommendations =
                expenseReductionService.getRecommendations(user.getUserId());

        final List<Map<String, Object>> response =
                recommendations.stream()
                        .map(this::toExpenseRecommendationMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get financial goal recommendations GET /api/insights/goal-recommendations */
    @GetMapping("/goal-recommendations")
    public ResponseEntity<List<Map<String, Object>>> getGoalRecommendations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final List<FinancialGoalRecommendation> recommendations =
                goalsService.getRecommendations(user.getUserId());

        final List<Map<String, Object>> response =
                recommendations.stream()
                        .map(this::toGoalRecommendationMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get missed payment alerts GET /api/insights/missed-payments */
    @GetMapping("/missed-payments")
    public ResponseEntity<List<Map<String, Object>>> getMissedPayments(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final List<MissedPaymentAlert> alerts =
                missedPaymentService.detectMissedPayments(user.getUserId());

        final List<Map<String, Object>> response =
                alerts.stream().map(this::toMissedPaymentMap).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get high interest alerts GET /api/insights/high-interest */
    @GetMapping("/high-interest")
    public ResponseEntity<List<Map<String, Object>>> getHighInterest(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final List<HighInterestAlert> alerts = highInterestService.detectHighInterest(user.getUserId());

        final List<Map<String, Object>> response =
                alerts.stream().map(this::toHighInterestMap).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get all insights summary GET /api/insights/summary */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getInsightsSummary(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final Map<String, Object> summary = new HashMap<>();

        // Get counts for each insight type
        final List<TransactionAnomaly> anomalies = anomalyService.detectAnomalies(user.getUserId());
        final List<ExpenseRecommendation> expenseReductions =
                expenseReductionService.getRecommendations(user.getUserId());
        final List<FinancialGoalRecommendation> goalRecommendations =
                goalsService.getRecommendations(user.getUserId());
        final List<MissedPaymentAlert> missedPayments =
                missedPaymentService.detectMissedPayments(user.getUserId());
        final List<HighInterestAlert> highInterest =
                highInterestService.detectHighInterest(user.getUserId());

        summary.put("anomaliesCount", anomalies.size());
        summary.put("expenseReductionsCount", expenseReductions.size());
        summary.put("goalRecommendationsCount", goalRecommendations.size());
        summary.put("missedPaymentsCount", missedPayments.size());
        summary.put("highInterestCount", highInterest.size());

        // Calculate total potential savings
        final double totalSavings =
                expenseReductions.stream()
                        .mapToDouble(r -> r.getAnnualSavings().doubleValue())
                        .sum();
        summary.put("totalPotentialSavings", totalSavings);

        // Count high priority items
        final long highPriorityAnomalies =
                anomalies.stream()
                        .filter(a -> a.getSeverity() == TransactionAnomalyService.Severity.HIGH)
                        .count();
        final long highPriorityMissedPayments =
                missedPayments.stream()
                        .filter(a -> a.getSeverity() == MissedPaymentDetectionService.Severity.HIGH)
                        .count();
        final long highPriorityHighInterest =
                highInterest.stream()
                        .filter(a -> a.getSeverity() == HighInterestDetectionService.Severity.HIGH)
                        .count();

        summary.put(
                "highPriorityCount",
                highPriorityAnomalies + highPriorityMissedPayments + highPriorityHighInterest);

        return ResponseEntity.ok(summary);
    }

    /**
     * Get ML-based predictions for transaction anomalies GET
     * /api/insights/predictions/anomalies?daysAhead=30
     */
    @GetMapping("/predictions/anomalies")
    public ResponseEntity<List<Map<String, Object>>> getPredictedAnomalies(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "30") final int daysAhead) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Get historical transactions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(12); // 12 months of history
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        final List<FinancialInsightsPredictionService.PredictedAnomaly> predictions =
                predictionService.predictAnomalies(historicalTransactions, daysAhead);

        final List<Map<String, Object>> response =
                predictions.stream().map(this::toPredictedAnomalyMap).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for expense reductions GET
     * /api/insights/predictions/expense-reductions
     */
    @GetMapping("/predictions/expense-reductions")
    public ResponseEntity<List<Map<String, Object>>> getPredictedExpenseReductions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Get historical transactions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(6);
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Get current subscriptions
        final List<SubscriptionTable> subscriptions =
                subscriptionRepository.findByUserId(user.getUserId());
        final Map<String, BigDecimal> subscriptionMap = new HashMap<>();
        for (final SubscriptionTable sub : subscriptions) {
            if (sub.getActive() != null && sub.getActive() && sub.getAmount() != null) {
                final String name = sub.getMerchantName() != null ? sub.getMerchantName() : UNKNOWN;
                subscriptionMap.put(name, sub.getAmount());
            }
        }

        final List<FinancialInsightsPredictionService.PredictedExpenseReduction> predictions =
                predictionService.predictExpenseReductions(historicalTransactions, subscriptionMap);

        final List<Map<String, Object>> response =
                predictions.stream()
                        .map(this::toPredictedExpenseReductionMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for goal achievements GET
     * /api/insights/predictions/goal-achievements
     */
    @GetMapping("/predictions/goal-achievements")
    public ResponseEntity<List<Map<String, Object>>> getPredictedGoalAchievements(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Get historical transactions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(6);
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Flow 7 / O3 — fulfil the previous TODO. Pull every live goal for the user
        // and feed the predictor. GoalData is a simple value object; we use its
        // constructor to populate name + amounts.
        final Map<String, FinancialInsightsPredictionService.GoalData> goals = new HashMap<>();
        try {
            final List<com.budgetbuddy.model.dynamodb.GoalTable> rows =
                    goalRepository.findByUserId(user.getUserId());
            for (final var g : rows) {
                if (g == null || g.getDeletedAt() != null) {
                    continue;
                }
                if (Boolean.TRUE.equals(g.getCompleted())) {
                    continue;
                }
                goals.put(
                        g.getGoalId(),
                        new FinancialInsightsPredictionService.GoalData(
                                g.getName() == null ? "Goal" : g.getName(),
                                g.getCurrentAmount() == null
                                        ? BigDecimal.ZERO
                                        : g.getCurrentAmount(),
                                g.getTargetAmount() == null
                                        ? BigDecimal.ZERO
                                        : g.getTargetAmount()));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(FinancialInsightsController.class)
                    .warn("Goal fetch for predictions failed: {}", e.getMessage());
        }

        final List<FinancialInsightsPredictionService.PredictedGoalAchievement> predictions =
                predictionService.predictGoalAchievements(goals, historicalTransactions);

        final List<Map<String, Object>> response =
                predictions.stream()
                        .map(this::toPredictedGoalAchievementMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get ML-based predictions for missed payments GET /api/insights/predictions/missed-payments
     */
    @GetMapping("/predictions/missed-payments")
    public ResponseEntity<List<Map<String, Object>>> getPredictedMissedPayments(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Get historical transactions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(6);
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Flow 7 / O3 — derive payment patterns from detected subscriptions + repeated
        // same-merchant outflows. A pattern needs at least 3 charges within ±7 days of
        // a regular cadence to qualify; anything noisier than that would cry wolf.
        final Map<String, FinancialInsightsPredictionService.PaymentPattern> patterns =
                extractPaymentPatterns(user.getUserId(), historicalTransactions);

        final List<FinancialInsightsPredictionService.PredictedMissedPayment> predictions =
                predictionService.predictMissedPayments(historicalTransactions, patterns);

        final List<Map<String, Object>> response =
                predictions.stream()
                        .map(this::toPredictedMissedPaymentMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get ML-based predictions for interest costs GET /api/insights/predictions/interest-costs */
    @GetMapping("/predictions/interest-costs")
    public ResponseEntity<List<Map<String, Object>>> getPredictedInterestCosts(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // Get historical transactions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(6);
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        // Get accounts and calculate interest rates from transactions
        final List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        final Map<String, FinancialInsightsPredictionService.AccountData> accountMap = new HashMap<>();
        for (final AccountTable account : accounts) {
            final BigDecimal balance =
                    account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Calculate interest rate from interest charges in transactions
            final List<TransactionTable> accountTx =
                    historicalTransactions.stream()
                            .filter(tx -> account.getAccountId().equals(tx.getAccountId()))
                            .collect(Collectors.toList());

            final List<TransactionTable> interestCharges =
                    accountTx.stream()
                            .filter(
                                    tx -> {
                                        final String desc =
                                                tx.getDescription() != null
                                                        ? tx.getDescription().toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String category =
                                                tx.getCategoryPrimary() != null
                                                        ? tx.getCategoryPrimary().toLowerCase(Locale.ROOT)
                                                        : "";
                                        return (desc.contains(INTEREST)
                                                || desc.contains("finance charge")
                                                || category.contains(INTEREST))
                                                && tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0;
                                    })
                            .collect(Collectors.toList());

            if (!interestCharges.isEmpty()) {
                final BigDecimal totalInterest =
                        interestCharges.stream()
                                .map(tx -> tx.getAmount().abs())
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Estimate monthly interest (assuming 3 months of data)
                final BigDecimal monthlyInterest =
                        totalInterest.divide(
                                BigDecimal.valueOf(Math.max(1, interestCharges.size() / 3.0)),
                                2,
                                java.math.RoundingMode.HALF_UP);
                final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

                // Calculate interest rate
                final double rate =
                        balance.compareTo(BigDecimal.ZERO) > 0
                                ? annualInterest
                                .divide(balance, 4, java.math.RoundingMode.HALF_UP)
                                .doubleValue()
                                : 0.0;

                if (rate > 0.01) { // At least 1% interest rate
                    final String name =
                            account.getAccountName() != null ? account.getAccountName() : UNKNOWN;
                    accountMap.put(
                            account.getAccountId(),
                            new FinancialInsightsPredictionService.AccountData(
                                    name, balance, rate));
                }
            }
        }

        final List<FinancialInsightsPredictionService.PredictedInterestCost> predictions =
                predictionService.predictInterestCosts(accountMap, historicalTransactions);

        final List<Map<String, Object>> response =
                predictions.stream()
                        .map(this::toPredictedInterestCostMap)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /** Get all ML predictions summary GET /api/insights/predictions/summary */
    @GetMapping("/predictions/summary")
    public ResponseEntity<Map<String, Object>> getPredictionsSummary(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final Map<String, Object> summary = new HashMap<>();

        // Get all predictions
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusMonths(6);
        final String startStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(user.getUserId(), startStr, endStr);

        final List<FinancialInsightsPredictionService.PredictedAnomaly> predictedAnomalies =
                predictionService.predictAnomalies(historicalTransactions, 30);

        final List<SubscriptionTable> subscriptions =
                subscriptionRepository.findByUserId(user.getUserId());
        final Map<String, BigDecimal> subscriptionMap = new HashMap<>();
        for (final SubscriptionTable sub : subscriptions) {
            if (sub.getActive() != null && sub.getActive() && sub.getAmount() != null) {
                final String name = sub.getMerchantName() != null ? sub.getMerchantName() : UNKNOWN;
                subscriptionMap.put(name, sub.getAmount());
            }
        }
        final List<FinancialInsightsPredictionService.PredictedExpenseReduction>
                predictedExpenseReductions =
                        predictionService.predictExpenseReductions(
                                historicalTransactions, subscriptionMap);

        final List<AccountTable> accounts = accountRepository.findByUserId(user.getUserId());
        final Map<String, FinancialInsightsPredictionService.AccountData> accountMap = new HashMap<>();
        for (final AccountTable account : accounts) {
            final BigDecimal balance =
                    account.getBalance() != null ? account.getBalance().abs() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Calculate interest rate from interest charges in transactions
            final List<TransactionTable> accountTx =
                    historicalTransactions.stream()
                            .filter(tx -> account.getAccountId().equals(tx.getAccountId()))
                            .collect(Collectors.toList());

            final List<TransactionTable> interestCharges =
                    accountTx.stream()
                            .filter(
                                    tx -> {
                                        final String desc =
                                                tx.getDescription() != null
                                                        ? tx.getDescription().toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String category =
                                                tx.getCategoryPrimary() != null
                                                        ? tx.getCategoryPrimary().toLowerCase(Locale.ROOT)
                                                        : "";
                                        return (desc.contains(INTEREST)
                                                || desc.contains("finance charge")
                                                || category.contains(INTEREST))
                                                && tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0;
                                    })
                            .collect(Collectors.toList());

            if (!interestCharges.isEmpty()) {
                final BigDecimal totalInterest =
                        interestCharges.stream()
                                .map(tx -> tx.getAmount().abs())
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Estimate monthly interest
                final BigDecimal monthlyInterest =
                        totalInterest.divide(
                                BigDecimal.valueOf(Math.max(1, interestCharges.size() / 3.0)),
                                2,
                                java.math.RoundingMode.HALF_UP);
                final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

                // Calculate interest rate
                final double rate =
                        balance.compareTo(BigDecimal.ZERO) > 0
                                ? annualInterest
                                .divide(balance, 4, java.math.RoundingMode.HALF_UP)
                                .doubleValue()
                                : 0.0;

                if (rate > 0.01) { // At least 1% interest rate
                    final String name =
                            account.getAccountName() != null ? account.getAccountName() : UNKNOWN;
                    accountMap.put(
                            account.getAccountId(),
                            new FinancialInsightsPredictionService.AccountData(
                                    name, balance, rate));
                }
            }
        }
        final List<FinancialInsightsPredictionService.PredictedInterestCost> predictedInterestCosts =
                predictionService.predictInterestCosts(accountMap, historicalTransactions);

        summary.put("predictedAnomaliesCount", predictedAnomalies.size());
        summary.put("predictedExpenseReductionsCount", predictedExpenseReductions.size());
        summary.put("predictedInterestCostsCount", predictedInterestCosts.size());

        // Calculate total predicted savings
        final double totalPredictedSavings =
                predictedExpenseReductions.stream()
                        .mapToDouble(r -> r.getAnnualSavings().doubleValue())
                        .sum();
        summary.put("totalPredictedSavings", totalPredictedSavings);

        // Calculate total predicted interest costs
        final double totalPredictedInterest =
                predictedInterestCosts.stream()
                        .mapToDouble(c -> c.getAnnualInterest().doubleValue())
                        .sum();
        summary.put("totalPredictedInterest", totalPredictedInterest);

        return ResponseEntity.ok(summary);
    }

    // Helper methods to convert to maps

    private Map<String, Object> toPredictedAnomalyMap(
            final FinancialInsightsPredictionService.PredictedAnomaly prediction) {
        final Map<String, Object> map = new HashMap<>();
        map.put(CATEGORY, prediction.getCategory());
        map.put("predictedAmount", prediction.getPredictedAmount());
        map.put("historicalAverage", prediction.getHistoricalAverage());
        map.put("confidence", prediction.getConfidence());
        map.put(REASON, prediction.getReason());
        map.put(PREDICTED_DATE, prediction.getPredictedDate().toString());
        return map;
    }

    private Map<String, Object> toPredictedExpenseReductionMap(
            final FinancialInsightsPredictionService.PredictedExpenseReduction prediction) {
        final Map<String, Object> map = new HashMap<>();
        map.put("expenseName", prediction.getExpenseName());
        map.put(MONTHLY_SAVINGS, prediction.getMonthlySavings());
        map.put("annualSavings", prediction.getAnnualSavings());
        map.put("probability", prediction.getProbability());
        map.put(REASON, prediction.getReason());
        map.put(PREDICTED_DATE, prediction.getPredictedDate().toString());
        return map;
    }

    private Map<String, Object> toPredictedGoalAchievementMap(
            final FinancialInsightsPredictionService.PredictedGoalAchievement prediction) {
        final Map<String, Object> map = new HashMap<>();
        map.put("goalId", prediction.getGoalId());
        map.put("goalName", prediction.getGoalName());
        map.put("currentAmount", prediction.getCurrentAmount());
        map.put("targetAmount", prediction.getTargetAmount());
        map.put(PREDICTED_DATE, prediction.getPredictedDate().toString());
        map.put("achievementProbability", prediction.getAchievementProbability());
        map.put(MONTHLY_SAVINGS, prediction.getMonthlySavings());
        map.put("remaining", prediction.getRemaining());
        map.put("savingsRate", prediction.getSavingsRate());
        return map;
    }

    private Map<String, Object> toPredictedMissedPaymentMap(
            final FinancialInsightsPredictionService.PredictedMissedPayment prediction) {
        final Map<String, Object> map = new HashMap<>();
        map.put("paymentName", prediction.getPaymentName());
        map.put("dueDate", prediction.getDueDate().toString());
        map.put(AMOUNT, prediction.getAmount());
        map.put("riskProbability", prediction.getRiskProbability());
        map.put(REASON, prediction.getReason());
        map.put("daysUntilDue", prediction.getDaysUntilDue());
        return map;
    }

    private Map<String, Object> toPredictedInterestCostMap(
            final FinancialInsightsPredictionService.PredictedInterestCost prediction) {
        final Map<String, Object> map = new HashMap<>();
        map.put("accountId", prediction.getAccountId());
        map.put("accountName", prediction.getAccountName());
        map.put("interestRate", prediction.getInterestRate());
        map.put("currentBalance", prediction.getCurrentBalance());
        map.put("predictedBalance", prediction.getPredictedBalance());
        map.put("monthlyInterest", prediction.getMonthlyInterest());
        map.put("annualInterest", prediction.getAnnualInterest());
        map.put("potentialSavings", prediction.getPotentialSavings());
        map.put("confidence", prediction.getConfidence());
        map.put("trend", prediction.getTrend());
        return map;
    }

    private Map<String, Object> toAnomalyMap(final TransactionAnomaly anomaly) {
        final Map<String, Object> map = new HashMap<>();
        map.put("transactionId", anomaly.getTransactionId());
        map.put(AMOUNT, anomaly.getAmount());
        map.put(DESCRIPTION, anomaly.getDescription());
        map.put("merchantName", anomaly.getMerchantName());
        map.put("transactionDate", anomaly.getTransactionDate());
        map.put(CATEGORY, anomaly.getCategory());
        map.put("type", anomaly.getType().name());
        map.put(SEVERITY, anomaly.getSeverity().name());
        map.put(REASON, anomaly.getReason());
        // Flow 7 / O2: the CTA the iOS card should render. Deep link format is
        // "<kind>:<id>" so the client only has to split once. Anomalies almost always
        // want to open the offending transaction.
        if (anomaly.getTransactionId() != null) {
            map.put("suggestedAction", "openTransaction:" + anomaly.getTransactionId());
            map.put("suggestedActionLabel", "Review transaction");
        }
        return map;
    }

    private Map<String, Object> toExpenseRecommendationMap(final ExpenseRecommendation rec) {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", rec.getType().name());
        map.put(TITLE, rec.getTitle());
        map.put(MONTHLY_SAVINGS, rec.getMonthlySavings());
        map.put("annualSavings", rec.getAnnualSavings());
        map.put(DESCRIPTION, rec.getDescription());
        map.put("priority", rec.getPriority().name());
        map.put(CATEGORY, rec.getCategory());
        map.put("entityId", rec.getEntityId());
        // Flow 7 / O2: route each recommendation type to the view that can act on it.
        final String action = suggestedActionFor(rec);
        if (action != null) {
            map.put("suggestedAction", action);
            map.put("suggestedActionLabel", labelFor(rec.getType().name()));
        }
        return map;
    }

    private String suggestedActionFor(final ExpenseRecommendation rec) {
        if (rec == null || rec.getType() == null) {
            return null;
        }
        switch (rec.getType().name()) {
            case "DORMANT_SUBSCRIPTION":
            case "DUPLICATE_SUBSCRIPTION":
            case "SUBSCRIPTION_PRICE_INCREASE":
                return rec.getEntityId() != null
                        ? ("openSubscription:" + rec.getEntityId())
                        : "openSubscriptions";
            case "CATEGORY_OVERSPEND":
            case "BUDGET_ADJUSTMENT":
                return rec.getCategory() != null
                        ? ("editBudget:" + rec.getCategory())
                        : "openBudgets";
            case "CREATE_BUDGET":
                return "createBudget:"
                        + (rec.getCategory() == null ? "general" : rec.getCategory());
            default:
                return null;
        }
    }

    private String labelFor(final String recType) {
        return switch (recType) {
            case "DORMANT_SUBSCRIPTION", "DUPLICATE_SUBSCRIPTION", "SUBSCRIPTION_PRICE_INCREASE" ->
                    "Review subscription";
            case "CATEGORY_OVERSPEND" -> "Edit budget";
            case "BUDGET_ADJUSTMENT" -> "Adjust budget";
            case "CREATE_BUDGET" -> "Create budget";
            default -> "View details";
        };
    }

    private Map<String, Object> toGoalRecommendationMap(final FinancialGoalRecommendation rec) {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", rec.getType().name());
        map.put(TITLE, rec.getTitle());
        map.put(DESCRIPTION, rec.getDescription());
        map.put("currentAmount", rec.getCurrentAmount());
        map.put("targetAmount", rec.getTargetAmount());
        map.put("targetDate", rec.getTargetDate().toString());
        map.put("priority", rec.getPriority().name());
        map.put("actionPlan", rec.getActionPlan());
        map.put("gap", rec.getGap());
        return map;
    }

    private Map<String, Object> toMissedPaymentMap(final MissedPaymentAlert alert) {
        final Map<String, Object> map = new HashMap<>();
        map.put("actionId", alert.getActionId());
        map.put(TITLE, alert.getTitle());
        map.put(DESCRIPTION, alert.getDescription());
        map.put("dueDate", alert.getDueDate().toString());
        map.put("daysOverdue", alert.getDaysOverdue());
        map.put("type", alert.getType().name());
        map.put(SEVERITY, alert.getSeverity().name());
        map.put("message", alert.getMessage());
        map.put(AMOUNT, alert.getAmount());
        return map;
    }

    private Map<String, Object> toHighInterestMap(final HighInterestAlert alert) {
        final Map<String, Object> map = new HashMap<>();
        map.put("accountId", alert.getAccountId());
        map.put("accountName", alert.getAccountName());
        map.put("institutionName", alert.getInstitutionName());
        map.put("accountType", alert.getAccountType());
        map.put("balance", alert.getBalance());
        map.put("interestRate", alert.getInterestRate());
        map.put("monthlyInterest", alert.getMonthlyInterest());
        map.put("annualInterestCost", alert.getAnnualInterestCost());
        map.put(SEVERITY, alert.getSeverity().name());
        map.put("recommendation", alert.getRecommendation());
        return map;
    }
}
