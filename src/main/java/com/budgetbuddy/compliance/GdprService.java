package com.budgetbuddy.compliance;

import com.budgetbuddy.model.User;
import com.budgetbuddy.repository.dynamodb.*;
import com.budgetbuddy.service.aws.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GDPR Compliance Service
 * Handles data export and deletion requests
 *
 * Note: DynamoDB doesn't use Spring's @Transactional. Use DynamoDB TransactWriteItems for transactions.
 */
@Service
public class GdprService {

    private static final Logger logger = LoggerFactory.getLogger(GdprService.class);

    private final com.budgetbuddy.repository.dynamodb.UserRepository userRepository;
    private final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;
    private final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository;
    private final com.budgetbuddy.repository.dynamodb.BudgetRepository budgetRepository;
    private final com.budgetbuddy.repository.dynamodb.GoalRepository goalRepository;
    private final com.budgetbuddy.repository.dynamodb.AuditLogRepository auditLogRepository;
    private final S3Service s3Service;

    public GdprService(
            final com.budgetbuddy.repository.dynamodb.UserRepository userRepository,
            final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository,
            final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository,
            final com.budgetbuddy.repository.dynamodb.BudgetRepository budgetRepository,
            final com.budgetbuddy.repository.dynamodb.GoalRepository goalRepository,
            final com.budgetbuddy.repository.dynamodb.AuditLogRepository auditLogRepository,
            final S3Service s3Service) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.auditLogRepository = auditLogRepository;
        this.s3Service = s3Service;
    }

    /**
     * Export all user data (GDPR right to data portability)
     * Exports to S3 and returns download URL
     *
     * TODO: Refactor for DynamoDB - needs conversion from domain models to table models
     */
    public String exportUserData((final String userId) {
        var userTable = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            // Collect all user data using DynamoDB repositories
            UserDataExport export = new UserDataExport();
            // TODO: Convert UserTable to User domain model
            export.setAccounts(accountRepository.findByUserId(userId).stream()
                    .map(this::convertAccountTable).collect(java.util.stream.Collectors.toList()));
            export.setTransactions(transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE).stream()
                    .map(this::convertTransactionTable).collect(java.util.stream.Collectors.toList()));
            export.setBudgets(budgetRepository.findByUserId(userId).stream()
                    .map(this::convertBudgetTable).collect(java.util.stream.Collectors.toList()));
            export.setGoals(goalRepository.findByUserId(userId).stream()
                    .map(this::convertGoalTable).collect(java.util.stream.Collectors.toList()));

            // Get audit logs for last 7 years
            long sevenYearsAgo = java.time.Instant.now().minusSeconds(7L * 365 * 24 * 60 * 60).getEpochSecond() * 1000;
            long now = System.currentTimeMillis();
            export.setAuditLogs(auditLogRepository.findByUserIdAndDateRange(userId, sevenYearsAgo, now).stream()
                    .map(this::convertAuditLogTable).collect(java.util.stream.Collectors.toList()));

            // Compress and upload to S3
            byte[] compressedData = compressData(export);
            String s3Key = "exports/user_" + userId + "_" + System.currentTimeMillis() + ".gz";

            s3Service.uploadFileInfrequentAccess(
                    s3Key,
                    new ByteArrayInputStream(compressedData),
                    compressedData.length,
                    "application/gzip"
            );

            // Generate presigned URL (valid for 7 days)
            String downloadUrl = s3Service.getPresignedUrl(s3Key, 7 * 24 * 60);

            logger.info("Exported user data for user: {}", userId);
            return downloadUrl;
        } catch (Exception e) {
            logger.error("Error exporting user data: {}", e.getMessage());
            throw new RuntimeException("Failed to export user data", e);
        }
    }

    /**
     * Delete all user data (GDPR right to erasure)
     * Archives data to S3 before deletion for compliance
     *
     * TODO: Refactor for DynamoDB
     */
    public void deleteUserData((final String userId) {
        try {
            // Archive data before deletion
            String archiveUrl = exportUserData(userId);
            logger.info("Archived user data before deletion: {}", archiveUrl);

            // Delete all user data
            transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE)
                    .forEach(t -> transactionRepository.delete(t.getTransactionId()));
            accountRepository.findByUserId(userId)
                    .forEach(a -> accountRepository.findById(a.getAccountId()).ifPresent(acc ->
                            accountRepository.save(acc))); // Mark as inactive instead of delete
            budgetRepository.findByUserId(userId)
                    .forEach(b -> budgetRepository.delete(b.getBudgetId()));
            goalRepository.findByUserId(userId)
                    .forEach(g -> goalRepository.delete(g.getGoalId()));

            // Anonymize audit logs (keep for compliance, but remove PII)
            long sevenYearsAgo = java.time.Instant.now().minusSeconds(7L * 365 * 24 * 60 * 60).getEpochSecond() * 1000;
            long now = System.currentTimeMillis();
            auditLogRepository.findByUserIdAndDateRange(userId, sevenYearsAgo, now)
                    .forEach(log -> {
                        log.setUserId(null);
                        log.setIpAddress("REDACTED");
                        log.setUserAgent("REDACTED");
                        auditLogRepository.save(log);
                    });

            // Delete user account
            userRepository.delete(userId);

            logger.info("Deleted all data for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting user data: {}", e.getMessage());
            throw new RuntimeException("Failed to delete user data", e);
        }
    }

    // Helper methods to convert table models to domain models (stubs for now)
    private User convertUserTable((final com.budgetbuddy.model.dynamodb.UserTable userTable) {
        // TODO: Implement conversion
        return new User();
    }

    private com.budgetbuddy.model.Account convertAccountTable(com.budgetbuddy.model.dynamodb.AccountTable accountTable) {
        // TODO: Implement conversion
        return new com.budgetbuddy.model.Account();
    }

    private com.budgetbuddy.model.Transaction convertTransactionTable(com.budgetbuddy.model.dynamodb.TransactionTable transactionTable) {
        // TODO: Implement conversion
        return new com.budgetbuddy.model.Transaction();
    }

    private com.budgetbuddy.model.Budget convertBudgetTable(com.budgetbuddy.model.dynamodb.BudgetTable budgetTable) {
        // TODO: Implement conversion
        return new com.budgetbuddy.model.Budget();
    }

    private com.budgetbuddy.model.Goal convertGoalTable(com.budgetbuddy.model.dynamodb.GoalTable goalTable) {
        // TODO: Implement conversion
        return new com.budgetbuddy.model.Goal();
    }

    private AuditLog convertAuditLogTable((final com.budgetbuddy.compliance.AuditLogTable auditLogTable) {
        // TODO: Implement conversion
        return new AuditLog();
    }

    private byte[] compressData(Object data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
                 ObjectOutputStream oos = new ObjectOutputStream(gzos)) {
                oos.writeObject(data);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error compressing data: {}", e.getMessage());
            throw new RuntimeException("Failed to compress data", e);
        }
    }

    // Inner class for data export
    public static class UserDataExport {
        private User user;
        private java.util.List<com.budgetbuddy.model.Account> accounts;
        private java.util.List<com.budgetbuddy.model.Transaction> transactions;
        private java.util.List<com.budgetbuddy.model.Budget> budgets;
        private java.util.List<com.budgetbuddy.model.Goal> goals;
        private java.util.List<AuditLog> auditLogs;

        // Getters and setters
        public User getUser() { return user; }
        public void setUser(final User user) { this.user = user; }
        public java.util.List<com.budgetbuddy.model.Account> getAccounts() { return accounts; }
        public void setAccounts((final java.util.List<com.budgetbuddy.model.Account> accounts) { this.accounts = accounts; }
        public java.util.List<com.budgetbuddy.model.Transaction> getTransactions() { return transactions; }
        public void setTransactions((final java.util.List<com.budgetbuddy.model.Transaction> transactions) { this.transactions = transactions; }
        public java.util.List<com.budgetbuddy.model.Budget> getBudgets() { return budgets; }
        public void setBudgets((final java.util.List<com.budgetbuddy.model.Budget> budgets) { this.budgets = budgets; }
        public java.util.List<com.budgetbuddy.model.Goal> getGoals() { return goals; }
        public void setGoals((final java.util.List<com.budgetbuddy.model.Goal> goals) { this.goals = goals; }
        public java.util.List<AuditLog> getAuditLogs() { return auditLogs; }
        public void setAuditLogs((final java.util.List<AuditLog> auditLogs) { this.auditLogs = auditLogs; }
    }
}

