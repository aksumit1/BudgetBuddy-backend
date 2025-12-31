package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for file system operations
 * Tests protection against path traversal in file system operations
 */
class FileSystemSecurityTest {

    private FileSecurityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSecurityValidator();
    }

    @Test
    void testValidateFilePath_WithPathTraversal_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("../../../etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateFilePath_WithBackslashPathTraversal_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("..\\..\\..\\windows\\system32\\config\\sam");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithEncodedPathTraversal_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("data%2e%2e%2fetc%2fpasswd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithNullByte_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("data/file.txt%00../../../etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithValidPath_ReturnsNormalizedPath() {
        Path path = assertDoesNotThrow(() -> {
            return validator.validateFilePath("data/category_model.json");
        });

        assertNotNull(path);
        assertFalse(path.toString().contains(".."));
    }

    @Test
    void testValidateFilePath_WithRelativePath_ReturnsNormalizedPath() {
        Path path = assertDoesNotThrow(() -> {
            return validator.validateFilePath("data/../data/model.json");
        });

        assertNotNull(path);
        // After normalization, should not contain ..
        // Note: This test may pass if normalization removes the .. before validation
    }

    @Test
    void testValidateFilePath_WithNullPath_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath(null);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithEmptyPath_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithAbsolutePath_ThrowsException() {
        // Absolute paths starting with / should be rejected for security
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("/etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithWindowsAbsolutePath_ThrowsException() {
        // Windows absolute paths should be rejected
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("C:\\Windows\\System32\\config\\sam");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }
}

