package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

/**
 * DynamoDB table for Import History
 * Tracks all import operations for audit trail and history
 */
@DynamoDbBean
public class ImportHistoryTable {

    private String importId; // Partition key
    private String userId; // GSI partition key
    private String fileName;
    private String fileType; // CSV, EXCEL, PDF
    private String importSource; // CSV, EXCEL, PDF, BULK
    private String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL, CANCELLED
    private Integer totalTransactions;
    private Integer successfulTransactions;
    private Integer failedTransactions;
    private Integer skippedTransactions;
    private Integer duplicateTransactions;
    private String accountId; // Optional: account to import into
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;
    private String validationErrors; // JSON string of validation errors
    private String validationWarnings; // JSON string of validation warnings
    private Boolean canResume; // Whether this import can be resumed
    private String resumeToken; // Token for resuming partial imports
    private Integer lastProcessedIndex; // Last successfully processed transaction index
    private String importBatchId; // UUID for grouping bulk imports
    private Instant createdAt;
    private Instant updatedAt;
    private Long createdAtTimestamp; // GSI sort key (epoch seconds) for sorting by creation time

    @DynamoDbPartitionKey
    @DynamoDbAttribute("importId")
    public String getImportId() {
        return importId;
    }

    public void setImportId(final String importId) {
        this.importId = importId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdCreatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("fileName")
    public String getFileName() {
        return fileName;
    }

    public void setFileName(final String fileName) {
        this.fileName = fileName;
    }

    @DynamoDbAttribute("fileType")
    public String getFileType() {
        return fileType;
    }

    public void setFileType(final String fileType) {
        this.fileType = fileType;
    }

    @DynamoDbAttribute("importSource")
    public String getImportSource() {
        return importSource;
    }

    public void setImportSource(final String importSource) {
        this.importSource = importSource;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    @DynamoDbAttribute("totalTransactions")
    public Integer getTotalTransactions() {
        return totalTransactions;
    }

    public void setTotalTransactions(final Integer totalTransactions) {
        this.totalTransactions = totalTransactions;
    }

    @DynamoDbAttribute("successfulTransactions")
    public Integer getSuccessfulTransactions() {
        return successfulTransactions;
    }

    public void setSuccessfulTransactions(final Integer successfulTransactions) {
        this.successfulTransactions = successfulTransactions;
    }

    @DynamoDbAttribute("failedTransactions")
    public Integer getFailedTransactions() {
        return failedTransactions;
    }

    public void setFailedTransactions(final Integer failedTransactions) {
        this.failedTransactions = failedTransactions;
    }

    @DynamoDbAttribute("skippedTransactions")
    public Integer getSkippedTransactions() {
        return skippedTransactions;
    }

    public void setSkippedTransactions(final Integer skippedTransactions) {
        this.skippedTransactions = skippedTransactions;
    }

    @DynamoDbAttribute("duplicateTransactions")
    public Integer getDuplicateTransactions() {
        return duplicateTransactions;
    }

    public void setDuplicateTransactions(final Integer duplicateTransactions) {
        this.duplicateTransactions = duplicateTransactions;
    }

    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbAttribute("startedAt")
    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(final Instant startedAt) {
        this.startedAt = startedAt;
    }

    @DynamoDbAttribute("completedAt")
    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(final Instant completedAt) {
        this.completedAt = completedAt;
    }

    @DynamoDbAttribute("errorMessage")
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(final String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @DynamoDbAttribute("validationErrors")
    public String getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(final String validationErrors) {
        this.validationErrors = validationErrors;
    }

    @DynamoDbAttribute("validationWarnings")
    public String getValidationWarnings() {
        return validationWarnings;
    }

    public void setValidationWarnings(final String validationWarnings) {
        this.validationWarnings = validationWarnings;
    }

    @DynamoDbAttribute("canResume")
    public Boolean getCanResume() {
        return canResume;
    }

    public void setCanResume(final Boolean canResume) {
        this.canResume = canResume;
    }

    @DynamoDbAttribute("resumeToken")
    public String getResumeToken() {
        return resumeToken;
    }

    public void setResumeToken(final String resumeToken) {
        this.resumeToken = resumeToken;
    }

    @DynamoDbAttribute("lastProcessedIndex")
    public Integer getLastProcessedIndex() {
        return lastProcessedIndex;
    }

    public void setLastProcessedIndex(final Integer lastProcessedIndex) {
        this.lastProcessedIndex = lastProcessedIndex;
    }

    @DynamoDbAttribute("importBatchId")
    public String getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(final String importBatchId) {
        this.importBatchId = importBatchId;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
        // Auto-populate timestamp for GSI sort key
        this.createdAtTimestamp = createdAt != null ? createdAt.getEpochSecond() : null;
    }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdCreatedAtIndex")
    @DynamoDbAttribute("createdAtTimestamp")
    public Long getCreatedAtTimestamp() {
        return createdAtTimestamp;
    }

    public void setCreatedAtTimestamp(final Long createdAtTimestamp) {
        this.createdAtTimestamp = createdAtTimestamp;
    }
}