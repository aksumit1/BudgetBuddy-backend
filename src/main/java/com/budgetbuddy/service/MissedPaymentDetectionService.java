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

    private final TransactionActionRepository actionRepository;
    private final TransactionRepository transactionRepository;

    public MissedPaymentDetectionService(
            final TransactionActionRepository actionRepository,
            final TransactionRepository transactionRepository) {
        this.actionRepository = actionRepository;
        this.transactionRepository = transactionRepository;
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
                .collect(Collectors.toList());
    }

    /** Check TransactionActions for overdue or at-risk payments */
    private List<MissedPaymentAlert> checkTransactionActions(final String userId) {
        final List<MissedPaymentAlert> alerts = new ArrayList<>();

        final List<TransactionActionTable> actions = actionRepository.findByUserId(userId);
        final LocalDate today = LocalDate.now();

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
                    final boolean paymentFound = checkPaymentMade(userId, action, dueDate);

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
                    final boolean paymentFound = checkPaymentMade(userId, action, dueDate);

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

    /** Check if payment was made by looking for matching transaction */
    private boolean checkPaymentMade(
            final String userId, final TransactionActionTable action, final LocalDate dueDate) {

        // Look for transactions around due date (±7 days)
        final LocalDate startDate = dueDate.minusDays(7);
        final LocalDate endDate = dueDate.plusDays(7);

        final String startDateStr = startDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Check if any transaction matches the action
        final String actionTitle =
                action.getTitle() != null ? action.getTitle().toLowerCase(Locale.ROOT) : "";
        final String actionDesc =
                action.getDescription() != null
                        ? action.getDescription().toLowerCase(Locale.ROOT)
                        : "";
        final BigDecimal actionAmount = null; // Amount not available in TransactionActionTable

        for (final TransactionTable tx : transactions) {
            final String txDesc =
                    tx.getDescription() != null ? tx.getDescription().toLowerCase(Locale.ROOT) : "";
            final String txMerchant =
                    tx.getMerchantName() != null
                            ? tx.getMerchantName().toLowerCase(Locale.ROOT)
                            : "";
            final BigDecimal txAmount = tx.getAmount() != null ? tx.getAmount().abs() : null;

            // Check if description or merchant matches
            final boolean descriptionMatches =
                    txDesc.contains(actionTitle)
                            || txDesc.contains(actionDesc)
                            || txMerchant.contains(actionTitle);

            // Check if amount matches (within $5 tolerance)
            final boolean amountMatches =
                    actionAmount != null
                            && txAmount != null
                            && actionAmount
                                            .subtract(txAmount)
                                            .abs()
                                            .compareTo(BigDecimal.valueOf(5))
                                    <= 0;

            if (descriptionMatches && (amountMatches || actionAmount == null)) {
                return true; // Payment found
            }
        }

        return false; // Payment not found
    }

    /** Detect recurring payment patterns and check for missing payments */
    private List<MissedPaymentAlert> detectRecurringPaymentPatterns(final String userId) {
        final List<MissedPaymentAlert> alerts = new ArrayList<>();

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(RECURRING_PATTERN_DAYS);

        final String startDateStr = startDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Filter to expense transactions
        final List<TransactionTable> expenses =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .collect(Collectors.toList());

        // Group by merchant and amount (for recurring bills)
        final Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (final TransactionTable tx : expenses) {
            final String merchant = normalizeMerchant(tx);
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            final BigDecimal amount = tx.getAmount().abs();
            // Round to nearest $10 for grouping
            final BigDecimal roundedAmount =
                    amount.divide(BigDecimal.valueOf(10), 0, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(10));

            final String key = merchant.toLowerCase(Locale.ROOT) + "|" + roundedAmount;
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

            // Check if pattern is monthly (25-35 days) or bi-weekly (12-16 days)
            final boolean isMonthly = avgInterval >= 25 && avgInterval <= 35;
            final boolean isBiWeekly = avgInterval >= 12 && avgInterval <= 16;

            if (isMonthly || isBiWeekly) {
                // Check if next payment is overdue
                final TransactionTable lastPayment = group.get(group.size() - 1);
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
