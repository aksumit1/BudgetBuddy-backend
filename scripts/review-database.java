package com.budgetbuddy.scripts;

import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Database Review Script
 * 
 * Reviews all DynamoDB tables, identifies duplicates, and provides statistics.
 * 
 * Usage:
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--review-database"
 *   OR
 *   java -jar target/budgetbuddy-backend.jar --review-database
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.budgetbuddy")
public class DatabaseReviewScript implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseReviewScript.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    public static void main(String[] args) {
        SpringApplication.run(DatabaseReviewScript.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("--review-database")) {
            logger.info("To run database review, use: --review-database");
            return;
        }

        logger.info("========================================");
        logger.info("BudgetBuddy Database Review");
        logger.info("========================================");
        logger.info("");

        // Review Users
        reviewUsers();

        // Review Accounts
        reviewAccounts();

        // Review Transactions
        reviewTransactions();

        // Review Budgets
        reviewBudgets();

        // Review Goals
        reviewGoals();

        // Review Audit Logs
        reviewAuditLogs();

        logger.info("");
        logger.info("========================================");
        logger.info("Review complete!");
        logger.info("========================================");
    }

    private void reviewUsers() {
        logger.info("📊 REVIEWING USERS TABLE");
        logger.info("----------------------------------------");

        try {
            // Get all users (this might be expensive, consider pagination for production)
            List<UserTable> users = userRepository.findAll();
            
            logger.info("Total Users: {}", users.size());
            
            // Check for duplicate emails
            Map<String, List<UserTable>> emailMap = users.stream()
                    .filter(u -> u.getEmail() != null)
                    .collect(Collectors.groupingBy(UserTable::getEmail));
            
            long duplicateEmails = emailMap.values().stream()
                    .filter(list -> list.size() > 1)
                    .count();
            
            if (duplicateEmails > 0) {
                logger.warn("⚠️  DUPLICATE EMAILS FOUND: {}", duplicateEmails);
                emailMap.entrySet().stream()
                        .filter(e -> e.getValue().size() > 1)
                        .forEach(e -> {
                            logger.warn("  Email: {} ({} duplicates)", e.getKey(), e.getValue().size());
                            e.getValue().forEach(u -> 
                                logger.warn("    - User ID: {}, Created: {}", 
                                        u.getUserId(), u.getCreatedAt()));
                        });
            } else {
                logger.info("✅ No duplicate emails found");
            }
            
            // Statistics
            long enabled = users.stream().filter(u -> Boolean.TRUE.equals(u.getEnabled())).count();
            long emailVerified = users.stream().filter(u -> Boolean.TRUE.equals(u.getEmailVerified())).count();
            
            logger.info("Enabled Users: {}", enabled);
            logger.info("Email Verified: {}", emailVerified);
            logger.info("");
            
        } catch (Exception e) {
            logger.error("Error reviewing users: {}", e.getMessage(), e);
        }
    }

    private void reviewAccounts() {
        logger.info("📊 REVIEWING ACCOUNTS TABLE");
        logger.info("----------------------------------------");

        try {
            // Get all unique user IDs first
            Set<String> userIds = new HashSet<>();
            // Scan all accounts to get user IDs (this is a limitation - we need to scan)
            // For production, consider using a separate user ID list
            
            // For now, let's check a sample by getting accounts for known users
            // In production, you'd want to scan the table properly
            
            // Check for duplicates by scanning all accounts
            Map<String, List<AccountTable>> accountIdMap = new HashMap<>();
            Map<String, List<AccountTable>> plaidAccountIdMap = new HashMap<>();
            Map<String, List<AccountTable>> userAccountMap = new HashMap<>();
            
            // This is expensive - in production, use pagination
            // For now, we'll use a more efficient approach by checking known users
            logger.info("Scanning accounts... (this may take a while)");
            
            // Get accounts by user (we need to know user IDs first)
            // For a full review, we'd need to scan the table
            // Let's create a helper method to scan all accounts
            
            int totalAccounts = 0;
            int duplicateAccountIds = 0;
            int duplicatePlaidIds = 0;
            
            // Note: Full table scan is expensive. In production, consider:
            // 1. Using DynamoDB Streams
            // 2. Using a separate analytics table
            // 3. Running this as a scheduled job
            
            logger.info("⚠️  Full account scan not implemented (too expensive)");
            logger.info("   Use the shell script for full table scans");
            logger.info("");
            
        } catch (Exception e) {
            logger.error("Error reviewing accounts: {}", e.getMessage(), e);
        }
    }

    private void reviewAccountsForUser(String userId) {
        try {
            List<AccountTable> accounts = accountRepository.findByUserId(userId);
            
            logger.info("User {}: {} accounts", userId, accounts.size());
            
            // Check for duplicates
            Map<String, List<AccountTable>> accountIdMap = accounts.stream()
                    .filter(a -> a.getAccountId() != null)
                    .collect(Collectors.groupingBy(AccountTable::getAccountId));
            
            Map<String, List<AccountTable>> plaidIdMap = accounts.stream()
                    .filter(a -> a.getPlaidAccountId() != null && !a.getPlaidAccountId().isEmpty())
                    .collect(Collectors.groupingBy(AccountTable::getPlaidAccountId));
            
            long duplicateAccountIds = accountIdMap.values().stream()
                    .filter(list -> list.size() > 1)
                    .count();
            
            long duplicatePlaidIds = plaidIdMap.values().stream()
                    .filter(list -> list.size() > 1)
                    .count();
            
            if (duplicateAccountIds > 0) {
                logger.warn("⚠️  User {} has {} duplicate account IDs", userId, duplicateAccountIds);
            }
            
            if (duplicatePlaidIds > 0) {
                logger.warn("⚠️  User {} has {} duplicate Plaid account IDs", userId, duplicatePlaidIds);
            }
            
            long active = accounts.stream().filter(a -> Boolean.TRUE.equals(a.getActive())).count();
            logger.info("  Active: {}, Inactive: {}", active, accounts.size() - active);
            
        } catch (Exception e) {
            logger.error("Error reviewing accounts for user {}: {}", userId, e.getMessage());
        }
    }

    private void reviewTransactions() {
        logger.info("📊 REVIEWING TRANSACTIONS TABLE");
        logger.info("----------------------------------------");

        try {
            logger.info("⚠️  Full transaction scan not implemented (too expensive)");
            logger.info("   Use the shell script for full table scans");
            logger.info("   Or review transactions per user using the API");
            logger.info("");
        } catch (Exception e) {
            logger.error("Error reviewing transactions: {}", e.getMessage(), e);
        }
    }

    private void reviewTransactionsForUser(String userId) {
        try {
            List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 1000);
            
            logger.info("User {}: {} transactions (showing first 1000)", userId, transactions.size());
            
            // Check for duplicates
            Map<String, List<TransactionTable>> transactionIdMap = transactions.stream()
                    .filter(t -> t.getTransactionId() != null)
                    .collect(Collectors.groupingBy(TransactionTable::getTransactionId));
            
            Map<String, List<TransactionTable>> plaidIdMap = transactions.stream()
                    .filter(t -> t.getPlaidTransactionId() != null && !t.getPlaidTransactionId().isEmpty())
                    .collect(Collectors.groupingBy(TransactionTable::getPlaidTransactionId));
            
            long duplicateTransactionIds = transactionIdMap.values().stream()
                    .filter(list -> list.size() > 1)
                    .count();
            
            long duplicatePlaidIds = plaidIdMap.values().stream()
                    .filter(list -> list.size() > 1)
                    .count();
            
            if (duplicateTransactionIds > 0) {
                logger.warn("⚠️  User {} has {} duplicate transaction IDs", userId, duplicateTransactionIds);
            }
            
            if (duplicatePlaidIds > 0) {
                logger.warn("⚠️  User {} has {} duplicate Plaid transaction IDs", userId, duplicatePlaidIds);
            }
            
        } catch (Exception e) {
            logger.error("Error reviewing transactions for user {}: {}", userId, e.getMessage());
        }
    }

    private void reviewBudgets() {
        logger.info("📊 REVIEWING BUDGETS TABLE");
        logger.info("----------------------------------------");
        logger.info("(Budget review not fully implemented)");
        logger.info("");
    }

    private void reviewGoals() {
        logger.info("📊 REVIEWING GOALS TABLE");
        logger.info("----------------------------------------");
        logger.info("(Goal review not fully implemented)");
        logger.info("");
    }

    private void reviewAuditLogs() {
        logger.info("📊 REVIEWING AUDIT LOGS TABLE");
        logger.info("----------------------------------------");
        logger.info("(Audit log review not fully implemented)");
        logger.info("");
    }
}

