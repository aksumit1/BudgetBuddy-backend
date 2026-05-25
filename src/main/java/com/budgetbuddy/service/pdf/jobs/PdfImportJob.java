package com.budgetbuddy.service.pdf.jobs;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Async PDF import job. Created when a user POSTs to /import-pdf — the
 * controller enqueues a job and returns the jobId immediately. The user
 * polls /import-pdf/jobs/{jobId} until status reaches a terminal state.
 *
 * <p>Replaces the synchronous request/response pattern that held the LB
 * connection open for the entire parse + persist cycle (5-15s typical,
 * 60s+ for batch imports). LB timeouts caused partial imports + duplicate
 * tx bugs on retry.
 *
 * <p>Persisted via the same DynamoDbEnhancedClient pattern as
 * TransactionTable; queried by userId via {@code UserIdCreatedAtIndex}
 * so iOS can list a user's recent jobs without scanning.
 */
@DynamoDbBean
public class PdfImportJob {

    /** Terminal-state classification. */
    public enum Status {
        QUEUED,         // Job created, worker hasn't picked it up
        PROCESSING,     // Worker is currently parsing
        COMPLETED,      // Parse + persist done successfully
        FAILED,         // Parse threw; failureReason populated
        CANCELLED       // User cancelled via DELETE
    }

    private String jobId;
    private String userId;
    private String fileName;
    private long fileSizeBytes;
    private String fileSha256;
    private String status;
    private Integer totalTransactions;
    private Integer transactionsCreated;
    private Integer transactionsFailed;
    private String failureReason;
    private String pdfArchivePath;     // populated from PdfRawArchive
    private String diagnosticPath;     // populated from PdfImportDiagnosticStore

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant startedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant completedAt;

    private Long updatedAtTimestamp; // GSI sort for incremental polling

    @DynamoDbPartitionKey
    @DynamoDbAttribute("jobId")
    public String getJobId() { return jobId; }
    public void setJobId(final String v) { this.jobId = v; }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdCreatedAtIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() { return userId; }
    public void setUserId(final String v) { this.userId = v; }

    @DynamoDbAttribute("fileName")
    public String getFileName() { return fileName; }
    public void setFileName(final String v) { this.fileName = v; }

    @DynamoDbAttribute("fileSizeBytes")
    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(final long v) { this.fileSizeBytes = v; }

    @DynamoDbAttribute("fileSha256")
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(final String v) { this.fileSha256 = v; }

    @DynamoDbAttribute("status")
    public String getStatus() { return status; }
    public void setStatus(final String v) { this.status = v; }

    @DynamoDbAttribute("totalTransactions")
    public Integer getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(final Integer v) { this.totalTransactions = v; }

    @DynamoDbAttribute("transactionsCreated")
    public Integer getTransactionsCreated() { return transactionsCreated; }
    public void setTransactionsCreated(final Integer v) { this.transactionsCreated = v; }

    @DynamoDbAttribute("transactionsFailed")
    public Integer getTransactionsFailed() { return transactionsFailed; }
    public void setTransactionsFailed(final Integer v) { this.transactionsFailed = v; }

    @DynamoDbAttribute("failureReason")
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(final String v) { this.failureReason = v; }

    @DynamoDbAttribute("pdfArchivePath")
    public String getPdfArchivePath() { return pdfArchivePath; }
    public void setPdfArchivePath(final String v) { this.pdfArchivePath = v; }

    @DynamoDbAttribute("diagnosticPath")
    public String getDiagnosticPath() { return diagnosticPath; }
    public void setDiagnosticPath(final String v) { this.diagnosticPath = v; }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(final Instant v) { this.createdAt = v; }

    @DynamoDbAttribute("startedAt")
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(final Instant v) { this.startedAt = v; }

    @DynamoDbAttribute("completedAt")
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(final Instant v) { this.completedAt = v; }

    @DynamoDbSecondarySortKey(indexNames = "UserIdCreatedAtIndex")
    @DynamoDbAttribute("updatedAtTimestamp")
    public Long getUpdatedAtTimestamp() { return updatedAtTimestamp; }
    public void setUpdatedAtTimestamp(final Long v) { this.updatedAtTimestamp = v; }
}
