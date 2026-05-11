package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Data Archiving Service Archives old data to S3 Glacier to minimize database storage costs */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class DataArchivingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataArchivingService.class);
    private static final int ARCHIVE_DAYS = 365; // Archive data older than 1 year
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final DistributedLockService distributedLock;

    public DataArchivingService(
            final TransactionRepository transactionRepository,
            final UserRepository userRepository,
            final S3Service s3Service,
            final ObjectMapper objectMapper,
            final DistributedLockService distributedLock) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
        this.distributedLock = distributedLock;
    }

    /**
     * Archive old transactions to S3 Glacier Runs monthly to minimize storage costs.
     *
     * <p>Distributed-lock guarded so when ECS auto-scales to N tasks the archive runs exactly once
     * per month — otherwise each task would race to S3-PUT the same object key.
     */
    @Scheduled(cron = "0 10 2 1 * ?", zone = "UTC") // First of month 02:10 UTC (staggered)
    public void archiveOldTransactions() {
        final LocalDate today = LocalDate.now();
        final String lockKey = "dataArchiving:" + today.withDayOfMonth(1);
        // 4-hour TTL — archive can iterate over thousands of users with S3 PUTs.
        distributedLock.runOnce(lockKey, 240, this::archiveOldTransactionsInner);
    }

    private void archiveOldTransactionsInner() {
        final LocalDate cutoffDate = LocalDate.now().minusDays(ARCHIVE_DAYS);
        LOGGER.info("Starting transaction archiving for data before {}", cutoffDate);

        try {
            // IMPLEMENTATION: Archive transactions per user using GSI
            // This is more efficient than scanning the entire table
            // For each user, query transactions older than cutoff date using GSI

            // Note: This requires getting all user IDs first
            // In production, you might want to:
            // 1. Maintain a list of active users
            // 2. Or use a separate scheduled job that processes users in batches
            // 3. Or implement TTL + Streams for automatic archiving

            // Get all active user IDs for per-user archiving
            // Note: For very large user bases, consider batching or using pagination
            // Using 365 days (1 year) to get users active in the last year, with a high limit
            final List<String> activeUserIds = userRepository.findActiveUserIds(365, 10_000);
            LOGGER.info("Found {} active users for transaction archiving", activeUserIds.size());

            int totalArchived = 0;
            int usersProcessed = 0;
            int usersWithErrors = 0;

            // Process each user's old transactions
            for (final String userId : activeUserIds) {
                try {
                    // Query transactions older than cutoff date for this user
                    // Using "1970-01-01" as start date to get all transactions up to cutoff
                    final String startDate = "1970-01-01";
                    final String endDateStr = cutoffDate.format(DATE_FORMATTER);

                    final List<TransactionTable> oldTransactions =
                            transactionRepository.findByUserIdAndDateRange(
                                    userId, startDate, endDateStr);

                    if (!oldTransactions.isEmpty()) {
                        LOGGER.info(
                                "Found {} transactions to archive for user {}",
                                oldTransactions.size(),
                                userId);
                        archiveTransactions(oldTransactions);
                        totalArchived += oldTransactions.size();
                    }

                    usersProcessed++;

                    // Log progress every 100 users
                    if (usersProcessed % 100 == 0) {
                        LOGGER.info(
                                "Archiving progress: {} users processed, {} transactions archived",
                                usersProcessed,
                                totalArchived);
                    }
                } catch (Exception e) {
                    LOGGER.error(
                            "Error archiving transactions for user {}: {}",
                            userId,
                            e.getMessage(),
                            e);
                    usersWithErrors++;
                }
            }

            LOGGER.info(
                    "Transaction archiving completed: {} users processed, {} transactions archived, {} errors",
                    usersProcessed,
                    totalArchived,
                    usersWithErrors);

            // Note: For production scale, consider implementing DynamoDB TTL + Streams for
            // automatic archiving
            // This would be more efficient than per-user queries for very large datasets
        } catch (Exception e) {
            LOGGER.error("Error during transaction archiving: {}", e.getMessage(), e);
        }
    }

    /** Archive specific transactions (called from DynamoDB Streams handler) */
    public void archiveTransactions(final List<TransactionTable> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            LOGGER.info("No transactions to archive");
            return;
        }

        try {
            // Compress and upload to S3
            final byte[] compressedData = compressTransactions(transactions);
            final String dateStr = LocalDate.now().format(DATE_FORMATTER);
            final String s3Key = "archive/transactions/" + dateStr + ".gz";

            s3Service.uploadFileInfrequentAccess(
                    s3Key,
                    new ByteArrayInputStream(compressedData),
                    compressedData.length,
                    "application/gzip");

            LOGGER.info("Archived {} transactions to S3", transactions.size());
        } catch (Exception e) {
            LOGGER.error("Error archiving transactions: {}", e.getMessage());
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to archive transactions", e);
        }
    }

    /**
     * Compress transactions using GZIP to minimize storage Uses JSON serialization with Jackson
     * ObjectMapper (injected from Spring)
     */
    private byte[] compressTransactions(final List<TransactionTable> transactions) {
        try {
            // Use injected ObjectMapper (configured in JacksonConfig)
            final String json = objectMapper.writeValueAsString(transactions);

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } catch (Exception e) {
            LOGGER.error("Error compressing transactions: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to compress transactions", e);
        }
    }
}
