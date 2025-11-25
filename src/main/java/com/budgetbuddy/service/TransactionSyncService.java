package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.plaid.PlaidService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    @Autowired
    private PlaidService plaidService;

    @Autowired
    private TransactionRepository transactionRepository;

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

                    // Check if transaction already exists
                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(plaidTransactionId);

                    if (existing.isPresent()) {
                        // Update existing transaction
                        TransactionTable transaction = existing.get();
                        updateTransactionFromPlaid(transaction, plaidTransaction);
                        transactionRepository.save(transaction);
                        updatedCount++;
                    } else {
                        // Create new transaction
                        TransactionTable transaction = createTransactionFromPlaid(userId, plaidTransaction);
                        transactionRepository.save(transaction);
                        newCount++;
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

                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(plaidTransactionId);

                    if (existing.isPresent()) {
                        TransactionTable transaction = existing.get();
                        updateTransactionFromPlaid(transaction, plaidTransaction);
                        transactionRepository.save(transaction);
                        updatedCount++;
                    } else {
                        TransactionTable transaction = createTransactionFromPlaid(userId, plaidTransaction);
                        transactionRepository.save(transaction);
                        newCount++;
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
     */
    private TransactionTable createTransactionFromPlaid(final String userId, final Object plaidTransaction) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(java.util.UUID.randomUUID().toString());
        transaction.setUserId(userId);
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
