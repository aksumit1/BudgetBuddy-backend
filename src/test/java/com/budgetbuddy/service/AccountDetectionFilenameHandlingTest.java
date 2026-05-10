package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for filename handling edge cases in AccountDetectionService Tests boundary conditions,
 * special characters, and error handling
 */
@ExtendWith(MockitoExtension.class)
class AccountDetectionFilenameHandlingTest {

    @Mock private AccountRepository accountRepository;

    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        accountDetectionService =
                new AccountDetectionService(
                        accountRepository, new com.budgetbuddy.service.BalanceExtractor());
    }

    @Test
    void testDetectFromFilenameUnknownFilenameReturnsNull() {
        // Test that "unknown" filenames are skipped
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("unknown.csv");
        assertNull(result, "Should return null for 'unknown' filename");
    }

    @Test
    void testDetectFromFilenameUnknownPrefixReturnsNull() {
        // Test that filenames starting with "unknown" are skipped
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("unknown_file.csv");
        assertNull(result, "Should return null for filename starting with 'unknown'");
    }

    @Test
    void testDetectFromFilenameImportPrefixReturnsNull() {
        // Test that generated import filenames are skipped
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("import_1234567890.csv");
        assertNull(result, "Should return null for generated import filename");
    }

    @Test
    void testDetectFromFilenameUUIDFilenameReturnsNull() {
        // Test that UUID filenames are skipped
        final String uuidFilename = "3BD4A80B-60FD-48AC-B0CF-03302A2A1A77.CSV";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(uuidFilename);
        assertNull(result, "Should return null for UUID filename");
    }

    @Test
    void testDetectFromFilenameUUIDLowercaseReturnsNull() {
        // Test that lowercase UUID filenames are skipped
        final String uuidFilename = "3bd4a80b-60fd-48ac-b0cf-03302a2a1a77.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(uuidFilename);
        assertNull(result, "Should return null for lowercase UUID filename");
    }

    @Test
    void testDetectFromFilenameNormalFilenameDetects() {
        // Test that normal filenames are still detected
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("Chase3100_Activity_29251221.csv");
        assertNotNull(result, "Should detect account from normal filename");
        assertNotNull(result.getInstitutionName(), "Should detect institution name");
    }

    @Test
    void testDetectFromFilenameNullFilenameReturnsNull() {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(null);
        assertNull(result, "Should return null for null filename");
    }

    @Test
    void testDetectFromFilenameEmptyFilenameReturnsNull() {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("");
        assertNull(result, "Should return null for empty filename");
    }

    @Test
    void testDetectFromFilenameSpecialCharacters() {
        // Test that special characters in filename don't break detection
        final String filename = "Chase 3100 (Activity).csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        // Should not throw exception, may or may not detect depending on pattern matching
        // Main goal is that it doesn't crash
        assertNotNull(result, "Should handle special characters without crashing");
    }

    @Test
    void testDetectFromFilenameVeryLongFilename() {
        // Test that very long filenames don't cause issues
        final String longFilename = "A".repeat(300) + ".csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(longFilename);
        // Should not throw exception
        assertNotNull(result, "Should handle very long filenames without crashing");
    }

    @Test
    void testDetectFromFilenameWithSpaces() {
        // Test filenames with spaces
        final String filename = "My Bank Account Statement.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        // Should handle gracefully
        assertNotNull(result, "Should handle filenames with spaces");
    }

    @Test
    void testDetectFromFilenameWithUnderscores() {
        // Test filenames with underscores (common pattern)
        final String filename = "chase_checking_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result, "Should detect from filename with underscores");
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    void testDetectFromFilenameWithHyphens() {
        // Test filenames with hyphens
        final String filename = "chase-checking-1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        // Should handle gracefully
        assertNotNull(result, "Should handle filenames with hyphens");
    }

    @Test
    void testDetectFromFilenameNoExtension() {
        // Test filename without extension
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("Chase3100_Activity");
        // Should still attempt detection
        assertNotNull(result, "Should handle filenames without extension");
    }

    @Test
    void testDetectFromFilenameOnlyExtension() {
        // Test edge case: just extension
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(".csv");
        // Should return null or handle gracefully
        // This is an edge case - may or may not detect, but shouldn't crash
        assertNotNull(result, "Should handle edge case without crashing");
    }
}
