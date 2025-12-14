package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for detecting and managing subscriptions
 * Identifies recurring transactions based on amount, merchant, and date patterns
 */
@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final SubscriptionRepository subscriptionRepository;
    private final TransactionRepository transactionRepository;

    public SubscriptionService(
            final SubscriptionRepository subscriptionRepository,
            final TransactionRepository transactionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Detects subscriptions from user's transactions
     * Groups transactions by merchant and amount, then identifies recurring patterns
     */
    public List<Subscription> detectSubscriptions(final String userId) {
        logger.info("Detecting subscriptions for user: {}", userId);
        
        // Get all transactions for the user
        List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 10000);
        
        // Filter to subscription category transactions and group by merchant
        Map<String, List<TransactionTable>> transactionsByMerchant = transactions.stream()
                .filter(tx -> {
                    String category = tx.getCategoryPrimary() != null ? tx.getCategoryPrimary() : tx.getCategoryDetailed();
                    // Include transactions with subscription category OR subscription keywords in description
                    boolean isSubscriptionCategory = "subscriptions".equalsIgnoreCase(category);
                    boolean hasSubscriptionKeyword = tx.getDescription() != null && isSubscriptionKeyword(tx.getDescription());
                    return isSubscriptionCategory || hasSubscriptionKeyword;
                })
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0) // Only expenses
                .collect(Collectors.groupingBy(tx -> normalizeMerchantName(tx)));
        
        List<Subscription> detectedSubscriptions = new ArrayList<>();
        
        for (Map.Entry<String, List<TransactionTable>> entry : transactionsByMerchant.entrySet()) {
            String merchant = entry.getKey();
            List<TransactionTable> merchantTransactions = entry.getValue();
            
            if (merchantTransactions.size() < 2) {
                continue; // Need at least 2 transactions to detect a pattern
            }
            
            // Group by amount (within 5% tolerance)
            Map<BigDecimal, List<TransactionTable>> transactionsByAmount = groupByAmount(merchantTransactions);
            
            for (Map.Entry<BigDecimal, List<TransactionTable>> amountEntry : transactionsByAmount.entrySet()) {
                BigDecimal amount = amountEntry.getKey();
                List<TransactionTable> sameAmountTransactions = amountEntry.getValue();
                
                if (sameAmountTransactions.size() < 2) {
                    continue;
                }
                
                // Sort by date
                sameAmountTransactions.sort((a, b) -> {
                    LocalDate dateA = parseDate(a.getTransactionDate());
                    LocalDate dateB = parseDate(b.getTransactionDate());
                    if (dateA == null || dateB == null) {
                        return 0;
                    }
                    return dateA.compareTo(dateB);
                });
                
                // Detect frequency
                Subscription.SubscriptionFrequency frequency = detectFrequency(sameAmountTransactions);
                
                if (frequency != null) {
                    TransactionTable firstTransaction = sameAmountTransactions.get(0);
                    LocalDate startDate = parseDate(firstTransaction.getTransactionDate());
                    
                    if (startDate != null) {
                        Subscription subscription = new Subscription();
                        subscription.setSubscriptionId(IdGenerator.generateSubscriptionId(userId, merchant, amount));
                        subscription.setUserId(userId);
                        subscription.setAccountId(firstTransaction.getAccountId());
                        subscription.setMerchantName(merchant);
                        subscription.setDescription(firstTransaction.getDescription());
                        subscription.setAmount(amount.abs()); // Store as positive
                        subscription.setFrequency(frequency);
                        subscription.setStartDate(startDate);
                        subscription.setCategory("subscriptions");
                        subscription.setActive(true);
                        subscription.setPlaidTransactionId(firstTransaction.getPlaidTransactionId());
                        
                        // Set last payment date
                        TransactionTable lastTransaction = sameAmountTransactions.get(sameAmountTransactions.size() - 1);
                        LocalDate lastPaymentDate = parseDate(lastTransaction.getTransactionDate());
                        if (lastPaymentDate != null) {
                            subscription.setLastPaymentDate(lastPaymentDate);
                            subscription.recordPayment(lastPaymentDate);
                        }
                        
                        detectedSubscriptions.add(subscription);
                        logger.info("Detected subscription: {} - {} - {} ({})", 
                                merchant, amount, frequency, startDate);
                    }
                }
            }
        }
        
        logger.info("Detected {} subscriptions for user: {}", detectedSubscriptions.size(), userId);
        return detectedSubscriptions;
    }

    /**
     * Saves or updates subscriptions for a user
     */
    public void saveSubscriptions(final String userId, final List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            SubscriptionTable table = toSubscriptionTable(subscription);
            subscriptionRepository.save(table);
        }
        logger.info("Saved {} subscriptions for user: {}", subscriptions.size(), userId);
    }

    /**
     * Gets all subscriptions for a user
     */
    public List<Subscription> getSubscriptions(final String userId) {
        List<SubscriptionTable> tables = subscriptionRepository.findByUserId(userId);
        return tables.stream()
                .map(this::toSubscription)
                .collect(Collectors.toList());
    }

    /**
     * Gets active subscriptions for a user
     */
    public List<Subscription> getActiveSubscriptions(final String userId) {
        List<SubscriptionTable> tables = subscriptionRepository.findActiveByUserId(userId);
        return tables.stream()
                .map(this::toSubscription)
                .collect(Collectors.toList());
    }

    /**
     * Deletes a subscription
     */
    public void deleteSubscription(final String subscriptionId) {
        subscriptionRepository.delete(subscriptionId);
        logger.info("Deleted subscription: {}", subscriptionId);
    }

    /**
     * Detects frequency from transaction dates
     */
    private Subscription.SubscriptionFrequency detectFrequency(final List<TransactionTable> transactions) {
        if (transactions.size() < 2) {
            return null;
        }
        
        List<LocalDate> dates = transactions.stream()
                .map(tx -> parseDate(tx.getTransactionDate()))
                .filter(date -> date != null)
                .sorted()
                .collect(Collectors.toList());
        
        if (dates.size() < 2) {
            return null;
        }
        
        // Calculate average days between transactions
        long totalDays = 0;
        int intervals = 0;
        for (int i = 1; i < dates.size(); i++) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            totalDays += days;
            intervals++;
        }
        
        if (intervals == 0) {
            return null;
        }
        
        double averageDays = (double) totalDays / intervals;
        
        // Determine frequency based on average days
        if (averageDays >= 25 && averageDays <= 35) {
            return Subscription.SubscriptionFrequency.MONTHLY;
        } else if (averageDays >= 85 && averageDays <= 95) {
            return Subscription.SubscriptionFrequency.QUARTERLY;
        } else if (averageDays >= 175 && averageDays <= 185) {
            return Subscription.SubscriptionFrequency.SEMI_ANNUAL;
        } else if (averageDays >= 360 && averageDays <= 370) {
            return Subscription.SubscriptionFrequency.ANNUAL;
        }
        
        return null;
    }

    /**
     * Groups transactions by amount (within 5% tolerance)
     */
    private Map<BigDecimal, List<TransactionTable>> groupByAmount(final List<TransactionTable> transactions) {
        Map<BigDecimal, List<TransactionTable>> grouped = new HashMap<>();
        
        for (TransactionTable tx : transactions) {
            BigDecimal amount = tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO;
            
            // Find existing group within 5% tolerance
            BigDecimal matchingAmount = null;
            for (BigDecimal existingAmount : grouped.keySet()) {
                BigDecimal difference = amount.subtract(existingAmount).abs();
                BigDecimal tolerance = existingAmount.multiply(new BigDecimal("0.05"));
                if (difference.compareTo(tolerance) <= 0) {
                    matchingAmount = existingAmount;
                    break;
                }
            }
            
            if (matchingAmount != null) {
                grouped.get(matchingAmount).add(tx);
            } else {
                grouped.put(amount, new ArrayList<>(List.of(tx)));
            }
        }
        
        return grouped;
    }

    /**
     * Normalizes merchant name for grouping
     */
    private String normalizeMerchantName(final TransactionTable transaction) {
        String merchant = transaction.getMerchantName();
        if (merchant == null || merchant.isEmpty()) {
            merchant = transaction.getDescription();
        }
        if (merchant == null) {
            return "Unknown";
        }
        return merchant.toLowerCase().trim();
    }

    /**
     * Checks if description contains subscription keywords
     */
    private boolean isSubscriptionKeyword(final String description) {
        if (description == null) {
            return false;
        }
        String lower = description.toLowerCase();
        return lower.contains("subscription") || lower.contains("monthly") ||
               lower.contains("annual") || lower.contains("recurring");
    }

    /**
     * Parses date string to LocalDate
     */
    private LocalDate parseDate(final String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString, DATE_FORMATTER);
        } catch (Exception e) {
            logger.debug("Failed to parse date: {}", dateString);
            return null;
        }
    }

    /**
     * Converts Subscription to SubscriptionTable
     */
    private SubscriptionTable toSubscriptionTable(final Subscription subscription) {
        SubscriptionTable table = new SubscriptionTable();
        table.setSubscriptionId(subscription.getSubscriptionId());
        table.setUserId(subscription.getUserId());
        table.setAccountId(subscription.getAccountId());
        table.setMerchantName(subscription.getMerchantName());
        table.setDescription(subscription.getDescription());
        table.setAmount(subscription.getAmount());
        table.setFrequency(subscription.getFrequency() != null ? subscription.getFrequency().name() : null);
        table.setStartDate(subscription.getStartDate() != null ? subscription.getStartDate().format(DATE_FORMATTER) : null);
        table.setNextPaymentDate(subscription.getNextPaymentDate() != null ? subscription.getNextPaymentDate().format(DATE_FORMATTER) : null);
        table.setLastPaymentDate(subscription.getLastPaymentDate() != null ? subscription.getLastPaymentDate().format(DATE_FORMATTER) : null);
        table.setCategory(subscription.getCategory());
        table.setActive(subscription.getActive());
        table.setPlaidTransactionId(subscription.getPlaidTransactionId());
        if (subscription.getCreatedAt() != null) {
            table.setCreatedAt(subscription.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant());
        } else {
            table.setCreatedAt(java.time.Instant.now());
        }
        table.setUpdatedAt(java.time.Instant.now());
        return table;
    }

    /**
     * Converts SubscriptionTable to Subscription
     */
    private Subscription toSubscription(final SubscriptionTable table) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(table.getSubscriptionId());
        subscription.setUserId(table.getUserId());
        subscription.setAccountId(table.getAccountId());
        subscription.setMerchantName(table.getMerchantName());
        subscription.setDescription(table.getDescription());
        subscription.setAmount(table.getAmount());
        
        if (table.getFrequency() != null) {
            try {
                subscription.setFrequency(Subscription.SubscriptionFrequency.valueOf(table.getFrequency()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid frequency: {}", table.getFrequency());
            }
        }
        
        subscription.setStartDate(parseDate(table.getStartDate()));
        subscription.setNextPaymentDate(parseDate(table.getNextPaymentDate()));
        subscription.setLastPaymentDate(parseDate(table.getLastPaymentDate()));
        subscription.setCategory(table.getCategory());
        subscription.setActive(table.getActive());
        subscription.setPlaidTransactionId(table.getPlaidTransactionId());
        
        if (table.getCreatedAt() != null) {
            subscription.setCreatedAt(java.time.LocalDateTime.ofInstant(table.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (table.getUpdatedAt() != null) {
            subscription.setUpdatedAt(java.time.LocalDateTime.ofInstant(table.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        
        return subscription;
    }
}

