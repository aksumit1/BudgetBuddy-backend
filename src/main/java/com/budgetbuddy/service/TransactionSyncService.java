package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.plaid.PlaidService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Transaction Sync Service
 * Handles real-time and scheduled transaction synchronization
 *
 * Features:
 * - Full and incremental sync
 * - Async processing
 * - Error handling
 * - Transaction mapping
 */
@Service
public class TransactionSyncService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionSyncService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_SYNC_DAYS = 30;

    private final PlaidService plaidService;
    private final TransactionRepository transactionRepository;

    public TransactionSyncService(
            final PlaidService plaidService,
            final TransactionRepository transactionRepository) {
        this.plaidService = plaidService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Sync transactions for a user
     * Fetches transactions from Plaid and updates local database
     */
    @Async
    public CompletableFuture<SyncResult> syncTransactions(String userId, String accessToken) {
        if (userId == null || userId.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            logger.error("Invalid parameters for transaction sync: userId={}, accessToken={}",
                    userId != null ? "present" : "null", accessToken != null ? "present" : "null");
            SyncResult errorResult = new SyncResult();
            errorResult.setErrorCount(1);
            return CompletableFuture.completedFuture(errorResult);
        }

        try {
            logger.info("Starting transaction sync for user: {}", userId);

            // Get transactions from last 30 days
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(DEFAULT_SYNC_DAYS);

            var plaidResponse = plaidService.getTransactions(
                    accessToken,
                    startDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );

            if (plaidResponse == null || plaidResponse.getTransactions() == null) {
                logger.warn("No transactions returned from Plaid for user: {}", userId);
                SyncResult result = new SyncResult();
                result.setTotalProcessed(0);
                return CompletableFuture.completedFuture(result);
            }

            int newCount = 0;
            int updatedCount = 0;
            int errorCount = 0;

            for (var plaidTransaction : plaidResponse.getTransactions()) {
                try {
                    String plaidTransactionId = extractPlaidTransactionId(plaidTransaction);
                    if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                        logger.warn("Plaid transaction ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }

                    // Use conditional write to prevent duplicates and race conditions
                    TransactionTable transaction = createTransactionFromPlaid(userId, plaidTransaction, plaidTransactionId);
                    // plaidTransactionId is always set in createTransactionFromPlaid, so use Plaid deduplication
                    boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                    if (saved) {
                        newCount++;
                    } else {
                        // Transaction already exists, update it
                        Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                        if (existing.isPresent()) {
                            transaction = existing.get();
                            updateTransactionFromPlaid(transaction, plaidTransaction);
                            transactionRepository.save(transaction);
                            updatedCount++;
                        } else {
                            logger.warn("Transaction with Plaid ID {} already exists but could not be retrieved", plaidTransactionId);
                            errorCount++;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to sync transaction: {}", e.getMessage(), e);
                    errorCount++;
                }
            }

            SyncResult result = new SyncResult();
            result.setNewCount(newCount);
            result.setUpdatedCount(updatedCount);
            result.setErrorCount(errorCount);
            result.setTotalProcessed(newCount + updatedCount + errorCount);

            logger.info("Transaction sync completed for user: {} - New: {}, Updated: {}, Errors: {}",
                    userId, newCount, updatedCount, errorCount);

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Transaction sync failed for user {}: {}", userId, e.getMessage(), e);
            SyncResult result = new SyncResult();
            result.setErrorCount(1);
            result.setErrorMessage(e.getMessage());
            return CompletableFuture.completedFuture(result);
        }
    }

    /**
     * Sync transactions incrementally (only new/updated)
     */
    @Async
    public CompletableFuture<SyncResult> syncIncremental(String userId, String accessToken,
                                                         LocalDate sinceDate) {
        if (userId == null || userId.isEmpty() || accessToken == null || accessToken.isEmpty()) {
            logger.error("Invalid parameters for incremental sync");
            SyncResult errorResult = new SyncResult();
            errorResult.setErrorCount(1);
            return CompletableFuture.completedFuture(errorResult);
        }

        if (sinceDate == null) {
            sinceDate = LocalDate.now().minusDays(DEFAULT_SYNC_DAYS);
        }

        try {
            logger.info("Starting incremental transaction sync for user: {} since: {}", userId, sinceDate);

            LocalDate endDate = LocalDate.now();

            var plaidResponse = plaidService.getTransactions(
                    accessToken,
                    sinceDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER)
            );

            if (plaidResponse == null || plaidResponse.getTransactions() == null) {
                logger.warn("No transactions returned from Plaid for incremental sync");
                SyncResult result = new SyncResult();
                result.setTotalProcessed(0);
                return CompletableFuture.completedFuture(result);
            }

            int newCount = 0;
            int updatedCount = 0;

            for (var plaidTransaction : plaidResponse.getTransactions()) {
                try {
                    String plaidTransactionId = extractPlaidTransactionId(plaidTransaction);
                    if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
                        continue;
                    }

                    // Use conditional write to prevent duplicates and race conditions
                    TransactionTable transaction = createTransactionFromPlaid(userId, plaidTransaction, plaidTransactionId);
                    // plaidTransactionId is always set in createTransactionFromPlaid, so use Plaid deduplication
                    boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                    if (saved) {
                        newCount++;
                    } else {
                        // Transaction already exists, update it
                        Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                        if (existing.isPresent()) {
                            transaction = existing.get();
                            updateTransactionFromPlaid(transaction, plaidTransaction);
                            transactionRepository.save(transaction);
                            updatedCount++;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to sync transaction in incremental sync: {}", e.getMessage());
                }
            }

            SyncResult result = new SyncResult();
            result.setNewCount(newCount);
            result.setUpdatedCount(updatedCount);
            result.setTotalProcessed(newCount + updatedCount);

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Incremental sync failed for user {}: {}", userId, e.getMessage(), e);
            SyncResult result = new SyncResult();
            result.setErrorCount(1);
            result.setErrorMessage(e.getMessage());
            return CompletableFuture.completedFuture(result);
        }
    }

    /**
     * Extract Plaid transaction ID from Plaid transaction object
     */
    private String extractPlaidTransactionId(final Object plaidTransaction) {
        // In production, properly extract transaction ID from Plaid transaction object
        // This is a placeholder - actual implementation depends on Plaid SDK structure
        try {
            // Use reflection or proper Plaid SDK methods to get transaction ID
            return plaidTransaction.toString(); // Placeholder
        } catch (Exception e) {
            logger.error("Failed to extract Plaid transaction ID", e);
            return null;
        }
    }

    /**
     * Create TransactionTable from Plaid transaction
     * Uses Plaid transaction ID as transactionId to ensure consistency between backend and app
     * Sets plaidTransactionId to enable proper Plaid-based deduplication
     */
    private TransactionTable createTransactionFromPlaid(
            final String userId,
            final Object plaidTransaction,
            final String plaidTransactionId) {
        TransactionTable transaction = new TransactionTable();
        
        // Use Plaid transaction ID as transactionId if it's a valid UUID format
        // Otherwise, generate a deterministic UUID from the Plaid ID (UUID v5)
        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            try {
                // Try to use Plaid ID directly if it's a valid UUID
                java.util.UUID.fromString(plaidTransactionId);
                transaction.setTransactionId(plaidTransactionId);
                logger.debug("Using Plaid transaction ID as transactionId: {}", plaidTransactionId);
            } catch (IllegalArgumentException e) {
                // Plaid ID is not a valid UUID format - generate deterministic UUID v5 from it
                // Using UUID v5 (SHA-1 based) for deterministic UUID generation
                java.util.UUID namespaceUUID = java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"); // DNS namespace
                transaction.setTransactionId(java.util.UUID.nameUUIDFromBytes(
                    (namespaceUUID.toString() + plaidTransactionId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ).toString());
                logger.debug("Generated deterministic UUID from Plaid transaction ID: {} -> {}", 
                    plaidTransactionId, transaction.getTransactionId());
            }
        } else {
            // Fallback: generate random UUID if Plaid ID is missing
            transaction.setTransactionId(java.util.UUID.randomUUID().toString());
            logger.warn("Plaid transaction ID is null or empty, generated random UUID: {}", transaction.getTransactionId());
        }
        
        transaction.setUserId(userId);
        transaction.setPlaidTransactionId(plaidTransactionId); // CRITICAL: Set Plaid ID for deduplication
        // Map other fields from plaidTransaction
        // In production, properly map all fields from Plaid transaction
        transaction.setCreatedAt(java.time.Instant.now());
        transaction.setUpdatedAt(java.time.Instant.now());
        return transaction;
    }

    /**
     * Update TransactionTable from Plaid transaction
     */
    private void updateTransactionFromPlaid(final TransactionTable transaction, final Object plaidTransaction) {
        // Update transaction fields from Plaid
        // In production, properly map all fields from Plaid transaction
        transaction.setUpdatedAt(java.time.Instant.now());
    }

    /**
     * Sync Result DTO
     */
    public static class SyncResult {
        private int newCount;
        private int updatedCount;
        private int errorCount;
        private int totalProcessed;
        private String errorMessage;

        public int getNewCount() { return newCount; }
        public void setNewCount(final int newCount) { this.newCount = newCount; }
        public int getUpdatedCount() { return updatedCount; }
        public void setUpdatedCount(final int updatedCount) { this.updatedCount = updatedCount; }
        public int getErrorCount() { return errorCount; }
        public void setErrorCount(final int errorCount) { this.errorCount = errorCount; }
        public int getTotalProcessed() { return totalProcessed; }
        public void setTotalProcessed(final int totalProcessed) { this.totalProcessed = totalProcessed; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(final String errorMessage) { this.errorMessage = errorMessage; }
    }
}
