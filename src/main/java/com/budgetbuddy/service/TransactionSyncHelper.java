package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Helper service for transaction synchronization
 * Extracts common logic for syncing Plaid transactions to reduce duplication
 */
@Component
public class TransactionSyncHelper {

    private static final Logger logger = LoggerFactory.getLogger(TransactionSyncHelper.class);

    private final TransactionRepository transactionRepository;

    public TransactionSyncHelper(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Sync a single Plaid transaction to the database
     * Handles duplicate detection, conditional writes, and updates
     *
     * @param transaction Transaction to sync
     * @param plaidTransactionId Plaid transaction ID
     * @return SyncResult with counts of new/updated/error transactions
     */
    public SyncResult syncSingleTransaction(
            final TransactionTable transaction,
            final String plaidTransactionId) {
        SyncResult result = new SyncResult();

        if (transaction == null) {
            logger.warn("Transaction is null, skipping sync");
            result.setErrorCount(1);
            return result;
        }

        if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
            logger.warn("Plaid transaction ID is null or empty, skipping");
            result.setErrorCount(1);
            return result;
        }

        try {
            // Use conditional write to prevent duplicates and race conditions
            if (transaction.getPlaidTransactionId() != null && !transaction.getPlaidTransactionId().isEmpty()) {
                // Use conditional write to prevent duplicate Plaid transactions
                boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                if (saved) {
                    result.setNewCount(1);
                } else {
                    // Transaction already exists, update it
                    Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                    if (existing.isPresent()) {
                        TransactionTable existingTransaction = existing.get();
                        // Note: updateTransactionFromPlaid should be called by the caller
                        // as it depends on the Plaid transaction object structure
                        transactionRepository.save(existingTransaction);
                        result.setUpdatedCount(1);
                    } else {
                        logger.warn("Transaction with Plaid ID {} already exists but could not be retrieved", plaidTransactionId);
                        result.setErrorCount(1);
                    }
                }
            } else {
                // No Plaid ID, use regular save with transactionId check
                if (transactionRepository.saveIfNotExists(transaction)) {
                    result.setNewCount(1);
                } else {
                    // Transaction already exists
                    result.setUpdatedCount(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to sync transaction with Plaid ID {}: {}", plaidTransactionId, e.getMessage(), e);
            result.setErrorCount(1);
        }

        result.setTotalProcessed(result.getNewCount() + result.getUpdatedCount());
        return result;
    }

    /**
     * Sync result for a single transaction
     */
    public static class SyncResult {
        private int newCount = 0;
        private int updatedCount = 0;
        private int errorCount = 0;
        private int totalProcessed = 0;

        public int getNewCount() {
            return newCount;
        }

        public void setNewCount(final int newCount) {
            this.newCount = newCount;
        }

        public int getUpdatedCount() {
            return updatedCount;
        }

        public void setUpdatedCount(final int updatedCount) {
            this.updatedCount = updatedCount;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public void setErrorCount(final int errorCount) {
            this.errorCount = errorCount;
        }

        public int getTotalProcessed() {
            return totalProcessed;
        }

        public void setTotalProcessed(final int totalProcessed) {
            this.totalProcessed = totalProcessed;
        }
    }
}

