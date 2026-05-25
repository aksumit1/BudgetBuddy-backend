package com.budgetbuddy.service.pdf.jobs;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.PDFImportService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Background worker that processes queued {@link PdfImportJob}s. The
 * controller stores the PDF bytes to a scratch path on disk + enqueues a
 * job; this worker picks up the job, reads the bytes, calls
 * {@link PDFImportService#parsePDF}, persists transactions, and updates
 * the job state.
 *
 * <p>Single-process executor for now (1 ECS task handles its own queue).
 * Multi-instance fanout is a future change — the {@link PdfImportJobService}
 * would gain a {@code claim()} method backed by a DDB conditional write.
 */
@Service
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
public class PdfImportJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfImportJobWorker.class);

    @Value("${app.pdf.async.scratch-dir:/tmp/pdf-import-scratch}")
    private String scratchDir;

    @Value("${app.pdf.async.worker-threads:2}")
    private int workerThreads;

    private final PdfImportJobService jobService;
    private final PDFImportService pdfImportService;
    private final UserRepository userRepository;
    private final PdfTransactionPersister persister;
    private ExecutorService executor;

    @Autowired
    public PdfImportJobWorker(
            final PdfImportJobService jobService,
            final PDFImportService pdfImportService,
            final UserRepository userRepository,
            final PdfTransactionPersister persister) {
        this.jobService = jobService;
        this.pdfImportService = pdfImportService;
        this.userRepository = userRepository;
        this.persister = persister;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.executor = Executors.newFixedThreadPool(workerThreads, r -> {
            final Thread t = new Thread(r);
            // Thread.getId() was deprecated in JDK 21; threadId() is the replacement.
            t.setName("pdf-import-worker-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdown();
    }

    /**
     * Persist PDF bytes to scratch and enqueue async work. Returns the
     * scratch path (jobId.pdf). Called by the controller from the
     * synchronous request handler.
     */
    public Path persistScratch(final String jobId, final byte[] pdfBytes) throws IOException {
        final Path dir = java.nio.file.Paths.get(scratchDir);
        Files.createDirectories(dir);
        final Path file = dir.resolve(jobId + ".pdf");
        Files.write(file, pdfBytes);
        return file;
    }

    /**
     * Schedule background execution. The controller calls this immediately
     * after returning the jobId to the user. Job-state transitions go
     * through {@link PdfImportJobService}.
     */
    public void scheduleAsyncProcess(
            final PdfImportJob job, final Path scratchFile, final String fileName) {
        try {
            executor.submit(() -> processJob(job, scratchFile, fileName));
        } catch (final RejectedExecutionException e) {
            jobService.markFailed(job, "Worker queue full — please retry");
            LOGGER.warn("worker queue full; job {} marked failed", job.getJobId());
        }
    }

    private void processJob(final PdfImportJob job, final Path scratchFile,
                             final String fileName) {
        // Multi-instance safety: claim() is a conditional-write that
        // transitions QUEUED → PROCESSING atomically. When two ECS tasks
        // race for the same job, exactly one wins; the loser skips
        // immediately. Single-process deployments race-free at the
        // executor layer, so this is a no-op overhead until horizontal
        // scaling kicks in.
        if (!jobService.claim(job.getJobId())) {
            LOGGER.info("worker: jobId {} already claimed by another worker, skipping",
                    job.getJobId());
            // Clean scratch — another worker has its own copy. Do not
            // mark the job failed; the winning worker will update state.
            try { Files.deleteIfExists(scratchFile); } catch (final IOException ignored) { }
            return;
        }
        try {
            final UserTable user = userRepository.findById(job.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "job's user no longer exists: " + job.getUserId()));
            final byte[] pdfBytes = Files.readAllBytes(scratchFile);
            final PDFImportService.ImportResult result;
            try (final var in = new ByteArrayInputStream(pdfBytes)) {
                result = pdfImportService.parsePDF(in, fileName, user.getUserId(), null);
            }
            final PdfTransactionPersister.Tally tally = persister.persist(
                    user, result.getTransactions(), "PDF", fileName);
            jobService.markCompleted(job, tally.total(), tally.created(), tally.failed());
        } catch (final Exception e) {
            LOGGER.warn("job {} failed: {}", job.getJobId(), e.getMessage(), e);
            jobService.markFailed(job, e.getMessage() == null
                    ? "Unknown error" : e.getMessage());
        } finally {
            // Always clean up scratch — the raw archive (if enabled) has
            // already captured the bytes for forensic re-parsing.
            try {
                Files.deleteIfExists(scratchFile);
            } catch (final IOException ignored) { }
        }
    }
}
