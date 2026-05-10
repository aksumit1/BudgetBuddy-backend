package com.budgetbuddy.api;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 8 / O9 — one endpoint per purchase decision.
 *
 * <p>The iOS client has a purchase-simulator sheet (Flow 8 / O13) that needs four answers for every
 * "would you really buy this?" prompt: affordability verdict, which card to swipe, which goals
 * slip, and what the user could cut to close a shortfall. Instead of sending four requests, we roll
 * them up here.
 *
 * <p>The iOS-side services do the same maths as the Java implementations here; the overlap is
 * deliberate. iOS stays responsive for keystroke-level updates in the simulator; the backend is
 * authoritative for "record this as my planned purchase" flows that may cross devices.
 *
 * <p>Response shape intentionally mirrors the iOS `AffordabilityService.Verdict` so the client can
 * decode with no transformation.
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
@RestController
@RequestMapping("/api/planner")
public class PlannerController {

    private static final String MONTHS_DELAYED = "monthsDelayed";

    private static final String PROJECTED_REWARD = "projectedReward";

    private static final String SUGGESTED_CUT = "suggestedCut";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UserService userService;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;

    public PlannerController(
            final UserService userService,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final AccountRepository accountRepository) {
        this.userService = userService;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(
            @AuthenticationPrincipal final UserDetails userDetails, @RequestBody final EvaluateRequest req) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (req == null || req.amount == null || req.amount.signum() <= 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "amount must be > 0");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", req.amount);
        out.put("category", req.category);
        out.put(
                "purchaseDate",
                req.purchaseDate == null ? LocalDate.now().toString() : req.purchaseDate);

        // Affordability + shortfall
        final BigDecimal safeBefore = computeSafeToSpend(user);
        final BigDecimal safeAfter = safeBefore.subtract(req.amount);
        final Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("safeToSpendBefore", safeBefore);
        verdict.put("safeToSpendAfter", safeAfter);
        final String outcome;
        if (safeAfter.signum() < 0) {
            outcome = "no";
        } else if (safeAfter.compareTo(safeBefore.multiply(new BigDecimal("0.2"))) < 0) {
            outcome = "caution";
        } else {
            outcome = "yes";
        }
        verdict.put("outcome", outcome);
        verdict.put("shortfall", safeAfter.signum() < 0 ? safeAfter.abs() : BigDecimal.ZERO);
        out.put("affordability", verdict);

        // Goal impacts
        out.put("goalImpacts", computeGoalImpacts(user, req));

        // Card recommendation (light server-side version; full scoring is on iOS)
        out.put("cardRecommendations", recommendCards(user, req));

        // What-could-I-cut (light version; full algorithm on iOS)
        if ("no".equals(outcome)) {
            out.put("suggestedCuts", suggestCuts(user, safeAfter.abs()));
        } else {
            out.put("suggestedCuts", List.of());
        }
        return ResponseEntity.ok(out);
    }

    private BigDecimal computeSafeToSpend(final UserTable user) {
        // Sum positive balances from deposit-style accounts.
        BigDecimal cash = BigDecimal.ZERO;
        for (final AccountTable a : accountRepository.findByUserId(user.getUserId())) {
            if (a.getBalance() == null) {
                continue;
            }
            final String type = a.getAccountType() == null ? "" : a.getAccountType().toUpperCase(Locale.ROOT);
            if (type.contains("CHECKING")
                    || type.contains("SAVINGS")
                    || type.contains("MONEY_MARKET")) {
                if (a.getBalance().signum() > 0) {
                    cash = cash.add(a.getBalance());
                }
            }
        }
        // Subtract remaining-month budget commitment (rough: sum of monthlyLimits minus MTD spend).
        final LocalDate now = LocalDate.now();
        final LocalDate monthStart = now.withDayOfMonth(1);
        BigDecimal remainingBudget = BigDecimal.ZERO;
        final Map<String, BigDecimal> mtdSpend =
                monthToDateSpendByCategory(user.getUserId(), monthStart, now);
        for (final BudgetTable b : budgetRepository.findByUserId(user.getUserId())) {
            if (b.getMonthlyLimit() == null) {
                continue;
            }
            final BigDecimal spent = mtdSpend.getOrDefault(b.getCategory(), BigDecimal.ZERO);
            final BigDecimal remainingInBudget = b.getMonthlyLimit().subtract(spent).max(BigDecimal.ZERO);
            remainingBudget = remainingBudget.add(remainingInBudget);
        }
        return cash.subtract(remainingBudget).max(BigDecimal.ZERO);
    }

