package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.aws.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Data Archiving Service
 * Migrated to DynamoDB
 * Archives old data to S3 Glacier to minimize database storage costs
 */
@Service
public class DataArchivingService {

    private static final Logger logger = LoggerFactory.getLogger(DataArchivingService.class);
    private static final int ARCHIVE_DAYS = 365; // Archive data older than 1 year
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final S3Service s3Service;

    public DataArchivingService(final TransactionRepository transactionRepository, final S3Service s3Service) {
        this.transactionRepository = transactionRepository;
        this.s3Service = s3Service;
    }

    /**
     * Archive old transactions to S3 Glacier
     * Runs monthly to minimize storage costs
     * Note: DynamoDB doesn't support date-based queries efficiently, so we use scan with filter
     */
    @Scheduled(cron = "0 0 2 1 * ?") // First day of month at 2 AM
    public void archiveOldTransactions() {
        LocalDate cutoffDate = LocalDate.now().minusDays(ARCHIVE_DAYS);
        String cutoffDateStr = cutoffDate.format(DATE_FORMATTER);
        logger.info("Starting transaction archiving for data before {}", cutoffDate);

        // Note: DynamoDB scan is expensive, consider using DynamoDB Streams or TTL for automatic archiving
        // For now, we'll need to scan all transactions and filter by date
        // This is a limitation - in production, consider using DynamoDB TTL feature
        logger.warn("DynamoDB scan operation is expensive. Consider using DynamoDB TTL for automatic archiving.");

        // Since DynamoDB doesn't support efficient date range queries without GSI,
        // we would need to implement a different strategy:
        // 1. Use DynamoDB TTL to automatically expire old items
        // 2. Use DynamoDB Streams to capture deletions and archive to S3
        // 3. Or maintain a separate archive table with TTL

        // For now, this method is a placeholder - actual implementation would require
        // DynamoDB Streams or a scheduled job that processes items with TTL
        logger.info("Transaction archiving requires DynamoDB Streams or TTL implementation");
    }

    /**
     * Archive specific transactions (called from DynamoDB Streams handler)
     */
    public void archiveTransactions(final List<TransactionTable> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            logger.info("No transactions to archive");
            return;
        }

        try {
            // Compress and upload to S3
            byte[] compressedData = compressTransactions(transactions);
            String dateStr = LocalDate.now().format(DATE_FORMATTER);
            String s3Key = "archive/transactions/" + dateStr + ".gz";

            s3Service.uploadFileInfrequentAccess(
                    s3Key,
                    new ByteArrayInputStream(compressedData),
                    compressedData.length,
                    "application/gzip"
            );

            logger.info("Archived {} transactions to S3", transactions.size());
        } catch (Exception e) {
            logger.error("Error archiving transactions: {}", e.getMessage());
            throw new RuntimeException("Failed to archive transactions", e);
        }
    }

    /**
     * Compress transactions using GZIP to minimize storage
     */
    private byte[] compressTransactions(List<TransactionTable> transactions) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
                 ObjectOutputStream oos = new ObjectOutputStream(gzos)) {
                oos.writeObject(transactions);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error compressing transactions: {}", e.getMessage());
            throw new RuntimeException("Failed to compress transactions", e);
        }
    }
}
