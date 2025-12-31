package com.budgetbuddy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class SecurityCleanupSchedulerTest {

    @Mock
    private FileQuarantineService fileQuarantineService;

    @Mock
    private FileUploadRateLimiter fileUploadRateLimiter;

    private SecurityCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new SecurityCleanupScheduler(fileQuarantineService, fileUploadRateLimiter);
    }

    @Test
    void testCleanupQuarantinedFiles_ShouldCallService() {
        // When
        scheduler.cleanupQuarantinedFiles();
        
        // Then
        verify(fileQuarantineService, times(1)).cleanupOldQuarantinedFiles();
    }

    @Test
    void testCleanupQuarantinedFiles_OnException_ShouldNotThrow() {
        // Given
        doThrow(new RuntimeException("Test exception")).when(fileQuarantineService).cleanupOldQuarantinedFiles();
        
        // When/Then - Should not throw
        scheduler.cleanupQuarantinedFiles();
    }

    @Test
    void testCleanupRateLimitEntries_ShouldComplete() {
        // When
        scheduler.cleanupRateLimitEntries();
        
        // Then - Should complete without exception (no actual cleanup needed, just logging)
        scheduler.cleanupRateLimitEntries();
    }

    @Test
    void testGenerateSecurityReport_ShouldCallService() {
        // Given
        List<FileQuarantineService.QuarantineRecord> records = new ArrayList<>();
        when(fileQuarantineService.getAllQuarantineRecords()).thenReturn(records);
        
        // When
        scheduler.generateSecurityReport();
        
        // Then
        verify(fileQuarantineService, times(1)).getAllQuarantineRecords();
    }

    @Test
    void testGenerateSecurityReport_WithRecords_ShouldLog() {
        // Given
        List<FileQuarantineService.QuarantineRecord> records = new ArrayList<>();
        FileQuarantineService.QuarantineRecord record = mock(FileQuarantineService.QuarantineRecord.class);
        records.add(record);
        when(fileQuarantineService.getAllQuarantineRecords()).thenReturn(records);
        
        // When
        scheduler.generateSecurityReport();
        
        // Then
        verify(fileQuarantineService, times(1)).getAllQuarantineRecords();
    }

    @Test
    void testGenerateSecurityReport_OnException_ShouldNotThrow() {
        // Given
        when(fileQuarantineService.getAllQuarantineRecords()).thenThrow(new RuntimeException("Test exception"));
        
        // When/Then - Should not throw
        scheduler.generateSecurityReport();
    }
}

