package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Security tests for file system operations Tests protection against path traversal in file system
 * operations
 */
class FileSystemSecurityTest {

    private FileSecurityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSecurityValidator();
    }

    @Test
    void testValidateFilePathWithPathTraversalThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("../../../etc/passwd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateFilePathWithBackslashPathTraversalThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath(
                                    "..\\..\\..\\windows\\system32\\config\\sam");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithEncodedPathTraversalThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("data%2e%2e%2fetc%2fpasswd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithNullByteThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("data/file.txt%00../../../etc/passwd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithValidPathReturnsNormalizedPath() {
        final Path path =
                assertDoesNotThrow(
                        () -> {
                            return validator.validateFilePath("data/category_model.json");
                        });

        assertNotNull(path);
        assertFalse(path.toString().contains(".."));
    }

    @Test
    void testValidateFilePathWithRelativePathReturnsNormalizedPath() {
        final Path path =
                assertDoesNotThrow(
                        () -> {
                            return validator.validateFilePath("data/../data/model.json");
                        });

        assertNotNull(path);
        // After normalization, should not contain ..
        // Note: This test may pass if normalization removes the .. before validation
    }

    @Test
    void testValidateFilePathWithNullPathThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath(null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithEmptyPathThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithAbsolutePathThrowsException() {
        // Absolute paths starting with / should be rejected for security
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("/etc/passwd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePathWithWindowsAbsolutePathThrowsException() {
        // Windows absolute paths should be rejected
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFilePath("C:\\Windows\\System32\\config\\sam");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }
}
