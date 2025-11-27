package com.budgetbuddy.compliance;

import com.budgetbuddy.model.User;
import com.budgetbuddy.service.aws.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public String exportUserData(final String userId) {
        var userTable = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            // Collect all user data using DynamoDB repositories
            UserDataExport export = new UserDataExport();
            // Convert UserTable to User domain model and set in export
            export.setUser(convertUserTable(userTable));
            export.setAccounts(accountRepository.findByUserId(userId).stream()
                    .map(this::convertAccountTable).collect(java.util.stream.Collectors.toList()));
            
            // Use pagination to avoid memory issues (limit to 10,000 transactions)
            int transactionLimit = 10000;
            export.setTransactions(transactionRepository.findByUserId(userId, 0, transactionLimit).stream()
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
    public void deleteUserData(final String userId) {
        try {
            // Archive data before deletion
            String archiveUrl = exportUserData(userId);
            logger.info("Archived user data before deletion: {}", archiveUrl);

            // Delete all user data with pagination to avoid memory issues
            // Transactions - delete in batches
            int transactionLimit = 1000;
            int transactionSkip = 0;
            List<com.budgetbuddy.model.dynamodb.TransactionTable> transactions;
            do {
                transactions = transactionRepository.findByUserId(userId, transactionSkip, transactionLimit);
                for (com.budgetbuddy.model.dynamodb.TransactionTable t : transactions) {
                    transactionRepository.delete(t.getTransactionId());
                }
                transactionSkip += transactions.size();
            } while (transactions.size() == transactionLimit);

            // Accounts - mark as inactive instead of delete (for compliance)
            for (com.budgetbuddy.model.dynamodb.AccountTable account : accountRepository.findByUserId(userId)) {
                account.setActive(false);
                account.setUpdatedAt(java.time.Instant.now());
                accountRepository.save(account);
            }

            // Budgets - delete
            for (com.budgetbuddy.model.dynamodb.BudgetTable budget : budgetRepository.findByUserId(userId)) {
                budgetRepository.delete(budget.getBudgetId());
            }

            // Goals - delete
            for (com.budgetbuddy.model.dynamodb.GoalTable goal : goalRepository.findByUserId(userId)) {
                goalRepository.delete(goal.getGoalId());
            }

            // Anonymize audit logs (keep for compliance, but remove PII)
            // Use batch operations for better performance
            long sevenYearsAgo = java.time.Instant.now().minusSeconds(7L * 365 * 24 * 60 * 60).getEpochSecond() * 1000;
            long now = System.currentTimeMillis();
            List<com.budgetbuddy.compliance.AuditLogTable> auditLogs = auditLogRepository.findByUserIdAndDateRange(userId, sevenYearsAgo, now);
            for (com.budgetbuddy.compliance.AuditLogTable log : auditLogs) {
                log.setUserId(null);
                log.setIpAddress("REDACTED");
                log.setUserAgent("REDACTED");
                auditLogRepository.save(log);
            }

            // Delete user account
            userRepository.delete(userId);

            logger.info("Deleted all data for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error deleting user data: {}", e.getMessage());
            throw new RuntimeException("Failed to delete user data", e);
        }
    }

    // Helper methods to convert table models to domain models
    private User convertUserTable(final com.budgetbuddy.model.dynamodb.UserTable userTable) {
        if (userTable == null) {
            return null;
        }
        User user = new User();
        user.setEmail(userTable.getEmail());
        // Note: Password hash is not exported for security (GDPR compliance)
        // Only export non-sensitive user information
        user.setFirstName(userTable.getFirstName());
        user.setLastName(userTable.getLastName());
        user.setPhoneNumber(userTable.getPhoneNumber());
        user.setEnabled(userTable.getEnabled());
        user.setEmailVerified(userTable.getEmailVerified());
        user.setTwoFactorEnabled(userTable.getTwoFactorEnabled());
        user.setPreferredCurrency(userTable.getPreferredCurrency());
        user.setTimezone(userTable.getTimezone());
        
        // Convert Set<String> roles to Set<Role>
        if (userTable.getRoles() != null) {
            Set<User.Role> roleSet = new HashSet<>();
            for (String roleStr : userTable.getRoles()) {
                try {
                    roleSet.add(User.Role.valueOf(roleStr));
                } catch (IllegalArgumentException e) {
                    // Skip invalid role values
                    logger.warn("Invalid role value in user data: {}", roleStr);
                }
            }
            user.setRoles(roleSet);
        }
        
        // Convert Instant to LocalDateTime
        if (userTable.getCreatedAt() != null) {
            user.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    userTable.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (userTable.getUpdatedAt() != null) {
            user.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                    userTable.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (userTable.getLastLoginAt() != null) {
            user.setLastLoginAt(java.time.LocalDateTime.ofInstant(
                    userTable.getLastLoginAt(), java.time.ZoneId.systemDefault()));
        }
        if (userTable.getPasswordChangedAt() != null) {
            user.setPasswordChangedAt(java.time.LocalDateTime.ofInstant(
                    userTable.getPasswordChangedAt(), java.time.ZoneId.systemDefault()));
        }
        
        return user;
    }

    private com.budgetbuddy.model.Account convertAccountTable(com.budgetbuddy.model.dynamodb.AccountTable accountTable) {
        if (accountTable == null) {
            return null;
        }
        com.budgetbuddy.model.Account account = new com.budgetbuddy.model.Account();
        account.setAccountName(accountTable.getAccountName());
        account.setInstitutionName(accountTable.getInstitutionName());
        if (accountTable.getAccountType() != null) {
            try {
                account.setAccountType(com.budgetbuddy.model.Account.AccountType.valueOf(accountTable.getAccountType()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid account type: {}", accountTable.getAccountType());
            }
        }
        account.setAccountSubtype(accountTable.getAccountSubtype());
        account.setBalance(accountTable.getBalance());
        account.setCurrencyCode(accountTable.getCurrencyCode());
        account.setPlaidAccountId(accountTable.getPlaidAccountId());
        account.setPlaidItemId(accountTable.getPlaidItemId());
        account.setActive(accountTable.getActive());
        if (accountTable.getLastSyncedAt() != null) {
            account.setLastSyncedAt(java.time.LocalDateTime.ofInstant(
                    accountTable.getLastSyncedAt(), java.time.ZoneId.systemDefault()));
        }
        if (accountTable.getCreatedAt() != null) {
            account.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    accountTable.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (accountTable.getUpdatedAt() != null) {
            account.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                    accountTable.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        return account;
    }

    private com.budgetbuddy.model.Transaction convertTransactionTable(com.budgetbuddy.model.dynamodb.TransactionTable transactionTable) {
        if (transactionTable == null) {
            return null;
        }
        com.budgetbuddy.model.Transaction transaction = new com.budgetbuddy.model.Transaction();
        transaction.setAmount(transactionTable.getAmount());
        transaction.setDescription(transactionTable.getDescription());
        transaction.setMerchantName(transactionTable.getMerchantName());
        if (transactionTable.getCategory() != null) {
            try {
                transaction.setCategory(com.budgetbuddy.model.Transaction.TransactionCategory.valueOf(transactionTable.getCategory()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid transaction category: {}", transactionTable.getCategory());
            }
        }
        if (transactionTable.getTransactionDate() != null) {
            try {
                transaction.setTransactionDate(java.time.LocalDate.parse(transactionTable.getTransactionDate()));
            } catch (Exception e) {
                logger.warn("Invalid transaction date format: {}", transactionTable.getTransactionDate());
            }
        }
        transaction.setCurrencyCode(transactionTable.getCurrencyCode());
        transaction.setPlaidTransactionId(transactionTable.getPlaidTransactionId());
        transaction.setPending(transactionTable.getPending());
        if (transactionTable.getCreatedAt() != null) {
            transaction.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    transactionTable.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (transactionTable.getUpdatedAt() != null) {
            transaction.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                    transactionTable.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        return transaction;
    }

    private com.budgetbuddy.model.Budget convertBudgetTable(com.budgetbuddy.model.dynamodb.BudgetTable budgetTable) {
        if (budgetTable == null) {
            return null;
        }
        com.budgetbuddy.model.Budget budget = new com.budgetbuddy.model.Budget();
        if (budgetTable.getCategory() != null) {
            try {
                budget.setCategory(com.budgetbuddy.model.Transaction.TransactionCategory.valueOf(budgetTable.getCategory()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid budget category: {}", budgetTable.getCategory());
            }
        }
        budget.setMonthlyLimit(budgetTable.getMonthlyLimit());
        budget.setCurrentSpent(budgetTable.getCurrentSpent());
        budget.setCurrencyCode(budgetTable.getCurrencyCode());
        if (budgetTable.getCreatedAt() != null) {
            budget.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    budgetTable.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (budgetTable.getUpdatedAt() != null) {
            budget.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                    budgetTable.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        return budget;
    }

    private com.budgetbuddy.model.Goal convertGoalTable(com.budgetbuddy.model.dynamodb.GoalTable goalTable) {
        if (goalTable == null) {
            return null;
        }
        com.budgetbuddy.model.Goal goal = new com.budgetbuddy.model.Goal();
        goal.setName(goalTable.getName());
        goal.setDescription(goalTable.getDescription());
        goal.setTargetAmount(goalTable.getTargetAmount());
        goal.setCurrentAmount(goalTable.getCurrentAmount());
        if (goalTable.getTargetDate() != null) {
            try {
                goal.setTargetDate(java.time.LocalDate.parse(goalTable.getTargetDate()));
            } catch (Exception e) {
                logger.warn("Invalid goal target date format: {}", goalTable.getTargetDate());
            }
        }
        goal.setMonthlyContribution(goalTable.getMonthlyContribution());
        if (goalTable.getGoalType() != null) {
            try {
                goal.setGoalType(com.budgetbuddy.model.Goal.GoalType.valueOf(goalTable.getGoalType()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid goal type: {}", goalTable.getGoalType());
            }
        }
        goal.setCurrencyCode(goalTable.getCurrencyCode());
        goal.setActive(goalTable.getActive());
        if (goalTable.getCreatedAt() != null) {
            goal.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    goalTable.getCreatedAt(), java.time.ZoneId.systemDefault()));
        }
        if (goalTable.getUpdatedAt() != null) {
            goal.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                    goalTable.getUpdatedAt(), java.time.ZoneId.systemDefault()));
        }
        return goal;
    }

    private AuditLog convertAuditLogTable(final com.budgetbuddy.compliance.AuditLogTable auditLogTable) {
        if (auditLogTable == null) {
            return null;
        }
        AuditLog auditLog = new AuditLog();
        if (auditLogTable.getUserId() != null) {
            try {
                auditLog.setUserId(Long.parseLong(auditLogTable.getUserId()));
            } catch (NumberFormatException e) {
                logger.warn("Invalid user ID format in audit log: {}", auditLogTable.getUserId());
            }
        }
        auditLog.setAction(auditLogTable.getAction());
        auditLog.setResourceType(auditLogTable.getResourceType());
        auditLog.setResourceId(auditLogTable.getResourceId());
        auditLog.setDetails(auditLogTable.getDetails());
        auditLog.setIpAddress(auditLogTable.getIpAddress());
        auditLog.setUserAgent(auditLogTable.getUserAgent());
        if (auditLogTable.getCreatedAt() != null) {
            auditLog.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(auditLogTable.getCreatedAt()),
                    java.time.ZoneId.systemDefault()));
        }
        return auditLog;
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
        public void setAccounts(final java.util.List<com.budgetbuddy.model.Account> accounts) { this.accounts = accounts; }
        public java.util.List<com.budgetbuddy.model.Transaction> getTransactions() { return transactions; }
        public void setTransactions(final java.util.List<com.budgetbuddy.model.Transaction> transactions) { this.transactions = transactions; }
        public java.util.List<com.budgetbuddy.model.Budget> getBudgets() { return budgets; }
        public void setBudgets(final java.util.List<com.budgetbuddy.model.Budget> budgets) { this.budgets = budgets; }
        public java.util.List<com.budgetbuddy.model.Goal> getGoals() { return goals; }
        public void setGoals(final java.util.List<com.budgetbuddy.model.Goal> goals) { this.goals = goals; }
        public java.util.List<AuditLog> getAuditLogs() { return auditLogs; }
        public void setAuditLogs(final java.util.List<AuditLog> auditLogs) { this.auditLogs = auditLogs; }
    }
}

