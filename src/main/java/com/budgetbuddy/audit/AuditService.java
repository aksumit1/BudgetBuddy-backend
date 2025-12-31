package com.budgetbuddy.audit;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * P2: Audit logging service for financial transactions
 * Logs all category/type changes for compliance and debugging
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    /**
     * Logs a category change for a transaction
     */
    public void logCategoryChange(String transactionId, String userId, 
                                  String oldCategory, String newCategory,
                                  String source, String reason) {
        auditLogger.info("CATEGORY_CHANGE|transactionId={}|userId={}|oldCategory={}|newCategory={}|source={}|reason={}|timestamp={}",
            transactionId, userId, oldCategory, newCategory, source, reason, Instant.now());
        
        logger.debug("Category change logged: transactionId={}, old={}, new={}, source={}", 
            transactionId, oldCategory, newCategory, source);
    }

    /**
     * Logs a transaction type change
     */
    public void logTypeChange(String transactionId, String userId,
                             String oldType, String newType,
                             String source, String reason) {
        auditLogger.info("TYPE_CHANGE|transactionId={}|userId={}|oldType={}|newType={}|source={}|reason={}|timestamp={}",
            transactionId, userId, oldType, newType, source, reason, Instant.now());
        
        logger.debug("Type change logged: transactionId={}, old={}, new={}, source={}", 
            transactionId, oldType, newType, source);
    }

    /**
     * Logs a transaction creation
     */
    public void logTransactionCreation(TransactionTable transaction, String source) {
        auditLogger.info("TRANSACTION_CREATED|transactionId={}|userId={}|accountId={}|amount={}|category={}|type={}|source={}|timestamp={}",
            transaction.getTransactionId(), transaction.getUserId(), transaction.getAccountId(),
            transaction.getAmount(), transaction.getCategoryPrimary(), transaction.getTransactionType(),
            source, Instant.now());
    }

    /**
     * Logs a transaction update
     */
    public void logTransactionUpdate(String transactionId, String userId, String changes, String source) {
        auditLogger.info("TRANSACTION_UPDATED|transactionId={}|userId={}|changes={}|source={}|timestamp={}",
            transactionId, userId, changes, source, Instant.now());
    }

    /**
     * Logs an import operation
     */
    public void logImport(String userId, String importSource, int transactionCount, String fileName) {
        auditLogger.info("IMPORT|userId={}|source={}|transactionCount={}|fileName={}|timestamp={}",
            userId, importSource, transactionCount, fileName, Instant.now());
    }
}

