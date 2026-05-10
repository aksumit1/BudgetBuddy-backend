package com.budgetbuddy.audit;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P2: Audit logging service for financial transactions Logs all category/type changes for
 * compliance and debugging
 */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("AUDIT");

    /** Logs a category change for a transaction */
    public void logCategoryChange(
            final String transactionId,
            final String userId,
            final String oldCategory,
            final String newCategory,
            final String source,
            final String reason) {
        AUDIT_LOGGER.info(
                "CATEGORY_CHANGE|transactionId={}|userId={}|oldCategory={}|newCategory={}|source={}|reason={}|timestamp={}",
                transactionId,
                userId,
                oldCategory,
                newCategory,
                source,
                reason,
                Instant.now());

        LOGGER.debug(
                "Category change logged: transactionId={}, old={}, new={}, source={}",
                transactionId,
                oldCategory,
                newCategory,
                source);
    }

    /** Logs a transaction type change */
    public void logTypeChange(
            final String transactionId,
            final String userId,
            final String oldType,
            final String newType,
            final String source,
            final String reason) {
        AUDIT_LOGGER.info(
                "TYPE_CHANGE|transactionId={}|userId={}|oldType={}|newType={}|source={}|reason={}|timestamp={}",
                transactionId,
                userId,
                oldType,
                newType,
                source,
                reason,
                Instant.now());

        LOGGER.debug(
                "Type change logged: transactionId={}, old={}, new={}, source={}",
                transactionId,
                oldType,
                newType,
                source);
    }

    /** Logs a transaction creation */
    public void logTransactionCreation(final TransactionTable transaction, final String source) {
        AUDIT_LOGGER.info(
                "TRANSACTION_CREATED|transactionId={}|userId={}|accountId={}|amount={}|category={}|type={}|source={}|timestamp={}",
                transaction.getTransactionId(),
                transaction.getUserId(),
                transaction.getAccountId(),
                transaction.getAmount(),
                transaction.getCategoryPrimary(),
                transaction.getTransactionType(),
                source,
                Instant.now());
    }

    /** Logs a transaction update */
    public void logTransactionUpdate(
            final String transactionId,
            final String userId,
            final String changes,
            final String source) {
        AUDIT_LOGGER.info(
                "TRANSACTION_UPDATED|transactionId={}|userId={}|changes={}|source={}|timestamp={}",
                transactionId,
                userId,
                changes,
                source,
                Instant.now());
    }

    /** Logs an import operation */
    public void logImport(
            final String userId,
            final String importSource,
            final int transactionCount,
            final String fileName) {
        AUDIT_LOGGER.info(
                "IMPORT|userId={}|source={}|transactionCount={}|fileName={}|timestamp={}",
                userId,
                importSource,
                transactionCount,
                fileName,
                Instant.now());
    }
}
