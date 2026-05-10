package com.budgetbuddy.security;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Security tests for FileSecurityValidator Tests protection against: - Path traversal attacks -
 * Malicious file uploads - File type spoofing - Oversized files - Dangerous file extensions
 */
class FileSecurityValidatorTest {

    private FileSecurityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSecurityValidator();
    }

    // ========== Path Traversal Tests ==========

    @Test
    void testValidateFileNameWithPathTraversalThrowsException() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "../../../etc/passwd",
                        "text/csv",
                        "date,amount,description\n2025-01-01,100.00,Test".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateFileNameWithEncodedPathTraversalThrowsException() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "file.csv%00../../../etc/passwd",
                        "text/csv",
                        "date,amount,description\n2025-01-01,100.00,Test".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileNameWithBackslashPathTraversalThrowsException() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "..\\..\\..\\windows\\system32\\config\\sam",
                        "text/csv",
                        "date,amount,description\n2025-01-01,100.00,Test".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateS3KeyWithPathTraversalThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateS3Key("../../../etc/passwd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("path traversal"));
    }

    @Test
    void testValidateS3KeyWithNullByteThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateS3Key("file.txt%00../../../etc/passwd");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
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
    }

    // ========== File Type Validation Tests ==========

    @Test
    void testValidateFileUploadWithDangerousExtensionThrowsException() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "malware.exe",
                        "application/x-msdownload",
                        new byte[]{0x4D, 0x5A} // PE executable signature
                );

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("not allowed for security reasons"));
    }

    @Test
    void testValidateFileUploadWithScriptExtensionThrowsException() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "script.sh",
                        "application/x-sh",
                        "#!/bin/bash\necho 'malicious'".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUploadWithWrongExtensionThrowsException() {
        final MultipartFile file =
                new MockMultipartFile("file", "data.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // ========== File Size Validation Tests ==========

    @Test
    void testValidateFileUploadWithOversizedFileThrowsException() {
        // Create a file larger than 10MB
        final byte[] largeContent = new byte[11 * 1024 * 1024]; // 11MB
        final MultipartFile file = new MockMultipartFile("file", "large.csv", "text/csv", largeContent);

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("exceeds maximum"));
    }

    // ========== Magic Bytes Validation Tests ==========

    @Test
    void testValidateFileUploadWithSpoofedPDFThrowsException() {
        // File has .pdf extension but contains CSV content
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "spoofed.pdf",
                        "application/pdf",
                        "date,amount,description\n2025-01-01,100.00,Test".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("pdf"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("does not match declared type"));
    }

    @Test
    void testValidateFileUploadWithSpoofedCSVThrowsException() {
        // File has .csv extension but contains binary content
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "spoofed.csv",
                        "text/csv",
                        new byte[]{
                                (byte) 0x4D, (byte) 0x5A, (byte) 0x90, (byte) 0x00
                        } // PE executable signature
                );

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUploadWithValidPDFPasses() {
        // Valid PDF with correct magic bytes
        final byte[] pdfContent = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // %PDF-1.4
        final MultipartFile file =
                new MockMultipartFile("file", "valid.pdf", "application/pdf", pdfContent);

        assertDoesNotThrow(
                () -> {
                    validator.validateFileUpload(file, Set.of("pdf"));
                });
    }

    @Test
    void testValidateFileUploadWithValidCSVPasses() {
        final MultipartFile file =
                new MockMultipartFile(
                        "file",
                        "valid.csv",
                        "text/csv",
                        "date,amount,description\n2025-01-01,100.00,Test".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(
                () -> {
                    validator.validateFileUpload(file, Set.of("csv"));
                });
    }

    // ========== S3 Key Validation Tests ==========

    @Test
    void testValidateS3KeyWithValidKeyPasses() {
        assertDoesNotThrow(
                () -> {
                    validator.validateS3Key("user-123/transactions/file.csv");
                });
    }

    @Test
    void testValidateS3KeyWithLeadingSlashThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateS3Key("/user-123/file.csv");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateS3KeyWithTooLongKeyThrowsException() {
        final String longKey = "a".repeat(1025); // 1025 characters
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateS3Key(longKey);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // ========== File Path Validation Tests ==========

    @Test
    void testValidateFilePathWithValidPathPasses() {
        final Path path =
                assertDoesNotThrow(
                        () -> {
                            return validator.validateFilePath("data/model.bin");
                        });

        assertNotNull(path);
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

    // ========== Edge Cases ==========

    @Test
    void testValidateFileUploadWithNullFileThrowsException() {
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(null, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUploadWithEmptyFilenameThrowsException() {
        final MultipartFile file =
                new MockMultipartFile("file", "", "text/csv", "date,amount".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUploadWithNoExtensionThrowsException() {
        final MultipartFile file =
                new MockMultipartFile("file", "filename", "text/csv", "date,amount".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testValidateFileUploadWithVeryLongFilenameThrowsException() {
        final String longName = "a".repeat(256) + ".csv";
        final MultipartFile file =
                new MockMultipartFile("file", longName, "text/csv", "date,amount".getBytes(StandardCharsets.UTF_8));

        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            validator.validateFileUpload(file, Set.of("csv"));
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }
}
