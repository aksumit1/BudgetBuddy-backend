package com.budgetbuddy.scripts;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.service.SubscriptionService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Subscription Scanner Script
 *
 * <p>Scans and analyzes subscriptions in the database to identify issues.
 *
 * <p>Usage: mvn spring-boot:run -Dspring-boot.run.arguments="--scan-subscriptions" OR java -jar
 * target/budgetbuddy-backend.jar --scan-subscriptions
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class SubscriptionScanner implements CommandLineRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionScanner.class);

    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private SubscriptionService subscriptionService;

    public static void main(final String[] args) {
        SpringApplication.run(SubscriptionScanner.class, args);
    }

    @Override
    public void run(final String... args) {
        if (args.length == 0 || !"--scan-subscriptions".equals(args[0])) {
            LOGGER.info("To scan subscriptions, use: --scan-subscriptions");
            return;
        }

        LOGGER.info("========================================");
        LOGGER.info("BudgetBuddy Subscription Scanner");
        LOGGER.info("========================================");
        LOGGER.info("");

        scanSubscriptions();

        LOGGER.info("");
        LOGGER.info("========================================");
        LOGGER.info("Scan complete!");
        LOGGER.info("========================================");
    }

    private void scanSubscriptions() {
        LOGGER.info("📊 SCANNING SUBSCRIPTIONS");
        LOGGER.info("----------------------------------------");

        try {
            // Get all subscriptions from repository
            final List<SubscriptionTable> allSubscriptions = new ArrayList<>();

            // Scan by getting all unique user IDs first (if possible)
            // For now, we'll need to scan the table
            // In production, you might want to maintain a user ID list

            // Get subscriptions for a sample user (you can modify this)
            // For full scan, you'd need to iterate through all users

            LOGGER.info("Scanning subscription table...");

            // Try to get subscriptions for known users
            // This is a simplified version - in production, you'd scan all users
            final Set<String> userIds = new HashSet<>();

            // For demonstration, we'll show how to analyze subscriptions
            // In practice, you'd need to get all user IDs first

            LOGGER.info("Note: Full scan requires iterating through all users");
            LOGGER.info("For now, showing analysis structure");

            // Example: Analyze subscriptions if we have a user ID
            // String testUserId = "your-user-id-here";
            // analyzeSubscriptionsForUser(testUserId);

            LOGGER.info("✅ Subscription scanner ready");
            LOGGER.info("To scan specific user, modify the script with a user ID");

        } catch (Exception e) {
            LOGGER.error("Error scanning subscriptions: {}", e.getMessage(), e);
        }
    }

    private void analyzeSubscriptionsForUser(final String userId) {
        LOGGER.info("Analyzing subscriptions for user: {}", userId);

        final List<Subscription> allSubscriptions = subscriptionService.getSubscriptions(userId);
        final List<Subscription> activeSubscriptions = subscriptionService.getActiveSubscriptions(userId);

        LOGGER.info("Total Subscriptions: {}", allSubscriptions.size());
        LOGGER.info("Active Subscriptions: {}", activeSubscriptions.size());
        LOGGER.info(
                "Inactive Subscriptions: {}", allSubscriptions.size() - activeSubscriptions.size());

        final LocalDate now = LocalDate.now();

        // Analyze by merchant
        final Map<String, Long> byMerchant =
                allSubscriptions.stream()
                        .collect(
                                Collectors.groupingBy(
                                        sub ->
                                                sub.getMerchantName() != null
                                                        ? sub.getMerchantName()
                                                        : "Unknown",
                                        Collectors.counting()));

        LOGGER.info("\nSubscriptions by Merchant:");
        byMerchant.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> LOGGER.info("  {}: {}", entry.getKey(), entry.getValue()));

        // Analyze by frequency
        final Map<String, Long> byFrequency =
                allSubscriptions.stream()
                        .collect(
                                Collectors.groupingBy(
                                        sub ->
                                                sub.getFrequency() != null
                                                        ? sub.getFrequency().name()
                                                        : "Unknown",
                                        Collectors.counting()));

        LOGGER.info("\nSubscriptions by Frequency:");
        byFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> LOGGER.info("  {}: {}", entry.getKey(), entry.getValue()));

        // Find overdue subscriptions
        final List<Subscription> overdue =
                allSubscriptions.stream()
                        .filter(
                                sub -> {
                                    if (sub.getNextPaymentDate() == null) {
                                        return false;
                                    }
                                    return sub.getNextPaymentDate().isBefore(now);
                                })
                        .collect(Collectors.toList());

        if (!overdue.isEmpty()) {
            LOGGER.warn("\n⚠️  Overdue Subscriptions ({}):", overdue.size());
            overdue.forEach(
                    sub -> {
                        final long daysOverdue =
                                java.time.temporal.ChronoUnit.DAYS.between(
                                        sub.getNextPaymentDate(), now);
                        LOGGER.warn(
                                "  {} - {} days overdue (Next: {}, Active: {})",
                                sub.getMerchantName(),
                                daysOverdue,
                                sub.getNextPaymentDate(),
                                sub.getActive());
                    });
        }

        // Find subscriptions with missing required fields
        final List<Subscription> missingFields =
                allSubscriptions.stream()
                        .filter(
                                sub ->
                                        sub.getNextPaymentDate() == null
                                                || sub.getStartDate() == null
                                                || sub.getFrequency() == null)
                        .collect(Collectors.toList());

        if (!missingFields.isEmpty()) {
            LOGGER.warn("\n⚠️  Subscriptions with Missing Fields ({}):", missingFields.size());
            missingFields.forEach(
                    sub -> {
                        final List<String> missing = new ArrayList<>();
                        if (sub.getNextPaymentDate() == null) {
                            missing.add("nextPaymentDate");
                        }
                        if (sub.getStartDate() == null) {
                            missing.add("startDate");
                        }
                        if (sub.getFrequency() == null) {
                            missing.add("frequency");
                        }
                        LOGGER.warn(
                                "  {} - Missing: {}",
                                sub.getMerchantName(),
                                String.join(", ", missing));
                    });
        }

        // Show sample subscriptions
        LOGGER.info("\nSample Subscriptions (First 5):");
        allSubscriptions.stream()
                .limit(5)
                .forEach(
                        sub -> {
                            LOGGER.info("  Merchant: {}", sub.getMerchantName());
                            LOGGER.info("    Amount: {}", sub.getAmount());
                            LOGGER.info("    Frequency: {}", sub.getFrequency());
                            LOGGER.info("    Active: {}", sub.getActive());
                            LOGGER.info("    Start Date: {}", sub.getStartDate());
                            LOGGER.info("    Next Payment: {}", sub.getNextPaymentDate());
                            LOGGER.info("    Last Payment: {}", sub.getLastPaymentDate());
                            LOGGER.info("");
                        });
    }
}
