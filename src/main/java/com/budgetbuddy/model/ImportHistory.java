package com.budgetbuddy.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Import History Model
 * Tracks all import operations for audit trail and history
 */
public class ImportHistory {
    private String importId; // UUID
    private String userId;
    private String fileName;
    private String fileType; // CSV, EXCEL, PDF
    private String importSource; // CSV, EXCEL, PDF, BULK
    private String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL, CANCELLED
    private int totalTransactions;
    private int successfulTransactions;
    private int failedTransactions;
    private int skippedTransactions;
    private int duplicateTransactions;
    private String accountId; // Optional: account to import into
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;
    private String validationErrors; // JSON string of validation errors
    private String validationWarnings; // JSON string of validation warnings
    private boolean canResume; // Whether this import can be resumed
    private String resumeToken; // Token for resuming partial imports
    private int lastProcessedIndex; // Last successfully processed transaction index
    private String importBatchId; // UUID for grouping bulk imports
    private Instant createdAt;
    private Instant updatedAt;

    public ImportHistory() {
        this.importId = UUID.randomUUID().toString();
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.canResume = false;
    }

    // Getters and setters
    public String getImportId() { return importId; }
    public void setImportId(String importId) { this.importId = importId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getImportSource() { return importSource; }
    public void setImportSource(String importSource) { this.importSource = importSource; }

    public String getStatus() { return status; }
    public void setStatus(String status) { 
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public int getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(int totalTransactions) { this.totalTransactions = totalTransactions; }

    public int getSuccessfulTransactions() { return successfulTransactions; }
    public void setSuccessfulTransactions(int successfulTransactions) { this.successfulTransactions = successfulTransactions; }

    public int getFailedTransactions() { return failedTransactions; }
    public void setFailedTransactions(int failedTransactions) { this.failedTransactions = failedTransactions; }

    public int getSkippedTransactions() { return skippedTransactions; }
    public void setSkippedTransactions(int skippedTransactions) { this.skippedTransactions = skippedTransactions; }

    public int getDuplicateTransactions() { return duplicateTransactions; }
    public void setDuplicateTransactions(int duplicateTransactions) { this.duplicateTransactions = duplicateTransactions; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getValidationErrors() { return validationErrors; }
    public void setValidationErrors(String validationErrors) { this.validationErrors = validationErrors; }

    public String getValidationWarnings() { return validationWarnings; }
    public void setValidationWarnings(String validationWarnings) { this.validationWarnings = validationWarnings; }

    public boolean isCanResume() { return canResume; }
    public void setCanResume(boolean canResume) { this.canResume = canResume; }

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }

    public int getLastProcessedIndex() { return lastProcessedIndex; }
    public void setLastProcessedIndex(int lastProcessedIndex) { this.lastProcessedIndex = lastProcessedIndex; }

    public String getImportBatchId() { return importBatchId; }
    public void setImportBatchId(String importBatchId) { this.importBatchId = importBatchId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

