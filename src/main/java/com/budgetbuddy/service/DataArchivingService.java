package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    public DataArchivingService(final TransactionRepository transactionRepository,
                               final UserRepository userRepository,
                               final S3Service s3Service,
                               final ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    /**
     * Archive old transactions to S3 Glacier
     * Runs monthly to minimize storage costs
     * 
     * IMPLEMENTATION: Uses GSI-based queries per user for efficient archiving
     * For production at scale, consider implementing DynamoDB TTL + Streams:
     * 1. Set TTL attribute on transactions older than ARCHIVE_DAYS
     * 2. Configure DynamoDB Streams to capture deletions
     * 3. Stream handler calls archiveTransactions() for deleted items
     * 
     * Current implementation: Per-user GSI queries (efficient for moderate scale)
     */
    @Scheduled(cron = "0 0 2 1 * ?") // First day of month at 2 AM
    public void archiveOldTransactions() {
        LocalDate cutoffDate = LocalDate.now().minusDays(ARCHIVE_DAYS);
        logger.info("Starting transaction archiving for data before {}", cutoffDate);

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
            List<String> activeUserIds = userRepository.findActiveUserIds(365, 10000);
            logger.info("Found {} active users for transaction archiving", activeUserIds.size());
            
            int totalArchived = 0;
            int usersProcessed = 0;
            int usersWithErrors = 0;
            
            // Process each user's old transactions
            for (String userId : activeUserIds) {
                try {
                    // Query transactions older than cutoff date for this user
                    // Using "1970-01-01" as start date to get all transactions up to cutoff
                    String startDate = "1970-01-01";
                    String endDateStr = cutoffDate.format(DATE_FORMATTER);
                    
                    List<TransactionTable> oldTransactions = transactionRepository.findByUserIdAndDateRange(
                            userId, startDate, endDateStr);
                    
                    if (!oldTransactions.isEmpty()) {
                        logger.info("Found {} transactions to archive for user {}", oldTransactions.size(), userId);
                        archiveTransactions(oldTransactions);
                        totalArchived += oldTransactions.size();
                    }
                    
                    usersProcessed++;
                    
                    // Log progress every 100 users
                    if (usersProcessed % 100 == 0) {
                        logger.info("Archiving progress: {} users processed, {} transactions archived", 
                                usersProcessed, totalArchived);
                    }
                } catch (Exception e) {
                    logger.error("Error archiving transactions for user {}: {}", userId, e.getMessage(), e);
                    usersWithErrors++;
                }
            }
            
            logger.info("Transaction archiving completed: {} users processed, {} transactions archived, {} errors", 
                    usersProcessed, totalArchived, usersWithErrors);
            
            // Note: For production scale, consider implementing DynamoDB TTL + Streams for automatic archiving
            // This would be more efficient than per-user queries for very large datasets
        } catch (Exception e) {
            logger.error("Error during transaction archiving: {}", e.getMessage(), e);
        }
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
     * Uses JSON serialization with Jackson ObjectMapper (injected from Spring)
     */
    private byte[] compressTransactions(List<TransactionTable> transactions) {
        try {
            // Use injected ObjectMapper (configured in JacksonConfig)
            String json = objectMapper.writeValueAsString(transactions);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error compressing transactions: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to compress transactions", e);
        }
    }
}
