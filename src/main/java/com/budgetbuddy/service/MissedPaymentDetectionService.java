package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Missed Payment Detection Service
 *
 * <p>Identifies bills and payments that were due but not paid.
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
public class MissedPaymentDetectionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MissedPaymentDetectionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final int RECURRING_PATTERN_DAYS = 90; // Look for patterns in last 90 days
    private static final int DAYS_BEFORE_OVERDUE = 3; // Alert 3 days before due date

    /**
     * Categories that are inherently discretionary and never represent a
     * "bill" the user has to pay on a schedule. The recurring-pattern
     * detector used to fire on these because three Dining or Shopping rows
     * with similar amounts happened to fall ~14 days apart.
     */
    private static final Set<String> DISCRETIONARY_CATEGORIES = Set.of(
            "dining",
            "groceries",
            "shopping",
            "transportation",
            "entertainment",
            "travel",
            "healthcare",
            "health",
            "personal_care",
            "other");

    private final TransactionActionRepository actionRepository;
    private final TransactionRepository transactionRepository;

    public MissedPaymentDetectionService(
            final TransactionActionRepository actionRepository,
            final TransactionRepository transactionRepository) {
        this.actionRepository = actionRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Context-aware overload for /summary. Reads everything from the
     * snapshot: actions, transactions for the payment-window matching,
     * and the recurring-pattern transactions — zero additional repo
     * calls. {@link #checkPaymentMadeFrom} replaces the per-action ±7d
     * fetch with an in-memory window slice of {@code ctx.transactions()}.
     */
    public List<MissedPaymentAlert> detectMissedPayments(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        if (ctx == null) {
            return new ArrayList<>();
        }
        final List<MissedPaymentAlert> alerts = new ArrayList<>();
        alerts.addAll(checkTransactionActionsFrom(
                ctx.transactionActions(), ctx.transactions(), ctx.asOf(), ctx.userId()));
        alerts.addAll(detectRecurringPaymentPatternsFrom(ctx.transactions(), ctx.asOf()));
        return alerts.stream()
                .sorted(Comparator.comparing(
                                (MissedPaymentAlert a) -> a.getSeverity().ordinal())
                        .reversed()
                        .thenComparing(MissedPaymentAlert::getDueDate))
                .toList();
    }

    /** Detect missed payments for a user */
    public List<MissedPaymentAlert> detectMissedPayments(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        LOGGER.info("Detecting missed payments for user: {}", userId);

        final List<MissedPaymentAlert> alerts = new ArrayList<>();

        // 1. Check TransactionActions with due dates
        alerts.addAll(checkTransactionActions(userId));

        // 2. Detect recurring payment patterns
        alerts.addAll(detectRecurringPaymentPatterns(userId));

        // Sort by severity and due date
        return alerts.stream()
                .sorted(
                        Comparator.comparing((MissedPaymentAlert a) -> a.getSeverity().ordinal())
                                .reversed()
                                .thenComparing(MissedPaymentAlert::getDueDate))
                .toList();
    }

    /** Check TransactionActions for overdue or at-risk payments */
    private List<MissedPaymentAlert> checkTransactionActions(final String userId) {
        // Fetch on the legacy path; the context path uses the
        // From-variant below with pre-fetched data.
        final List<TransactionActionTable> actions = actionRepository.findByUserId(userId);
        return checkTransactionActionsFrom(actions, null, LocalDate.now(), userId);
    }

    /**
     * Context-aware variant — works on pre-fetched actions + pre-fetched
     * transactions. When {@code preFetchedTxs} is non-null it's used as
     * the source for payment-matching (in-memory filtered to ±7d around
     * each action's due date), skipping the per-action repo call that
     * the legacy path issued. Pass null to fall back to the legacy
     * per-action fetch (for backwards compat with {@link
     * #checkTransactionActions(String)}).
     */
    private List<MissedPaymentAlert> checkTransactionActionsFrom(
            final List<TransactionActionTable> actions,
            final List<TransactionTable> preFetchedTxs,
            final LocalDate today,
            final String userId) {
        final List<MissedPaymentAlert> alerts = new ArrayList<>();

        for (final TransactionActionTable action : actions) {
            // Skip if already completed
            if (action.getIsCompleted() != null && action.getIsCompleted()) {
                continue;
            }

            final String dueDateStr = action.getDueDate();
            if (dueDateStr == null || dueDateStr.isEmpty()) {
                continue;
            }

            try {
                final LocalDate dueDate = LocalDate.parse(dueDateStr, DATE_FORMATTER);
                final long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
                final long daysOverdue = -daysUntilDue; // Negative if overdue

                if (daysOverdue > 0) {
                    // Overdue payment
                    final Severity severity = daysOverdue > 7 ? Severity.HIGH : Severity.MEDIUM;

                    // Check if payment was made (look for matching transaction)
                    final boolean paymentFound =
                            paymentMatched(action, dueDate, preFetchedTxs, userId);

                    if (!paymentFound) {
                        alerts.add(
                                new MissedPaymentAlert(
                                        action.getActionId(),
                                        action.getTitle(),
                                        action.getDescription(),
                                        dueDate,
                                        daysOverdue,
                                        AlertType.OVERDUE,
                                        severity,
                                        String.format(
                                                "Payment was due %d days ago and hasn't been paid",
                                                daysOverdue),
                                        null // Amount not available in TransactionActionTable
                                        ));
                    }
                } else if (daysUntilDue <= DAYS_BEFORE_OVERDUE && daysUntilDue >= 0) {
                    // At risk - due soon
                    final boolean paymentFound =
                            paymentMatched(action, dueDate, preFetchedTxs, userId);

                    if (!paymentFound) {
                        alerts.add(
                                new MissedPaymentAlert(
                                        action.getActionId(),
                                        action.getTitle(),
                                        action.getDescription(),
                                        dueDate,
                                        daysUntilDue,
                                        AlertType.AT_RISK,
                                        Severity.MEDIUM,
                                        String.format("Payment due in %d days", daysUntilDue),
                                        null // Amount not available in TransactionActionTable
                                        ));
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Error parsing due date for action {}: {}",
                            action.getActionId(),
                            e.getMessage());
                }
            }
        }

        return alerts;
    }

    /**
     * Dispatch helper used by both the legacy and context-aware paths.
     * When {@code preFetchedTxs} is non-null, slice it in-memory for
     * the ±7-day window around {@code dueDate} (no repo hit). When
     * null, fall back to the legacy per-action repo fetch keyed by
     * {@code userId}.
     */
    private boolean paymentMatched(
            final TransactionActionTable action,
            final LocalDate dueDate,
            final List<TransactionTable> preFetchedTxs,
            final String userId) {
        final LocalDate startDate = dueDate.minusDays(7);
        final LocalDate endDate = dueDate.plusDays(7);
        final List<TransactionTable> windowed;
        if (preFetchedTxs != null) {
            final String startStr = startDate.format(DATE_FORMATTER);
            final String endStr = endDate.format(DATE_FORMATTER);
            windowed = preFetchedTxs.stream()
                    .filter(tx -> tx.getTransactionDate() != null
                            && tx.getTransactionDate().compareTo(startStr) >= 0
                            && tx.getTransactionDate().compareTo(endStr) <= 0)
                    .toList();
        } else {
            windowed = transactionRepository.findByUserIdAndDateRange(
                    userId,
                    startDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER));
        }
        return matchPaymentAgainst(action, windowed);
    }

    /**
     * Title/merchant/description substring match between an action and
     * a window of candidate-payment transactions. Any blank action
     * field is treated as no-signal rather than a wildcard match —
     * otherwise a null description would match every transaction and
     * silently suppress every overdue alert.
     */
    private boolean matchPaymentAgainst(
            final TransactionActionTable action, final List<TransactionTable> transactions) {
        final String actionTitle = lowerOrNull(action.getTitle());
        final String actionDesc = lowerOrNull(action.getDescription());

        if (actionTitle == null && actionDesc == null) {
            return false;
        }

        for (final TransactionTable tx : transactions) {
            final String txDesc =
                    tx.getDescription() != null ? tx.getDescription().toLowerCase(Locale.ROOT) : "";
            final String txMerchant =
                    tx.getMerchantName() != null
                            ? tx.getMerchantName().toLowerCase(Locale.ROOT)
                            : "";

            final boolean titleInDesc = actionTitle != null && txDesc.contains(actionTitle);
            final boolean descInDesc = actionDesc != null && txDesc.contains(actionDesc);
            final boolean titleInMerchant =
                    actionTitle != null && txMerchant.contains(actionTitle);

            if (titleInDesc || descInDesc || titleInMerchant) {
                return true;
            }
        }
        return false;
    }

    private static String lowerOrNull(final String s) {
        if (s == null) {
            return null;
        }
        final String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Detect recurring payment patterns and check for missing payments */
    private List<MissedPaymentAlert> detectRecurringPaymentPatterns(final String userId) {
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(RECURRING_PATTERN_DAYS);

        final String startDateStr = startDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);
        return detectRecurringPaymentPatternsFrom(transactions, endDate);
    }

    /**
     * Same pattern detection as {@link #detectRecurringPaymentPatterns}
     * but operates on a pre-supplied transaction list — used by the
     * /summary path. The caller is responsible for windowing; we
     * filter internally to the {@link #RECURRING_PATTERN_DAYS} window
     * from {@code asOf} so passing a wider list (e.g. the full 365-day
     * context snapshot) is safe.
     */
    private List<MissedPaymentAlert> detectRecurringPaymentPatternsFrom(
            final List<TransactionTable> rawTransactions, final LocalDate asOf) {
        final List<MissedPaymentAlert> alerts = new ArrayList<>();
        final LocalDate cutoff = asOf.minusDays(RECURRING_PATTERN_DAYS);
        final List<TransactionTable> transactions = rawTransactions.stream()
                .filter(tx -> tx.getTransactionDate() != null
                        && tx.getTransactionDate().compareTo(cutoff.toString()) >= 0)
                .toList();
        final LocalDate endDate = asOf;

        // Filter to expense transactions
        final List<TransactionTable> expenses =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .toList();

        // Group by merchant and amount (for recurring bills). Real bills
        // have a stable cents-exact amount; discretionary spending like
        // dining or groceries varies. Pre-validation in audit script showed
        // a $10 rounding bucket grouped unrelated "Dining purchase" rows
        // into a phantom "recurring bill" because they happened to land in
        // the same bucket twice in a 90-day window. Tighten to exact-cents
        // grouping (key contains the full amount), then also reject groups
        // whose amounts vary by more than 5% — a real bill is stable.
        final Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (final TransactionTable tx : expenses) {
            final String merchant = normalizeMerchant(tx);
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            // Discretionary-spending categories never represent bills
            // by themselves. The seed and many real users have many
            // "Dining purchase" / "Groceries purchase" rows that the
            // recurrence math will otherwise treat as bi-weekly bills.
            final String categoryLower = tx.getCategoryPrimary() == null
                    ? ""
                    : tx.getCategoryPrimary().toLowerCase(Locale.ROOT);
            if (DISCRETIONARY_CATEGORIES.contains(categoryLower)) {
                continue;
            }

            final BigDecimal amount = tx.getAmount().abs();
            final String key = merchant.toLowerCase(Locale.ROOT) + "|"
                    + amount.setScale(2, java.math.RoundingMode.HALF_UP);
            byMerchantAndAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        // Find recurring patterns (3+ transactions with similar timing)
        for (final Map.Entry<String, List<TransactionTable>> entry :
                byMerchantAndAmount.entrySet()) {
            final List<TransactionTable> group = entry.getValue();

            if (group.size() < 3) {
                continue; // Need at least 3 transactions to establish pattern
            }

            // Sort by date
            group.sort(Comparator.comparing(TransactionTable::getTransactionDate));

            // Calculate average days between transactions
            final List<Long> intervals = new ArrayList<>();
            for (int i = 0; i < group.size() - 1; i++) {
                final LocalDate date1 =
                        LocalDate.parse(group.get(i).getTransactionDate(), DATE_FORMATTER);
                final LocalDate date2 =
                        LocalDate.parse(group.get(i + 1).getTransactionDate(), DATE_FORMATTER);
                final long days = ChronoUnit.DAYS.between(date1, date2);
                intervals.add(days);
            }

            if (intervals.isEmpty()) {
                continue;
            }

            // Calculate average interval
            final double avgInterval =
                    intervals.stream().mapToLong(Long::longValue).average().orElse(0);

            // Require interval consistency too — a true bill repeats on
            // a tight schedule (variance < 4 days). Without this the
            // detector calls 12 + 16 + 18 day intervals "bi-weekly" and
            // treats one-off bursts as bills.
            final double intervalMean = avgInterval;
            final double intervalVariance = intervals.stream()
                    .mapToDouble(d -> {
                        final double diff = d - intervalMean;
                        return diff * diff;
                    })
                    .average()
                    .orElse(0);
            final double intervalStdDev = Math.sqrt(intervalVariance);
            if (intervalStdDev > 4.0) {
                continue;
            }

            // Check if pattern is monthly (25-35 days) or bi-weekly (12-16 days)
            final boolean isMonthly = avgInterval >= 25 && avgInterval <= 35;
            final boolean isBiWeekly = avgInterval >= 12 && avgInterval <= 16;

            if (isMonthly || isBiWeekly) {
                // Check if next payment is overdue
                final TransactionTable lastPayment = group.getLast();
                final LocalDate lastPaymentDate =
                        LocalDate.parse(lastPayment.getTransactionDate(), DATE_FORMATTER);
                final LocalDate expectedNextPayment = lastPaymentDate.plusDays((long) avgInterval);

                final long daysSinceExpected =
                        ChronoUnit.DAYS.between(expectedNextPayment, endDate);

                if (daysSinceExpected > 7) {
                    // Payment is overdue
                    final String merchant = normalizeMerchant(lastPayment);
                    final BigDecimal amount = lastPayment.getAmount().abs();

                    alerts.add(
                            new MissedPaymentAlert(
                                    null, // No action ID for pattern-based detection
                                    merchant,
                                    String.format(
                                            "Recurring %s payment",
                                            isMonthly ? "monthly" : "bi-weekly"),
                                    expectedNextPayment,
                                    daysSinceExpected,
                                    AlertType.PATTERN_BREAK,
                                    Severity.MEDIUM,
                                    String.format(
                                            "Expected %s payment of $%.2f was due %d days ago",
                                            isMonthly ? "monthly" : "bi-weekly",
                                            amount.doubleValue(),
                                            daysSinceExpected),
                                    amount));
                }
            }
        }

        return alerts;
    }

    /** Normalize merchant name from transaction */
    private String normalizeMerchant(final TransactionTable tx) {
        if (tx.getMerchantName() != null && !tx.getMerchantName().isBlank()) {
            return tx.getMerchantName().trim();
        }
        if (tx.getDescription() != null && !tx.getDescription().isBlank()) {
            final String desc = tx.getDescription().trim();
            // Extract merchant from description (first part before common separators)
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

    public static class MissedPaymentAlert {
        private final String actionId;
        private final String title;
        private final String description;
        private final LocalDate dueDate;
        private final long daysOverdue;
        private final AlertType type;
        private final Severity severity;
        private final String message;
        private final BigDecimal amount;

        public MissedPaymentAlert(
                final String actionId,
                final String title,
                final String description,
                final LocalDate dueDate,
                final long daysOverdue,
                final AlertType type,
                final Severity severity,
                final String message,
                final BigDecimal amount) {
            this.actionId = actionId;
            this.title = title;
            this.description = description;
            this.dueDate = dueDate;
            this.daysOverdue = daysOverdue;
            this.type = type;
            this.severity = severity;
            this.message = message;
            this.amount = amount;
        }

        public String getActionId() {
            return actionId;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public long getDaysOverdue() {
            return daysOverdue;
        }

        public AlertType getType() {
            return type;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    public enum AlertType {
        OVERDUE,
        AT_RISK,
        PATTERN_BREAK
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
