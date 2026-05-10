package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Helper service for transaction synchronization Extracts common logic for syncing Plaid
 * transactions to reduce duplication
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Component
public class TransactionSyncHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionSyncHelper.class);

    private final TransactionRepository transactionRepository;

    public TransactionSyncHelper(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Sync a single Plaid transaction to the database Handles duplicate detection, conditional
     * writes, and updates
     *
     * @param transaction Transaction to sync
     * @param plaidTransactionId Plaid transaction ID
     * @return SyncResult with counts of new/updated/error transactions
     */
    public SyncResult syncSingleTransaction(
            final TransactionTable transaction, final String plaidTransactionId) {
        final SyncResult result = new SyncResult();

        if (transaction == null) {
            LOGGER.warn("Transaction is null, skipping sync");
            result.setErrorCount(1);
            return result;
        }

        if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
            LOGGER.warn("Plaid transaction ID is null or empty, skipping");
            result.setErrorCount(1);
            return result;
        }

        try {
            // Use conditional write to prevent duplicates and race conditions
            if (transaction.getPlaidTransactionId() != null
                    && !transaction.getPlaidTransactionId().isEmpty()) {
                // Use conditional write to prevent duplicate Plaid transactions
                final boolean saved = transactionRepository.saveIfPlaidTransactionNotExists(transaction);
                if (saved) {
                    result.setNewCount(1);
                } else {
                    // Transaction already exists, update it
                    final Optional<TransactionTable> existing =
                            transactionRepository.findByPlaidTransactionId(plaidTransactionId);
                    if (existing.isPresent()) {
                        final TransactionTable existingTransaction = existing.get();
                        // Note: updateTransactionFromPlaid should be called by the caller
                        // as it depends on the Plaid transaction object structure
                        transactionRepository.save(existingTransaction);
                        result.setUpdatedCount(1);
                    } else {
                        LOGGER.warn(
                                "Transaction with Plaid ID {} already exists but could not be retrieved",
                                plaidTransactionId);
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
        } catch (IllegalArgumentException e) {
            // Invalid input - log as WARN since this is a data validation issue
            LOGGER.warn(
                    "Invalid transaction data for Plaid ID {}: {}",
                    plaidTransactionId,
                    e.getMessage());
            result.setErrorCount(1);
        } catch (Exception e) {
            // Real database errors, network issues, etc. - log as ERROR
            LOGGER.error(
                    "Failed to sync transaction with Plaid ID {}: {}",
                    plaidTransactionId,
                    e.getMessage(),
                    e);
            result.setErrorCount(1);
        }

        result.setTotalProcessed(result.getNewCount() + result.getUpdatedCount());
        return result;
    }

    /** Sync result for a single transaction */
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
