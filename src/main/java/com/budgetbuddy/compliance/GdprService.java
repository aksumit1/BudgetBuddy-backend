package com.budgetbuddy.compliance;

import com.budgetbuddy.model.User;
import com.budgetbuddy.repository.*;
import com.budgetbuddy.service.aws.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GDPR Compliance Service
 * Handles data export and deletion requests
 */
@Service
@Transactional
public class GdprService {

    private static final Logger logger = LoggerFactory.getLogger(GdprService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final AuditLogRepository auditLogRepository;
    private final S3Service s3Service;

    public GdprService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            GoalRepository goalRepository,
            AuditLogRepository auditLogRepository,
            S3Service s3Service) {
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
     */
    public String exportUserData(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            // Collect all user data
            UserDataExport export = new UserDataExport();
            export.setUser(user);
            export.setAccounts(accountRepository.findByUserAndActiveTrue(user));
            export.setTransactions(transactionRepository.findByUserOrderByTransactionDateDesc(user, 
                    org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent());
            export.setBudgets(budgetRepository.findByUser(user));
            export.setGoals(goalRepository.findByUserAndActiveTrue(user));
            export.setAuditLogs(auditLogRepository.findByUserIdAndCreatedAtBetween(
                    userId, user.getCreatedAt().minusYears(7), java.time.LocalDateTime.now()));

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
     */
    public void deleteUserData(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            // Archive data before deletion
            String archiveUrl = exportUserData(userId);
            logger.info("Archived user data before deletion: {}", archiveUrl);

            // Delete all user data
            transactionRepository.deleteAll(transactionRepository.findByUserOrderByTransactionDateDesc(
                    user, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getContent());
            accountRepository.deleteAll(accountRepository.findByUserAndActiveTrue(user));
            budgetRepository.deleteAll(budgetRepository.findByUser(user));
            goalRepository.deleteAll(goalRepository.findByUserAndActiveTrue(user));
            
            // Anonymize audit logs (keep for compliance, but remove PII)
            auditLogRepository.findByUserIdAndCreatedAtBetween(
                    userId, user.getCreatedAt().minusYears(7), java.time.LocalDateTime.now())
                    .forEach(log -> {
                        log.setUserId(null);
                        log.setIpAddress("REDACTED");
                        log.setUserAgent("REDACTED");
                        auditLogRepository.save(log);
                    });

            // Delete user account
            userRepository.delete(user);
            
            logger.info("Deleted all data for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting user data: {}", e.getMessage());
            throw new RuntimeException("Failed to delete user data", e);
        }
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
        public void setUser(User user) { this.user = user; }
        public java.util.List<com.budgetbuddy.model.Account> getAccounts() { return accounts; }
        public void setAccounts(java.util.List<com.budgetbuddy.model.Account> accounts) { this.accounts = accounts; }
        public java.util.List<com.budgetbuddy.model.Transaction> getTransactions() { return transactions; }
        public void setTransactions(java.util.List<com.budgetbuddy.model.Transaction> transactions) { this.transactions = transactions; }
        public java.util.List<com.budgetbuddy.model.Budget> getBudgets() { return budgets; }
        public void setBudgets(java.util.List<com.budgetbuddy.model.Budget> budgets) { this.budgets = budgets; }
        public java.util.List<com.budgetbuddy.model.Goal> getGoals() { return goals; }
        public void setGoals(java.util.List<com.budgetbuddy.model.Goal> goals) { this.goals = goals; }
        public java.util.List<AuditLog> getAuditLogs() { return auditLogs; }
        public void setAuditLogs(java.util.List<AuditLog> auditLogs) { this.auditLogs = auditLogs; }
    }
}