    private Map<String, BigDecimal> monthToDateSpendByCategory(
            final String userId, final LocalDate start, final LocalDate end) {
        final Map<String, BigDecimal> map = new HashMap<>();
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        userId, start.format(DATE), end.format(DATE));
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
            final String cat = t.getCategoryPrimary() == null ? "other" : t.getCategoryPrimary();
            map.merge(cat, t.getAmount().abs(), BigDecimal::add);
        }
        return map;
    }

    private List<Map<String, Object>> computeGoalImpacts(
            final UserTable user, final EvaluateRequest req) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final GoalTable g : goalRepository.findByUserId(user.getUserId())) {
            if (g == null || g.getDeletedAt() != null) {
                continue;
            }
            if (Boolean.TRUE.equals(g.getCompleted())) {
                continue;
            }
            if (g.getMonthlyContribution() == null || g.getMonthlyContribution().signum() <= 0) {
                continue;
            }
            // Months of contribution the purchase effectively consumes.
            final BigDecimal monthsConsumed =
                    req.amount.divide(g.getMonthlyContribution(), 2, RoundingMode.HALF_UP);
            final int delay = monthsConsumed.setScale(0, RoundingMode.UP).intValue();
            if (delay <= 0) {
                continue;
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("goalId", g.getGoalId());
            entry.put("goalName", g.getName());
            entry.put(MONTHS_DELAYED, delay);
            entry.put(
                    "severity", delay >= 6 ? "significant" : delay >= 2 ? "notable" : "negligible");
            out.add(entry);
        }
        out.sort(
                (a, b) ->
                        Integer.compare(
                                (int) b.get(MONTHS_DELAYED), (int) a.get(MONTHS_DELAYED)));
        return out;
    }

    private List<Map<String, Object>> recommendCards(
            final UserTable user, final EvaluateRequest req) {
        final String cat = req.category == null ? "default" : req.category.toLowerCase(Locale.ROOT);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final AccountTable a : accountRepository.findByUserId(user.getUserId())) {
            final String type = a.getAccountType() == null ? "" : a.getAccountType().toUpperCase(Locale.ROOT);
            if (!type.contains("CREDIT") && !type.contains("CHARGE")) {
                continue;
            }
            // Availability check.
            BigDecimal available = a.getAvailableCredit();
            if (available == null && a.getCreditLimit() != null && a.getBalance() != null) {
                available = a.getCreditLimit().subtract(a.getBalance().abs()).max(BigDecimal.ZERO);
            }
            if (available == null || available.compareTo(req.amount) < 0) {
                continue;
            }
            // Multiplier lookup. Keep everything in BigDecimal — this number
            // is shown to the user as a dollar amount so one cent of drift
            // from floating-point math is immediately visible.
            BigDecimal multiplier = BigDecimal.ONE;
            if (a.getRewardMultipliers() != null) {
                final BigDecimal m =
                        a.getRewardMultipliers()
                                .getOrDefault(cat, a.getRewardMultipliers().get("default"));
                if (m != null) {
                    multiplier = m;
                }
            }
            final BigDecimal valuePerUnit =
                    "miles".equalsIgnoreCase(a.getRewardType())
                            ? new BigDecimal("0.012")
                            : new BigDecimal("0.01");
            // grossReward = amount × multiplier × valuePerUnit
            final BigDecimal grossReward =
                    req.amount
                            .multiply(multiplier)
                            .multiply(valuePerUnit)
                            .setScale(2, RoundingMode.HALF_UP);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("accountId", a.getAccountId());
            entry.put("cardName", a.getAccountName());
            entry.put("rewardMultiplier", multiplier);
            entry.put(PROJECTED_REWARD, grossReward);
            entry.put("rewardType", a.getRewardType());
            entry.put("availableCredit", available);
            out.add(entry);
        }
        out.sort(
                (x, y) -> {
                    final BigDecimal px = (BigDecimal) x.get(PROJECTED_REWARD);
                    final BigDecimal py = (BigDecimal) y.get(PROJECTED_REWARD);
                    return py.compareTo(px);
                });
        return out;
    }

    private List<Map<String, Object>> suggestCuts(final UserTable user, final BigDecimal gap) {
        // Lightweight backend version: no category classification service here, so use
        // the same shortlist of known-discretionary categories used elsewhere.
        final Set<String> discretionary =
                Set.of("dining", "entertainment", "shopping", "travel", "subscriptions");
        final LocalDate now = LocalDate.now();
        final LocalDate from = now.minusDays(90);
        final Map<String, BigDecimal> totals = new HashMap<>();
        for (final TransactionTable t :
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), from.format(DATE), now.format(DATE))) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (t.getDeletedAt() != null || t.getAmount().signum() >= 0) {
                continue;
            }
            final String cat = t.getCategoryPrimary() == null ? "" : t.getCategoryPrimary().toLowerCase(Locale.ROOT);
            if (!discretionary.contains(cat)) {
                continue;
            }
            totals.merge(cat, t.getAmount().abs(), BigDecimal::add);
        }
        if (totals.isEmpty()) {
            return List.of();
        }

        final BigDecimal total = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final var e : totals.entrySet()) {
            final BigDecimal monthly = e.getValue().divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);
            if (monthly.compareTo(new BigDecimal("20")) < 0) {
                continue;
            }
            final BigDecimal share = e.getValue().divide(total, 4, RoundingMode.HALF_UP);
            final BigDecimal weightedCut = gap.multiply(share);
            final BigDecimal cap = monthly.multiply(new BigDecimal("0.5"));
            final BigDecimal cut = weightedCut.min(cap).setScale(2, RoundingMode.HALF_UP);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", e.getKey());
            entry.put("recentMonthlySpend", monthly);
            entry.put(SUGGESTED_CUT, cut);
            entry.put(
                    "trimPercent",
                    cut.divide(monthly, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(0, RoundingMode.HALF_UP));
            out.add(entry);
        }
        out.sort(
                (x, y) ->
                        ((BigDecimal) y.get(SUGGESTED_CUT))
                                .compareTo((BigDecimal) x.get(SUGGESTED_CUT)));
        return out;
    }

    public static class EvaluateRequest {
        public BigDecimal amount;
        public String category;
        public String purchaseDate; // ISO yyyy-MM-dd; optional, defaults to today
        public Boolean isForeign;

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(final String category) {
            this.category = category;
        }

        public String getPurchaseDate() {
            return purchaseDate;
        }

        public void setPurchaseDate(final String purchaseDate) {
            this.purchaseDate = purchaseDate;
        }

        public Boolean getIsForeign() {
            return isForeign;
        }

        public void setIsForeign(final Boolean isForeign) {
            this.isForeign = isForeign;
        }
    }
}
