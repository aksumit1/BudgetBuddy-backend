package com.budgetbuddy.service;

import com.budgetbuddy.model.ImportHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Import History Service
 * Manages import history and audit trail
 * In-memory implementation (can be migrated to DynamoDB later)
 */
@Service
public class ImportHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ImportHistoryService.class);

    // In-memory storage (can be migrated to DynamoDB)
    private final Map<String, ImportHistory> importHistoryMap = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userImportsMap = new ConcurrentHashMap<>(); // userId -> list of importIds

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

        importHistoryMap.put(history.getImportId(), history);

        // Track user imports
        userImportsMap.computeIfAbsent(userId, k -> new ArrayList<>()).add(history.getImportId());

        logger.info("Created import history: {} for user: {}, file: {}", history.getImportId(), userId, fileName);
        return history;
    }

    /**
     * Update import history
     */
    public void updateImportHistory(ImportHistory history) {
        history.setUpdatedAt(Instant.now());
        importHistoryMap.put(history.getImportId(), history);
        logger.debug("Updated import history: {}", history.getImportId());
    }

    /**
     * Complete import history
     */
    public void completeImportHistory(String importId, int successful, int failed, int skipped, int duplicates) {
        ImportHistory history = importHistoryMap.get(importId);
        if (history != null) {
            history.setSuccessfulTransactions(successful);
            history.setFailedTransactions(failed);
            history.setSkippedTransactions(skipped);
            history.setDuplicateTransactions(duplicates);
            history.setCompletedAt(Instant.now());
            history.setStatus("COMPLETED");
            updateImportHistory(history);
            logger.info("Completed import history: {}, successful: {}, failed: {}, skipped: {}, duplicates: {}",
                    importId, successful, failed, skipped, duplicates);
        }
    }

    /**
     * Mark import as failed
     */
    public void failImportHistory(String importId, String errorMessage) {
        ImportHistory history = importHistoryMap.get(importId);
        if (history != null) {
            history.setStatus("FAILED");
            history.setErrorMessage(errorMessage);
            history.setCompletedAt(Instant.now());
            updateImportHistory(history);
            logger.error("Failed import history: {}, error: {}", importId, errorMessage);
        }
    }

    /**
     * Mark import as partial (can be resumed)
     */
    public void markPartialImport(String importId, int lastProcessedIndex, String resumeToken) {
        ImportHistory history = importHistoryMap.get(importId);
        if (history != null) {
            history.setStatus("PARTIAL");
            history.setLastProcessedIndex(lastProcessedIndex);
            history.setResumeToken(resumeToken);
            history.setCanResume(true);
            history.setCompletedAt(Instant.now());
            updateImportHistory(history);
            logger.info("Marked import as partial: {}, last processed: {}", importId, lastProcessedIndex);
        }
    }

    /**
     * Get import history by ID
     */
    public Optional<ImportHistory> getImportHistory(String importId) {
        return Optional.ofNullable(importHistoryMap.get(importId));
    }

    /**
     * Get all import history for a user
     */
    public List<ImportHistory> getUserImportHistory(String userId) {
        List<String> importIds = userImportsMap.getOrDefault(userId, new ArrayList<>());
        return importIds.stream()
                .map(importHistoryMap::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ImportHistory::getCreatedAt).reversed())
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
        ImportHistory history = importHistoryMap.get(importId);
        if (history != null && history.isCanResume() && resumeToken.equals(history.getResumeToken())) {
            history.setStatus("IN_PROGRESS");
            history.setStartedAt(Instant.now());
            updateImportHistory(history);
            logger.info("Resumed import: {}", importId);
            return Optional.of(history);
        }
        return Optional.empty();
    }

    /**
     * Delete import history (for cleanup)
     */
    public void deleteImportHistory(String importId) {
        ImportHistory history = importHistoryMap.remove(importId);
        if (history != null) {
            userImportsMap.getOrDefault(history.getUserId(), new ArrayList<>()).remove(importId);
            logger.info("Deleted import history: {}", importId);
        }
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

