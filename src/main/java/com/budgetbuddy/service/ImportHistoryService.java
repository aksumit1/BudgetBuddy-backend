package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.ImportHistory;
import com.budgetbuddy.model.dynamodb.ImportHistoryTable;
import com.budgetbuddy.repository.dynamodb.ImportHistoryRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Import History Service Manages import history and audit trail Migrated to DynamoDB for
 * persistence
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class ImportHistoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportHistoryService.class);

    private final ImportHistoryRepository importHistoryRepository;

    public ImportHistoryService(final ImportHistoryRepository importHistoryRepository) {
        this.importHistoryRepository = importHistoryRepository;
    }

    /** Convert ImportHistoryTable to ImportHistory model */
    private ImportHistory toModel(final ImportHistoryTable table) {
        if (table == null) {
            return null;
        }
        final ImportHistory model = new ImportHistory();
        model.setImportId(table.getImportId());
        model.setUserId(table.getUserId());
        model.setFileName(table.getFileName());
        model.setFileType(table.getFileType());
        model.setImportSource(table.getImportSource());
        model.setStatus(table.getStatus());
        model.setTotalTransactions(
                table.getTotalTransactions() != null ? table.getTotalTransactions() : 0);
        model.setSuccessfulTransactions(
                table.getSuccessfulTransactions() != null ? table.getSuccessfulTransactions() : 0);
        model.setFailedTransactions(
                table.getFailedTransactions() != null ? table.getFailedTransactions() : 0);
        model.setSkippedTransactions(
                table.getSkippedTransactions() != null ? table.getSkippedTransactions() : 0);
        model.setDuplicateTransactions(
                table.getDuplicateTransactions() != null ? table.getDuplicateTransactions() : 0);
        model.setAccountId(table.getAccountId());
        model.setStartedAt(table.getStartedAt());
        model.setCompletedAt(table.getCompletedAt());
        model.setErrorMessage(table.getErrorMessage());
        model.setValidationErrors(table.getValidationErrors());
        model.setValidationWarnings(table.getValidationWarnings());
        model.setCanResume(table.getCanResume() != null ? table.getCanResume() : false);
        model.setResumeToken(table.getResumeToken());
        model.setLastProcessedIndex(
                table.getLastProcessedIndex() != null ? table.getLastProcessedIndex() : 0);
        model.setImportBatchId(table.getImportBatchId());
        model.setCreatedAt(table.getCreatedAt());
        model.setUpdatedAt(table.getUpdatedAt());
        return model;
    }

    /** Convert ImportHistory model to ImportHistoryTable */
    private ImportHistoryTable toTable(final ImportHistory model) {
        if (model == null) {
            return null;
        }
        final ImportHistoryTable table = new ImportHistoryTable();
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

    /** Create a new import history record */
    public ImportHistory createImportHistory(
            final String userId, final String fileName, final String fileType, final String importSource) {
        final ImportHistory history = new ImportHistory();
        history.setUserId(userId);
        history.setFileName(fileName);
        history.setFileType(fileType);
        history.setImportSource(importSource);
        history.setStartedAt(Instant.now());
        history.setStatus("IN_PROGRESS");
        history.setCreatedAt(Instant.now());
        history.setUpdatedAt(Instant.now());

        final ImportHistoryTable table = toTable(history);
        importHistoryRepository.save(table);

        LOGGER.info(
                "Created import history: {} for user: {}, file: {}",
                history.getImportId(),
                userId,
                fileName);
        return history;
    }

    /** Update import history */
    public void updateImportHistory(final ImportHistory history) {
        if (history == null || history.getImportId() == null) {
            LOGGER.warn("Cannot update import history: history or importId is null");
            return;
        }
        history.setUpdatedAt(Instant.now());
        final ImportHistoryTable table = toTable(history);
        importHistoryRepository.save(table);
        LOGGER.debug("Updated import history: {}", history.getImportId());
    }

    /** Complete import history */
    public void completeImportHistory(
            final String importId, final int successful, final int failed, final int skipped, final int duplicates) {
        final Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            final ImportHistoryTable table = tableOpt.get();
            table.setSuccessfulTransactions(successful);
            table.setFailedTransactions(failed);
            table.setSkippedTransactions(skipped);
            table.setDuplicateTransactions(duplicates);
            table.setCompletedAt(Instant.now());
            table.setStatus("COMPLETED");
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            LOGGER.info(
                    "Completed import history: {}, successful: {}, failed: {}, skipped: {}, duplicates: {}",
                    importId,
                    successful,
                    failed,
                    skipped,
                    duplicates);
        } else {
            LOGGER.warn("Import history not found for completion: {}", importId);
        }
    }

    /** Mark import as failed */
    public void failImportHistory(final String importId, final String errorMessage) {
        final Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            final ImportHistoryTable table = tableOpt.get();
            table.setStatus("FAILED");
            table.setErrorMessage(errorMessage);
            table.setCompletedAt(Instant.now());
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            LOGGER.error("Failed import history: {}, error: {}", importId, errorMessage);
        } else {
            LOGGER.warn("Import history not found for failure: {}", importId);
        }
    }

    /** Mark import as partial (can be resumed) */
    public void markPartialImport(final String importId, final int lastProcessedIndex, final String resumeToken) {
        final Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            final ImportHistoryTable table = tableOpt.get();
            table.setStatus("PARTIAL");
            table.setLastProcessedIndex(lastProcessedIndex);
            table.setResumeToken(resumeToken);
            table.setCanResume(true);
            table.setCompletedAt(Instant.now());
            table.setUpdatedAt(Instant.now());
            importHistoryRepository.save(table);
            LOGGER.info(
                    "Marked import as partial: {}, last processed: {}",
                    importId,
                    lastProcessedIndex);
        } else {
            LOGGER.warn("Import history not found for partial marking: {}", importId);
        }
    }

    /** Get import history by ID */
    public Optional<ImportHistory> getImportHistory(final String importId) {
        return importHistoryRepository.findById(importId).map(this::toModel);
    }

    /** Get all import history for a user */
    public List<ImportHistory> getUserImportHistory(final String userId) {
        final List<ImportHistoryTable> tables = importHistoryRepository.findByUserId(userId);
        return tables.stream()
                .map(this::toModel)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** Get import history by status */
    public List<ImportHistory> getImportHistoryByStatus(final String userId, final String status) {
        return getUserImportHistory(userId).stream()
                .filter(h -> status.equals(h.getStatus()))
                .collect(Collectors.toList());
    }

    /** Get resumable imports for a user */
    public List<ImportHistory> getResumableImports(final String userId) {
        return getUserImportHistory(userId).stream()
                .filter(ImportHistory::isCanResume)
                .filter(h -> "PARTIAL".equals(h.getStatus()))
                .collect(Collectors.toList());
    }

    /** Resume a partial import */
    public Optional<ImportHistory> resumeImport(final String importId, final String resumeToken) {
        final Optional<ImportHistoryTable> tableOpt = importHistoryRepository.findById(importId);
        if (tableOpt.isPresent()) {
            final ImportHistoryTable table = tableOpt.get();
            if (Boolean.TRUE.equals(table.getCanResume())
                    && resumeToken != null
                    && resumeToken.equals(table.getResumeToken())) {
                table.setStatus("IN_PROGRESS");
                table.setStartedAt(Instant.now());
                table.setUpdatedAt(Instant.now());
                importHistoryRepository.save(table);
                LOGGER.info("Resumed import: {}", importId);
                return Optional.of(toModel(table));
            }
        }
        return Optional.empty();
    }

    /** Delete import history (for cleanup) */
    public void deleteImportHistory(final String importId) {
        importHistoryRepository.delete(importId);
        LOGGER.info("Deleted import history: {}", importId);
    }

    /** Get import statistics for a user */
    public Map<String, Object> getImportStatistics(final String userId) {
        final List<ImportHistory> history = getUserImportHistory(userId);

        final Map<String, Object> stats = new HashMap<>();
        stats.put("totalImports", history.size());
        stats.put(
                "completedImports",
                history.stream().filter(h -> "COMPLETED".equals(h.getStatus())).count());
        stats.put(
                "failedImports",
                history.stream().filter(h -> "FAILED".equals(h.getStatus())).count());
        stats.put(
                "partialImports",
                history.stream().filter(h -> "PARTIAL".equals(h.getStatus())).count());
        stats.put(
                "totalTransactionsImported",
                history.stream().mapToInt(ImportHistory::getSuccessfulTransactions).sum());
        stats.put(
                "totalTransactionsFailed",
                history.stream().mapToInt(ImportHistory::getFailedTransactions).sum());
        stats.put(
                "totalDuplicates",
                history.stream().mapToInt(ImportHistory::getDuplicateTransactions).sum());

        return stats;
    }
}
