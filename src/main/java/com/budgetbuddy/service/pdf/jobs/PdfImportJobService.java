package com.budgetbuddy.service.pdf.jobs;

import com.budgetbuddy.service.pdf.jobs.PdfImportJob.Status;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * CRUD facade for {@link PdfImportJob} rows. The controller calls
 * {@code create} to enqueue, the background worker calls {@code update}
 * to transition states, and the polling endpoint calls {@code findById}.
 *
 * <p>Scaffolded for B7 (async PDF import). The actual job-execution
 * worker (PdfImportJobWorker) and the controller wiring follow in a
 * second pass — this layer is the persistence boundary.
 */
@Service
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
public class PdfImportJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfImportJobService.class);
    private static final String TABLE = "BudgetBuddy-PdfImportJobs";

    private final DynamoDbTable<PdfImportJob> jobTable;

    public PdfImportJobService(final DynamoDbEnhancedClient enhancedClient) {
        this.jobTable = enhancedClient.table(TABLE,
                TableSchema.fromBean(PdfImportJob.class));
    }

    /**
     * Create a new QUEUED job and return it. Caller (the controller) gets
     * the jobId and returns it to the user immediately while the actual
     * parse happens out-of-band.
     */
    public PdfImportJob create(final String userId, final String fileName,
                                final long fileSizeBytes, final String sha256) {
        final PdfImportJob job = new PdfImportJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setUserId(userId);
        job.setFileName(fileName);
        job.setFileSizeBytes(fileSizeBytes);
        job.setFileSha256(sha256);
        job.setStatus(Status.QUEUED.name());
        final Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setUpdatedAtTimestamp(now.getEpochSecond());
        jobTable.putItem(job);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("PdfImportJob created: jobId={} user={} file={} size={}",
                    job.getJobId(), userId, fileName, fileSizeBytes);
        }
        return job;
    }

    /** Mark the job as PROCESSING (worker picked it up). */
    public void markProcessing(final PdfImportJob job) {
        job.setStatus(Status.PROCESSING.name());
        job.setStartedAt(Instant.now());
        job.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        jobTable.putItem(job);
    }

    /**
     * Atomic claim — used by multi-instance worker fanout. Transitions
     * the job from QUEUED to PROCESSING via a conditional-write that only
     * succeeds when the current row's status is still QUEUED. When two
     * ECS tasks race for the same job, exactly one wins; the other gets
     * a {@code false} return and skips processing.
     *
     * <p>Single-process deployments don't need to call this — the
     * scheduling executor in {@link PdfImportJobWorker} already gives
     * single-thread-per-job guarantees within one JVM. As soon as the
     * service runs on more than one ECS task, every worker MUST call
     * {@code claim} before doing real work.
     *
     * @return true when this caller successfully claimed the job; false
     *         when another worker already claimed it (or the job is gone)
     */
    public boolean claim(final String jobId) {
        if (jobId == null || jobId.isBlank()) return false;
        final PdfImportJob fetched = findById(jobId);
        if (fetched == null) {
            LOGGER.debug("claim: jobId {} not found", jobId);
            return false;
        }
        // Only claim if status is currently QUEUED. Any other state
        // (already PROCESSING, COMPLETED, FAILED, CANCELLED) means
        // another worker got there first or the job was terminated.
        if (!Status.QUEUED.name().equals(fetched.getStatus())) {
            LOGGER.debug("claim: jobId {} not claimable, status={}",
                    jobId, fetched.getStatus());
            return false;
        }
        try {
            final Instant now = Instant.now();
            fetched.setStatus(Status.PROCESSING.name());
            fetched.setStartedAt(now);
            fetched.setUpdatedAtTimestamp(now.getEpochSecond());
            // Conditional putItem: succeeds only when stored status is
            // still QUEUED. If another worker claimed between our read
            // and write, this throws ConditionalCheckFailedException.
            jobTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest
                            .builder(PdfImportJob.class)
                            .item(fetched)
                            .conditionExpression(
                                    software.amazon.awssdk.enhanced.dynamodb.Expression
                                            .builder()
                                            .expression("#s = :queued")
                                            .putExpressionName("#s", "status")
                                            .putExpressionValue(":queued",
                                                    software.amazon.awssdk.services.dynamodb.model.AttributeValue
                                                            .builder().s(Status.QUEUED.name()).build())
                                            .build())
                            .build());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("claim: jobId {} claimed by this worker", jobId);
            }
            return true;
        } catch (final software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            // Another worker beat us. Expected under contention; debug-level.
            LOGGER.debug("claim: jobId {} lost the race ({})", jobId, e.getMessage());
            return false;
        } catch (final RuntimeException e) {
            // Anything else (network, throttling, etc.) — log warn and
            // return false so the caller skips, no orphan PROCESSING row.
            LOGGER.warn("claim: jobId {} threw during conditional write: {}",
                    jobId, e.getMessage());
            return false;
        }
    }

    /** Mark the job as COMPLETED with the parse result counts. */
    public void markCompleted(final PdfImportJob job, final int totalTx,
                               final int created, final int failed) {
        job.setStatus(Status.COMPLETED.name());
        job.setTotalTransactions(totalTx);
        job.setTransactionsCreated(created);
        job.setTransactionsFailed(failed);
        final Instant now = Instant.now();
        job.setCompletedAt(now);
        job.setUpdatedAtTimestamp(now.getEpochSecond());
        jobTable.putItem(job);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("PdfImportJob completed: jobId={} total={} created={} failed={}",
                    job.getJobId(), totalTx, created, failed);
        }
    }

    /** Mark the job as FAILED with a redacted failure reason for the user. */
    public void markFailed(final PdfImportJob job, final String reason) {
        job.setStatus(Status.FAILED.name());
        job.setFailureReason(reason == null ? "Unknown error" : reason);
        final Instant now = Instant.now();
        job.setCompletedAt(now);
        job.setUpdatedAtTimestamp(now.getEpochSecond());
        jobTable.putItem(job);
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn("PdfImportJob failed: jobId={} reason={}", job.getJobId(), reason);
        }
    }

    /** Polling endpoint backend. Returns null when jobId doesn't exist. */
    public PdfImportJob findById(final String jobId) {
        if (jobId == null || jobId.isBlank()) return null;
        final software.amazon.awssdk.enhanced.dynamodb.Key key =
                software.amazon.awssdk.enhanced.dynamodb.Key.builder()
                        .partitionValue(jobId)
                        .build();
        return jobTable.getItem(key);
    }

    /** Authorization helper: returns true when the job belongs to the user. */
    public boolean isOwnedBy(final PdfImportJob job, final String userId) {
        return job != null && job.getUserId() != null
                && job.getUserId().equals(userId);
    }
}
