package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Missed Payment Detection Service
 * 
 * Identifies bills and payments that were due but not paid.
 */
@Service
public class MissedPaymentDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(MissedPaymentDetectionService.class);
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

    /**
     * Detect missed payments for a user
     */
    public List<MissedPaymentAlert> detectMissedPayments(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Detecting missed payments for user: {}", userId);

        List<MissedPaymentAlert> alerts = new ArrayList<>();

        // 1. Check TransactionActions with due dates
        alerts.addAll(checkTransactionActions(userId));

        // 2. Detect recurring payment patterns
        alerts.addAll(detectRecurringPaymentPatterns(userId));

        // Sort by severity and due date
        return alerts.stream()
                .sorted(Comparator
                        .comparing((MissedPaymentAlert a) -> a.getSeverity().ordinal())
                        .reversed()
                        .thenComparing(MissedPaymentAlert::getDueDate))
                .collect(Collectors.toList());
    }

    /**
     * Check TransactionActions for overdue or at-risk payments
     */
    private List<MissedPaymentAlert> checkTransactionActions(final String userId) {
        List<MissedPaymentAlert> alerts = new ArrayList<>();

        List<TransactionActionTable> actions = actionRepository.findByUserId(userId);
        LocalDate today = LocalDate.now();

        for (TransactionActionTable action : actions) {
            // Skip if already completed
            if (action.getIsCompleted() != null && action.getIsCompleted()) {
                continue;
            }

            String dueDateStr = action.getDueDate();
            if (dueDateStr == null || dueDateStr.isEmpty()) {
                continue;
            }

            try {
                LocalDate dueDate = LocalDate.parse(dueDateStr, DATE_FORMATTER);
                long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
                long daysOverdue = -daysUntilDue; // Negative if overdue

                if (daysOverdue > 0) {
                    // Overdue payment
                    Severity severity = daysOverdue > 7 ? Severity.HIGH : Severity.MEDIUM;
                    
                    // Check if payment was made (look for matching transaction)
                    boolean paymentFound = checkPaymentMade(userId, action, dueDate);

                    if (!paymentFound) {
                    alerts.add(new MissedPaymentAlert(
                            action.getActionId(),
                            action.getTitle(),
                            action.getDescription(),
                            dueDate,
                            daysOverdue,
                            AlertType.OVERDUE,
                            severity,
                            String.format("Payment was due %d days ago and hasn't been paid", daysOverdue),
                            null // Amount not available in TransactionActionTable
                    ));
                    }
                } else if (daysUntilDue <= DAYS_BEFORE_OVERDUE && daysUntilDue >= 0) {
                    // At risk - due soon
                    boolean paymentFound = checkPaymentMade(userId, action, dueDate);

                    if (!paymentFound) {
                        alerts.add(new MissedPaymentAlert(
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
                logger.warn("Error parsing due date for action {}: {}", action.getActionId(), e.getMessage());
            }
        }

        return alerts;
    }

    /**
     * Check if payment was made by looking for matching transaction
     */
    private boolean checkPaymentMade(
            final String userId,
            final TransactionActionTable action,
            final LocalDate dueDate) {
        
        // Look for transactions around due date (±7 days)
        LocalDate startDate = dueDate.minusDays(7);
        LocalDate endDate = dueDate.plusDays(7);

        String startDateStr = startDate.format(DATE_FORMATTER);
        String endDateStr = endDate.format(DATE_FORMATTER);

        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

            // Check if any transaction matches the action
            String actionTitle = action.getTitle() != null ? action.getTitle().toLowerCase() : "";
            String actionDesc = action.getDescription() != null ? action.getDescription().toLowerCase() : "";
            BigDecimal actionAmount = null; // Amount not available in TransactionActionTable

        for (TransactionTable tx : transactions) {
            String txDesc = tx.getDescription() != null ? tx.getDescription().toLowerCase() : "";
            String txMerchant = tx.getMerchantName() != null ? tx.getMerchantName().toLowerCase() : "";
            BigDecimal txAmount = tx.getAmount() != null ? tx.getAmount().abs() : null;

            // Check if description or merchant matches
            boolean descriptionMatches = txDesc.contains(actionTitle) || 
                                       txDesc.contains(actionDesc) ||
                                       txMerchant.contains(actionTitle);

            // Check if amount matches (within $5 tolerance)
            boolean amountMatches = actionAmount != null && txAmount != null &&
                    actionAmount.subtract(txAmount).abs().compareTo(BigDecimal.valueOf(5)) <= 0;

            if (descriptionMatches && (amountMatches || actionAmount == null)) {
                return true; // Payment found
            }
        }

        return false; // Payment not found
    }

    /**
     * Detect recurring payment patterns and check for missing payments
     */
    private List<MissedPaymentAlert> detectRecurringPaymentPatterns(final String userId) {
        List<MissedPaymentAlert> alerts = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(RECURRING_PATTERN_DAYS);

        String startDateStr = startDate.format(DATE_FORMATTER);
        String endDateStr = endDate.format(DATE_FORMATTER);

        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Filter to expense transactions
        List<TransactionTable> expenses = transactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                .collect(Collectors.toList());

        // Group by merchant and amount (for recurring bills)
        Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (TransactionTable tx : expenses) {
            String merchant = normalizeMerchant(tx);
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            BigDecimal amount = tx.getAmount().abs();
            // Round to nearest $10 for grouping
            BigDecimal roundedAmount = amount.divide(BigDecimal.valueOf(10), 0, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(10));
            
            String key = merchant.toLowerCase() + "|" + roundedAmount;
            byMerchantAndAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        // Find recurring patterns (3+ transactions with similar timing)
        for (Map.Entry<String, List<TransactionTable>> entry : byMerchantAndAmount.entrySet()) {
            List<TransactionTable> group = entry.getValue();
            
            if (group.size() < 3) {
                continue; // Need at least 3 transactions to establish pattern
            }

            // Sort by date
            group.sort(Comparator.comparing(TransactionTable::getTransactionDate));

            // Calculate average days between transactions
            List<Long> intervals = new ArrayList<>();
            for (int i = 0; i < group.size() - 1; i++) {
                LocalDate date1 = LocalDate.parse(group.get(i).getTransactionDate(), DATE_FORMATTER);
                LocalDate date2 = LocalDate.parse(group.get(i + 1).getTransactionDate(), DATE_FORMATTER);
                long days = ChronoUnit.DAYS.between(date1, date2);
                intervals.add(days);
            }

            if (intervals.isEmpty()) {
                continue;
            }

            // Calculate average interval
            double avgInterval = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            
            // Check if pattern is monthly (25-35 days) or bi-weekly (12-16 days)
            boolean isMonthly = avgInterval >= 25 && avgInterval <= 35;
            boolean isBiWeekly = avgInterval >= 12 && avgInterval <= 16;

            if (isMonthly || isBiWeekly) {
                // Check if next payment is overdue
                TransactionTable lastPayment = group.get(group.size() - 1);
                LocalDate lastPaymentDate = LocalDate.parse(lastPayment.getTransactionDate(), DATE_FORMATTER);
                LocalDate expectedNextPayment = lastPaymentDate.plusDays((long) avgInterval);
                
                long daysSinceExpected = ChronoUnit.DAYS.between(expectedNextPayment, endDate);

                if (daysSinceExpected > 7) {
                    // Payment is overdue
                    String merchant = normalizeMerchant(lastPayment);
                    BigDecimal amount = lastPayment.getAmount().abs();

                    alerts.add(new MissedPaymentAlert(
                            null, // No action ID for pattern-based detection
                            merchant,
                            String.format("Recurring %s payment", isMonthly ? "monthly" : "bi-weekly"),
                            expectedNextPayment,
                            daysSinceExpected,
                            AlertType.PATTERN_BREAK,
                            Severity.MEDIUM,
                            String.format("Expected %s payment of $%.2f was due %d days ago",
                                    isMonthly ? "monthly" : "bi-weekly", amount.doubleValue(), daysSinceExpected),
                            amount
                    ));
                }
            }
        }

        return alerts;
    }

    /**
     * Normalize merchant name from transaction
     */
    private String normalizeMerchant(final TransactionTable tx) {
        if (tx.getMerchantName() != null && !tx.getMerchantName().trim().isEmpty()) {
            return tx.getMerchantName().trim();
        }
        if (tx.getDescription() != null && !tx.getDescription().trim().isEmpty()) {
            String desc = tx.getDescription().trim();
            // Extract merchant from description (first part before common separators)
            String[] separators = {" - ", " | ", " @ ", " # "};
            for (String sep : separators) {
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

        public String getActionId() { return actionId; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public LocalDate getDueDate() { return dueDate; }
        public long getDaysOverdue() { return daysOverdue; }
        public AlertType getType() { return type; }
        public Severity getSeverity() { return severity; }
        public String getMessage() { return message; }
        public BigDecimal getAmount() { return amount; }
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
