package com.budgetbuddy.service;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for filename handling edge cases in AccountDetectionService
 * Tests boundary conditions, special characters, and error handling
 */
@ExtendWith(MockitoExtension.class)
class AccountDetectionFilenameHandlingTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        accountDetectionService = new AccountDetectionService(accountRepository);
    }

    @Test
    void testDetectFromFilename_UnknownFilename_ReturnsNull() {
        // Test that "unknown" filenames are skipped
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("unknown.csv");
        assertNull(result, "Should return null for 'unknown' filename");
    }

    @Test
    void testDetectFromFilename_UnknownPrefix_ReturnsNull() {
        // Test that filenames starting with "unknown" are skipped
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("unknown_file.csv");
        assertNull(result, "Should return null for filename starting with 'unknown'");
    }

    @Test
    void testDetectFromFilename_ImportPrefix_ReturnsNull() {
        // Test that generated import filenames are skipped
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("import_1234567890.csv");
        assertNull(result, "Should return null for generated import filename");
    }

    @Test
    void testDetectFromFilename_UUIDFilename_ReturnsNull() {
        // Test that UUID filenames are skipped
        String uuidFilename = "3BD4A80B-60FD-48AC-B0CF-03302A2A1A77.CSV";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(uuidFilename);
        assertNull(result, "Should return null for UUID filename");
    }

    @Test
    void testDetectFromFilename_UUIDLowercase_ReturnsNull() {
        // Test that lowercase UUID filenames are skipped
        String uuidFilename = "3bd4a80b-60fd-48ac-b0cf-03302a2a1a77.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(uuidFilename);
        assertNull(result, "Should return null for lowercase UUID filename");
    }

    @Test
    void testDetectFromFilename_NormalFilename_Detects() {
        // Test that normal filenames are still detected
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("Chase3100_Activity_29251221.csv");
        assertNotNull(result, "Should detect account from normal filename");
        assertNotNull(result.getInstitutionName(), "Should detect institution name");
    }

    @Test
    void testDetectFromFilename_NullFilename_ReturnsNull() {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(null);
        assertNull(result, "Should return null for null filename");
    }

    @Test
    void testDetectFromFilename_EmptyFilename_ReturnsNull() {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("");
        assertNull(result, "Should return null for empty filename");
    }

    @Test
    void testDetectFromFilename_SpecialCharacters() {
        // Test that special characters in filename don't break detection
        String filename = "Chase 3100 (Activity).csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        // Should not throw exception, may or may not detect depending on pattern matching
        // Main goal is that it doesn't crash
        assertNotNull(result, "Should handle special characters without crashing");
    }

    @Test
    void testDetectFromFilename_VeryLongFilename() {
        // Test that very long filenames don't cause issues
        String longFilename = "A".repeat(300) + ".csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(longFilename);
        // Should not throw exception
        assertNotNull(result, "Should handle very long filenames without crashing");
    }

    @Test
    void testDetectFromFilename_WithSpaces() {
        // Test filenames with spaces
        String filename = "My Bank Account Statement.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        // Should handle gracefully
        assertNotNull(result, "Should handle filenames with spaces");
    }

    @Test
    void testDetectFromFilename_WithUnderscores() {
        // Test filenames with underscores (common pattern)
        String filename = "chase_checking_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result, "Should detect from filename with underscores");
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    void testDetectFromFilename_WithHyphens() {
        // Test filenames with hyphens
        String filename = "chase-checking-1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        // Should handle gracefully
        assertNotNull(result, "Should handle filenames with hyphens");
    }

    @Test
    void testDetectFromFilename_NoExtension() {
        // Test filename without extension
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("Chase3100_Activity");
        // Should still attempt detection
        assertNotNull(result, "Should handle filenames without extension");
    }

    @Test
    void testDetectFromFilename_OnlyExtension() {
        // Test edge case: just extension
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(".csv");
        // Should return null or handle gracefully
        // This is an edge case - may or may not detect, but shouldn't crash
        assertNotNull(result, "Should handle edge case without crashing");
    }
}


