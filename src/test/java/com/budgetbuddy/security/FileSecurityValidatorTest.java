package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for FileSecurityValidator
 * Tests protection against:
 * - Path traversal attacks
 * - Malicious file uploads
 * - File type spoofing
 * - Oversized files
 * - Dangerous file extensions
 */
class FileSecurityValidatorTest {

    private FileSecurityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSecurityValidator();
    }

    // ========== Path Traversal Tests ==========

    @Test
    void testValidateFileName_WithPathTraversal_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "../../../etc/passwd",
                "text/csv",
                "date,amount,description\n2025-01-01,100.00,Test".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateFileName_WithEncodedPathTraversal_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "file.csv%00../../../etc/passwd",
                "text/csv",
                "date,amount,description\n2025-01-01,100.00,Test".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileName_WithBackslashPathTraversal_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "..\\..\\..\\windows\\system32\\config\\sam",
                "text/csv",
                "date,amount,description\n2025-01-01,100.00,Test".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateS3Key_WithPathTraversal_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateS3Key("../../../etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateS3Key_WithNullByte_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateS3Key("file.txt%00../../../etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFilePath_WithPathTraversal_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFilePath("../../../etc/passwd");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // ========== File Type Validation Tests ==========

    @Test
    void testValidateFileUpload_WithDangerousExtension_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "malware.exe",
                "application/x-msdownload",
                new byte[]{0x4D, 0x5A} // PE executable signature
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("not allowed for security reasons"));
    }

    @Test
    void testValidateFileUpload_WithScriptExtension_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "script.sh",
                "application/x-sh",
                "#!/bin/bash\necho 'malicious'".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUpload_WithWrongExtension_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.pdf",
                "application/pdf",
                "%PDF-1.4".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // ========== File Size Validation Tests ==========

    @Test
    void testValidateFileUpload_WithOversizedFile_ThrowsException() {
        // Create a file larger than 10MB
        byte[] largeContent = new byte[11 * 1024 * 1024]; // 11MB
        MultipartFile file = new MockMultipartFile(
                "file",
                "large.csv",
                "text/csv",
                largeContent
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("exceeds maximum"));
    }

    // ========== Magic Bytes Validation Tests ==========

    @Test
    void testValidateFileUpload_WithSpoofedPDF_ThrowsException() {
        // File has .pdf extension but contains CSV content
        MultipartFile file = new MockMultipartFile(
                "file",
                "spoofed.pdf",
                "application/pdf",
                "date,amount,description\n2025-01-01,100.00,Test".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("pdf"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("does not match declared type"));
    }

    @Test
    void testValidateFileUpload_WithSpoofedCSV_ThrowsException() {
        // File has .csv extension but contains binary content
        MultipartFile file = new MockMultipartFile(
                "file",
                "spoofed.csv",
                "text/csv",
                new byte[]{(byte)0x4D, (byte)0x5A, (byte)0x90, (byte)0x00} // PE executable signature
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUpload_WithValidPDF_Passes() {
        // Valid PDF with correct magic bytes
        byte[] pdfContent = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // %PDF-1.4
        MultipartFile file = new MockMultipartFile(
                "file",
                "valid.pdf",
                "application/pdf",
                pdfContent
        );

        assertDoesNotThrow(() -> {
            validator.validateFileUpload(file, Set.of("pdf"));
        });
    }

    @Test
    void testValidateFileUpload_WithValidCSV_Passes() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "valid.csv",
                "text/csv",
                "date,amount,description\n2025-01-01,100.00,Test".getBytes()
        );

        assertDoesNotThrow(() -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });
    }

    // ========== S3 Key Validation Tests ==========

    @Test
    void testValidateS3Key_WithValidKey_Passes() {
        assertDoesNotThrow(() -> {
            validator.validateS3Key("user-123/transactions/file.csv");
        });
    }

    @Test
    void testValidateS3Key_WithLeadingSlash_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateS3Key("/user-123/file.csv");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateS3Key_WithTooLongKey_ThrowsException() {
        String longKey = "a".repeat(1025); // 1025 characters
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateS3Key(longKey);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // ========== File Path Validation Tests ==========

    @Test
    void testValidateFilePath_WithValidPath_Passes() {
        Path path = assertDoesNotThrow(() -> {
            return validator.validateFilePath("data/model.bin");
        });

        assertNotNull(path);
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

    // ========== Edge Cases ==========

    @Test
    void testValidateFileUpload_WithNullFile_ThrowsException() {
        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(null, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUpload_WithEmptyFilename_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "",
                "text/csv",
                "date,amount".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUpload_WithNoExtension_ThrowsException() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "filename",
                "text/csv",
                "date,amount".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUpload_WithVeryLongFilename_ThrowsException() {
        String longName = "a".repeat(256) + ".csv";
        MultipartFile file = new MockMultipartFile(
                "file",
                longName,
                "text/csv",
                "date,amount".getBytes()
        );

        AppException exception = assertThrows(AppException.class, () -> {
            validator.validateFileUpload(file, Set.of("csv"));
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }
}

