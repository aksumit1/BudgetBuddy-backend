package com.budgetbuddy.scripts;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Subscription Scanner Script
 * 
 * Scans and analyzes subscriptions in the database to identify issues.
 * 
 * Usage:
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--scan-subscriptions"
 *   OR
 *   java -jar target/budgetbuddy-backend.jar --scan-subscriptions
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class SubscriptionScanner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionScanner.class);

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionScanner.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("--scan-subscriptions")) {
            logger.info("To scan subscriptions, use: --scan-subscriptions");
            return;
        }

        logger.info("========================================");
        logger.info("BudgetBuddy Subscription Scanner");
        logger.info("========================================");
        logger.info("");

        scanSubscriptions();

        logger.info("");
        logger.info("========================================");
        logger.info("Scan complete!");
        logger.info("========================================");
    }

    private void scanSubscriptions() {
        logger.info("📊 SCANNING SUBSCRIPTIONS");
        logger.info("----------------------------------------");

        try {
            // Get all subscriptions from repository
            List<SubscriptionTable> allSubscriptions = new ArrayList<>();
            
            // Scan by getting all unique user IDs first (if possible)
            // For now, we'll need to scan the table
            // In production, you might want to maintain a user ID list
            
            // Get subscriptions for a sample user (you can modify this)
            // For full scan, you'd need to iterate through all users
            
            logger.info("Scanning subscription table...");
            
            // Try to get subscriptions for known users
            // This is a simplified version - in production, you'd scan all users
            Set<String> userIds = new HashSet<>();
            
            // For demonstration, we'll show how to analyze subscriptions
            // In practice, you'd need to get all user IDs first
            
            logger.info("Note: Full scan requires iterating through all users");
            logger.info("For now, showing analysis structure");
            
            // Example: Analyze subscriptions if we have a user ID
            // String testUserId = "your-user-id-here";
            // analyzeSubscriptionsForUser(testUserId);
            
            logger.info("✅ Subscription scanner ready");
            logger.info("To scan specific user, modify the script with a user ID");
            
        } catch (Exception e) {
            logger.error("Error scanning subscriptions: {}", e.getMessage(), e);
        }
    }

    private void analyzeSubscriptionsForUser(String userId) {
        logger.info("Analyzing subscriptions for user: {}", userId);
        
        List<Subscription> allSubscriptions = subscriptionService.getSubscriptions(userId);
        List<Subscription> activeSubscriptions = subscriptionService.getActiveSubscriptions(userId);
        
        logger.info("Total Subscriptions: {}", allSubscriptions.size());
        logger.info("Active Subscriptions: {}", activeSubscriptions.size());
        logger.info("Inactive Subscriptions: {}", allSubscriptions.size() - activeSubscriptions.size());
        
        LocalDate now = LocalDate.now();
        
        // Analyze by merchant
        Map<String, Long> byMerchant = allSubscriptions.stream()
                .collect(Collectors.groupingBy(
                    sub -> sub.getMerchantName() != null ? sub.getMerchantName() : "Unknown",
                    Collectors.counting()
                ));
        
        logger.info("\nSubscriptions by Merchant:");
        byMerchant.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> logger.info("  {}: {}", entry.getKey(), entry.getValue()));
        
        // Analyze by frequency
        Map<String, Long> byFrequency = allSubscriptions.stream()
                .collect(Collectors.groupingBy(
                    sub -> sub.getFrequency() != null ? sub.getFrequency().name() : "Unknown",
                    Collectors.counting()
                ));
        
        logger.info("\nSubscriptions by Frequency:");
        byFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> logger.info("  {}: {}", entry.getKey(), entry.getValue()));
        
        // Find overdue subscriptions
        List<Subscription> overdue = allSubscriptions.stream()
                .filter(sub -> {
                    if (sub.getNextPaymentDate() == null) return false;
                    return sub.getNextPaymentDate().isBefore(now);
                })
                .collect(Collectors.toList());
        
        if (!overdue.isEmpty()) {
            logger.warn("\n⚠️  Overdue Subscriptions ({}):", overdue.size());
            overdue.forEach(sub -> {
                long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(
                    sub.getNextPaymentDate(), now);
                logger.warn("  {} - {} days overdue (Next: {}, Active: {})",
                    sub.getMerchantName(),
                    daysOverdue,
                    sub.getNextPaymentDate(),
                    sub.getActive());
            });
        }
        
        // Find subscriptions with missing required fields
        List<Subscription> missingFields = allSubscriptions.stream()
                .filter(sub -> 
                    sub.getNextPaymentDate() == null ||
                    sub.getStartDate() == null ||
                    sub.getFrequency() == null
                )
                .collect(Collectors.toList());
        
        if (!missingFields.isEmpty()) {
            logger.warn("\n⚠️  Subscriptions with Missing Fields ({}):", missingFields.size());
            missingFields.forEach(sub -> {
                List<String> missing = new ArrayList<>();
                if (sub.getNextPaymentDate() == null) missing.add("nextPaymentDate");
                if (sub.getStartDate() == null) missing.add("startDate");
                if (sub.getFrequency() == null) missing.add("frequency");
                logger.warn("  {} - Missing: {}", sub.getMerchantName(), String.join(", ", missing));
            });
        }
        
        // Show sample subscriptions
        logger.info("\nSample Subscriptions (First 5):");
        allSubscriptions.stream()
                .limit(5)
                .forEach(sub -> {
                    logger.info("  Merchant: {}", sub.getMerchantName());
                    logger.info("    Amount: {}", sub.getAmount());
                    logger.info("    Frequency: {}", sub.getFrequency());
                    logger.info("    Active: {}", sub.getActive());
                    logger.info("    Start Date: {}", sub.getStartDate());
                    logger.info("    Next Payment: {}", sub.getNextPaymentDate());
                    logger.info("    Last Payment: {}", sub.getLastPaymentDate());
                    logger.info("");
                });
    }
}
