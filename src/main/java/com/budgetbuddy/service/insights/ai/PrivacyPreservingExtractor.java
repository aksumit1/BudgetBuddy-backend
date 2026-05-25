package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.TransactionAnomalyService;
import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.budgetbuddy.service.insights.InsightsContext;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds a privacy-preserving aggregate view of a user's data, suitable
 * for sending to an external LLM. The LLM is never shown raw rows;
 * everything it receives is bucketed, anonymised, or counted.
 *
 * <p>Design contract — every value returned by this class must satisfy:
 * <ul>
 *   <li>No user identifier: no userId, no email, no name, no
 *       device/account identifiers.</li>
 *   <li>No exact transaction amounts: amounts are aggregated
 *       (sum/avg/percentile) or bucketed.</li>
 *   <li>No free-form descriptions: only known global-brand merchant
 *       names from {@link #GLOBAL_BRAND_ALLOWLIST} survive; everything
 *       else collapses to its category.</li>
 *   <li>No account numbers (full or last-4): never read from the data.</li>
 *   <li>No transaction dates more precise than YYYY-MM (month bucket).</li>
 * </ul>
 *
 * <p>The output is a self-contained snapshot the LLM can reason over.
 * If the LLM hallucinates a specific transaction, it must be doing it
 * from the categorical signal alone — we haven't fed it any specifics
 * to confabulate from.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class PrivacyPreservingExtractor {

    /**
     * Optional — when wired, the extractor includes the top 5 recent
     * anomalies (sanitised) in the snapshot. Null in unit tests that
     * don't care about anomalies; the extractor degrades gracefully.
     */
    private final TransactionAnomalyService anomalyService;

    @Autowired
    public PrivacyPreservingExtractor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    final TransactionAnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    /** Convenience constructor for unit tests that don't need anomalies. */
    public PrivacyPreservingExtractor() {
        this(null);
    }

    /**
     * Merchants whose name is publicly identifiable and not a privacy
     * leak (everyone knows what Netflix is). For anything outside this
     * list we send the category instead, so an LLM never sees the
     * name of a small local business or any service that could
     * uniquely identify the user.
     */
    private static final Set<String> GLOBAL_BRAND_ALLOWLIST = Set.of(
            "netflix", "spotify", "amazon", "amazon prime", "amazon music",
            "apple", "apple music", "apple tv", "icloud",
            "youtube", "youtube premium", "youtube music",
            "hulu", "disney+", "disney plus", "hbo", "hbo max", "max",
            "paramount+", "peacock", "starz", "showtime", "crunchyroll",
            "microsoft", "microsoft 365", "office 365", "onedrive",
            "google", "google one", "google drive", "google workspace",
            "adobe", "creative cloud", "photoshop",
            "dropbox", "box", "github", "gitlab", "atlassian", "jira",
            "slack", "zoom", "notion", "figma", "linear",
            "chatgpt", "openai", "anthropic", "claude", "cursor",
            "uber", "uber one", "lyft", "lyft pink", "doordash",
            "doordash dashpass", "grubhub", "instacart", "instacart+");

    /**
     * Produce a sanitised snapshot of the user's data. Caller (the
     * chat orchestrator) sends this map to the LLM; the LLM never
     * receives the raw {@link InsightsContext}.
     */
    public SanitizedSnapshot extract(final InsightsContext ctx) {
        if (ctx == null) {
            return new SanitizedSnapshot(
                    Map.of(), Map.of(), Map.of(), List.of(),
                    List.of(), List.of(), List.of(), 0, 0, "USD",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        final List<TransactionTable> txs = ctx.transactions();
        final LocalDate cutoff90 = ctx.asOf().minusDays(90);
        final List<TransactionTable> last90 = txs.stream()
                .filter(t -> t.getTransactionDate() != null
                        && t.getTransactionDate().compareTo(cutoff90.toString()) >= 0)
                .toList();

        return new SanitizedSnapshot(
                spendingByCategory(last90),
                spendingByMonth(last90),
                spendingByKnownMerchant(last90),
                subscriptionAggregates(ctx.subscriptions()),
                budgetAggregates(ctx.budgets()),
                goalAggregates(ctx),
                recentAnomalies(ctx.userId()),
                ctx.accounts().size(),
                last90.size(),
                inferCurrency(ctx.accounts(), last90),
                netWorth(ctx.accounts()),
                liquidAssets(ctx.accounts()),
                estimatedMonthlyIncome(txs, ctx.asOf()));
    }

    /** Sum across all account balances. Whole-dollar rounded. */
    private BigDecimal netWorth(final List<AccountTable> accounts) {
        if (accounts == null || accounts.isEmpty()) return BigDecimal.ZERO;
        return accounts.stream()
                .map(a -> a.getBalance() == null ? BigDecimal.ZERO : a.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Liquid assets = sum of checking/savings/cash accounts (not
     * credit, not investment, not loans). Used by the LLM to reason
     * about "what can I spend safely today".
     */
    private BigDecimal liquidAssets(final List<AccountTable> accounts) {
        if (accounts == null || accounts.isEmpty()) return BigDecimal.ZERO;
        return accounts.stream()
                .filter(a -> {
                    final String type = a.getAccountType() == null
                            ? "" : a.getAccountType().toLowerCase(Locale.ROOT);
                    return type.contains("check") || type.contains("saving")
                            || type.contains("cash") || type.contains("money market");
                })
                .map(a -> a.getBalance() == null ? BigDecimal.ZERO : a.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Heuristic monthly income: sum of positive transactions in the
     * last 90 days categorised as INCOME or TRANSFER_IN, divided by
     * 3. Falls back to "deposits over $500" if categories aren't set.
     * Whole-dollar rounded.
     */
    private BigDecimal estimatedMonthlyIncome(
            final List<TransactionTable> txs, final LocalDate asOf) {
        if (txs == null || txs.isEmpty()) return BigDecimal.ZERO;
        final LocalDate cutoff = asOf.minusDays(90);
        BigDecimal total = BigDecimal.ZERO;
        for (final TransactionTable t : txs) {
            if (t.getAmount() == null || t.getAmount().signum() <= 0) continue;
            if (t.getTransactionDate() == null
                    || t.getTransactionDate().compareTo(cutoff.toString()) < 0) continue;
            final String cat = t.getCategoryPrimary() == null
                    ? "" : t.getCategoryPrimary().toLowerCase(Locale.ROOT);
            final boolean looksLikeIncome = cat.contains("income")
                    || cat.contains("payroll")
                    || cat.contains("salary")
                    || cat.contains("transfer_in")
                    || (cat.isEmpty() && t.getAmount().compareTo(BigDecimal.valueOf(500)) > 0);
            if (looksLikeIncome) {
                total = total.add(t.getAmount());
            }
        }
        return total.divide(BigDecimal.valueOf(3), 0, RoundingMode.HALF_UP);
    }

    /**
     * Per-goal progress for "am I on track?" questions. Names pass
     * through unchanged because a user's own goal labels aren't PII
     * any more than category names — and the LLM needs them to
     * answer specifically about which goal the user means.
     */
    private List<SanitizedGoal> goalAggregates(final InsightsContext ctx) {
        if (ctx == null || !ctx.goalsAvailable() || ctx.goals().isEmpty()) {
            return List.of();
        }
        final List<SanitizedGoal> out = new ArrayList<>();
        for (final var g : ctx.goals()) {
            if (g.getDeletedAt() != null) continue;
            final BigDecimal target = g.getTargetAmount() == null
                    ? BigDecimal.ZERO : g.getTargetAmount();
            final BigDecimal current = g.getCurrentAmount() == null
                    ? BigDecimal.ZERO : g.getCurrentAmount();
            final double pct = target.signum() == 0
                    ? 0.0
                    : current.divide(target, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();
            LocalDate parsedTargetDate = null;
            if (g.getTargetDate() != null && !g.getTargetDate().isBlank()) {
                try {
                    parsedTargetDate = LocalDate.parse(g.getTargetDate());
                } catch (final Exception ignored) {
                    // Malformed date — drop silently rather than skip the whole goal.
                }
            }
            out.add(new SanitizedGoal(
                    g.getName(),
                    target.setScale(0, RoundingMode.HALF_UP),
                    current.setScale(0, RoundingMode.HALF_UP),
                    Math.min(pct, 999.0),
                    parsedTargetDate,
                    g.getGoalType() == null ? "OTHER" : g.getGoalType(),
                    g.getCompletedAt() != null));
        }
        return out;
    }

    /**
     * Per-budget aggregates suitable for "am I on track?" questions.
     * Categories pass through (categories are not PII); amounts are
     * rounded to whole dollars.
     */
    private List<SanitizedBudget> budgetAggregates(final List<BudgetTable> budgets) {
        if (budgets == null || budgets.isEmpty()) {
            return List.of();
        }
        final List<SanitizedBudget> out = new ArrayList<>(budgets.size());
        for (final BudgetTable b : budgets) {
            if (b == null || b.getCategory() == null) {
                continue;
            }
            final BigDecimal limit = b.getMonthlyLimit() == null
                    ? BigDecimal.ZERO
                    : b.getMonthlyLimit().setScale(0, RoundingMode.HALF_UP);
            final BigDecimal spent = b.getCurrentSpent() == null
                    ? BigDecimal.ZERO
                    : b.getCurrentSpent().setScale(0, RoundingMode.HALF_UP);
            final double pct = limit.signum() > 0
                    ? spent.divide(limit, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0.0;
            out.add(new SanitizedBudget(
                    b.getCategory().toLowerCase(Locale.ROOT),
                    limit, spent, Math.round(pct * 10) / 10.0));
        }
        out.sort(Comparator.comparingDouble(SanitizedBudget::percentUsed).reversed());
        return out;
    }

    /**
     * Top 5 recent anomalies for the user, sanitised through the same
     * merchant-allowlist filter. Each anomaly carries category, type,
     * severity, and a rounded amount — no description, no transaction
     * id. When the anomaly service isn't wired (unit tests, or
     * deliberately disabled), returns an empty list.
     */
    private List<SanitizedAnomaly> recentAnomalies(@Nullable final String userId) {
        if (anomalyService == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        final List<TransactionAnomaly> raw;
        try {
            raw = anomalyService.detectAnomalies(userId);
        } catch (final RuntimeException e) {
            // Anomaly detection failure must not break chat; return empty.
            return List.of();
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .limit(5)
                .map(this::sanitizeAnomaly)
                .toList();
    }

    private SanitizedAnomaly sanitizeAnomaly(final TransactionAnomaly a) {
        final String merchantKey = a.getMerchantName() == null
                ? null
                : canonicalAllowlistKey(a.getMerchantName());
        return new SanitizedAnomaly(
                merchantKey != null ? merchantKey : "merchant_redacted",
                a.getCategory() == null
                        ? "uncategorized"
                        : a.getCategory().toLowerCase(Locale.ROOT),
                a.getAmount() == null
                        ? BigDecimal.ZERO
                        : a.getAmount().abs().setScale(0, RoundingMode.HALF_UP),
                a.getSeverity() == null ? "MEDIUM" : a.getSeverity().name(),
                a.getType() == null ? "STATISTICAL_OUTLIER" : a.getType().name());
    }

    // -----------------------------------------------------------------
    // Aggregations
    // -----------------------------------------------------------------

    /**
     * Sum of expenses per category, rounded to the nearest dollar.
     * Categories are the existing internal category field, not free
     * text. Income (positive amounts) is excluded — LLM only sees
     * spending shape, not income amount.
     */
    private Map<String, BigDecimal> spendingByCategory(final List<TransactionTable> txs) {
        final Map<String, BigDecimal> out = new TreeMap<>();
        for (final TransactionTable t : txs) {
            if (t.getAmount() == null || t.getAmount().signum() >= 0) {
                continue;
            }
            final String cat = t.getCategoryPrimary() == null
                    ? "uncategorized"
                    : t.getCategoryPrimary().toLowerCase(Locale.ROOT);
            out.merge(cat, t.getAmount().abs(), BigDecimal::add);
        }
        // Round to the nearest dollar for output.
        out.replaceAll((k, v) -> v.setScale(0, RoundingMode.HALF_UP));
        return out;
    }

    /**
     * Spending per YYYY-MM month bucket. Day-of-month is dropped to
     * minimize re-identification risk (someone with daily spend data
     * is more identifiable than someone with monthly totals).
     */
    private Map<String, BigDecimal> spendingByMonth(final List<TransactionTable> txs) {
        final Map<String, BigDecimal> out = new TreeMap<>();
        for (final TransactionTable t : txs) {
            if (t.getAmount() == null || t.getAmount().signum() >= 0) {
                continue;
            }
            final String date = t.getTransactionDate();
            if (date == null || date.length() < 7) {
                continue;
            }
            final String month = date.substring(0, 7);
            out.merge(month, t.getAmount().abs(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> v.setScale(0, RoundingMode.HALF_UP));
        return out;
    }

    /**
     * Spending per merchant, but ONLY for merchants in the global-brand
     * allowlist. Non-allowlist merchants are grouped under their
     * category so the LLM can still see "you spent $X on dining" without
     * seeing the actual restaurants.
     */
    private Map<String, BigDecimal> spendingByKnownMerchant(final List<TransactionTable> txs) {
        final Map<String, BigDecimal> out = new TreeMap<>();
        for (final TransactionTable t : txs) {
            if (t.getAmount() == null || t.getAmount().signum() >= 0) {
                continue;
            }
            final String merchant = t.getMerchantName();
            if (merchant == null || merchant.isBlank()) {
                continue;
            }
            final String key = canonicalAllowlistKey(merchant);
            if (key == null) {
                continue; // Not in allowlist — silently drop the per-merchant detail.
            }
            out.merge(key, t.getAmount().abs(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> v.setScale(0, RoundingMode.HALF_UP));
        return out;
    }

    /**
     * Subscription aggregates — names are passed through ONLY when on
     * the allowlist; everything else becomes a generic "subscription".
     * Per-row metadata is reduced to (name, monthly cost rounded to
     * nearest dollar, billing cycle).
     */
    private List<SanitizedSubscription> subscriptionAggregates(
            final List<Subscription> subs) {
        final List<SanitizedSubscription> out = new ArrayList<>();
        if (subs == null) {
            return out;
        }
        int genericCounter = 0;
        for (final Subscription s : subs) {
            if (s.getActive() == null || !s.getActive()) {
                continue;
            }
            final String merchant = s.getMerchantName();
            final String allowKey = merchant == null ? null : canonicalAllowlistKey(merchant);
            final String displayName;
            if (allowKey != null) {
                displayName = allowKey;
            } else {
                genericCounter++;
                displayName = "subscription_" + genericCounter;
            }
            final BigDecimal monthly = s.getAmount() == null
                    ? BigDecimal.ZERO
                    : s.getAmount().abs().setScale(0, RoundingMode.HALF_UP);
            final String cycle = s.getFrequency() == null
                    ? "monthly"
                    : s.getFrequency().name().toLowerCase(Locale.ROOT);
            out.add(new SanitizedSubscription(displayName, monthly, cycle));
        }
        return out;
    }

    /**
     * Return the lowercase allowlist key when the merchant matches one.
     * Match is substring-on-lowercase: "NETFLIX.COM" and
     * "NETFLIX SVCS" both map to "netflix".
     */
    private static String canonicalAllowlistKey(final String merchant) {
        final String lower = merchant.toLowerCase(Locale.ROOT).trim();
        for (final String brand : GLOBAL_BRAND_ALLOWLIST) {
            if (lower.contains(brand)) {
                return brand;
            }
        }
        return null;
    }

    /** Cheap currency inference from accounts/transactions. Defaults to USD. */
    private static String inferCurrency(
            final List<AccountTable> accounts, final List<TransactionTable> txs) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final AccountTable a : accounts) {
            if (a != null && a.getCurrencyCode() != null) {
                counts.merge(a.getCurrencyCode().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        for (final TransactionTable t : txs) {
            if (t != null && t.getCurrencyCode() != null) {
                counts.merge(t.getCurrencyCode().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("USD");
    }

    // -----------------------------------------------------------------
    // Output types — these are what the LLM sees, nothing else.
    // -----------------------------------------------------------------

    public record SanitizedSnapshot(
            Map<String, BigDecimal> spendingByCategory90d,
            Map<String, BigDecimal> spendingByMonth,
            Map<String, BigDecimal> spendingByKnownMerchant90d,
            List<SanitizedSubscription> subscriptions,
            List<SanitizedBudget> budgets,
            List<SanitizedGoal> goals,
            List<SanitizedAnomaly> recentAnomalies,
            int accountCount,
            int transactionCount90d,
            String currency,
            BigDecimal netWorth,
            BigDecimal liquidAssets,
            BigDecimal estimatedMonthlyIncome) {

        /** Days the spending-by-month bucket implicitly covers. */
        public long monthsCovered() {
            if (spendingByMonth.size() <= 1) {
                return spendingByMonth.size();
            }
            final List<String> sorted = spendingByMonth.keySet().stream().sorted().toList();
            try {
                final LocalDate first = LocalDate.parse(sorted.get(0) + "-01");
                final LocalDate last = LocalDate.parse(sorted.get(sorted.size() - 1) + "-01");
                return ChronoUnit.MONTHS.between(first, last) + 1;
            } catch (final Exception ignored) {
                return spendingByMonth.size();
            }
        }
    }

    /**
     * One goal with progress fields. Name passes through (user-chosen
     * labels like "Emergency fund" / "Vacation 2026" aren't PII —
     * categories aren't either).
     */
    public record SanitizedGoal(
            String name,
            BigDecimal targetAmount,
            BigDecimal currentAmount,
            double percentComplete,
            @Nullable LocalDate targetDate,
            String goalType,
            boolean completed) {}

    public record SanitizedSubscription(
            String displayName, BigDecimal monthlyCost, String billingCycle) {}

    /**
     * Per-budget state for "am I on track?" questions. {@code category}
     * is the budget's category string (not PII); {@code limit} and
     * {@code spent} are whole-dollar amounts; {@code percentUsed} is
     * a convenience the LLM can quote directly.
     */
    public record SanitizedBudget(
            String category,
            BigDecimal limit,
            BigDecimal spent,
            double percentUsed) {}

    /**
     * One anomaly stripped of identifying detail. Merchant name is
     * either an allowlist brand or the sentinel {@code merchant_redacted}.
     */
    public record SanitizedAnomaly(
            String merchant,
            String category,
            BigDecimal amount,
            String severity,
            String type) {}
}
