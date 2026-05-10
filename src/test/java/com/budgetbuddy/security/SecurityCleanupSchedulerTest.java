package com.budgetbuddy.security;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SecurityCleanupSchedulerTest {

    @Mock private FileQuarantineService fileQuarantineService;

    @Mock private FileUploadRateLimiter fileUploadRateLimiter;

    private SecurityCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new SecurityCleanupScheduler(fileQuarantineService, fileUploadRateLimiter);
    }

    @Test
    void testCleanupQuarantinedFilesShouldCallService() {
        // When
        scheduler.cleanupQuarantinedFiles();

        // Then
        verify(fileQuarantineService, times(1)).cleanupOldQuarantinedFiles();
    }

    @Test
    void testCleanupQuarantinedFilesOnExceptionShouldNotThrow() {
        // Given
        doThrow(new RuntimeException("Test exception"))
                .when(fileQuarantineService)
                .cleanupOldQuarantinedFiles();

        // When/Then - Should not throw
        scheduler.cleanupQuarantinedFiles();
    }

    @Test
    void testCleanupRateLimitEntriesShouldComplete() {
        // When
        scheduler.cleanupRateLimitEntries();

        // Then - Should complete without exception (no actual cleanup needed, just logging)
        scheduler.cleanupRateLimitEntries();
    }

    @Test
    void testGenerateSecurityReportShouldCallService() {
        // Given
        final List<FileQuarantineService.QuarantineRecord> records = new ArrayList<>();
        when(fileQuarantineService.getAllQuarantineRecords()).thenReturn(records);

        // When
        scheduler.generateSecurityReport();

        // Then
        verify(fileQuarantineService, times(1)).getAllQuarantineRecords();
    }

    @Test
    void testGenerateSecurityReportWithRecordsShouldLog() {
        // Given
        final List<FileQuarantineService.QuarantineRecord> records = new ArrayList<>();
        final FileQuarantineService.QuarantineRecord record =
                mock(FileQuarantineService.QuarantineRecord.class);
        records.add(record);
        when(fileQuarantineService.getAllQuarantineRecords()).thenReturn(records);

        // When
        scheduler.generateSecurityReport();

        // Then
        verify(fileQuarantineService, times(1)).getAllQuarantineRecords();
    }

    @Test
    void testGenerateSecurityReportOnExceptionShouldNotThrow() {
        // Given
        when(fileQuarantineService.getAllQuarantineRecords())
                .thenThrow(new RuntimeException("Test exception"));

        // When/Then - Should not throw
        scheduler.generateSecurityReport();
    }
}
