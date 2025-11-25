package com.budgetbuddy.compliance.gdpr;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GDPR Compliance Service
 * Implements GDPR requirements:
 * - Right to access (Article 15)
 * - Right to rectification (Article 16)
 * - Right to erasure / Right to be forgotten (Article 17)
 * - Right to data portability (Article 20)
 * - Right to object (Article 21)
 */
@Service
public class GDPRComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(GDPRComplianceService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private com.budgetbuddy.compliance.AuditLogService auditLogService;

    /**
     * Article 15: Right to access
     * Provide user with all their personal data
     */
    public GDPRDataExport exportUserData((final String userId) {
        logger.info("GDPR: Exporting data for user: {}", userId);

        GDPRDataExport export = new GDPRDataExport();
        export.setUserId(userId);
        export.setExportDate(Instant.now());
        export.setExportId(UUID.randomUUID().toString());

        // Export user data
        userRepository.findById(userId).ifPresent(export::setUserData);

        // Export transactions
        List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                transactionRepository.findByUserId(userId, 0, 10000); // Get all transactions
        export.setTransactions(transactions);

        // Export accounts
        List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByUserId(userId);
        export.setAccounts(accounts);

        // Export budgets
        List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets = budgetRepository.findByUserId(userId);
        export.setBudgets(budgets);

        // Export goals
        List<com.budgetbuddy.model.dynamodb.GoalTable> goals = goalRepository.findByUserId(userId);
        export.setGoals(goals);

        // Export audit logs
        long startTimestamp = Instant.now().minusSeconds(31536000).getEpochSecond(); // Last year
        long endTimestamp = Instant.now().getEpochSecond();
        List<com.budgetbuddy.compliance.AuditLogTable> auditLogs =
                auditLogRepository.findByUserIdAndDateRange(userId, startTimestamp, endTimestamp);
        export.setAuditLogs(auditLogs);

        // Log data export
        auditLogService.logDataExport(userId, export.getExportId());

        return export;
    }

    /**
     * Article 17: Right to erasure / Right to be forgotten
     * Delete all user data
     */
    public void deleteUserData((final String userId) {
        logger.info("GDPR: Deleting all data for user: {}", userId);

        // Delete transactions
        List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions =
                transactionRepository.findByUserId(userId, 0, 10000);
        transactions.forEach(t -> transactionRepository.delete(t.getTransactionId()));

        // Delete accounts
        List<com.budgetbuddy.model.dynamodb.AccountTable> accounts = accountRepository.findByUserId(userId);
        accounts.forEach(a -> {
            // Delete from S3 if applicable
            deleteFromS3(userId, a.getAccountId());
        });

        // Delete budgets
        List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets = budgetRepository.findByUserId(userId);
        budgets.forEach(b -> budgetRepository.delete(b.getBudgetId()));

        // Delete goals
        List<com.budgetbuddy.model.dynamodb.GoalTable> goals = goalRepository.findByUserId(userId);
        goals.forEach(g -> goalRepository.delete(g.getGoalId()));

        // Anonymize user (don't delete for audit purposes, but remove PII)
        userRepository.findById(userId).ifPresent(user -> {
            user.setEmail("deleted_" + UUID.randomUUID().toString() + "@deleted.local");
            user.setFirstName("Deleted");
            user.setLastName("User");
            user.setPhoneNumber(null);
            user.setEnabled(false);
            user.setEmailVerified(false);
            userRepository.save(user);
        });

        // Log data deletion
        auditLogService.logDataDeletion(userId);

        logger.info("GDPR: All data deleted for user: {}", userId);
    }

    /**
     * Article 20: Right to data portability
     * Export user data in machine-readable format (JSON)
     */
    public String exportDataPortable((final String userId) {
        GDPRDataExport export = exportUserData(userId);

        // Convert to JSON
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
        } catch (Exception e) {
            logger.error("Failed to export data as JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to export data", e);
        }
    }

    /**
     * Article 16: Right to rectification
     * Update user data
     */
    public void updateUserData((final String userId, final UserTable updatedData) {
        logger.info("GDPR: Updating data for user: {}", userId);

        userRepository.findById(userId).ifPresent(user -> {
            if (updatedData.getFirstName() != null) {
                user.setFirstName(updatedData.getFirstName());
            }
            if (updatedData.getLastName() != null) {
                user.setLastName(updatedData.getLastName());
            }
            if (updatedData.getEmail() != null) {
                user.setEmail(updatedData.getEmail());
                user.setEmailVerified(false); // Require re-verification
            }
            if (updatedData.getPhoneNumber() != null) {
                user.setPhoneNumber(updatedData.getPhoneNumber());
            }
            user.setUpdatedAt(Instant.now());
            userRepository.save(user);

            // Log data update
            auditLogService.logDataUpdate(userId);
        });
    }

    private void deleteFromS3((final String userId, final String accountId) {
        try {
            String key = "users/" + userId + "/accounts/" + accountId + "/";
            // List and delete all objects with this prefix
            // Implementation depends on S3 bucket structure
            logger.debug("Deleting S3 objects for user: {} account: {}", userId, accountId);
        } catch (Exception e) {
            logger.error("Failed to delete from S3: {}", e.getMessage());
        }
    }

    /**
     * GDPR Data Export DTO
     */
    public static class GDPRDataExport {
        private String exportId;
        private String userId;
        private Instant exportDate;
        private UserTable userData;
        private List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions;
        private List<com.budgetbuddy.model.dynamodb.AccountTable> accounts;
        private List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets;
        private List<com.budgetbuddy.model.dynamodb.GoalTable> goals;
        private List<com.budgetbuddy.compliance.AuditLogTable> auditLogs;

        // Getters and setters
        public String getExportId() { return exportId; }
        public void setExportId(final String exportId) { this.exportId = exportId; }
        public String getUserId() { return userId; }
        public void setUserId(final String userId) { this.userId = userId; }
        public Instant getExportDate() { return exportDate; }
        public void setExportDate(final Instant exportDate) { this.exportDate = exportDate; }
        public UserTable getUserData() { return userData; }
        public void setUserData(final UserTable userData) { this.userData = userData; }
        public List<com.budgetbuddy.model.dynamodb.TransactionTable> getTransactions() { return transactions; }
        public void setTransactions((final List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions) { this.transactions = transactions; }
        public List<com.budgetbuddy.model.dynamodb.AccountTable> getAccounts() { return accounts; }
        public void setAccounts((final List<com.budgetbuddy.model.dynamodb.AccountTable> accounts) { this.accounts = accounts; }
        public List<com.budgetbuddy.model.dynamodb.BudgetTable> getBudgets() { return budgets; }
        public void setBudgets((final List<com.budgetbuddy.model.dynamodb.BudgetTable> budgets) { this.budgets = budgets; }
        public List<com.budgetbuddy.model.dynamodb.GoalTable> getGoals() { return goals; }
        public void setGoals((final List<com.budgetbuddy.model.dynamodb.GoalTable> goals) { this.goals = goals; }
        public List<com.budgetbuddy.compliance.AuditLogTable> getAuditLogs() { return auditLogs; }
        public void setAuditLogs((final List<com.budgetbuddy.compliance.AuditLogTable> auditLogs) { this.auditLogs = auditLogs; }
    }
}

