package com.budgetbuddy.service;

import com.budgetbuddy.model.ImportHistory;
import com.budgetbuddy.model.dynamodb.ImportHistoryTable;
import com.budgetbuddy.repository.dynamodb.ImportHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Import History Service
 * Manages import history and audit trail
 * Migrated to DynamoDB for persistence
 */
@Service
public class ImportHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ImportHistoryService.class);

    private final ImportHistoryRepository importHistoryRepository;

    public ImportHistoryService(final ImportHistoryRepository importHistoryRepository) {
        this.importHistoryRepository = importHistoryRepository;
    }

    /**
     * Convert ImportHistoryTable to ImportHistory model
     */
    private ImportHistory toModel(ImportHistoryTable table) {
        if (table == null) {
            return null;
        }
        ImportHistory model = new ImportHistory();
        model.setImportId(table.getImportId());
        model.setUserId(table.getUserId());
        model.setFileName(table.getFileName());
        model.setFileType(table.getFileType());
        model.setImportSource(table.getImportSource());
        model.setStatus(table.getStatus());
        model.setTotalTransactions(table.getTotalTransactions() != null ? table.getTotalTransactions() : 0);
        model.setSuccessfulTransactions(table.getSuccessfulTransactions() != null ? table.getSuccessfulTransactions() : 0);
        model.setFailedTransactions(table.getFailedTransactions() != null ? table.getFailedTransactions() : 0);
        model.setSkippedTransactions(table.getSkippedTransactions() != null ? table.getSkippedTransactions() : 0);
        model.setDuplicateTransactions(table.getDuplicateTransactions() != null ? table.getDuplicateTransactions() : 0);
        model.setAccountId(table.getAccountId());
        model.setStartedAt(table.getStartedAt());
        model.setCompletedAt(table.getCompletedAt());
        model.setErrorMessage(table.getErrorMessage());
        model.setValidationErrors(table.getValidationErrors());
        model.setValidationWarnings(table.getValidationWarnings());
        model.setCanResume(table.getCanResume() != null ? table.getCanResume() : false);
        model.setResumeToken(table.getResumeToken());
        model.setLastProcessedIndex(table.getLastProcessedIndex() != null ? table.getLastProcessedIndex() : 0);
        model.setImportBatchId(table.getImportBatchId());
        model.setCreatedAt(table.getCreatedAt());
        model.setUpdatedAt(table.getUpdatedAt());
        return model;
    }

    /**
     * Convert ImportHistory model to ImportHistoryTable
     */
    private ImportHistoryTable toTable(ImportHistory model) {
        if (model == null) {
            return null;
        }
        ImportHistoryTable table = new ImportHistoryTable();
        table.setImportId(model.getImportId());
        table.setUserId(model.getUserId());
        table.setFileName(model.getFileName());
        table.setFileType(model.getFileType());
        table.setImportSource(model.getImportSource());
        table.setStatus(model.getStatus());
        table.setTotalTransactions(model.getTotalTransactions());
        table.setSuccessfulTransactions(model.getSuccessfulTransactions());
        table.setFailedTransactions(model.getFailedTransactions());
        table.setSkippedTransactions(model.getSkippedTransactions());
        table.setDuplicateTransactions(model.getDuplicateTransactions());
        table.setAccountId(model.getAccountId());
        table.setStartedAt(model.getStartedAt());
        table.setCompletedAt(model.getCompletedAt());
        table.setErrorMessage(model.getErrorMessage());
        table.setValidationErrors(model.getValidationErrors());
        table.setValidationWarnings(model.getValidationWarnings());
        table.setCanResume(model.isCanResume());
        table.setResumeToken(model.getResumeToken());
        table.setLastProcessedIndex(model.getLastProcessedIndex());
        table.setImportBatchId(model.getImportBatchId());
        table.setCreatedAt(model.getCreatedAt() != null ? model.getCreatedAt() : Instant.now());
        table.setUpdatedAt(model.getUpdatedAt() != null ? model.getUpdatedAt() : Instant.now());
        return table;
    }

    /**
     * Create a new import history record
     */
    public ImportHistory createImportHistory(String userId, String fileName, String fileType, String importSource) {
        ImportHistory history = new ImportHistory();
        history.setUserId(userId);
        history.setFileName(fileName);
        history.setFileType(fileType);
        history.setImportSource(importSource);
        history.setStartedAt(Instant.now());
        history.setStatus("IN_PROGRESS");
        history.setCreatedAt(Instant.now());
        history.setUpdatedAt(Instant.now());

        ImportHistoryTable table = toTable(history);
        importHistoryRepository.save(table);

        logger.info("Created import history: {} for user: {}, file: {}", history.getImportId(), userId, fileName);
        return history;
    }

    /**
     * Update import history
     */
    public void updateImportHistory(ImportHistory history) {
        if (history == null || history.getImportId() == null) {
            logger.warn("Cannot update import history: history or importId is null");
            return;
        }
        history.setUpdatedAt(Instant.now());
        ImportHistoryTable table = toTable(history);
        importHistoryRepository.save(table);
        logger.debug("Updated import history: {}", history.getImportId());
    }

    /**
     * Complete import history
     */
    public void completeImportHistory(String importId, int successful, int failed, int skipped, int duplicates) {
        Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            ImportHistoryTable table = tableOpt.get();
            table.setSuccessfulTransactions(successful);
            table.setFailedTransactions(failed);
            table.setSkippedTransactions(skipped);
            table.setDuplicateTransactions(duplicates);
            table.setCompletedAt(Instant.now());
            table.setStatus("COMPLETED");
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            logger.info("Completed import history: {}, successful: {}, failed: {}, skipped: {}, duplicates: {}",
                    importId, successful, failed, skipped, duplicates);
        } else {
            logger.warn("Import history not found for completion: {}", importId);
        }
    }

    /**
     * Mark import as failed
     */
    public void failImportHistory(String importId, String errorMessage) {
        Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            ImportHistoryTable table = tableOpt.get();
            table.setStatus("FAILED");
            table.setErrorMessage(errorMessage);
            table.setCompletedAt(Instant.now());
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            logger.error("Failed import history: {}, error: {}", importId, errorMessage);
        } else {
            logger.warn("Import history not found for failure: {}", importId);
        }
    }

    /**
     * Mark import as partial (can be resumed)
     */
    public void markPartialImport(String importId, int lastProcessedIndex, String resumeToken) {
        Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            ImportHistoryTable table = tableOpt.get();
            table.setStatus("PARTIAL");
            table.setLastProcessedIndex(lastProcessedIndex);
            table.setResumeToken(resumeToken);
            table.setCanResume(true);
            table.setCompletedAt(Instant.now());
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            logger.info("Marked import as partial: {}, last processed: {}", importId, lastProcessedIndex);
        } else {
            logger.warn("Import history not found for partial marking: {}", importId);
        }
    }

    /**
     * Get import history by ID
     */
    public Optional<ImportHistory> getImportHistory(String importId) {
        return importHistoryRepository.findById(importId).map(this::toModel);
    }

    /**
     * Get all import history for a user
     */
    public List<ImportHistory> getUserImportHistory(String userId) {
        List<ImportHistoryTable> tables = importHistoryRepository.findByUserId(userId);
        return tables.stream()
                .map(this::toModel)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get import history by status
     */
    public List<ImportHistory> getImportHistoryByStatus(String userId, String status) {
        return getUserImportHistory(userId).stream()
                .filter(h -> status.equals(h.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Get resumable imports for a user
     */
    public List<ImportHistory> getResumableImports(String userId) {
        return getUserImportHistory(userId).stream()
                .filter(ImportHistory::isCanResume)
                .filter(h -> "PARTIAL".equals(h.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Resume a partial import
     */
    public Optional<ImportHistory> resumeImport(String importId, String resumeToken) {
        Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            ImportHistoryTable table = tableOpt.get();
            if (Boolean.TRUE.equals(table.getCanResume()) && 
                resumeToken != null && resumeToken.equals(table.getResumeToken())) {
                table.setStatus("IN_PROGRESS");
                table.setStartedAt(Instant.now());
                table.setUpdatedAt(Instant.now());
                importHistoryRepository.save(table);
                logger.info("Resumed import: {}", importId);
                return Optional.of(toModel(table));
            }
        }
        return Optional.empty();
    }

    /**
     * Delete import history (for cleanup)
     */
    public void deleteImportHistory(String importId) {
        importHistoryRepository.delete(importId);
        logger.info("Deleted import history: {}", importId);
    }

    /**
     * Get import statistics for a user
     */
    public Map<String, Object> getImportStatistics(String userId) {
        List<ImportHistory> history = getUserImportHistory(userId);
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalImports", history.size());
        stats.put("completedImports", history.stream().filter(h -> "COMPLETED".equals(h.getStatus())).count());
        stats.put("failedImports", history.stream().filter(h -> "FAILED".equals(h.getStatus())).count());
        stats.put("partialImports", history.stream().filter(h -> "PARTIAL".equals(h.getStatus())).count());
        stats.put("totalTransactionsImported", history.stream().mapToInt(ImportHistory::getSuccessfulTransactions).sum());
        stats.put("totalTransactionsFailed", history.stream().mapToInt(ImportHistory::getFailedTransactions).sum());
        stats.put("totalDuplicates", history.stream().mapToInt(ImportHistory::getDuplicateTransactions).sum());
        
        return stats;
    }
}